'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),crypto=require('node:crypto');
const {RemoteController,validateCommand}=require('../../app/src/main/assets/task-notifications/remote-core.cjs');
const hash=s=>crypto.createHash('sha256').update(s).digest('hex');
const uuid=()=>crypto.randomUUID(), key=Buffer.alloc(32,4).toString('base64');
function fixture(){
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'remote-agents-fixture-')),home=path.join(dir,'home'),sessions=path.join(home,'sessions');
 fs.mkdirSync(sessions,{recursive:true});const host=uuid(),events=[];
 const rows=new Map(), calls=[];
 function rollout(id,source,extra=[]){
  const file=path.join(sessions,`rollout-${id}.jsonl`);
  fs.writeFileSync(file,[{type:'session_meta',timestamp:new Date().toISOString(),payload:{id,cwd:dir,source}},...extra].map(JSON.stringify).join('\n')+'\n');
  if(source==='cli'&&!rows.has(id))rows.set(id,{id,source,parentThreadId:null});
  return file;
 }
 function client(){return {supportsLiveWatch:true,setHandlers(){},async connect(){},close(){},reject(){},async call(method,params){
  calls.push([method,params]);if(method==='thread/list')return {data:[...rows.values()].filter(r=>r.parentThreadId===params.parentThreadId)};
  if(method==='thread/read')return {thread:rows.get(params.threadId)};
  if(method==='thread/turns/list')return {data:rows.get(params.threadId)?.turns||[]};
  if(method==='turn/interrupt')return {};throw Error('unsupported fixture call');
 }};}
 const controller=new RemoteController({directory:dir,key,hostId:host,config:{codexHome:home,appServerUrl:'fixture'},clientFactory:client,emit:async e=>events.push(e)});
 function command(action,extra={}){return {id:uuid(),host_id:host,thread_id:host,conversation_id:hash(host),baseline_turn:action,issued_at:Date.now(),action,...extra};}
 async function send(action,extra){const c=command(action,extra);assert.equal(validateCommand(c,host),true);await controller.handle(c);const e=events.at(-1);assert.equal(e.request_id,c.id);assert.equal(e.thread_id,host);assert.equal(e.conversation_id,hash(host));return e;}
 return {dir,home,sessions,host,events,rows,calls,rollout,controller,command,send,close(){controller.close();fs.rmSync(dir,{recursive:true,force:true});}};
}
const sub=(parent,nickname='worker')=>({subagent:{thread_spawn:{parent_thread_id:parent,agent_nickname:nickname,agent_role:'review'}}});
test('host commands require host identity and action baseline, and preserve ordinary mode validation',()=>{
 const f=fixture();try{const c=f.command('agents',{read_thread_id:uuid()});assert.equal(validateCommand(c,f.host),true);
  assert.equal(validateCommand({...c,thread_id:uuid()},f.host),false);assert.equal(validateCommand({...c,baseline_turn:'wrong'},f.host),false);
  assert.equal(validateCommand(f.command('agent_read',{mode:'subagent',read_thread_id:uuid()}),f.host),true);
  assert.equal(validateCommand(f.command('agent_read',{mode:'task',target_id:'task-1'}),f.host),true);
  assert.equal(validateCommand(f.command('agent_read',{mode:'unknown'}),f.host),false);
 }finally{f.close();}
});
test('agents list includes nested children, latest context, bounded replies and excludes unrelated threads',async()=>{
 const f=fixture();try{const root=uuid(),child=uuid(),nested=uuid(),other=uuid();f.rollout(root,'cli');
  f.rollout(child,sub(root),[{type:'turn_context',payload:{turn_id:'turn-1',model:'model-1',effort:'high'}},{type:'event_msg',payload:{type:'task_started',turn_id:'turn-1'}},{type:'response_item',payload:{type:'message',role:'assistant',content:[{type:'output_text',text:'甲'.repeat(450)}]}}]);
  f.rollout(nested,sub(child,'nested'));f.rollout(other,sub(uuid()));
  for(const [id,parent] of [[child,root],[nested,child],[other,uuid()]])f.rows.set(id,{id,parentThreadId:parent,source:sub(parent),status:{type:id===child?'active':'idle'},createdAt:1,updatedAt:2,turns:id===child?[{id:'turn-1',status:'inProgress'}]:[]});
  const e=await f.send('agents',{read_thread_id:root});assert.equal(e.status,'agents');assert.equal(e.agents.length,2);
  const first=e.agents.find(x=>x.thread_id===child),second=e.agents.find(x=>x.thread_id===nested);
  assert.equal(first.parent_thread_id,root);assert.equal(first.depth,1);assert.equal(first.nickname,'worker');assert.equal(first.model,'model-1');assert.equal(first.effort,'high');assert.equal(first.status,'running');assert.equal(first.turn_id,'turn-1');assert.equal(Array.from(first.last_reply).length,400);
  assert.equal(second.depth,2);assert.equal(second.parent_thread_id,child);assert.equal(f.calls.find(c=>c[0]==='thread/list')[1].sourceKinds[0],'subAgentThreadSpawn');
  assert.ok(f.calls.some(c=>c[0]==='thread/list'&&c[1].parentThreadId===root));assert.ok(f.calls.some(c=>c[0]==='thread/list'&&c[1].parentThreadId===child));
 }finally{f.close();}
});
test('shared discovery lists a valid parent and child even without local rollout files',async()=>{
 const f=fixture();try{const root=uuid(),child=uuid();f.rows.set(root,{id:root,source:'cli',parentThreadId:null});f.rows.set(child,{id:child,parentThreadId:root,source:sub(root),agentNickname:'server-agent',status:{type:'active'},createdAt:1,turns:[{id:'live',status:'inProgress'}]});
  const e=await f.send('agents',{read_thread_id:root});assert.equal(e.error,undefined);assert.equal(e.agents.length,1);assert.equal(e.agents[0].thread_id,child);assert.equal(e.agents[0].turn_id,'live');assert.equal(e.agents[0].nickname,'server-agent');
  assert.equal((await f.send('agent_read',{mode:'subagent',read_thread_id:child})).error,'ROLLOUT_UNAVAILABLE');
  assert.equal((await f.send('agent_stop',{read_thread_id:child,expected_turn_id:'live'})).reply,'stopped');assert.deepEqual(f.calls.find(c=>c[0]==='turn/interrupt')[1],{threadId:child,turnId:'live'});
 }finally{f.close();}
});
test('shared idle status does not advertise a stale locally recorded turn as active',async()=>{
 const f=fixture();try{const root=uuid(),child=uuid();f.rollout(root,'cli');f.rollout(child,sub(root),[{type:'event_msg',payload:{type:'task_started',turn_id:'old'}}]);
  f.rows.set(child,{id:child,parentThreadId:root,source:sub(root),status:{type:'idle'}});
  const e=await f.send('agents',{read_thread_id:root});assert.equal(e.agents[0].status,'idle');assert.equal(e.agents[0].turn_id,'');
 }finally{f.close();}
});
test('read projections drop injected user instructions, bound UTF-8 and include safe activities',async()=>{
 const f=fixture();try{const root=uuid(),child=uuid();f.rollout(root,'cli');f.rollout(child,sub(root),[
  {type:'turn_context',payload:{turn_id:'active'}},
  {type:'response_item',payload:{type:'message',role:'user',content:[{type:'input_text',text:'private project instructions'},{type:'input_text',text:'hello'}],internal_chat_message_metadata_passthrough:{content_item_kinds:['agents_md.instructions','user.text']}}},
  {type:'event_msg',payload:{type:'exec_command_begin',call_id:'call1',command:['npm','test']}},
  {type:'event_msg',payload:{type:'exec_command_end',call_id:'call1',exit_code:0,aggregated_output:'PRIVATE TOOL OUTPUT'}},
  {type:'response_item',payload:{type:'message',role:'assistant',content:[{type:'output_text',text:'汉'.repeat(20000)}]}}
 ]);
 const e=await f.send('agent_read',{mode:'subagent',read_thread_id:child});assert.equal(e.snapshot.messages[0].text,'hello');assert.ok(Buffer.byteLength(e.snapshot.messages[1].text)<=16384);assert.equal(e.snapshot.truncated,true);assert.equal(e.snapshot.activities[0].type,'commandExecution');assert.equal(e.snapshot.activities[0].title,'npm test');assert.equal(JSON.stringify(e).includes('PRIVATE'),false);
 }finally{f.close();}
});
test('agents falls back to rollout metadata for old servers and caps results',async()=>{
 const f=fixture();try{const root=uuid();f.rollout(root,'cli');for(let i=0;i<23;i++)f.rollout(uuid(),sub(root));
  f.controller.config.appServerUrl='';const e=await f.send('agents',{read_thread_id:root});
  assert.equal(e.agents.length,20);assert.equal(e.partial,true);assert.equal(e.error,undefined);
  assert.equal((await f.send('agents',{read_thread_id:uuid()})).error,'INVALID_THREAD');
 }finally{f.close();}
});
test('agent read validates the ancestor chain and returns bounded read-only snapshots',async()=>{
 const f=fixture();try{const root=uuid(),child=uuid(),orphan=uuid();f.rollout(root,'cli');
  f.rollout(child,sub(root),[{type:'response_item',payload:{type:'message',role:'user',content:[{type:'input_text',text:'question'}]}},{type:'response_item',payload:{type:'message',role:'assistant',content:[{type:'output_text',text:'answer'}]}}]);f.rollout(orphan,sub(uuid()));
  const e=await f.send('agent_read',{mode:'subagent',read_thread_id:child});assert.equal(e.status,'snapshot');assert.deepEqual(e.snapshot.messages.map(m=>m.text),['question','answer']);assert.ok(Array.isArray(e.snapshot.activities));assert.equal(e.snapshot.remote_ref,undefined);
  assert.equal((await f.send('agent_read',{mode:'subagent',read_thread_id:root})).error,'INVALID_THREAD');assert.equal((await f.send('agent_read',{mode:'subagent',read_thread_id:orphan})).error,'INVALID_THREAD');
 }finally{f.close();}
});
test('agent stop uses only shared interrupt for the verified active turn',async()=>{
 const f=fixture();try{const root=uuid(),child=uuid();f.rollout(root,'cli');f.rollout(child,sub(root));
  f.rows.set(child,{id:child,parentThreadId:root,source:sub(root),status:{type:'active'},turns:[{id:'turn-a',status:'inProgress'}]});
  assert.equal((await f.send('agent_stop',{read_thread_id:child,expected_turn_id:'old'})).error,'STALE_ACTIVE_TURN');assert.equal(f.calls.some(x=>x[0]==='turn/interrupt'),false);
  assert.equal((await f.send('agent_stop',{read_thread_id:root,expected_turn_id:'turn-a'})).error,'INVALID_THREAD');
  assert.equal((await f.send('agent_stop',{read_thread_id:child,expected_turn_id:'turn-a'})).reply,'stopped');
  assert.deepEqual(f.calls.find(x=>x[0]==='turn/interrupt')[1],{threadId:child,turnId:'turn-a'});
  f.rows.get(child).turns=[{id:'turn-a',status:'completed'}];assert.equal((await f.send('agent_stop',{read_thread_id:child,expected_turn_id:'turn-a'})).reply,'already_finished');
  f.controller.config.appServerUrl='';assert.equal((await f.send('agent_stop',{read_thread_id:child,expected_turn_id:'turn-a'})).error,'SHARED_REQUIRED');
 }finally{f.close();}
});
test('agent commands expose unavailable and failed outcomes without interrupting ordinary threads',async()=>{
 const f=fixture();try{const root=uuid(),child=uuid();f.rollout(root,'cli');f.rollout(child,sub(root));
  f.rows.set(child,{id:child,source:'cli',parentThreadId:null,threadSource:'user',status:{type:'active'},turns:[{id:'a',status:'inProgress'}]});
  assert.equal((await f.send('agent_stop',{read_thread_id:child,expected_turn_id:'a'})).error,'INVALID_THREAD');assert.equal(f.calls.some(x=>x[0]==='turn/interrupt'),false);
  fs.rmSync(f.sessions,{recursive:true});f.controller.config.appServerUrl='';assert.equal((await f.send('agents',{read_thread_id:root})).error,'AGENTS_UNAVAILABLE');assert.equal((await f.send('agent_read',{mode:'subagent',read_thread_id:child})).error,'ROLLOUT_UNAVAILABLE');
 }finally{f.close();}
});
test('rollout tail cache reads only the appended bytes and marks a bounded tail partial',()=>{
 const {Rollouts}=require('../../app/src/main/assets/task-notifications/rollout-reader.cjs'),f=fixture();try{const id=uuid(),file=f.rollout(id,'cli');fs.appendFileSync(file,'x'.repeat(4*1024*1024)+'\n');
  const reader=new Rollouts(f.home);assert.equal(reader.tail(file).partial,true);const original=fs.readSync,reads=[];fs.readSync=function(fd,buffer,offset,length,position){reads.push(length);return original(fd,buffer,offset,length,position);};
  try{fs.appendFileSync(file,JSON.stringify({type:'event_msg',payload:{type:'task_complete',turn_id:'x'}})+'\n');const result=reader.tail(file);assert.equal(result.partial,true);assert.equal(result.rows.at(-1).payload.turn_id,'x');assert.ok(reads.every(n=>n<1024));}finally{fs.readSync=original;}
 }finally{f.close();}
});
test('agent and task stops retain control capacity while catalog reads are busy',async()=>{
 const {CommandDispatcher}=require('../../app/src/main/assets/task-notifications/remote.cjs');let release;const gate=new Promise(r=>release=r),seen=[];
 const dispatcher=new CommandDispatcher(async c=>{seen.push(c.action);if(['agents','tasks'].includes(c.action))await gate;},()=>{},2);
 try{assert.equal(dispatcher.submit({id:'a',action:'agents'}),true);assert.equal(dispatcher.submit({id:'b',action:'tasks'}),true);
  assert.equal(dispatcher.submit({id:'c',action:'agent_stop'}),true);assert.equal(dispatcher.submit({id:'d',action:'task_stop'}),true);assert.deepEqual(seen,['agents','tasks','agent_stop','task_stop']);
 }finally{release();await dispatcher.idle();dispatcher.close();}
});
test('stop command replay returns its original host status without repeating an interrupt',async()=>{
 const f=fixture();try{const root=uuid(),child=uuid();f.rollout(root,'cli');f.rollout(child,sub(root));f.rows.set(child,{id:child,parentThreadId:root,source:sub(root),status:{type:'active'},turns:[{id:'turn-a',status:'inProgress'}]});
  const c=f.command('agent_stop',{read_thread_id:child,expected_turn_id:'turn-a'});await f.controller.handle(c);await f.controller.handle(c);
  assert.equal(f.events.at(-1).status,'agent_stop');assert.equal(f.events.at(-1).reply,'stopped');assert.equal(f.events.at(-1).error,undefined);assert.equal(f.calls.filter(x=>x[0]==='turn/interrupt').length,1);
  const uncertain=f.command('agent_stop',{read_thread_id:child,expected_turn_id:'turn-a'});f.controller.journal[uncertain.id]={status:'unknown',thread_id:f.host,action:'agent_stop',error:'STOP_FAILED'};
  await f.controller.handle(uncertain);assert.equal(f.events.at(-1).status,'agent_stop');assert.equal(f.events.at(-1).error,'STOP_FAILED');assert.equal(f.calls.filter(x=>x[0]==='turn/interrupt').length,1);
 }finally{f.close();}
});
