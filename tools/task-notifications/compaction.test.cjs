'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {RemoteController,validateCommand}=require('../../app/src/main/assets/task-notifications/remote-core.cjs');
function fixture(t,options={}){
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'codex-link-compact-'));
  const host=crypto.randomUUID(),thread=crypto.randomUUID(),key=crypto.randomBytes(32).toString('base64'),events=[],calls=[];
  const command={id:crypto.randomUUID(),action:'compact',host_id:host,thread_id:thread,
    conversation_id:crypto.createHash('sha256').update(thread).digest('hex'),baseline_turn:'previous',issued_at:Date.now()};
  const client={supportsLiveWatch:options.shared!==false,closed:false,setHandlers(fn){this.event=fn;},connect:async()=>{},reject(){},close(){this.closed=true;},
    async call(m,p){
      calls.push([m,p]);
      if(m==='thread/read')return {thread:{id:thread,status:{type:options.busy?'active':'idle'}}};
      if(m==='thread/turns/list')return {data:[{id:'previous',status:'completed'}]};
      if(m==='thread/resume')return {};
      if(m==='thread/compact/start'){
        if(options.error)throw options.error;
        if(options.auto!==false)queueMicrotask(()=>complete());
        return {};
      }
      throw Error('Unexpected operation');
    }};
  const make=()=>new RemoteController({directory:dir,key,hostId:host,clientFactory:()=>client,emit:async e=>events.push(e)});
  const controller=make();
  function complete(){
    client.event('turn/started',{threadId:thread,turn:{id:'compact-turn'}});
    client.event('item/completed',{threadId:thread,turnId:'compact-turn',item:{type:'contextCompaction',id:'summary'}});
    client.event('turn/completed',{threadId:thread,turn:{id:'compact-turn',status:'completed'}});
  }
  t.after(()=>{controller.close();assert.equal(path.dirname(dir),path.resolve(os.tmpdir()));fs.rmSync(dir,{recursive:true,force:true});});
  return {command,controller,client,calls,events,complete,make};
}
async function started(f){while(!f.calls.some(([m])=>m==='thread/compact/start'))await new Promise(resolve=>setImmediate(resolve));}
test('native compaction uses exact thread without prompt, permission or model overrides',async t=>{
  const f=fixture(t);assert.equal(validateCommand(f.command,f.command.host_id),true);
  await f.controller.handle(f.command);
  assert.deepEqual(f.events.map(e=>e.status),['compacting','compacted']);assert.equal(f.client.closed,true);
  assert.deepEqual(f.calls.find(([m])=>m==='thread/compact/start')[1],{threadId:f.command.thread_id});
  assert.deepEqual(f.calls.find(([m])=>m==='thread/resume')[1],{threadId:f.command.thread_id,excludeTurns:true});
  assert.equal(f.calls.some(([m])=>m==='turn/start'||m==='turn/interrupt'),false);
});
test('empty start acknowledgement and unrelated events do not complete or close the subscriber',async t=>{
  const f=fixture(t,{auto:false}),pending=f.controller.handle(f.command);await started(f);
  f.client.event('thread/compacted',{threadId:crypto.randomUUID(),turnId:'other'});
  f.client.event('thread/compacted',{threadId:f.command.thread_id,turnId:'previous'});
  f.client.event('turn/completed',{threadId:f.command.thread_id,turn:{id:'unrelated',status:'completed'}});
  assert.deepEqual(f.events.map(e=>e.status),['compacting']);assert.equal(f.client.closed,false);
  f.complete();await pending;assert.equal(f.events.at(-1).status,'compacted');
});
test('busy and independent-server hosts reject before starting native compaction',async t=>{
  for(const options of [{busy:true},{shared:false}]){
    const f=fixture(t,options);await f.controller.handle(f.command);
    assert.equal(f.events.at(-1).status,'failed');assert.equal(f.events.at(-1).error,options.busy?'BUSY':'COMPACTION_UNAVAILABLE');
    assert.equal(f.calls.some(([m])=>m==='thread/compact/start'),false);
  }
});
test('unsupported native method reports unavailable rather than success',async t=>{
  const f=fixture(t,{error:Object.assign(Error('CODEX_RPC'),{rpcCode:-32601})});await f.controller.handle(f.command);
  assert.equal(f.events.at(-1).error,'COMPACTION_UNAVAILABLE');assert.equal(f.events.at(-1).status,'failed');
});
test('disconnect and replay never silently repeat a side effect',async t=>{
  const f=fixture(t,{auto:false}),pending=f.controller.handle(f.command);await started(f);
  f.client.event('bridge/disconnected',{});await pending;await f.controller.handle(f.command);
  assert.equal(f.calls.filter(([m])=>m==='thread/compact/start').length,1);
  assert.equal(f.events.at(-1).status,'unknown');assert.equal(f.events.at(-1).error,'COMPACTION_UNCONFIRMED');
});
test('duplicate running requests and a second compaction preserve the first operation',async t=>{
  const f=fixture(t,{auto:false}),pending=f.controller.handle(f.command);await started(f);
  await f.controller.handle(f.command);
  await f.controller.handle({...f.command,id:crypto.randomUUID()});assert.equal(f.events.at(-1).error,'BUSY');
  f.complete();await pending;await f.controller.handle(f.command);
  assert.equal(f.calls.filter(([m])=>m==='thread/compact/start').length,1);assert.equal(f.events.at(-1).status,'compacted');
});
test('failed native turn does not report context compacted',async t=>{
  const f=fixture(t,{auto:false}),pending=f.controller.handle(f.command);await started(f);
  f.client.event('turn/started',{threadId:f.command.thread_id,turn:{id:'compact-turn'}});
  f.client.event('turn/completed',{threadId:f.command.thread_id,turn:{id:'compact-turn',status:'failed'}});
  await pending;assert.equal(f.events.at(-1).status,'failed');
});
test('legacy native compacted notification also confirms completion',async t=>{
  const f=fixture(t,{auto:false}),pending=f.controller.handle(f.command);await started(f);
  f.client.event('thread/compacted',{threadId:f.command.thread_id,turnId:'compact-turn'});
  await pending;assert.equal(f.events.at(-1).status,'compacted');
});
test('bridge restart preserves an uncertain compaction and never reconstructs success from a normal reply',async t=>{
  const f=fixture(t,{auto:false}),pending=f.controller.handle(f.command);await started(f);
  f.controller.close();await pending;
  const restored=f.make();restored.recover=()=>{throw Error('Must not recover a compaction from a chat reply');};
  try{await restored.handle(f.command);assert.equal(f.events.at(-1).status,'unknown');
    assert.equal(f.events.at(-1).error,'COMPACTION_UNCONFIRMED');assert.equal(f.calls.filter(([m])=>m==='thread/compact/start').length,1);
  }finally{restored.close();}
});
