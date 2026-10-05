'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),crypto=require('node:crypto');
const {DesktopQuestionReader,normalizeDesktopQuestion,replies,answerText}=require('../../app/src/main/assets/task-notifications/conversation-questions.cjs');
const {ActivityReader,watchCompletions}=require('../../app/src/main/assets/task-notifications/completion-watch.cjs');
const {RemoteController,validateCommand}=require('../../app/src/main/assets/task-notifications/remote-core.cjs');
const hash=s=>crypto.createHash('sha256').update(s).digest('hex');
function fixture(){const dir=fs.mkdtempSync(path.join(os.tmpdir(),'codex-link-events-')),root=path.join(dir,'sessions'),id=crypto.randomUUID();fs.mkdirSync(root);
 const file=path.join(root,'rollout-'+id+'.jsonl');fs.writeFileSync(file,JSON.stringify({type:'session_meta',payload:{id,source:'desktop'}})+'\n');
 return {dir,root,id,file,cleanup(){assert.equal(path.dirname(dir),os.tmpdir());assert.ok(path.basename(dir).startsWith('codex-link-events-'));fs.rmSync(dir,{recursive:true,force:true});}};}
const call={type:'function_call',name:'request_user_input_async',call_id:'call_fixture',arguments:JSON.stringify({questions:[{title:'请选择环境',options:['电脑','Cloud']}]})};
test('public questions reject other tool arguments and serialize explicit answers',()=>{
 const input=normalizeDesktopQuestion(call,'turn');assert.equal(input.questions[0].question,'请选择环境');
 assert.equal(normalizeDesktopQuestion({...call,name:'exec_command'},'turn'),null);
 assert.equal(normalizeDesktopQuestion({...call,arguments:'{}'},'turn'),null);
 const rows=replies(answerText(input,{[input.questions[0].id]:['Cloud']}));assert.equal(rows[0].answer,'Cloud');
 assert.equal(replies('prefix '+answerText(input,{[input.questions[0].id]:['Cloud']})),null);
});
test('question reader survives a UTF8 split, recovers on reopen, and removes real answers',()=>{
 const f=fixture();try{
  fs.appendFileSync(f.file,JSON.stringify({type:'turn_context',payload:{turn_id:'turn'}})+'\n');
  const bytes=Buffer.from(JSON.stringify({type:'response_item',payload:call})+'\n'),cut=bytes.indexOf(Buffer.from('选'))+1;
  const reader=new DesktopQuestionReader(f.dir,f.id);fs.appendFileSync(f.file,bytes.subarray(0,cut));assert.deepEqual(reader.read(f.file,'turn'),[]);
  fs.appendFileSync(f.file,bytes.subarray(cut));const input=reader.read(f.file,'turn')[0];assert.equal(input.questions[0].question,'请选择环境');
  assert.equal(new DesktopQuestionReader(f.dir,f.id).read(f.file,'turn').length,1);
  fs.appendFileSync(f.file,JSON.stringify({type:'response_item',payload:{type:'message',role:'user',content:[{type:'input_text',text:answerText(input,{[input.questions[0].id]:['电脑']})}]}})+'\n');
  assert.deepEqual(reader.read(f.file,'turn'),[]);
 }finally{f.cleanup();}
});
test('activity reader ignores historical alerts, waits for whole lines and rejects subagents',()=>{
 const f=fixture();try{
  const event=(type,turn,at)=>JSON.stringify({type:'event_msg',timestamp:new Date(at).toISOString(),payload:{type,turn_id:turn,last_agent_message:'Done'}})+'\n';
  fs.appendFileSync(f.file,event('task_complete','old',1));const reader=new ActivityReader(f.root,f.file,100);
  assert.equal(reader.read()[0].complete,false);
  const next=event('task_started','current',200);fs.appendFileSync(f.file,next.slice(0,-1));assert.deepEqual(reader.read(),[]);
  fs.appendFileSync(f.file,'\n'+event('task_complete','current',300));const rows=reader.read();assert.equal(rows[0].running,true);assert.equal(rows[1].complete,true);
  fs.appendFileSync(f.file,event('task_complete','current',300));assert.deepEqual(reader.read(),[]);
  fs.writeFileSync(f.file,JSON.stringify({type:'session_meta',payload:{id:f.id,source:{subagent:{}}}})+'\n'+event('task_complete','other',300));
  assert.deepEqual(new ActivityReader(f.root,f.file,100).read(),[]);
 }finally{f.cleanup();}
});
test('filesystem watcher emits completion immediately without waiting for scheduled scan',async()=>{
 const f=fixture();let watcher;
 try{
  const received=new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('watch timeout')),3000);
   watcher=watchCompletions(f.dir,{onCompletion:payload=>{clearTimeout(timer);resolve(payload);}});});
  fs.appendFileSync(f.file,JSON.stringify({type:'event_msg',timestamp:new Date().toISOString(),payload:{type:'task_complete',turn_id:'now',last_agent_message:'Done'}})+'\n');
  assert.equal((await received)['turn-id'],'now');
 }finally{watcher?.close();f.cleanup();}
});
test('watch questions submit to the same current turn once and reject other identities',async()=>{
 const f=fixture(),host=crypto.randomUUID(),key=Buffer.alloc(32,5).toString('base64'),events=[],calls=[];
 const input=normalizeDesktopQuestion(call,'original-question-turn');
 const client={supportsLiveWatch:true,close(){},async call(m,p){calls.push([m,p]);if(m==='thread/turns/list')return {data:[{id:'currently-running',status:'inProgress'}]};if(m==='turn/steer')return {turnId:'currently-running'};throw Error(m);}};
 const controller=new RemoteController({directory:f.dir,key,hostId:host,emit:async e=>events.push(e)});
 try{
  const query={id:crypto.randomUUID(),action:'read',host_id:host,thread_id:f.id,read_thread_id:f.id,conversation_id:hash(f.id),baseline_turn:'previous',issued_at:Date.now()};
  controller.watching={c:query,client,pending:new Map(),questions:{read:()=>[input]},live:{turn:'currently-running',value:{conversation_id:hash(f.id),messages:[]}},expiresAt:Date.now()+10000};
  const c={...query,id:crypto.randomUUID(),action:'answer_input',target_id:query.id,input_id:input.id,expected_turn_id:input.turn_id,answers:{[input.questions[0].id]:['Cloud']}};
  assert.equal(await controller.answerWatchInput({...c,thread_id:crypto.randomUUID()}),false);assert.equal(calls.length,0);
  await controller.answerWatchInput(c);await controller.answerWatchInput({...c,id:crypto.randomUUID()});
  assert.equal(calls.filter(([m])=>m==='turn/steer').length,1);assert.equal(calls[1][1].expectedTurnId,'currently-running');
  assert.equal(replies(calls[1][1].input[0].text)[0].answer,'Cloud');assert.equal(events.at(-1).status,'snapshot');
 }finally{controller.close();f.cleanup();}
});
test('new goal creation uses the native goal protocol rather than a prompt imitation',async()=>{
 const f=fixture(),host=crypto.randomUUID(),actual=crypto.randomUUID(),events=[],calls=[],key=Buffer.alloc(32,5).toString('base64');
 let goal;
 const client={setHandlers(e){this.e=e;},async connect(){},close(){},async reject(){},async resume(){},async models(){return [{model:'fixture',defaultReasoningEffort:'high',supportedReasoningEfforts:[{reasoningEffort:'high'}]}];},async modes(){return [];},
  async call(m,p){calls.push([m,p]);if(m==='thread/start')return {thread:{id:actual}};if(m==='thread/name/set')return {};
   if(m==='thread/goal/set'){goal={threadId:actual,objective:p.objective,status:'active',tokensUsed:0,timeUsedSeconds:0,createdAt:1,updatedAt:1};return {goal};}
   throw Error('unexpected '+m);}};
 const c={id:crypto.randomUUID(),action:'create',host_id:host,thread_id:f.id,conversation_id:hash(f.id),baseline_turn:'library',project_id:'default',issued_at:Date.now(),text:'Finish this objective',objective:'Finish this objective',model:'fixture',effort:'high'};
 const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,emit:async e=>events.push(e)});
 try{
  assert.equal(validateCommand(c,host),true);assert.equal(validateCommand({...c,objective:'x'.repeat(4001)},host),false);
  await controller.handle(c);assert.equal(events.at(-1).status,'running');assert.equal(events.at(-1).goal.thread_id,actual);
  assert.equal(events.at(-1).snapshot.remote_ref.thread_id,actual);assert.ok(calls.some(([m])=>m==='thread/goal/set'));
  assert.ok(!calls.some(([m])=>m==='turn/start'));assert.equal(controller.active.goalTask,true);
 }finally{controller.close();f.cleanup();}
});

test('shared runtime upgrade reattaches the exact live task without starting or changing its model',async()=>{
 const f=fixture(),host=crypto.randomUUID(),key=Buffer.alloc(32,5).toString('base64'),events=[],calls=[];
 const {encode}=require('../../app/src/main/assets/task-notifications/remote-core.cjs');
 const root=crypto.randomUUID(),turn='active-fixture';
 const saved={id:crypto.randomUUID(),request_id:root,host_id:host,thread_id:f.id,conversation_id:hash(f.id),status:'running',seq:1,at:Date.now(),turn_id:turn,reply:'old',active_input:'Fixture task',queued_entries:[]};
 fs.writeFileSync(path.join(f.dir,'remote-journal.json'),JSON.stringify({[root]:{status:'running',thread_id:f.id,turn_id:turn}}));
 fs.writeFileSync(path.join(f.dir,'remote-'+root+'.json'),JSON.stringify(encode('event',saved.id,saved,key)));
 const client={supportsLiveWatch:true,setHandlers(e){this.event=e;},async connect(){},close(){},async call(m,p){
  calls.push([m,p]);
  if(m==='thread/read')return {thread:{id:f.id,path:f.file,updatedAt:1000,status:{type:'active'}}};
  if(m==='thread/turns/list')return {data:[{id:turn,status:'inProgress',items:[{id:'reply',type:'agentMessage',text:'latest'}]}],nextCursor:null};
  if(m==='thread/goal/get')return {goal:null};if(m==='thread/resume')return {};
  throw Error('unexpected '+m);
 }};
 const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,emit:async e=>events.push(e),questionFactory:id=>new DesktopQuestionReader(f.dir,id)});
 try{
  await controller.restoreSharedTask();assert.equal(controller.active?.turn,turn);assert.equal(controller.active.reply,'latest');
  assert.deepEqual(calls.find(([m])=>m==='thread/resume')[1],{threadId:f.id,excludeTurns:true});
  assert.ok(!calls.some(([m])=>m==='turn/start'||m==='turn/interrupt'||m==='thread/goal/set'));
  fs.appendFileSync(f.file,JSON.stringify({type:'turn_context',payload:{turn_id:turn}})+'\n'+JSON.stringify({type:'response_item',payload:call})+'\n');
  await controller.refreshActive(controller.active);const input=events.at(-1).user_input;assert.equal(events.at(-1).status,'input_required');
  let steers=0;client.steer=async(threadId,turnId,text)=>{steers++;assert.equal(threadId,f.id);assert.equal(turnId,turn);assert.equal(replies(text)[0].answer,'Cloud');return {turnId:turn};};
  const answer={id:crypto.randomUUID(),action:'answer_input',target_id:root,host_id:host,thread_id:f.id,conversation_id:hash(f.id),input_id:input.id,expected_turn_id:turn,answers:{[input.questions[0].id]:['Cloud']},issued_at:Date.now()};
  await controller.handle(answer);await controller.handle({...answer,id:crypto.randomUUID()});assert.equal(steers,1);assert.equal(events.at(-1).status,'running');
 }finally{controller.close();f.cleanup();}
});
