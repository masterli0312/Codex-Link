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
async function list(controller,id){
 let index;try{index=controller.rollouts.scan();}catch{throw Error('AGENTS_UNAVAILABLE');}
 if(!index.has(id)||isSub(index.get(id).meta))throw Error('INVALID_THREAD');
 let natives=[],nativePartial=false;
 if(controller.config.appServerUrl)try{const native=await connected(controller,async client=>{
  const children=async parentId=>{let cursor='',all=[];for(let page=0;page<10;page++){
   const r=await client.call('thread/list',{limit:100,sourceKinds:['subAgentThreadSpawn'],parentThreadId:parentId,...(cursor?{cursor}:{})});
   all.push(...(Array.isArray(r.data)?r.data:[]).filter(t=>(t.parentThreadId||parent(t))===parentId));
   if(!r.nextCursor||r.nextCursor===cursor)break;cursor=r.nextCursor;if(page===9)nativePartial=true;
  }return all;};
  const direct=await children(id),all=[...direct];if(direct.length>100)nativePartial=true;
  for(const child of direct.slice(0,100))all.push(...await children(child.id));return all;
 });natives=native;}catch{} // Older servers fall back to rollout metadata.
 const nativeById=new Map(natives.filter(x=>UUID.test(x?.id||'')).map(x=>[x.id,x]));
 const candidates=new Map();for(const [key,entry] of index)if(isSub(entry.meta))candidates.set(key,{...entry,native:nativeById.get(key)});
 for(const n of natives)if(UUID.test(n?.id||'')&&!candidates.has(n.id))candidates.set(n.id,{meta:{id:n.id,source:n.source},native:n});
 const result=[];for(const [threadId,entry] of candidates){const n=entry.native||{},p=parent(entry.meta)||n.parentThreadId;let depth=0;
  if(p===id)depth=1;else{const ancestor=candidates.get(p),gp=ancestor&&(parent(ancestor.meta)||ancestor.native?.parentThreadId);if(gp===id)depth=2;}
  if(!depth)continue;
  let tail={rows:[]},state;try{if(entry.file)tail=controller.rollouts.tail(entry.file);}catch{}state=project(tail.rows);
  const running=n.status?.type==='active'||state.status==='running';let turn=state.turn_id;
  if(n.turns?.length){const last=n.turns[0];if(last.status==='inProgress')turn=last.id;}
  const status=running?'running':state.status==='completed'?'completed':state.status==='interrupted'?'interrupted':n.status?.type==='idle'?'idle':n.status?.type==='systemError'?'errored':'unknown';
  result.push({thread_id:threadId,parent_thread_id:p,depth,...identity(entry.meta),model:state.model,effort:state.effort,status,turn_id:running?clip(turn,128):'',
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
function read(controller,id){let index;try{index=controller.rollouts.scan();}catch{throw Error('ROLLOUT_UNAVAILABLE');}checkedSub(index,id);const entry=index.get(id);
 try{const tail=controller.rollouts.tail(entry.file),value=snapshot(id,entry.meta,tail);return {snapshot:value,partial:value.truncated};}catch{throw Error('ROLLOUT_UNAVAILABLE');}
}
async function stop(controller,id,expected){
 if(!controller.config.appServerUrl)throw Error('SHARED_REQUIRED');let index;try{index=controller.rollouts.scan();}catch{throw Error('INVALID_THREAD');}checkedSub(index,id);
 try{return await connected(controller,async client=>{
  const meta=(await client.call('thread/read',{threadId:id,includeTurns:false}))?.thread,expectedParent=parent(index.get(id).meta);
  if(meta?.id!==id||Object.hasOwn(meta,'parentThreadId')&&meta.parentThreadId!==expectedParent||meta.source&&typeof meta.source==='object'&&!isSub(meta)||typeof meta.source==='string'&&meta.source!=='subAgentThreadSpawn'||meta.threadSource==='user')throw Error('INVALID_THREAD');
  const latest=(await client.call('thread/turns/list',{threadId:id,limit:1,sortDirection:'desc',itemsView:'summary'}))?.data?.[0];
  if(latest&&['completed','interrupted','failed'].includes(latest.status)||!latest&&meta.status?.type==='idle')return {reply:'already_finished'};
  if(!latest||latest.status!=='inProgress')throw Error('STOP_FAILED');
  if(latest.id!==expected)throw Error('STALE_ACTIVE_TURN');
  await client.call('turn/interrupt',{threadId:id,turnId:expected});return {reply:'stopped'};
 });}catch(e){if(['INVALID_THREAD','STALE_ACTIVE_TURN'].includes(e.message))throw e;throw Error('STOP_FAILED');}
}
module.exports={checkedSub,list,read,stop};
