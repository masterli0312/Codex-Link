'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {subscribeCommands,CommandDispatcher,retryDelay,readStream,publishEvent,pruneOutbox,transportFailure}=require('../../app/src/main/assets/task-notifications/remote.cjs');
const wait=ms=>new Promise(r=>setTimeout(r,ms));
test('a daily relay quota is distinguished from a brief request limit without storing server text',async()=>{
 const response=()=>new Response(JSON.stringify({code:42908,error:'private server detail'}),{status:429});
 await assert.rejects(publishEvent('https://fixture.invalid/topic',Buffer.alloc(32).toString('base64'),
  {id:crypto.randomUUID(),reply:''},async()=>response()),error=>{
   assert.equal(transportFailure(error),'DAILY_LIMIT');
   assert.ok(error.retryAfter>=300000);
   assert.equal(error.message,'TRANSPORT');
   assert.equal(JSON.stringify(error).includes('private server detail'),false);
   return true;
  });
});
test('recovery drops expired presence and catalog replies but keeps task completion and latest live text',()=>{
 const now=1000000;
 const queue=new Map([
  ['presence-old',{status:'presence',at:now-25001}],
  ['presence-fresh',{status:'presence',at:now-1000}],
  ['catalog-old',{status:'threads',at:now-45001}],
  ['snapshot-old',{status:'snapshot',at:now-60001}],
  ['completion',{status:'completed',at:now-900000}],
  ['live',{status:'running',at:now-900000}],
  ['approval',{status:'approval',at:now-900000}]]);
 pruneOutbox(queue,now);
 assert.deepEqual([...queue.keys()],['presence-fresh','completion','live','approval']);
});
function stalledFetch(_,options){
 return new Promise((resolve,reject)=>{
  options.signal.addEventListener('abort',()=>reject(options.signal.reason),{once:true});
 });
}
test('a stalled subscription handshake times out without retaining a socket',async()=>{
 await assert.rejects(subscribeCommands('https://fixture.invalid/json',()=>{},
  {fetchImpl:stalledFetch,headerMs:30,idleMs:100}),e=>e.name==='TimeoutError');
});
test('a silent open subscription times out while valid keepalives renew its lease',async()=>{
 let stream,ticks=0,aborted=false;
 const fetchImpl=async(_,options)=>{
  options.signal.addEventListener('abort',()=>{aborted=true;stream.error(options.signal.reason);},{once:true});
  return new Response(new ReadableStream({start(c){stream=c;}}));
 };
 const timer=setInterval(()=>{if(ticks++<3)stream?.enqueue(Buffer.from('{"event":"keepalive"}\n'));},15);
 const rows=[];
 try{
  await assert.rejects(subscribeCommands('https://fixture.invalid/json',r=>rows.push(r),
   {fetchImpl,headerMs:100,idleMs:65}),e=>e.name==='TimeoutError');
  assert.equal(rows.length,3);assert.equal(aborted,true);
 }finally{clearInterval(timer);}
});
test('closing a subscription cancels the old socket and removes its abort handler',async()=>{
 const abort=new AbortController();let ready;
 const entered=new Promise(r=>ready=r);
 const pending=subscribeCommands('https://fixture.invalid/json',()=>{},
  {signal:abort.signal,headerMs:100,idleMs:200,fetchImpl:(url,options)=>{ready();return stalledFetch(url,options);}});
 await entered;abort.abort();await assert.rejects(pending,e=>e.name==='AbortError');
});
test('HTTP rate limiting obeys seconds or date headers and repeated failures increase backoff',async()=>{
 assert.equal(retryDelay({status:429,retryAfter:120000},4000),120000);
 assert.equal(retryDelay({},4000),4000);
 assert.equal(retryDelay({},8000),8000);
 assert.equal(retryDelay({status:429},4000),60000);
 let cancelled=false;
 await assert.rejects(subscribeCommands('https://fixture.invalid/json',()=>{}, {fetchImpl:async()=>new Response(
  new ReadableStream({cancel(){cancelled=true;}}),{status:429,headers:{'Retry-After':'90'}})}),e=>e.status===429&&e.retryAfter===90000);
 assert.equal(cancelled,true);
 const now=Date.now(),date=new Date(now+120000).toUTCString();
 await assert.rejects(subscribeCommands('https://fixture.invalid/json',()=>{}, {fetchImpl:async()=>new Response('',
  {status:429,headers:{'Retry-After':date}})}),e=>e.status===429&&e.retryAfter>=118000&&e.retryAfter<=120000);
});
test('slow preparations never block presence or Stop, and duplicate in-flight IDs are not repeated',async()=>{
 let release,started;const actions=[],errors=[],entered=new Promise(r=>started=r);
 const dispatcher=new CommandDispatcher(async c=>{
  actions.push(c.action);if(c.action==='send'){started();await new Promise(r=>release=r);}
 },e=>errors.push(e));
 const original={id:crypto.randomUUID(),action:'send'};
 try{
  assert.equal(dispatcher.submit(original),true);await entered;
  assert.equal(dispatcher.submit(original),false);
  const rows=[{event:'message',command:{id:crypto.randomUUID(),action:'presence'}},
   {event:'message',command:{id:crypto.randomUUID(),action:'stop'}}];
  await readStream(new Response(rows.map(r=>JSON.stringify(r)).join('\n')+'\n'),
   row=>{dispatcher.submit(row.command);});
  await wait(0);assert.deepEqual(actions,['send','presence','stop']);assert.equal(errors.length,0);
 }finally{release();await dispatcher.idle();dispatcher.close();}
});
test('dispatcher reserves bounded control capacity, releases failures, and never delays another click',async()=>{
 const releases=[],errors=[];
 const dispatcher=new CommandDispatcher(c=>new Promise((r,j)=>releases.push(c.action==='models'?()=>j(Error('private failure')):r)),e=>errors.push(e),2);
 const cmd=action=>({id:crypto.randomUUID(),action});
 try{
  dispatcher.submit(cmd('send'));dispatcher.submit(cmd('models'));
  assert.throws(()=>dispatcher.submit(cmd('read')),/STREAM_OVERLOAD/);
  assert.equal(dispatcher.submit(cmd('stop')),true);assert.equal(dispatcher.submit(cmd('presence')),true);
  assert.throws(()=>dispatcher.submit(cmd('status')),/STREAM_OVERLOAD/);
  releases[1]();await wait(0);assert.equal(errors.length,1);
  assert.equal(dispatcher.submit(cmd('read')),true);
  dispatcher.close();assert.equal(dispatcher.submit(cmd('create')),false);
 }finally{for(const release of releases)release();await dispatcher.idle();}
});
test('the receive path processes authenticated presence and Stop during a real controller preparation',async()=>{
 const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
 const {RemoteController,encode,decode,validateCommand}=require('../../app/src/main/assets/task-notifications/remote-core.cjs');
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'codex-usage-transport-'));
 const key=Buffer.alloc(32,7).toString('base64'),host=crypto.randomUUID(),thread=crypto.randomUUID(),events=[];
 let release,entered,starts=0;const preparing=new Promise(r=>entered=r);
 const client={setHandlers(){},async connect(){},close(){},
  models(){entered();return new Promise(r=>release=()=>r([{model:'fixture',supportedReasoningEfforts:[{reasoningEffort:'low'}]}]));},
  async start(){starts++;throw Error('must not start');}};
 const controller=new RemoteController({directory:dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:dir}),emit:async e=>events.push(e)});
 const dispatcher=new CommandDispatcher(c=>controller.handle(c));
 const command=(action,extra={})=>({id:crypto.randomUUID(),action,host_id:host,thread_id:thread,
  conversation_id:crypto.createHash('sha256').update(thread).digest('hex'),baseline_turn:'previous',text:'',issued_at:Date.now(),...extra});
 const send=command('send',{text:'fixture',model:'fixture',effort:'low'});
 const row=c=>JSON.stringify({event:'message',message:JSON.stringify(encode('command',c.id,c,key))})+'\n';
 const receive=async body=>readStream(new Response(body),r=>{
  const c=decode('command',JSON.parse(r.message),key);if(validateCommand(c,host))dispatcher.submit(c);
 });
 try{
  await receive(row(send));await preparing;
  await receive(row(send)+row(command('presence'))+row(command('stop',{target_id:send.id})));
  await wait(0);assert.ok(events.some(e=>e.status==='presence'));
  assert.equal(events.findLast(e=>e.request_id===send.id).status,'interrupted');
  release();await dispatcher.idle();assert.equal(starts,0);
 }finally{
  dispatcher.close();controller.close();
  assert.equal(path.dirname(dir),path.resolve(os.tmpdir()));assert.ok(path.basename(dir).startsWith('codex-usage-transport-'));
  fs.rmSync(dir,{recursive:true,force:true});
 }
});
test('concurrent receive cannot start an ordinary task while a goal is preparing',async()=>{
 const {RemoteController}=require('../../app/src/main/assets/task-notifications/remote-core.cjs');
 const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'codex-usage-transport-'));
 const host=crypto.randomUUID(),thread=crypto.randomUUID(),events=[];let starts=0;
 const controller=new RemoteController({directory:dir,key:Buffer.alloc(32,5).toString('base64'),hostId:host,
  clientFactory:()=>{starts++;throw Error('must not connect');},emit:async e=>events.push(e)});
 try{
  controller.goalBusy=true;
  await controller.handle({id:crypto.randomUUID(),action:'send',host_id:host,thread_id:thread,
   conversation_id:crypto.createHash('sha256').update(thread).digest('hex'),baseline_turn:'previous',text:'fixture',issued_at:Date.now()});
  assert.equal(starts,0);assert.equal(events.at(-1).error,'HOST_BUSY');
 }finally{controller.close();assert.equal(path.dirname(dir),path.resolve(os.tmpdir()));
  assert.ok(path.basename(dir).startsWith('codex-usage-transport-'));fs.rmSync(dir,{recursive:true,force:true});}
});
test('real loopback sockets recover from stalled headers and idle bodies, retaining a healthy heartbeat stream',async()=>{
 const http=require('node:http');const sockets=new Set();let closed=0,heartbeatTimer;
 const server=http.createServer((req,res)=>{
  if(req.url==='/headers')return;
  res.writeHead(200,{'Content-Type':'application/x-ndjson'});res.write('{"event":"open"}\n');
  if(req.url==='/idle')return;
  let tick=0;heartbeatTimer=setInterval(()=>{
   res.write('{"event":"keepalive"}\n');if(++tick===3){clearInterval(heartbeatTimer);res.end();}
  },150);
 });
 server.on('connection',socket=>{sockets.add(socket);socket.once('close',()=>{sockets.delete(socket);closed++;});});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const base='http://127.0.0.1:'+server.address().port;
 try{
  await assert.rejects(subscribeCommands(base+'/headers',()=>{}, {headerMs:300,idleMs:400}),e=>e.name==='TimeoutError');
  await assert.rejects(subscribeCommands(base+'/idle',()=>{}, {headerMs:1000,idleMs:300}),e=>e.name==='TimeoutError');
  const rows=[];await subscribeCommands(base+'/healthy',r=>rows.push(r),{headerMs:1000,idleMs:400});
  assert.equal(rows.filter(r=>r.event==='open').length,1);assert.equal(rows.filter(r=>r.event==='keepalive').length,3);
  // Both deliberately stalled HTTP connections must actually have closed, not just raced a timer.
  assert.ok(closed>=2);
 }finally{
  clearInterval(heartbeatTimer);for(const socket of sockets)socket.destroy();
  await new Promise(r=>server.close(r));
 }
});
