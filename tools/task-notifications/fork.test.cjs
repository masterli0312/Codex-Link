'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const lib=require('../../app/src/main/assets/task-notifications/conversation-library.cjs');
const {RemoteController,validateCommand,resolveThread,checkNativeBaseline}=require('../../app/src/main/assets/task-notifications/remote-core.cjs');
const source=crypto.randomUUID(),child=crypto.randomUUID(),host=crypto.randomUUID(),key=crypto.randomBytes(32).toString('base64');
const hash=s=>crypto.createHash('sha256').update(s).digest('hex');
function command(extra={}){return {id:crypto.randomUUID(),action:'fork',host_id:host,thread_id:source,conversation_id:hash(source),
  read_thread_id:source,baseline_turn:'last',expected_turn_id:'last',issued_at:Date.now(),...extra};}
function client(){let forks=0;const calls=[];return {calls,get forks(){return forks;},setHandlers(){},connect:async()=>{},close(){},call:async(m,p)=>{
 calls.push([m,p]);if(m==='thread/read')return {thread:{id:p.threadId,cwd:os.tmpdir(),modelProvider:'original-provider',status:{type:'notLoaded'},updatedAt:1000}};
 if(m==='thread/turns/list')return {data:[{id:'last',status:'completed',items:[{id:'reply',type:'agentMessage',text:'history'}]}]};
 if(m==='thread/fork'){forks++;return {thread:{id:child,forkedFromId:source},cwd:os.tmpdir(),modelProvider:'original-provider'};}
 throw Error('Unexpected operation');
 }};}
test('fork copies a known completed turn to a different native ID without generation or environment overrides',async()=>{
 const c=client(),result=await lib.forkThread(c,source,'last',host);
 assert.equal(result.forked_from_thread_id,source);assert.equal(result.snapshot.conversation_id,hash(child));
 assert.equal(result.snapshot.remote_ref.thread_id,child);assert.equal(result.snapshot.messages[0].text,'history');
 assert.deepEqual(c.calls.find(([m])=>m==='thread/fork')[1],{threadId:source,lastTurnId:'last',excludeTurns:true,ephemeral:false,deferGoalContinuation:true});
 assert.equal(c.calls.some(([m])=>['turn/start','thread/resume','thread/name/set'].includes(m)),false);
});
test('a confirmed child survives a snapshot failure and restart without generating another child',async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'codex-usage-fork-')),events=[],c=client(),call=c.call;let fail=true;
 c.call=async(m,p)=>{if(fail&&p.threadId===child)throw Error('CODEX_TIMEOUT');return call(m,p);};
 const make=()=>new RemoteController({directory:dir,key,hostId:host,clientFactory:()=>c,emit:async e=>events.push(e)});
 let controller=make();try{
  const cmd=command();await controller.handle(cmd);assert.equal(events.at(-1).status,'unknown');controller.close();fail=false;controller=make();
  await controller.handle({...cmd,id:crypto.randomUUID(),action:'status',target_id:cmd.id});
  assert.equal(c.forks,1);assert.equal(events.at(-1).status,'forked');assert.equal(events.at(-1).request_id,cmd.id);
  assert.equal(events.at(-1).snapshot.remote_ref.thread_id,child);
 }finally{controller.close();assert.equal(path.dirname(dir),path.resolve(os.tmpdir()));fs.rmSync(dir,{recursive:true,force:true});}
});
test('fork rejects a running or changed source and mismatched provider metadata',async()=>{
 const c=client();const original=c.call;
 c.call=async(m,p)=>m==='thread/read'?{thread:{id:source,status:{type:'active'}}}:original(m,p);
 await assert.rejects(lib.forkThread(c,source,'last',host),/BUSY/);assert.equal(c.forks,0);
 const stale=client();await assert.rejects(lib.forkThread(stale,source,'older',host),/STALE/);assert.equal(stale.forks,0);
 const wrong=client(),call=wrong.call;wrong.call=async(m,p)=>m==='thread/fork'?{thread:{id:source},cwd:os.tmpdir(),modelProvider:'original-provider'}:call(m,p);
 await assert.rejects(lib.forkThread(wrong,source,'last',host),/FORK_MISMATCH/);
});
test('fork is host and conversation bound and needs the exact completed turn',()=>{
 assert.equal(validateCommand(command(),host),true);
 for(const extra of [{read_thread_id:child},{expected_turn_id:''},{thread_id:'../../file'},{host_id:crypto.randomUUID()}])
  assert.equal(validateCommand(command(extra),host),false);
});
test('paginated forks require a fresh native terminal turn instead of missing legacy task_complete logs',async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'codex-usage-fork-'));
 try{
  fs.mkdirSync(path.join(dir,'sessions'));fs.writeFileSync(path.join(dir,'sessions','rollout-'+child+'.jsonl'),JSON.stringify({type:'session_meta',payload:{
    id:child,cwd:dir,source:'vscode',model_provider:'original-provider',forked_from_id:source,history_mode:'paginated'}})+'\n');
  const cmd={...command(),action:'send',thread_id:child,conversation_id:hash(child),baseline_turn:'last'};
  const local=resolveThread({codexHome:dir},cmd);assert.equal(local.requiresNativeBaseline,true);
  const c={call:async(m,p)=>{assert.equal(m,'thread/turns/list');assert.equal(p.threadId,child);return {data:[{id:'last',status:'completed'}]};}};
  await checkNativeBaseline(c,cmd,local);
  await assert.rejects(checkNativeBaseline({call:async()=>({data:[{id:'new',status:'completed'}]})},cmd,local),/STALE/);
  await assert.rejects(checkNativeBaseline({call:async()=>({data:[{id:'last',status:'inProgress'}]})},cmd,local),/BUSY/);
  await assert.rejects(checkNativeBaseline({call:async()=>({data:[]})},cmd,local),/STALE/);
 }finally{assert.equal(path.dirname(dir),path.resolve(os.tmpdir()));fs.rmSync(dir,{recursive:true,force:true});}
});
test('duplicate and restarted fork requests reuse the saved result and never fork again',async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'codex-usage-fork-')),events=[],c=client();
 const make=()=>new RemoteController({directory:dir,key,hostId:host,clientFactory:()=>c,emit:async e=>events.push(e)});
 let controller=make();try{
  const cmd=command();await controller.handle(cmd);await controller.handle(cmd);controller.close();controller=make();await controller.handle(cmd);
  assert.equal(c.forks,1);assert.equal(events.length,3);assert.ok(events.every(e=>e.status==='forked'&&e.snapshot.conversation_id===hash(child)));
 }finally{controller.close();assert.equal(path.dirname(dir),path.resolve(os.tmpdir()));fs.rmSync(dir,{recursive:true,force:true});}
});
test('ambiguous fork failure stays unknown and cannot create a duplicate on replay',async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'codex-usage-fork-')),events=[],c=client();let attempts=0;const call=c.call;
 c.call=async(m,p)=>{if(m==='thread/fork'){attempts++;throw Error('CODEX_TIMEOUT');}return call(m,p);};
 const controller=new RemoteController({directory:dir,key,hostId:host,clientFactory:()=>c,emit:async e=>events.push(e)});
 try{const cmd=command();await controller.handle(cmd);await controller.handle(cmd);assert.equal(attempts,1);
  assert.ok(events.every(e=>e.status==='unknown'&&e.error==='FORK_UNCONFIRMED'));}
 finally{controller.close();assert.equal(path.dirname(dir),path.resolve(os.tmpdir()));fs.rmSync(dir,{recursive:true,force:true});}
});
