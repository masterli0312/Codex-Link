'use strict';
// Closed Goal operations only. No caller-supplied RPC, origin, Provider or permissions.
const ACTIONS=['goal_read','goal_start','goal_resume','goal_pause','goal_clear'];
const STATES=['active','paused','blocked','usageLimited','budgetLimited','complete'];
function normalizeGoal(value,thread){
 if(value===null)return null;
 if(!value||value.threadId!==thread||typeof value.objective!=='string'||!value.objective.trim()||
  Array.from(value.objective).length>4000||Buffer.byteLength(value.objective)>16384||!STATES.includes(value.status)||
  !['tokensUsed','timeUsedSeconds','createdAt','updatedAt'].every(k=>Number.isSafeInteger(value[k])&&value[k]>=0)||
  value.tokenBudget!=null&&(!Number.isSafeInteger(value.tokenBudget)||value.tokenBudget<=0))throw Error('GOAL_INVALID');
 return {thread_id:thread,objective:value.objective,status:value.status,tokens_used:value.tokensUsed,
  time_used_seconds:value.timeUsedSeconds,created_at:value.createdAt,updated_at:value.updatedAt,token_budget:value.tokenBudget??null};
}
function validGoalCommand(c){
 if(!ACTIONS.includes(c.action))return true;
 if(c.action==='goal_read')return c.read_thread_id===c.thread_id;
 if(typeof c.baseline_turn!=='string'||!c.baseline_turn||c.baseline_turn.length>128)return false;
 if(!Number.isSafeInteger(c.expected_goal_updated_at)||c.expected_goal_updated_at<0)return false;
 if(c.expected_goal_created_at!==undefined&&(!Number.isSafeInteger(c.expected_goal_created_at)||c.expected_goal_created_at<0))return false;
 if(c.expected_goal_hash!==undefined&&c.expected_goal_hash!==''&&!/^[a-f0-9]{64}$/.test(c.expected_goal_hash))return false;
 if(c.action==='goal_start')return typeof c.objective==='string'&&!!c.objective.trim()&&Array.from(c.objective).length<=4000&&
  Buffer.byteLength(c.objective)<=16384&&(c.token_budget==null||Number.isSafeInteger(c.token_budget)&&c.token_budget>0&&c.token_budget<=2000000000);
 return c.objective===undefined||c.objective==='';
}
const getGoal=async(client,thread)=>normalizeGoal((await client.call('thread/goal/get',{threadId:thread})).goal,thread);
function safeError(e){
 if(e.rpcCode===-32601)return 'GOAL_UNAVAILABLE';
 if(e.rpcCode===-32600&&typeof e.rpcMessage==='string'&&/already has an active writer/.test(e.rpcMessage))return 'DESKTOP_OWNS_THREAD';
 return ['BUSY','STALE','GOAL_CHANGED','GOAL_ALREADY_EXISTS','GOAL_NOT_PAUSED','GOAL_INVALID','HOST_BUSY','GOAL_NOT_OWNED','INVALID_MODEL_SELECTION'].includes(e.message)?e.message:'GOAL_FAILED';
}
async function handleGoal(controller,c){
 if(c.action!=='goal_read'&&controller.journal[c.id]){await controller.replay(c,c.id);return;}
 if(controller.goalBusy){await controller.update(c,'failed',{error:'HOST_BUSY'});return;}
 controller.goalBusy=true;let client,owned=false,a;
 try{
  a=controller.active;
  const mutating=c.action!=='goal_read';
  if(mutating&&a&&((a.actualThread||a.c.thread_id)!==c.thread_id||a.c.id!==c.target_id))throw Error('HOST_BUSY');
  if(['goal_start','goal_resume'].includes(c.action)&&a)throw Error('HOST_BUSY');
  owned=!!a&&(a.actualThread||a.c.thread_id)===c.thread_id;
  client=owned?a.client:controller.clientFactory();
  if(!owned){client.setHandlers(()=>{},id=>client.reject(id));await client.connect();}
  const previous=await getGoal(client,c.thread_id);
  if(c.action==='goal_read'){await controller.update(c,'goal',{goal:previous});return;}
  const sameGeneration=previous&&c.expected_goal_created_at===previous.created_at&&c.expected_goal_hash===require('node:crypto').createHash('sha256').update(previous.objective).digest('hex');
  if(!(['goal_pause','goal_clear'].includes(c.action)&&sameGeneration&&previous.updated_at>=c.expected_goal_updated_at)&&(previous?.updated_at||0)!==c.expected_goal_updated_at)throw Error(previous&&c.action==='goal_start'?'GOAL_ALREADY_EXISTS':'GOAL_CHANGED');
  if(c.action==='goal_start'&&previous)throw Error('GOAL_ALREADY_EXISTS');
  if(c.action==='goal_resume'&&previous?.status!=='paused')throw Error('GOAL_NOT_PAUSED');
  if(['goal_pause','goal_clear'].includes(c.action)&&!previous)throw Error('GOAL_CHANGED');
  if(!owned&&previous?.status==='active')throw Error('GOAL_NOT_OWNED');
  if(['goal_start','goal_resume'].includes(c.action)){
   const local={...controller.resolve(c),permission:c.permission||'default'},read=await client.read(c.thread_id);
   const selected=await require('./remote-core.cjs').selection(client,c,local);
   if(selected.model)local.model=selected.model;
   if(selected.effort)local.resumeEffort=selected.effort;
   if(read.thread?.id!==c.thread_id||read.thread?.status?.type==='active')throw Error('BUSY');
   // An already-active persisted goal is never resumed implicitly by this bridge.
   // This installed protocol has no deferGoalContinuation on thread/resume.
   if(local.requiresNativeBaseline){
    const last=(await client.call('thread/turns/list',{threadId:c.thread_id,limit:1,sortDirection:'desc',itemsView:'summary'})).data?.[0];
    if(last?.status==='inProgress')throw Error('BUSY');
    if(!last||last.id!==c.baseline_turn)throw Error('STALE');
   }
   await client.resume(c.thread_id,local);controller.resolve(c);
   const latest=await getGoal(client,c.thread_id);
   if((latest?.updated_at||0)!==c.expected_goal_updated_at)throw Error('GOAL_CHANGED');
   a={c,client,local,turn:null,reply:'',pending:new Map(),items:new Map(),queue:[],activities:[],input:'',goalTask:true,goal:previous,
    goalWaiting:true,events:Promise.resolve()};controller.active=a;owned=true;
   const serialized=work=>{
    a.events=a.events.then(work).catch(async()=>{
     if(controller.active===a){await controller.update(c,'unknown',{error:'GOAL_UNCONFIRMED'});client.close();controller.active=null;}
    });return a.events;
   };
   client.setHandlers((m,p)=>serialized(()=>controller.event(a,m,p)),(id,m,p)=>serialized(()=>controller.request(a,id,m,p)));
   controller.journal[c.id]={status:'unknown',thread_id:c.thread_id,goal_task:true};controller.persist();
   await controller.update(c,'accepted',{goal:previous});a.startAttempted=true;
   const params={threadId:c.thread_id,status:'active',...(c.action==='goal_start'?{objective:c.objective,...(c.token_budget!=null?{tokenBudget:c.token_budget}:{})}:{})};
   a.goal=normalizeGoal((await client.call('thread/goal/set',params)).goal,c.thread_id);
   if(controller.active===a)await controller.refreshActive(a);return;
  }
  controller.journal[c.id]={status:'unknown',thread_id:c.thread_id};controller.persist();
  const goal=c.action==='goal_clear'?(await client.call('thread/goal/clear',{threadId:c.thread_id}),await getGoal(client,c.thread_id)):
   normalizeGoal((await client.call('thread/goal/set',{threadId:c.thread_id,status:'paused'})).goal,c.thread_id);
  if(c.action==='goal_clear'&&goal!==null||c.action==='goal_pause'&&goal?.status!=='paused')throw Error('GOAL_INVALID');
  await controller.update(c,'goal',{goal});
  if(owned&&a){
   a.goal=goal;a.stopping=true;await controller.cancelQueue(a,'QUEUE_STOPPED');
   if(a.turn)await client.interrupt(c.thread_id,a.turn);
   else await finishGoal(controller,a,'interrupted');
  }
 }catch(e){
  const attempted=controller.journal[c.id]?.status==='unknown'||a?.c.id===c.id&&a.startAttempted;
  await controller.update(c,attempted&&!e.rpcCode?'unknown':'failed',{error:attempted&&!e.rpcCode?'GOAL_UNCONFIRMED':safeError(e)});
  if(a?.c.id===c.id){client?.close();if(controller.active===a)controller.active=null;owned=false;}
 }finally{if(!owned)client?.close();controller.goalBusy=false;}
}
async function finishGoal(controller,a,status,snapshot){
 if(controller.active!==a)return;
 await controller.cancelQueue(a,'QUEUE_STOPPED');
 await controller.update(a.c,status,{reply:a.reply,turn_id:a.lastTurn||a.turn||'',goal:a.goal,goal_waiting:false,
  ...(snapshot?{snapshot}:{}),...(status==='failed'?{error:'GOAL_STOPPED'}:{})});
 clearTimeout(a.timer);a.client.close();if(controller.active===a)controller.active=null;
}
module.exports={ACTIONS,STATES,normalizeGoal,validGoalCommand,getGoal,handleGoal,finishGoal};
