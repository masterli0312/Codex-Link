'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),crypto=require('node:crypto');
const {commandTopic,eventTopic,encode,decode,validateCommand,resolveThread,RemoteController}=require('../../app/src/main/assets/task-notifications/remote-core.cjs');
const key=Buffer.alloc(32,9).toString('base64'),host=crypto.randomUUID(),thread=crypto.randomUUID();
const hash=s=>crypto.createHash('sha256').update(s).digest('hex');
test('provider failures are categorized without relaying private error text',()=>{
 const {providerFailure}=require('../../app/src/main/assets/task-notifications/remote-core.cjs');
 assert.equal(providerFailure({codexErrorInfo:{responseTooManyFailedAttempts:{httpStatusCode:429}},message:'SECRET'}),'RATE_LIMIT');
 assert.equal(providerFailure({codexErrorInfo:'unauthorized',message:'SECRET'}),'AUTH_REQUIRED');
 assert.equal(providerFailure({codexErrorInfo:{httpConnectionFailed:{httpStatusCode:403}},message:'SECRET'}),'ACCESS_DENIED');
 assert.equal(providerFailure({codexErrorInfo:'contextWindowExceeded'}),'CONTEXT_FULL');
 assert.equal(providerFailure({message:'SECRET'}),'CODEX_ERROR');
});
test('resuming an old thread omits its giant transcript without changing its provider',async()=>{
 const {CodexClient}=require('../../app/src/main/assets/task-notifications/codex-client.cjs');
 const client=new CodexClient('fixture','fixture');let params;
 client.call=async(method,p)=>{assert.equal(method,'thread/resume');params=p;return {thread:{id:thread,turns:[]}};};
 await client.resume(thread,{cwd:'fixture',model:'previous-model',modelProvider:'previous-provider'});
 assert.equal(params.excludeTurns,true);assert.equal(params.modelProvider,'previous-provider');assert.equal(params.model,'previous-model');
 client.close();
});
function command(extra={}){return {id:crypto.randomUUID(),action:'send',host_id:host,thread_id:thread,conversation_id:hash(thread),baseline_turn:'previous',text:'请继续',issued_at:Date.now(),...extra};}
function fixture(){const dir=fs.mkdtempSync(path.join(os.tmpdir(),'codex-usage-remote-test-'));fs.mkdirSync(path.join(dir,'sessions'));return {dir,cleanup(){assert.equal(path.dirname(dir),path.resolve(os.tmpdir()));assert.ok(path.basename(dir).startsWith('codex-usage-remote-test-'));fs.rmSync(dir,{recursive:true,force:true});}};}

test('watch recovers a missed terminal event without rolling back a newer live delta',async()=>{
 const f=fixture(),events=[];let latest='old',status='idle',duringRead;
 const client={supportsLiveWatch:true,setHandlers(event){this.event=event;},async connect(){},close(){},reject(){},async call(m){
  if(m==='thread/resume')return {};
  if(m==='thread/read')return {thread:{id:thread,updatedAt:Math.floor(Date.now()/1000),status:{type:status}}};
  assert.equal(m,'thread/turns/list');duringRead?.();
  return {data:[{id:latest,status:'completed',items:[{id:'a',type:'agentMessage',text:'complete '+latest}]}]};
 }};
 const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,emit:async e=>events.push(e)});
 try{
  await controller.handle(command({action:'read',read_thread_id:thread,watch:true,text:''}));
  status='active';client.event('turn/started',{threadId:thread,turn:{id:'new'}});
  client.event('item/agentMessage/delta',{threadId:thread,turnId:'new',itemId:'a',delta:'partial'});
  latest='new';status='idle';controller.watching.lastRead=Date.now()-3000;
  await controller.pollWatch();
  assert.equal(events.at(-1).snapshot.running,false);
  assert.equal(events.at(-1).snapshot.remote_ref.baseline_turn,'new');
  assert.equal(events.at(-1).snapshot.reply,'complete new');
  status='active';client.event('turn/started',{threadId:thread,turn:{id:'next'}});
  latest='new';status='idle';controller.watching.lastRead=Date.now()-3000;
  const before=events.length;await controller.pollWatch();assert.equal(events.length,before);
  assert.equal(controller.watching.live.turn,'next');assert.equal(controller.watching.live.value.running,true);
  latest='next';controller.watching.lastRead=Date.now()-3000;
  duringRead=()=>client.event('item/agentMessage/delta',{threadId:thread,turnId:'next',itemId:'a',delta:'newer live'});
  await controller.pollWatch();assert.equal(events.length,before);
  assert.equal(controller.watching.live.value.running,true);assert.equal(controller.watching.live.value.reply,'newer live');
 }finally{controller.close();f.cleanup();}
});
test('local relay health is a closed metadata schema and never prints private failure text',()=>{
 const {createHealth,transportFailure}=require('../../app/src/main/assets/task-notifications/remote.cjs');
 const f=fixture();try{
  const health=createHealth(f.dir,()=>1000);
  health.update({connected:true,lastCommandAt:1100,commandsReceived:1,endpoint:'PRIVATE',contentKey:'PRIVATE',reply:'PRIVATE',lastReceiveError:'PRIVATE',lastEventKind:'PRIVATE',lastCommandKind:'threads',lastThreadCount:1});
  const value=JSON.parse(fs.readFileSync(path.join(f.dir,'remote-health.json'),'utf8'));
  assert.equal(value.connected,true);assert.equal(value.commandsReceived,1);assert.equal(value.lastReceiveError,'');
  assert.equal(value.lastEventKind,'');assert.equal(value.lastCommandKind,'threads');assert.equal(value.lastThreadCount,1);
  assert.equal(JSON.stringify(value).includes('PRIVATE'),false);
  assert.equal(transportFailure({status:429,message:'PRIVATE'}),'RATE_LIMIT');
  assert.equal(transportFailure({name:'TimeoutError',message:'PRIVATE'}),'TIMEOUT');
  assert.equal(transportFailure({message:'PRIVATE'}),'NETWORK');
  health.update({commandsReceived:-1,lastCommandAt:'PRIVATE',lastReceiveError:'NETWORK'});
  assert.equal(health.snapshot().commandsReceived,1);assert.equal(health.snapshot().lastCommandAt,1100);
 }finally{f.cleanup();}
});
test('direction-separated encryption, paired host topics and freshness protect remote commands',()=>{
 const c=command(),wire=encode('command',c.id,c,key);
 assert.equal(JSON.stringify(wire).includes(c.text),false);
 assert.deepEqual(decode('command',wire,key),c);
 assert.equal(decode('event',wire,key),null);
 assert.equal(decode('command',{...wire,id:crypto.randomUUID()},key),null);
 assert.notEqual(commandTopic('https://ntfy.sh/paired',key,host),commandTopic('https://ntfy.sh/paired',key,crypto.randomUUID()));
 assert.equal(validateCommand(c,host),true);
 for(const v of [command({action:'command/exec'}),command({thread_id:'../../secret'}),command({host_id:crypto.randomUUID()}),command({issued_at:Date.now()-130000}),command({text:'文'.repeat(6000)})])assert.equal(validateCommand(v,host),false);
});
test('only the latest explicitly completed local thread may be resumed; phone cannot choose cwd',()=>{
 const f=fixture();try{
  const file=path.join(f.dir,'sessions','rollout-'+thread+'.jsonl');
  const line=p=>JSON.stringify(p)+'\n';
  fs.writeFileSync(file,line({type:'session_meta',payload:{id:thread,cwd:f.dir,source:'vscode'}})+line({type:'turn_context',payload:{model:'existing-model'}})+line({type:'event_msg',payload:{type:'task_complete',turn_id:'previous'}}));
  assert.equal(resolveThread({codexHome:f.dir},command()).cwd,f.dir);
  assert.throws(()=>resolveThread({codexHome:f.dir},command({baseline_turn:'older'})),/STALE/);
  fs.appendFileSync(file,line({type:'event_msg',payload:{type:'task_started',turn_id:'next'}}));
  assert.throws(()=>resolveThread({codexHome:f.dir},command()),/BUSY/);
 }finally{f.cleanup();}
});
function fakeClient(){const client={starts:0,callbacks:{},async connect(){},async call(method){if(method==='thread/goal/get')return {goal:null};throw Error('UNEXPECTED_RPC');},async read(){return {thread:{status:{type:'notLoaded'}}};},async resume(){},async start(){this.starts++;return {turn:{id:'new-turn'}};},async interrupt(){return this.callbacks.event?.('turn/completed',{threadId:thread,turn:{id:'new-turn',status:'interrupted',items:[]}});},close(){},setHandlers(event,request){this.callbacks={event,request};}};return client;}

test('a shared desktop turn cannot replace the confirmed phone turn or receive its approval',async()=>{
 const f=fixture(),client=fakeClient(),events=[],answers=[];let controller;
 try{
  client.reject=()=>{};client.answer=(id,value)=>answers.push({id,value});
  controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>events.push(e)});
  const c=command();await controller.handle(c);
  await client.callbacks.event('turn/started',{threadId:thread,turn:{id:'desktop-turn'}});
  await client.callbacks.event('item/agentMessage/delta',{threadId:thread,turnId:'desktop-turn',itemId:'other',delta:'OTHER_TASK'});
  await client.callbacks.request(70,'item/commandExecution/requestApproval',{threadId:thread,turnId:'desktop-turn',itemId:'other',command:'fixture'});
  assert.equal(controller.active.turn,'new-turn');assert.equal(controller.active.reply,'');assert.equal(controller.active.pending.size,0);
  assert.equal(events.some(e=>e.status==='approval'),false);assert.deepEqual(answers,[]);
 }finally{controller?.close();f.cleanup();}
});

test('shared events during preparation cannot claim a phone turn that has not started',async()=>{
 const f=fixture(),client=fakeClient(),events=[];let controller;
 try{
  client.reject=()=>{};
  client.connect=async()=>{
   await client.callbacks.event('turn/started',{threadId:thread,turn:{id:'desktop-turn'}});
   await client.callbacks.request(71,'item/commandExecution/requestApproval',{threadId:thread,turnId:'desktop-turn',itemId:'other',command:'fixture'});
   assert.equal(controller.active.turn,null);assert.equal(controller.active.pending.size,0);
  };
  controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>events.push(e)});
  await controller.handle(command());assert.equal(client.starts,1);assert.equal(controller.active.turn,'new-turn');
  assert.equal(events.some(e=>e.status==='approval'),false);
 }finally{controller?.close();f.cleanup();}
});

test('early shared frames are matched to the acknowledged start before exposing text or approval',async()=>{
 const f=fixture(),client=fakeClient(),events=[];let controller;
 try{
  client.reject=()=>{};
  client.start=async()=>{
   client.starts++;
   await client.callbacks.event('turn/started',{threadId:thread,turn:{id:'desktop-turn'}});
   await client.callbacks.request(72,'item/commandExecution/requestApproval',{threadId:thread,turnId:'desktop-turn',itemId:'other',command:'fixture'});
   await client.callbacks.event('turn/started',{threadId:thread,turn:{id:'new-turn'}});
   await client.callbacks.event('item/agentMessage/delta',{threadId:thread,turnId:'new-turn',itemId:'own',delta:'OWN_REPLY'});
   await client.callbacks.request(73,'item/commandExecution/requestApproval',{threadId:thread,turnId:'new-turn',itemId:'own',command:'fixture'});
   assert.equal(events.some(e=>e.status==='approval'),false);
   return {turn:{id:'new-turn'}};
  };
  controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>events.push(e)});
  await controller.handle(command());assert.equal(controller.active.turn,'new-turn');assert.equal(controller.active.reply,'OWN_REPLY');
  assert.equal(controller.active.pending.size,1);assert.equal(events.at(-1).status,'approval');
  assert.equal([...controller.active.pending.values()][0].id,73);
 }finally{controller?.close();f.cleanup();}
});

test('an early native completion stays terminal instead of reverting to running after the start reply',async()=>{
 const f=fixture(),client=fakeClient(),events=[];let controller;
 try{
  client.start=async()=>{
   client.starts++;
   await client.callbacks.event('turn/completed',{threadId:thread,turn:{id:'new-turn',status:'completed'}});
   return {turn:{id:'new-turn'}};
  };
  controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>events.push(e)});
  await controller.handle(command());assert.equal(client.starts,1);assert.equal(controller.active,null);assert.equal(events.at(-1).status,'completed');
 }finally{controller?.close();f.cleanup();}
});

test('unconfirmed start buffering is bounded and cannot cause a repeated native start',async()=>{
 const f=fixture(),client=fakeClient(),events=[];let controller;
 try{
  client.start=async()=>{
   client.starts++;
   for(let i=0;i<130;i++)await client.callbacks.event('item/agentMessage/delta',{threadId:thread,turnId:'new-turn',itemId:'own',delta:'x'});
   return {turn:{id:'new-turn'}};
  };
  controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>events.push(e)});
  const c=command();await controller.handle(c);assert.equal(controller.active,null);assert.equal(events.at(-1).status,'unknown');
  await controller.handle(c);assert.equal(client.starts,1);assert.equal(events.at(-1).status,'unknown');
  assert.equal(events.some(e=>e.reply?.includes('x')),false);
 }finally{controller?.close();f.cleanup();}
});

test('a queued turn with early native completion preserves both confirmed terminal results',async()=>{
 const f=fixture(),client=fakeClient(),events=[];let controller;
 try{
  controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>events.push(e)});
  const c=command();await controller.handle(c);
  const next=command({action:'queue',target_id:c.id,expected_turn_id:'new-turn',text:'Queued fixture'});await controller.handle(next);
  client.start=async()=>{
   client.starts++;
   await client.callbacks.event('turn/started',{threadId:thread,turn:{id:'queued-turn'}});
   await client.callbacks.event('turn/completed',{threadId:thread,turn:{id:'queued-turn',status:'completed'}});
   return {turn:{id:'queued-turn'}};
  };
  await client.callbacks.event('turn/completed',{threadId:thread,turn:{id:'new-turn',status:'completed'}});
  assert.equal(client.starts,2);assert.equal(controller.active,null);
  assert.equal(events.findLast(e=>e.request_id===next.id).status,'completed');assert.equal(events.at(-1).status,'completed');
 }finally{controller?.close();f.cleanup();}
});

test('watch sends complete changed native messages, never executes a turn, and expires',async()=>{
 const f=fixture(),events=[],calls=[];let revision=1,reply='first',closed=0;
 const client={setHandlers(){},async connect(){},close(){closed++;},call:async(m,p)=>{
   calls.push(m);assert.equal(p.threadId,thread);
   if(m==='thread/read')return {thread:{id:thread,name:'fixture',updatedAt:1000,status:{type:'notLoaded'}}};
   assert.equal(m,'thread/turns/list');assert.equal(p.itemsView,'full');
   return {data:[{id:'latest',status:'completed',items:[{id:'a',type:'agentMessage',text:reply}]}]};
 }};
 const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,readRevision:()=>String(revision),emit:async e=>events.push(e)});
 try{
  const c=command({action:'read',read_thread_id:thread,watch:true,text:''});
  await controller.handle(c);assert.equal(events.length,1);assert.equal(events[0].snapshot.messages[0].text,'first');
  await controller.pollWatch();assert.equal(events.length,1);assert.equal(calls.filter(m=>m==='thread/turns/list').length,1);
  revision++;reply='latest desktop reply';await controller.pollWatch();assert.equal(events.length,2);
  assert.equal(events[1].snapshot.messages[0].text,reply);assert.ok(events[1].seq>events[0].seq);
  revision++;await controller.pollWatch();assert.equal(events.length,2); // no publish for identical visible state
  controller.watching.expiresAt=Date.now()-1;await controller.pollWatch();assert.equal(controller.watching,null);assert.equal(closed,1);
  assert.equal(calls.some(m=>['thread/resume','turn/start'].includes(m)),false);
  assert.equal(validateCommand({...c,read_thread_id:'../../secret'},host),false);
  assert.equal(validateCommand(command({watch:true}),host),false);
 }finally{controller.close();f.cleanup();}
});

test('shared watch publishes native deltas before persistence and abstains from desktop approvals',async()=>{
 const f=fixture(),events=[],calls=[];let notify,request;
 const client={supportsLiveWatch:true,setHandlers(n,r){notify=n;request=r;},async connect(){},close(){},reject(){},async call(m,p){
  calls.push([m,p]);
  if(m==='thread/read')return {thread:{id:thread,name:'fixture',updatedAt:1000,status:{type:'notLoaded'}}};
  if(m==='thread/resume'){assert.deepEqual(p,{threadId:thread,excludeTurns:true});return {};}
  assert.equal(m,'thread/turns/list');return {data:[{id:'old',status:'completed',items:[{id:'a',type:'agentMessage',text:'persisted old'}]}]};
 }};
 const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,emit:async e=>events.push(e)});
 try{
  await controller.handle(command({action:'read',read_thread_id:thread,watch:true,text:''}));
  notify('turn/started',{threadId:thread,turn:{id:'desktop-live',status:'inProgress'}});
  notify('item/agentMessage/delta',{threadId:thread,turnId:'desktop-live',itemId:'answer',delta:'live before save'});
  request(10,'item/commandExecution/requestApproval',{threadId:thread});
  await new Promise(r=>setTimeout(r,240));
  assert.equal(events.at(-1).snapshot.reply,'live before save');assert.equal(events.at(-1).snapshot.running,true);
  controller.watching.lastRead=0;await controller.pollWatch();
  assert.equal(events.at(-1).snapshot.reply,'live before save'); // stale persistence cannot regress the delta
  notify('turn/completed',{threadId:thread,turn:{id:'desktop-live',status:'completed',items:[]}});
  assert.equal(events.at(-1).snapshot.running,false);assert.equal(events.at(-1).snapshot.remote_ref.baseline_turn,'desktop-live');
  assert.equal(calls.some(([m])=>['turn/start','turn/interrupt'].includes(m)),false);
  const before=events.length;controller.stopWatching();
  notify('item/agentMessage/delta',{threadId:thread,turnId:'desktop-live',itemId:'answer',delta:'late'});
  await new Promise(r=>setTimeout(r,240));assert.equal(events.length,before);
 }finally{controller.close();f.cleanup();}
});

test('shared subscribe races keep newer live events and a replaced socket reconnects without generation',async()=>{
 const f=fixture(),events=[];let count=0,notify;
 const controller=new RemoteController({directory:f.dir,key,hostId:host,emit:async e=>events.push(e),clientFactory:()=>{
  count++;return {supportsLiveWatch:true,setHandlers(n){notify=n;},async connect(){},close(){},async call(m){
   if(m==='thread/read')return {thread:{id:thread,updatedAt:1000}};
   if(m==='thread/resume'){
    notify('turn/started',{threadId:thread,turn:{id:'new-live',status:'inProgress'}});
    notify('item/agentMessage/delta',{threadId:thread,turnId:'new-live',itemId:'a',delta:'new'});return {};
   }
   assert.equal(m,'thread/turns/list');return {data:[]};
  }};
 }});
 try{
  await controller.handle(command({action:'read',read_thread_id:thread,watch:true,text:''}));
  assert.equal(controller.watching.live.value.reply,'new');
  notify('bridge/disconnected',{});await controller.pollWatch();assert.equal(count,2);
  assert.equal(controller.watching.live.value.reply,'new');
 }finally{controller.close();f.cleanup();}
});

test('a replacement watch isolates conversations and a read error never clears cached history',async()=>{
 const f=fixture(),events=[],other=crypto.randomUUID();let failed=false;
 const controller=new RemoteController({directory:f.dir,key,hostId:host,emit:async e=>events.push(e),clientFactory:()=>({
  setHandlers(){},async connect(){},close(){},async call(m,p){
   if(failed)throw Error('PRIVATE');
   if(m==='thread/read')return {thread:{id:p.threadId,updatedAt:1000}};
   return {data:[{id:'done',status:'completed',items:[{type:'agentMessage',text:'visible'}]}]};
  }
 })});
 try{
  const first=command({action:'read',read_thread_id:thread,watch:true,text:''});await controller.handle(first);const previous=controller.watching;
  const next=command({action:'read',read_thread_id:other,watch:true,text:''});await controller.handle(next);
  await controller.pollWatch(previous);assert.equal(events.length,2);assert.equal(events[1].request_id,next.id);
  assert.equal(events[1].snapshot.conversation_id,hash(other));
  failed=true;await controller.pollWatch();assert.equal(events.at(-1).error,'LIBRARY_UNAVAILABLE');
  assert.equal(events.at(-1).snapshot,undefined);assert.equal(JSON.stringify(events.at(-1)).includes('PRIVATE'),false);
  assert.equal(controller.watching,null);
 }finally{controller.close();f.cleanup();}
});
test('duplicate and crash-replayed send IDs never execute a second turn',async()=>{
 const f=fixture(),client=fakeClient(),updates=[];try{
  const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>updates.push(e)});
  const c=command();await controller.handle(c);await controller.handle(c);
  assert.equal(client.starts,1);assert.ok(updates.some(e=>e.status==='running'));
  const restarted=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>updates.push(e)});
  await restarted.handle(c);assert.equal(client.starts,1);assert.ok(updates.some(e=>e.status==='unknown'));
  const plain=fs.readFileSync(path.join(f.dir,'remote-journal.json'),'utf8');assert.ok(!plain.includes(c.text));
  controller.close();
 }finally{f.cleanup();}
});
test('busy conversations and a second concurrent request are rejected before generation',async()=>{
 const f=fixture(),client=fakeClient(),updates=[];try{
  const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>updates.push(e)});
  await controller.handle(command());await controller.handle(command());
  assert.equal(client.starts,1);assert.ok(updates.some(e=>e.error==='HOST_BUSY'));controller.close();
 }finally{f.cleanup();}
});
test('desktop writer lock has a specific safe error and never starts or retries a turn',async()=>{
 const f=fixture(),client=fakeClient(),events=[];try{
  client.resume=async()=>{throw Object.assign(Error('CODEX_RPC'),{rpcCode:-32600,rpcMessage:'thread already has an active writer PRIVATE'});};
  const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>events.push(e)});
  const c=command();await controller.handle(c);await controller.handle(c);
  assert.equal(client.starts,0);assert.equal(events.at(-1).error,'DESKTOP_OWNS_THREAD');assert.equal(events.at(-1).status,'failed');
  assert.equal(JSON.stringify(events).includes('PRIVATE'),false);assert.equal(controller.active,null);controller.close();
 }finally{f.cleanup();}
});
test('stopping is bound to the actual active request, not another conversation',async()=>{
 const f=fixture(),client=fakeClient(),updates=[];try{
  const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>updates.push(e)});
  const c=command();await controller.handle(c);
  await controller.handle(command({action:'stop',target_id:crypto.randomUUID(),text:''}));assert.ok(!updates.some(e=>e.status==='interrupted'));
  await controller.handle(command({action:'stop',target_id:c.id,text:''}));assert.ok(updates.some(e=>e.status==='interrupted'));controller.close();
 }finally{f.cleanup();}
});

test('stop during initial model lookup cannot start a task after the lookup returns',async()=>{
 const f=fixture(),client=fakeClient(),events=[];let release,entered;
 const checking=new Promise(r=>entered=r);
 try {
  client.models=()=>{entered();return new Promise(r=>release=()=>r([{model:'available',supportedReasoningEfforts:[{reasoningEffort:'low'}]}]));};
  const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>events.push(e)});
  const original=command({model:'available',effort:'low'}),send=controller.handle(original);
  await checking;await controller.handle(command({action:'stop',target_id:original.id,text:''}));
  release();await send;
  assert.equal(client.starts,0);assert.equal(events.findLast(e=>e.request_id===original.id).status,'interrupted');
  await controller.handle(original);assert.equal(client.starts,0);assert.equal(controller.active,null);controller.close();
 }finally{f.cleanup();}
});

test('stop while turn start is in flight interrupts the real turn exactly once',async()=>{
 const f=fixture(),client=fakeClient(),events=[],interrupts=[];let release,entered;
 const starting=new Promise(r=>entered=r);
 try {
  client.start=async()=>{client.starts++;entered();return new Promise(r=>release=()=>r({turn:{id:'new-turn'}}));};
  client.interrupt=async(_,id)=>{interrupts.push(id);};
  const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>events.push(e)});
  const original=command(),send=controller.handle(original);await starting;
  await controller.handle(command({action:'stop',target_id:original.id,text:''}));
  await client.callbacks.event('turn/started',{threadId:thread,turn:{id:'new-turn'}});
  release();await send;
  await controller.handle(command({action:'stop',target_id:original.id,text:''}));
  assert.deepEqual(interrupts,['new-turn']);assert.equal(client.starts,1);
  assert.equal(events.findLast(e=>e.request_id===original.id).status,'accepted');
  await client.callbacks.event('turn/completed',{threadId:thread,turn:{id:'new-turn',status:'interrupted'}});
  assert.equal(events.findLast(e=>e.request_id===original.id).status,'interrupted');controller.close();
 }finally{f.cleanup();}
});
test('permission requests wait for an explicit matching answer and do not auto-approve',async()=>{
 const f=fixture(),client=fakeClient(),updates=[],responses=[];try{
  client.answer=(id,result)=>responses.push({id,result});
  const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>updates.push(e)});
  const c=command();await controller.handle(c);
  await client.callbacks.request(90,'item/commandExecution/requestApproval',{threadId:thread,turnId:'new-turn',itemId:'cmd',command:'fixture'});
  assert.equal(responses.length,0);assert.ok(updates.some(e=>e.status==='approval'));
  const approval=updates.at(-1).approval_id;assert.notEqual(approval,'90');
  await controller.handle(command({action:'approve',target_id:c.id,approval_id:crypto.randomUUID(),expected_turn_id:'new-turn',allow:true,text:''}));assert.equal(responses.length,0);
  await controller.handle(command({action:'approve',target_id:c.id,approval_id:approval,expected_turn_id:'old-turn',allow:true,text:''}));assert.equal(responses.length,0);
  await controller.handle(command({action:'approve',target_id:c.id,approval_id:approval,expected_turn_id:'new-turn',allow:false,text:''}));assert.deepEqual(responses[0],{id:90,result:{decision:'decline'}});controller.close();
 }finally{f.cleanup();}
});

test('reused native approval IDs never let an old phone action approve a new request',async()=>{
 const f=fixture(),client=fakeClient(),events=[],responses=[];try{
  client.answer=(id,result)=>responses.push({id,result});client.reject=()=>{};
  const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>events.push(e)});
  const root=command();await controller.handle(root);
  const p={threadId:thread,turnId:'new-turn',itemId:'one',command:'fixture'};
  await client.callbacks.request(90,'item/commandExecution/requestApproval',p);const first=events.at(-1).approval_id;
  await client.callbacks.event('serverRequest/resolved',{threadId:thread,requestId:90});
  await client.callbacks.request(90,'item/commandExecution/requestApproval',{...p,itemId:'two'});const second=events.at(-1).approval_id;
  assert.notEqual(first,second);
  const answer=command({action:'approve',target_id:root.id,approval_id:first,expected_turn_id:'new-turn',allow:true,text:''});
  await controller.handle(answer);assert.equal(responses.length,0);
  await controller.handle({...answer,approval_id:second});await controller.handle({...answer,approval_id:second});
  assert.deepEqual(responses,[{id:90,result:{decision:'accept'}}]);controller.close();
 }finally{f.cleanup();}
});

test('file approval that requests a lasting grant cannot be accepted by the one-time mobile control',async()=>{
 const f=fixture(),client=fakeClient(),events=[],responses=[];try{
  client.answer=(id,result)=>responses.push({id,result});client.reject=()=>{};
  const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>events.push(e)});
  const root=command();await controller.handle(root);
  await client.callbacks.request(90,'item/fileChange/requestApproval',{threadId:thread,turnId:'new-turn',itemId:'patch',grantRoot:f.dir});
  const event=events.at(-1);assert.equal(event.status,'approval');assert.equal(event.approval_can_allow,false);
  const answer=command({action:'approve',target_id:root.id,approval_id:event.approval_id,expected_turn_id:'new-turn',allow:true,text:''});
  await controller.handle(answer);assert.equal(responses.length,0);
  await controller.handle({...answer,allow:false});assert.deepEqual(responses,[{id:90,result:{decision:'decline'}}]);controller.close();
 }finally{f.cleanup();}
});

test('real requestUserInput is answered only for the exact request, turn and question set',async()=>{
 const f=fixture(),client=fakeClient(),updates=[],responses=[];try{
  client.answer=(id,result)=>responses.push({id,result});client.reject=()=>{};
  const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>updates.push(e)});
  const c=command();await controller.handle(c);
  const params={threadId:thread,turnId:'new-turn',itemId:'input-item',isBlocking:true,
    questions:[{id:'choice',header:'Choose',question:'Which option?',isOther:false,isSecret:false,
      options:[{label:'One',description:'First'},{label:'Two',description:'Second'}]},
      {id:'detail',header:'Detail',question:'Add a detail',options:null,isSecret:true}]};
  await client.callbacks.request(91,'item/tool/requestUserInput',params);
  const e=updates.at(-1);assert.equal(e.status,'input_required');assert.equal(e.user_input.questions.length,2);
  assert.equal(e.user_input.questions[1].is_secret,true);assert.equal(responses.length,0);
  const answer=command({action:'answer_input',text:'',target_id:c.id,input_id:e.user_input.id,
    expected_turn_id:'new-turn',answers:{choice:['Two'],detail:['PRIVATE_FIXTURE_VALUE']}});
  assert.equal(validateCommand(answer,host),true);
  await controller.handle({...answer,expected_turn_id:'old-turn'});assert.equal(responses.length,0);
  await controller.handle({...answer,answers:{choice:['Three'],detail:['PRIVATE_FIXTURE_VALUE']}});assert.equal(responses.length,0);
  await controller.handle({...answer,answers:{choice:['One']}});assert.equal(responses.length,0);
  await controller.handle(answer);await controller.handle(answer);
  assert.equal(responses.length,1);assert.deepEqual(responses[0],{id:91,result:{answers:{choice:{answers:['Two']},detail:{answers:['PRIVATE_FIXTURE_VALUE']}}}});
  assert.equal(updates.at(-1).status,'running');
  assert.equal(fs.readFileSync(path.join(f.dir,'remote-journal.json'),'utf8').includes('PRIVATE_FIXTURE_VALUE'),false);
  controller.close();
 }finally{f.cleanup();}
});

test('request resolution and reused RPC ids cannot apply old answers to a new input request',async()=>{
 const f=fixture(),client=fakeClient(),updates=[],responses=[];try{
  client.answer=(id,result)=>responses.push({id,result});client.reject=()=>{};
  const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>updates.push(e)});
  const c=command();await controller.handle(c);
  const p={threadId:thread,turnId:'new-turn',itemId:'item',isBlocking:false,questions:[{id:'q',header:'Question',question:'Text?',options:null}]};
  await client.callbacks.request(92,'item/tool/requestUserInput',p);const old=updates.at(-1).user_input.id;
  await client.callbacks.event('serverRequest/resolved',{threadId:thread,requestId:92});assert.equal(updates.at(-1).status,'running');
  await client.callbacks.request(92,'item/tool/requestUserInput',p);assert.notEqual(updates.at(-1).user_input.id,old);
  await controller.handle(command({action:'answer_input',text:'',target_id:c.id,input_id:old,expected_turn_id:'new-turn',answers:{q:['old']}}));
  assert.equal(responses.length,0);controller.close();
 }finally{f.cleanup();}
});

test('another thread or turn cannot replace the active user-input request',async()=>{
 const f=fixture(),client=fakeClient(),updates=[],rejected=[];try{
  client.reject=id=>rejected.push(id);
  const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>updates.push(e)});
  const c=command();await controller.handle(c);
  const p={threadId:thread,turnId:'old-turn',itemId:'item',isBlocking:true,questions:[{id:'q',header:'Q',question:'Text?'}]};
  await client.callbacks.request(93,'item/tool/requestUserInput',p);
  assert.deepEqual(rejected,[93]);assert.equal(updates.at(-1).status,'running');assert.equal(controller.active.c.id,c.id);controller.close();
 }finally{f.cleanup();}
});

test('interrupt waits for turn/started rather than relying on an early start response',async()=>{
 const {CodexClient}=require('../../app/src/main/assets/task-notifications/codex-client.cjs');
 const client=new CodexClient('fixture','fixture');let calls=0;
 client.call=async()=>{calls++;};
 const stopped=client.interrupt(thread,'actual-turn');await Promise.resolve();assert.equal(calls,0);
 client.message({method:'turn/started',params:{threadId:thread,turn:{id:'actual-turn'}}});
 await stopped;assert.equal(calls,1);client.close();
});

test('an ambiguous turn/start error is never reported as a definitely failed send',async()=>{
 const f=fixture(),client=fakeClient(),updates=[];try{
  client.start=async()=>{client.starts++;throw Error('timeout after accepting');};
  const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>updates.push(e)});
  const c=command();await controller.handle(c);await controller.handle(c);
  assert.equal(client.starts,1);assert.equal(updates.at(-1).status,'unknown');controller.close();
 }finally{f.cleanup();}
});

test('large replies use encrypted attachments; transport refusal is never an acknowledgement',async()=>{
 const {publishEvent}=require('../../app/src/main/assets/task-notifications/remote.cjs');
 const e={...command(),reply:'中文'.repeat(2000),request_id:crypto.randomUUID(),status:'completed',seq:1,at:Date.now()};
 let payload;
 await publishEvent('https://ntfy.sh/fixture',key,e,async(url,options)=>{payload=options;return {ok:true,arrayBuffer:async()=>new ArrayBuffer(0)};});
 assert.equal(payload.headers.Filename,'codex-remote.bin');
 assert.equal(payload.body.includes(Buffer.from('中文')),false);
 const preview=decode('event',JSON.parse(payload.headers.Message),key);assert.equal(preview.partial,true);
 const full=decode('event',{schema_version:'2.0',id:e.id,encrypted:payload.body.toString('base64')},key);assert.equal(full.reply,e.reply);
 await assert.rejects(publishEvent('https://ntfy.sh/fixture',key,e,async()=>({ok:false,status:429,arrayBuffer:async()=>new ArrayBuffer(0)})),/TRANSPORT/);
});

test('command attachments are authenticated, bounded and restricted to the paired origin',async()=>{
 const {downloadCommand}=require('../../app/src/main/assets/task-notifications/remote.cjs');
 const c=command({text:'文'.repeat(3000)}),full=encode('command',c.id,c,key),preview=encode('command',c.id,{id:c.id,attachment:true},key);
 const row={message:JSON.stringify(preview),attachment:{url:'https://ntfy.sh/file/fixture.bin',name:'codex-command.bin',size:10000}};
 let fetched=0;const fetcher=async()=>{fetched++;return {ok:true,body:(async function*(){yield Buffer.from(full.encrypted,'base64');})()};};
 assert.deepEqual(await downloadCommand('https://ntfy.sh/topic',row,key,fetcher),c);
 assert.equal(await downloadCommand('https://ntfy.sh/topic',{...row,attachment:{...row.attachment,url:'https://attacker.invalid/file/a.bin'}},key,fetcher),null);
 assert.equal(fetched,1);
});

test('a restarted bridge recovers completion only from an explicit matching local turn',async()=>{
 const f=fixture(),client=fakeClient(),updates=[];try{
  const opts={directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>updates.push(e)};
  const first=new RemoteController(opts),c=command();await first.handle(c);first.close();
  const restarted=new RemoteController({...opts,recover:(id,turn)=>id===thread&&turn==='new-turn'?{reply:'actual persisted reply'}:null});
  await restarted.handle(command({action:'status',target_id:c.id,text:''}));
  assert.equal(updates.at(-1).status,'completed');assert.equal(updates.at(-1).reply,'actual persisted reply');assert.equal(client.starts,1);restarted.close();
 }finally{f.cleanup();}
});

test('model selection accepts only the real catalog and model-supported thinking levels',async()=>{
 const {selection}=require('../../app/src/main/assets/task-notifications/remote-core.cjs');
 const client={models:async()=>[{model:'available',displayName:'Available',hidden:false,defaultReasoningEffort:'medium',supportedReasoningEfforts:[{reasoningEffort:'low'},{reasoningEffort:'medium'}]}]};
 assert.deepEqual(await selection(client,command({model:'available',effort:'low'}),{}),{model:'available',effort:'low'});
 await assert.rejects(selection(client,command({model:'invented'}),{}),/INVALID_MODEL_SELECTION/);
 await assert.rejects(selection(client,command({model:'available',effort:'ultra'}),{}),/INVALID_MODEL_SELECTION/);
 assert.equal(validateCommand(command({model:'available\ninvalid'}),host),false);
});

test('model queries do not start a turn or mutate the active request journal',async()=>{
 const f=fixture(),client=fakeClient(),updates=[];try{
  client.models=async()=>[{model:'available',displayName:'Available',hidden:false,supportedReasoningEfforts:[],defaultReasoningEffort:'none'}];
  const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir,model:'available'}),emit:async e=>updates.push(e)});
  await controller.handle(command({action:'models',text:''}));
  assert.equal(client.starts,0);assert.equal(updates.at(-1).status,'models');assert.equal(updates.at(-1).models[0].model,'available');
  assert.deepEqual(controller.journal,{});controller.close();
 }finally{f.cleanup();}
});

test('new conversations require a real project identity and reads require a valid thread',()=>{
 assert.equal(validateCommand(command({action:'create',project_id:'arbitrary/path'}),host),false);
 assert.equal(validateCommand(command({action:'create',project_id:'a'.repeat(64)}),host),true);
 assert.equal(validateCommand(command({action:'create',project_id:'default'}),host),true);
 assert.equal(validateCommand(command({action:'create',project_id:''}),host),false);
 assert.equal(validateCommand(command({action:'read',read_thread_id:'../auth.json'}),host),false);
});

test('replayed rename IDs never overwrite a later title or execute a second mutation',async()=>{
 const f=fixture(),client=fakeClient(),events=[];let mutations=0;
 client.call=async(m,p)=>{if(m==='thread/read')return {thread:{id:p.threadId,status:{type:'notLoaded'}}};mutations++;return {};};
 const options={directory:f.dir,key,hostId:host,clientFactory:()=>client,emit:async e=>events.push(e)};
 try{
  const controller=new RemoteController(options),c=command({action:'rename',read_thread_id:thread,text:'First title'});
  assert.equal(validateCommand(c,host),true);
  await controller.handle(c);await controller.handle(command({action:'rename',read_thread_id:thread,text:'Second title'}));
  await controller.handle(c);assert.equal(mutations,2);assert.equal(events.at(-1).managed_name,'First title');
  const restarted=new RemoteController(options);await restarted.handle(c);assert.equal(mutations,2);
  assert.equal(validateCommand(command({action:'rename',read_thread_id:thread,text:'x'.repeat(241)}),host),false);
  assert.equal(validateCommand(command({action:'archive',read_thread_id:'bad'}),host),false);
 }finally{f.cleanup();}
});

test('queued text waits for completion, deduplicates and starts a real next turn in the same client',async()=>{
 const f=fixture(),client=fakeClient(),events=[];
 client.start=async function(){this.starts++;return {turn:{id:'turn-'+this.starts}};};
 try{
  const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>events.push(e)});
  const original=command();await controller.handle(original);
  const q=command({action:'queue',target_id:original.id,expected_turn_id:'turn-1',text:'Follow-up private text'});
  await controller.handle(q);await controller.handle(q);
  assert.equal(client.starts,1);assert.equal(controller.active.queue.length,1);
  assert.equal(events.at(-1).status,'queued');
  assert.ok(!fs.readFileSync(path.join(f.dir,'remote-journal.json'),'utf8').includes(q.text));
  await client.callbacks.event('turn/completed',{threadId:thread,turn:{id:'turn-1',status:'completed'}});
  assert.equal(client.starts,2);assert.equal(controller.active.turn,'turn-2');
  assert.equal(events.findLast(e=>e.request_id===original.id).active_input,q.text);
  controller.close();
 }finally{f.cleanup();}
});

test('steering targets the exact active turn and never creates another turn or replays its text',async()=>{
 const f=fixture(),client=fakeClient(),events=[],steers=[];
 client.steer=async(t,turn,text,id)=>{steers.push({t,turn,text,id});return {turnId:turn};};
 try{
  const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>events.push(e)});
  const original=command();await controller.handle(original);
  const s=command({action:'steer',target_id:original.id,expected_turn_id:'new-turn',text:'Use shorter sentences'});
  await controller.handle(s);await controller.handle(s);
  assert.equal(steers.length,1);assert.equal(steers[0].turn,'new-turn');assert.equal(client.starts,1);
  assert.equal(events.at(-1).status,'steered');
  await controller.handle(command({...s,id:crypto.randomUUID(),expected_turn_id:'old-turn'}));
  assert.equal(steers.length,1);assert.equal(events.at(-1).error,'STALE_ACTIVE_TURN');controller.close();
 }finally{f.cleanup();}
});

test('promoting a queued message immediately steers the active turn and never sends it again',async()=>{
 const f=fixture(),client=fakeClient(),events=[],steers=[];
 client.steer=async(t,turn,text,id)=>{steers.push({t,turn,text,id});return {turnId:turn};};
 const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>events.push(e)});
 try{
  const original=command();await controller.handle(original);
  const q=command({action:'queue',target_id:original.id,expected_turn_id:'new-turn',text:'Adjust existing requirement'});await controller.handle(q);
  const s=command({action:'steer',target_id:original.id,expected_turn_id:'new-turn',queue_id:q.id,text:q.text});
  await controller.handle(s);await controller.handle(s);
  assert.equal(steers.length,1);assert.equal(steers[0].text,q.text);assert.equal(client.starts,1);assert.equal(controller.active.queue.length,0);
  assert.equal(events.findLast(e=>e.request_id===q.id).status,'steered');assert.equal(events.findLast(e=>e.request_id===s.id).status,'steered');
  await client.callbacks.event('turn/completed',{threadId:thread,turn:{id:'new-turn',status:'completed'}});
  assert.equal(client.starts,1);
 }finally{controller.close();f.cleanup();}
});

test('a native completion during queued promotion cannot start the same text in a second turn',async()=>{
 const f=fixture(),client=fakeClient(),events=[];
 const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>events.push(e)});
 client.steer=async(t,turn)=>{await client.callbacks.event('turn/completed',{threadId:thread,turn:{id:turn,status:'completed'}});return {turnId:turn};};
 try{
  const original=command();await controller.handle(original);
  const q=command({action:'queue',target_id:original.id,expected_turn_id:'new-turn',text:'race fixture'});await controller.handle(q);
  await controller.handle(command({action:'steer',target_id:original.id,expected_turn_id:'new-turn',queue_id:q.id,text:q.text}));
  assert.equal(client.starts,1);assert.equal(events.findLast(e=>e.request_id===q.id).status,'steered');
 }finally{controller.close();f.cleanup();}
});

test('an uncertain steering response does not leave a promoted instruction queued for later execution',async()=>{
 const f=fixture(),client=fakeClient(),events=[];
 const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>events.push(e)});
 client.steer=async()=>{throw Error('Disconnected after sending');};
 try{
  const original=command();await controller.handle(original);
  const q=command({action:'queue',target_id:original.id,expected_turn_id:'new-turn',text:'ambiguous fixture'});await controller.handle(q);
  await controller.handle(command({action:'steer',target_id:original.id,expected_turn_id:'new-turn',queue_id:q.id,text:q.text}));
  assert.equal(controller.active.queue.length,0);assert.equal(events.findLast(e=>e.request_id===q.id).status,'unknown');
  await client.callbacks.event('turn/completed',{threadId:thread,turn:{id:'new-turn',status:'completed'}});assert.equal(client.starts,1);
 }finally{controller.close();f.cleanup();}
});

test('desktop steering uses the exact watched turn, never starts a turn, and rejects stale targets',async()=>{
 const f=fixture(),client=fakeClient(),events=[],calls=[];
 client.supportsLiveWatch=true;client.call=async(method,p)=>{assert.equal(method,'turn/steer');calls.push(p);return {turnId:p.expectedTurnId};};
 const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>{throw Error('Must use existing watch');},emit:async e=>events.push(e)});
 const watch=command({action:'read',read_thread_id:thread,watch:true});
 controller.watching={c:watch,client,live:{turn:'desktop-turn',value:{running:true}}};
 try{
  const s=command({action:'steer',target_id:watch.id,expected_turn_id:'desktop-turn',text:'Current desktop instruction'});
  await controller.handle(s);await controller.handle(s);assert.equal(calls.length,1);assert.equal(client.starts,0);assert.equal(controller.active,null);
  assert.equal(events.at(-1).status,'steered');assert.equal(calls[0].expectedTurnId,'desktop-turn');
  await controller.handle(command({...s,id:crypto.randomUUID(),expected_turn_id:'previous'}));assert.equal(calls.length,1);assert.equal(events.at(-1).error,'STALE_ACTIVE_TURN');
  controller.watching.live.value.running=false;
  await controller.handle(command({...s,id:crypto.randomUUID()}));assert.equal(calls.length,1);assert.equal(events.at(-1).status,'failed');
 }finally{controller.close();f.cleanup();}
});

test('promoting a queued instruction keeps its expected native turn even if another queue starts during acknowledgement',async()=>{
 const f=fixture(),client=fakeClient(),events=[],turns=[];let trigger=false,controller;
 controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>{
  events.push(e);if(trigger&&e.status==='unknown'&&e.error==='STEER_UNCONFIRMED')controller.active.turn='another-turn';
 }});
 client.steer=async(t,turn)=>{turns.push(turn);return {turnId:turn};};
 try{
  const original=command();await controller.handle(original);
  const q=command({action:'queue',target_id:original.id,expected_turn_id:'new-turn',text:'exact-turn fixture'});await controller.handle(q);
  trigger=true;await controller.handle(command({action:'steer',target_id:original.id,expected_turn_id:'new-turn',queue_id:q.id,text:q.text}));
  assert.deepEqual(turns,['new-turn']);assert.equal(client.starts,1);
 }finally{controller.close();f.cleanup();}
});

test('stopping cancels pending queued work and restart never executes an ambiguous queue',async()=>{
 const f=fixture(),client=fakeClient(),events=[];
 const opts={directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>events.push(e)};
 try{
  const controller=new RemoteController(opts),original=command();await controller.handle(original);
  const q=command({action:'queue',target_id:original.id,expected_turn_id:'new-turn',text:'Pending private message'});
  await controller.handle(q);
  const restarted=new RemoteController(opts);await restarted.handle(q);
  assert.equal(client.starts,1);assert.equal(events.at(-1).status,'cancelled');
  await controller.handle(command({action:'stop',target_id:original.id,text:''}));
  assert.equal(client.starts,1);assert.equal(controller.active,null);
 }finally{f.cleanup();}
});

test('paired command and event topics fit the real ntfy 64 character route limit',()=>{
 for(const make of [commandTopic,eventTopic])assert.match(new URL(make('https://ntfy.sh/paired',key,host)).pathname,/^\/[-_A-Za-z0-9]{1,64}$/);
});

test('new task recovery uses the created thread identity without starting a duplicate',async()=>{
 const f=fixture(),client=fakeClient(),updates=[],actual=crypto.randomUUID();try{
  client.call=async method=>method==='thread/list'?{data:[{id:thread,cwd:f.dir}]}:method==='thread/start'?{thread:{id:actual}}:{};
  const opts={directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>updates.push(e)};
  const first=new RemoteController(opts),c=command({action:'create',project_id:require('../../app/src/main/assets/task-notifications/conversation-library.cjs').project(f.dir).id});
  await first.handle(c);assert.equal(first.journal[c.id].actual_thread_id,actual);first.close();
  const restarted=new RemoteController({...opts,recover:(id,turn)=>id===actual&&turn==='new-turn'?{conversation_id:hash(actual),reply:'actual persisted reply'}:null});
  await restarted.handle(command({action:'status',target_id:c.id,text:''}));
  assert.equal(updates.at(-1).status,'completed');assert.equal(updates.at(-1).snapshot.conversation_id,hash(actual));assert.equal(client.starts,1);restarted.close();
 }finally{f.cleanup();}
});

test('stop during queued model validation prevents the next turn from starting',async()=>{
 const f=fixture(),client=fakeClient(),events=[];let release,entered;
 const checking=new Promise(r=>entered=r);
 try{
  const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>events.push(e)});
  const original=command();await controller.handle(original);
  const q=command({action:'queue',target_id:original.id,expected_turn_id:'new-turn',text:'Do not run after Stop',model:'available',effort:'low'});
  client.models=()=>{entered();return new Promise(r=>release=()=>r([{model:'available',supportedReasoningEfforts:[{reasoningEffort:'low'}]}]));};
  await controller.handle(q);
  const complete=client.callbacks.event('turn/completed',{threadId:thread,turn:{id:'new-turn',status:'completed'}});
  await checking;
  client.interrupt=async()=>{};
  await controller.handle(command({action:'stop',target_id:original.id,text:''}));
  release();await complete;
  assert.equal(client.starts,1);assert.equal(events.findLast(e=>e.request_id===q.id).status,'cancelled');
  assert.equal(controller.active,null);controller.close();
 }finally{f.cleanup();}
});

test('stop between queued turns never interrupts the already completed turn',async()=>{
 const f=fixture(),client=fakeClient(),events=[];let release,entered,interrupts=0;
 const checking=new Promise(r=>entered=r);
 try{
  const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>events.push(e)});
  const original=command();await controller.handle(original);
  const q=command({action:'queue',target_id:original.id,expected_turn_id:'new-turn',text:'Must not run',model:'available',effort:'low'});
  client.models=()=>{entered();return new Promise(r=>release=()=>r([{model:'available',supportedReasoningEfforts:[{reasoningEffort:'low'}]}]));};
  await controller.handle(q);
  const completed=client.callbacks.event('turn/completed',{threadId:thread,turn:{id:'new-turn',status:'completed'}});
  await checking;client.interrupt=async()=>{interrupts++;throw Error('NO_ACTIVE_TURN');};
  await controller.handle(command({action:'stop',target_id:original.id,text:''}));release();await completed;
  assert.equal(interrupts,0);assert.equal(client.starts,1);
  assert.equal(events.findLast(e=>e.request_id===original.id).status,'interrupted');controller.close();
 }finally{f.cleanup();}
});

test('presence confirms only the paired host without reading or starting Codex',async()=>{
 const f=fixture(),events=[];try{
  const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>{throw Error('NO_CODEX_FOR_PING');},emit:async e=>events.push(e)});
  const probe=command({action:'presence',text:''});await controller.handle(probe);
  assert.equal(events.length,1);assert.equal(events[0].status,'presence');assert.equal(events[0].request_id,probe.id);
  assert.equal(events[0].host_id,host);assert.ok(events[0].host_name.length>0);
  await controller.handle({...probe,id:crypto.randomUUID(),host_id:crypto.randomUUID()});assert.equal(events.length,1);
  assert.deepEqual(controller.journal,{});controller.close();
 }finally{f.cleanup();}
});

test('bridge restart retains monotonic event sequence even when the computer clock moves backward',async()=>{
 const f=fixture(),client=fakeClient(),events=[],originalNow=Date.now;
 const opts={directory:f.dir,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:f.dir}),emit:async e=>events.push(e)};
 try{
  const first=new RemoteController(opts),c=command();await first.handle(c);const sequence=events.at(-1).seq;first.close();
  Date.now=()=>1000;const restarted=new RemoteController(opts);
  await restarted.handle(command({action:'status',target_id:c.id,text:''}));
  assert.ok(events.at(-1).seq>sequence);restarted.close();
 }finally{Date.now=originalNow;f.cleanup();}
});

test('long prompts and queued text never overflow the relay attachment preview header',async()=>{
 const {publishEvent}=require('../../app/src/main/assets/task-notifications/remote.cjs');
 const c=command(),event={id:crypto.randomUUID(),request_id:c.id,host_id:host,thread_id:thread,conversation_id:c.conversation_id,
  status:'running',seq:1,at:Date.now(),active_input:'文'.repeat(5000),queued_entries:[{id:crypto.randomUUID(),text:'q'.repeat(512)}],reply:'short'};
 let payload;
 await publishEvent('https://ntfy.sh/fixture',key,event,async(_,options)=>{payload=options;return {ok:true,arrayBuffer:async()=>new ArrayBuffer(0)};});
 assert.equal(payload.headers.Filename,'codex-remote.bin');
 assert.ok(Buffer.byteLength(payload.headers.Message)<=3500);
 const preview=decode('event',JSON.parse(payload.headers.Message),key);assert.equal(preview.partial,true);
 const full=decode('event',{schema_version:'2.0',id:event.id,encrypted:payload.body.toString('base64')},key);
 assert.equal(full.active_input,event.active_input);assert.deepEqual(full.queued_entries,event.queued_entries);
});

test('oversize snapshots with an empty reply are bounded rather than spinning forever',async()=>{
 const f=fixture(),events=[];try{
  const c=command({text:'"'.repeat(16000)});
  const controller=new RemoteController({directory:f.dir,key,hostId:host,emit:async e=>events.push(e)});
  controller.active={c,input:c.text,queue:[],client:{close(){}},reply:''};
  const snapshot={conversation_id:c.conversation_id,messages:Array.from({length:12},(_,i)=>({role:'user',id:String(i),text:'"'.repeat(8000)})),reply:''};
  await controller.update(c,'completed',{snapshot});
  assert.ok(Buffer.byteLength(JSON.stringify(events[0]))<=196608);
  assert.equal(events[0].snapshot.truncated,true);assert.ok(events[0].snapshot.messages.length<12);
  controller.close();
 }finally{f.cleanup();}
});


test('no-project creates one real thread in its own workspace with selected permissions, even on retry',async()=>{
 const f=fixture(),client=fakeClient(),events=[],actual=crypto.randomUUID();let controller,creates=0,chosenCwd;
 try{
  client.call=async(method,params)=>{
   if(method==='thread/start'){
    creates++;chosenCwd=params.cwd;assert.equal(chosenCwd,path.join(f.dir,'conversation-workspace'));
    assert.equal(params.sandbox,'read-only');return {thread:{id:actual}};
   }
   if(method==='thread/name/set'){assert.equal(params.threadId,actual);return {};}
   throw Error('UNEXPECTED_RPC');
  };
  client.start=async(id,text,request,options)=>{assert.equal(id,actual);assert.equal(options.permission,'readonly');client.starts++;return {turn:{id:'real-turn'}};};
  controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,emit:async event=>events.push(event)});
  const c=command({action:'create',project_id:'default',permission:'readonly'});
  await controller.handle(c);await controller.handle(c);
  assert.equal(creates,1);assert.equal(client.starts,1);assert.equal(controller.active.actualThread,actual);
  assert.equal(events.at(-1).status,'running');assert.ok(fs.statSync(chosenCwd).isDirectory());
 }finally{controller?.close();f.cleanup();}
});
