'use strict';
const {UUID,clip,time,parent,isSub,identity,project,snapshot}=require('./rollout-reader.cjs');
function checkedSub(index,id){
 let current=index.get(id);if(!current||!isSub(current.meta))throw Error('INVALID_THREAD');
 const seen=new Set();for(let n=0;n<4;n++){
  if(!current||seen.has(current.meta.id)||!isSub(current.meta))throw Error('INVALID_THREAD');seen.add(current.meta.id);
  const p=parent(current.meta);if(!UUID.test(p))throw Error('INVALID_THREAD');current=index.get(p);
  if(current&&!isSub(current.meta))return;
 }
 throw Error('INVALID_THREAD');
}
async function connected(controller,fn){const client=controller.clientFactory();try{client.setHandlers(()=>{},id=>client.reject(id));await client.connect();return await fn(client);}finally{client.close();}}
async function checkedNative(client,id){
 const seen=new Set();let current=id,target;
 for(let depth=0;depth<=4;depth++){
  if(seen.has(current))throw Error('INVALID_THREAD');seen.add(current);
  const meta=(await client.call('thread/read',{threadId:current,includeTurns:false}))?.thread;if(meta?.id!==current)throw Error('INVALID_THREAD');
  if(!target)target=meta;const sub=isSub(meta)||/^subAgent/i.test(String(meta.source||''))||/^subAgent/i.test(String(meta.threadSource||''));
  if(!sub){if(depth===0||meta.parentThreadId)throw Error('INVALID_THREAD');return target;}
  if(depth===4||meta.threadSource==='user')throw Error('INVALID_THREAD');const p=meta.parentThreadId||parent(meta);
  if(!UUID.test(p)||Object.hasOwn(meta,'parentThreadId')&&meta.parentThreadId!==p)throw Error('INVALID_THREAD');current=p;
 }
 throw Error('INVALID_THREAD');
}
async function list(controller,id){
 let index,scanFailed=false;try{index=controller.rollouts.scan();}catch{index=new Map();scanFailed=true;}
 const local=index.get(id);if(local&&isSub(local.meta))throw Error('INVALID_THREAD');
 if(!controller.config.appServerUrl&&!local)throw Error(scanFailed?'AGENTS_UNAVAILABLE':'INVALID_THREAD');
 let natives=[],nativePartial=false;
 if(controller.config.appServerUrl)try{const native=await connected(controller,async client=>{
  if(!local){const root=(await client.call('thread/read',{threadId:id,includeTurns:false}))?.thread;
   if(root?.id!==id||isSub(root)||root.parentThreadId||/^subAgent/i.test(String(root.threadSource||'')))throw Error('INVALID_THREAD');}
  const children=async parentId=>{let cursor='',all=[];for(let page=0;page<10;page++){
   const r=await client.call('thread/list',{limit:100,sourceKinds:['subAgentThreadSpawn'],parentThreadId:parentId,...(cursor?{cursor}:{})});
   all.push(...(Array.isArray(r.data)?r.data:[]).filter(t=>(t.parentThreadId||parent(t))===parentId));
   if(!r.nextCursor||r.nextCursor===cursor)break;cursor=r.nextCursor;if(page===9)nativePartial=true;
  }return all;};
  const direct=await children(id),all=[...direct];if(direct.length>100)nativePartial=true;
  for(const child of direct.slice(0,100))all.push(...await children(child.id));return all;
 });natives=native;}catch(e){if(e.message==='INVALID_THREAD')throw e;if(!local)throw Error('AGENTS_UNAVAILABLE');} // Older servers fall back to rollout metadata.
 const nativeById=new Map(natives.filter(x=>UUID.test(x?.id||'')).map(x=>[x.id,x]));
 const candidates=new Map();for(const [key,entry] of index)if(isSub(entry.meta))candidates.set(key,{...entry,native:nativeById.get(key)});
 for(const n of natives)if(UUID.test(n?.id||'')&&!candidates.has(n.id))candidates.set(n.id,{meta:{id:n.id,source:n.source},native:n});
 const result=[];for(const [threadId,entry] of candidates){const n=entry.native||{},p=parent(entry.meta)||n.parentThreadId;let depth=0;
  if(p===id)depth=1;else{const ancestor=candidates.get(p),gp=ancestor&&(parent(ancestor.meta)||ancestor.native?.parentThreadId);if(gp===id)depth=2;}
  if(!depth)continue;
  let tail={rows:[]},state;try{if(entry.file)tail=controller.rollouts.tail(entry.file);}catch{}state=project(tail.rows);
  const running=n.status?.type==='active'||n.status?.type!=='idle'&&state.status==='running';let turn=state.turn_id;
  if(n.turns?.length){const last=n.turns[0];if(last.status==='inProgress')turn=last.id;}
  const status=running?'running':state.status==='completed'?'completed':state.status==='interrupted'?'interrupted':n.status?.type==='idle'?'idle':n.status?.type==='systemError'?'errored':'unknown';
  const names=identity(entry.meta);result.push({thread_id:threadId,parent_thread_id:p,depth,nickname:clip(n.agentNickname||names.nickname,160),role:clip(n.agentRole||names.role,160),model:state.model,effort:state.effort,status,turn_id:running?clip(turn,128):'',
   started_at:time(n.createdAt)||time(entry.meta._timestamp),updated_at:time(n.updatedAt)||state.updated_at||time(entry.meta._timestamp),last_reply:state.last_reply});
 }
 result.sort((a,b)=>b.started_at-a.started_at||a.thread_id.localeCompare(b.thread_id));const selected=result.slice(0,20);
 if(controller.config.appServerUrl&&natives.length)try{await connected(controller,async client=>{await Promise.all(selected.filter(x=>nativeById.get(x.thread_id)?.status?.type==='active').map(async a=>{
  try{const turn=(await client.call('thread/turns/list',{threadId:a.thread_id,limit:1,sortDirection:'desc',itemsView:'summary'}))?.data?.[0];
   if(turn?.status==='inProgress'){a.status='running';a.turn_id=clip(turn.id,128);}else if(turn&&['completed','interrupted','failed'].includes(turn.status)){a.status=turn.status==='failed'?'errored':turn.status;a.turn_id='';}
  }catch{}
 }));});}catch{}
 return {agents:selected,partial:result.length>20||nativePartial};
}
async function read(controller,id){let index,scanFailed=false;try{index=controller.rollouts.scan();}catch{index=new Map();scanFailed=true;}const entry=index.get(id);
 if(entry&&!isSub(entry.meta))throw Error('INVALID_THREAD');
 let valid=false;try{checkedSub(index,id);valid=true;}catch{}
 if(!valid&&controller.config.appServerUrl)try{const native=await connected(controller,client=>checkedNative(client,id));if(entry&&parent(entry.meta)!==(native.parentThreadId||parent(native)))throw Error('INVALID_THREAD');valid=true;}catch(e){if(e.message==='INVALID_THREAD')throw e;throw Error('ROLLOUT_UNAVAILABLE');}
 if(!valid)throw Error(scanFailed?'ROLLOUT_UNAVAILABLE':'INVALID_THREAD');if(!entry)throw Error('ROLLOUT_UNAVAILABLE');
 try{const tail=controller.rollouts.tail(entry.file),value=snapshot(id,entry.meta,tail);return {snapshot:value,partial:value.truncated};}catch{throw Error('ROLLOUT_UNAVAILABLE');}
}
async function stop(controller,id,expected){
 if(!controller.config.appServerUrl)throw Error('SHARED_REQUIRED');let index;try{index=controller.rollouts.scan();}catch{index=new Map();}const entry=index.get(id);if(entry&&!isSub(entry.meta))throw Error('INVALID_THREAD');
 try{return await connected(controller,async client=>{
  const meta=await checkedNative(client,id);if(entry&&parent(entry.meta)!==(meta.parentThreadId||parent(meta)))throw Error('INVALID_THREAD');
  const latest=(await client.call('thread/turns/list',{threadId:id,limit:1,sortDirection:'desc',itemsView:'summary'}))?.data?.[0];
  if(latest&&['completed','interrupted','failed'].includes(latest.status)||!latest&&meta.status?.type==='idle')return {reply:'already_finished'};
  if(!latest||latest.status!=='inProgress')throw Error('STOP_FAILED');
  if(latest.id!==expected)throw Error('STALE_ACTIVE_TURN');
  await client.call('turn/interrupt',{threadId:id,turnId:expected});return {reply:'stopped'};
 });}catch(e){if(['INVALID_THREAD','STALE_ACTIVE_TURN'].includes(e.message))throw e;throw Error('STOP_FAILED');}
}
module.exports={checkedSub,list,read,stop};
