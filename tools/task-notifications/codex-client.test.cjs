'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {EventEmitter}=require('node:events');
const {PassThrough,Writable}=require('node:stream');
const {CodexClient}=require('../../app/src/main/assets/task-notifications/codex-client.cjs');
function nativeFixture(){
 const child=new EventEmitter();child.stdout=new PassThrough();child.stderr=new PassThrough();child.exitCode=null;
 child.kill=()=>{child.exitCode=0;child.emit('exit',0);};
 child.stdin=new Writable({write(bytes,encoding,done){
  const row=JSON.parse(bytes.toString());if(row.method==='initialize')queueMicrotask(()=>child.stdout.write(JSON.stringify({id:row.id,result:{}})+'\n'));
  done();
 },final(done){child.exitCode=0;queueMicrotask(()=>child.emit('exit',0));done();}});
 const client=new CodexClient('fixture','fixture',()=>child);return {client,child};
}
test('a fatal native frame marks the controller disconnected and rejects pending calls exactly once',async()=>{
 const {client,child}=nativeFixture(),events=[];client.setHandlers(m=>events.push(m),()=>{});
 try{
  await client.connect();const pending=client.call('thread/read',{});const rejected=assert.rejects(pending,/CODEX_DISCONNECTED/);
  child.stdout.emit('data',Buffer.alloc(16*1024*1024+1,65));
  await rejected;child.emit('error',Error('private native failure'));child.emit('exit',1);
  assert.deepEqual(events,['bridge/disconnected']);assert.equal(client.buffer,'');assert.equal(client.pending.size,0);
 }finally{client.close();}
});
test('native exit rejects a turn-start waiter immediately rather than retaining a fifteen-second timeout',async()=>{
 const {client,child}=nativeFixture(),events=[];client.setHandlers(m=>events.push(m),()=>{});
 try{
  await client.connect();const stop=client.interrupt('fixture-thread','not-started-yet');const rejected=assert.rejects(stop,/CODEX_DISCONNECTED/);
  child.emit('exit',1);await rejected;assert.equal(client.startWaiters.size,0);assert.deepEqual(events,['bridge/disconnected']);
 }finally{client.close();}
});
test('intentional close ignores late native frames and future interrupts fail without starting timers',async()=>{
 const {client,child}=nativeFixture(),events=[];client.setHandlers(m=>events.push(m),()=>{});
 await client.connect();client.close();
 child.stdout.write(JSON.stringify({method:'turn/started',params:{turn:{id:'late-turn'}}})+'\n');
 const stop=client.interrupt('fixture-thread','late-turn');await assert.rejects(stop,/CODEX_DISCONNECTED/);
 assert.equal(client.started.size,0);assert.equal(client.startWaiters.size,0);assert.deepEqual(events,[]);
});
test('an oversized native response moves an active controller to unknown while preserving its cached reply',async()=>{
 const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),crypto=require('node:crypto');
 const {RemoteController}=require('../../app/src/main/assets/task-notifications/remote-core.cjs');
 const directory=fs.mkdtempSync(path.join(os.tmpdir(),'codex-usage-disconnect-'));
 const {client,child}=nativeFixture(),updates=[];let finished;
 const done=new Promise(r=>finished=r),thread=crypto.randomUUID();
 const command={id:crypto.randomUUID(),action:'send',host_id:crypto.randomUUID(),thread_id:thread,
  conversation_id:crypto.createHash('sha256').update(thread).digest('hex'),baseline_turn:'previous',text:'fixture',issued_at:Date.now()};
 const controller=new RemoteController({directory,key:Buffer.alloc(32,3).toString('base64'),hostId:command.host_id,
  emit:async e=>{updates.push(e);if(e.status==='unknown')finished();}});
 const active={c:command,client,turn:'actual-turn',reply:'last verified reply',queue:[],activities:[]};controller.active=active;
 client.setHandlers((m,p)=>controller.event(active,m,p),()=>{});
 try{
  await client.connect();child.stdout.emit('data',Buffer.alloc(16*1024*1024+1,65));await done;
  await new Promise(r=>setImmediate(r));assert.equal(controller.active,null);
  assert.equal(updates.length,1);assert.equal(updates[0].error,'DISCONNECTED');assert.equal(updates[0].reply,'last verified reply');
  assert.equal(updates[0].turn_id,'actual-turn');assert.equal(JSON.stringify(updates).includes('private native failure'),false);
  const recovered=[];let observed;
  const restarted=new RemoteController({directory,key:Buffer.alloc(32,3).toString('base64'),hostId:command.host_id,
   recover:(threadId,turnId)=>{observed=[threadId,turnId];return {reply:'verified recovered final'};},emit:async e=>recovered.push(e)});
  await restarted.replay({...command,action:'status'},command.id);
  assert.deepEqual(observed,[thread,'actual-turn']);assert.equal(recovered.at(-1).status,'completed');
  assert.equal(recovered.at(-1).reply,'verified recovered final');restarted.close();
 }finally{controller.close();client.close();assert.equal(path.dirname(directory),path.resolve(os.tmpdir()));
  assert.ok(path.basename(directory).startsWith('codex-usage-disconnect-'));fs.rmSync(directory,{recursive:true,force:true});}
});
