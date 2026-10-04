'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),crypto=require('node:crypto'),fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {RemoteController,validateCommand}=require('../../app/src/main/assets/task-notifications/remote-core.cjs');
const host=crypto.randomUUID(),thread=crypto.randomUUID(),key=Buffer.alloc(32,8).toString('base64');
const hash=s=>crypto.createHash('sha256').update(s).digest('hex');
const command=(extra={})=>({id:crypto.randomUUID(),host_id:host,thread_id:thread,conversation_id:hash(thread),baseline_turn:'previous',issued_at:Date.now(),action:'goal_start',
 objective:extra.action&&extra.action!=='goal_start'?'':'Complete the isolated task',expected_goal_updated_at:0,...extra});
function fixture(){
 const directory=fs.mkdtempSync(path.join(os.tmpdir(),'codex-usage-goal-')),events=[],calls=[];
 let goal=null,closed=0,writer=false;
 const client={setHandlers(e,r){this.e=e;this.r=r;},async connect(){},async read(){return {thread:{id:thread,status:{type:'idle'}}};},
  async resume(){if(writer)throw Object.assign(Error('RPC'),{rpcCode:-32600,rpcMessage:'thread already has an active writer SECRET'});},
  close(){closed++;},async reject(){},async interrupt(id,turn){calls.push(['interrupt',id,turn]);},
  async call(m,p){calls.push([m,p]);
   if(m==='thread/goal/get')return {goal};
   if(m==='thread/goal/set'){goal={threadId:thread,objective:p.objective||goal?.objective,status:p.status,tokensUsed:goal?.tokensUsed||0,
    timeUsedSeconds:0,createdAt:1,updatedAt:(goal?.updatedAt||0)+1,tokenBudget:p.tokenBudget??goal?.tokenBudget??null};return {goal};}
   if(m==='thread/goal/clear'){goal=null;return {cleared:true};}
   if(m==='thread/read')return {thread:{id:thread,status:{type:'idle'},updatedAt:10}};
   if(m==='thread/turns/list')return {data:[{id:'previous',status:'completed',items:[]}]};
   throw Error('UNEXPECTED_RPC');
  }};
 const controller=new RemoteController({directory,key,hostId:host,clientFactory:()=>client,resolve:()=>({cwd:directory}),emit:async e=>events.push(e)});
 return {controller,client,events,calls,setGoal(v){goal=v;},get goal(){return goal;},get closed(){return closed;},setWriter(){writer=true;},
  cleanup(){controller.close();assert.equal(path.dirname(directory),path.resolve(os.tmpdir()));assert.ok(path.basename(directory).startsWith('codex-usage-goal-'));fs.rmSync(directory,{recursive:true,force:true});},directory};
}
test('goal commands are bounded, explicit, scoped and cannot set terminal provider states',()=>{
 assert.equal(validateCommand(command(),host),true);
 for(const extra of [{objective:''},{objective:'x'.repeat(4001)},{token_budget:-1},{expected_goal_updated_at:-1},{action:'goal_complete'},
  {action:'goal_read',read_thread_id:crypto.randomUUID()}])assert.equal(validateCommand(command(extra),host),false);
 assert.equal(validateCommand(command({action:'goal_read',read_thread_id:thread}),host),true);
});
test('starting a Goal uses the real API and duplicate/restarted commands never start it again',async()=>{
 const f=fixture();try{
  const c=command();await f.controller.handle(c);assert.equal(f.goal.status,'active');
  assert.equal(f.calls.some(([m])=>m==='turn/start'),false);assert.equal(f.closed,0);
  await f.controller.handle(c);assert.equal(f.calls.filter(([m])=>m==='thread/goal/set').length,1);
  f.controller.close();const restored=new RemoteController({directory:f.directory,key,hostId:host,clientFactory:()=>f.client,resolve:()=>({}),emit:async e=>f.events.push(e)});
  await restored.handle(c);assert.equal(f.calls.filter(([m])=>m==='thread/goal/set').length,1);
  assert.equal(f.events.at(-1).status,'unknown');restored.close();
 }finally{f.cleanup();}
});
test('Goal spans completed turns and accepts fresh turn events without closing the engine',async()=>{
 const f=fixture();try{
  const c=command();await f.controller.handle(c);
  await f.client.e('turn/started',{threadId:thread,turn:{id:'one'}});
  await f.client.e('item/agentMessage/delta',{threadId:thread,turnId:'one',itemId:'a',delta:'First'});
  await f.client.e('turn/completed',{threadId:thread,turn:{id:'one',status:'completed'}});
  assert.equal(f.closed,0);assert.equal(f.events.at(-1).status,'running');assert.equal(f.events.at(-1).goal_waiting,true);
  await f.client.e('turn/started',{threadId:thread,turn:{id:'two'}});
  await f.client.e('item/agentMessage/delta',{threadId:thread,turnId:'two',itemId:'b',delta:'Second'});
  assert.equal(f.controller.active.reply,'Second');assert.equal(f.controller.active.turn,'two');
  f.setGoal({...f.goal,status:'complete',updatedAt:50});
  await f.client.e('thread/goal/updated',{threadId:thread,goal:f.goal});
  await f.client.e('turn/completed',{threadId:thread,turn:{id:'two',status:'completed'}});
  assert.equal(f.closed,1);assert.equal(f.events.at(-1).status,'completed');assert.equal(f.events.at(-1).goal.status,'complete');
 }finally{f.cleanup();}
});
test('pause confirms native Goal status and interrupts only its real active turn',async()=>{
 const f=fixture();try{
  const c=command();await f.controller.handle(c);await f.client.e('turn/started',{threadId:thread,turn:{id:'own'}});
  await f.controller.handle(command({action:'goal_pause',target_id:c.id,expected_goal_updated_at:999}));
  assert.equal(f.goal.status,'active');assert.equal(f.events.at(-1).error,'GOAL_CHANGED');
  await f.controller.handle(command({action:'goal_pause',target_id:c.id,expected_goal_updated_at:f.goal.updatedAt}));
  assert.equal(f.goal.status,'paused');assert.deepEqual(f.calls.find(([m])=>m==='interrupt'),['interrupt',thread,'own']);
  await f.client.e('turn/completed',{threadId:thread,turn:{id:'own',status:'interrupted'}});
  assert.equal(f.closed,1);assert.equal(f.events.at(-1).status,'interrupted');
 }finally{f.cleanup();}
});
test('existing active goals and desktop ownership never cause hidden generation',async()=>{
 const f=fixture();try{
  f.setGoal({threadId:thread,objective:'Existing',status:'active',updatedAt:5,tokensUsed:1,timeUsedSeconds:1,createdAt:1});
  await f.controller.handle(command());assert.equal(f.events.at(-1).error,'GOAL_ALREADY_EXISTS');assert.equal(f.calls.some(([m])=>m==='thread/goal/set'),false);
  f.setGoal(null);f.setWriter();await f.controller.handle(command());assert.equal(f.events.at(-1).error,'DESKTOP_OWNS_THREAD');
  assert.equal(JSON.stringify(f.events).includes('SECRET'),false);assert.equal(f.calls.some(([m])=>m==='thread/goal/set'),false);
 }finally{f.cleanup();}
});
test('read is non-mutating; clear verifies revision and never claims completion',async()=>{
 const f=fixture();try{
  f.setGoal({threadId:thread,objective:'Existing',status:'paused',updatedAt:5,tokensUsed:1,timeUsedSeconds:1,createdAt:1});
  await f.controller.handle(command({action:'goal_read',read_thread_id:thread}));assert.equal(f.events.at(-1).status,'goal');assert.equal(f.events.at(-1).goal.status,'paused');
  const clear=command({action:'goal_clear',expected_goal_updated_at:5});await f.controller.handle(clear);await f.controller.handle(clear);
  assert.equal(f.goal,null);assert.equal(f.calls.filter(([m])=>m==='thread/goal/clear').length,1);assert.equal(f.events.at(-1).goal,null);
  assert.equal(f.calls.some(([m])=>m==='turn/start'),false);
 }finally{f.cleanup();}
});
test('pausing matches the stable goal generation while live usage updates advance its revision',async()=>{
 const f=fixture();try{
  const c=command();await f.controller.handle(c);await f.client.e('turn/started',{threadId:thread,turn:{id:'own'}});
  const original=f.goal;f.setGoal({...original,tokensUsed:300,updatedAt:99});
  await f.controller.handle(command({action:'goal_pause',target_id:c.id,expected_goal_updated_at:original.updatedAt,
   expected_goal_created_at:original.createdAt,expected_goal_hash:hash(original.objective)}));
  assert.equal(f.goal.status,'paused');assert.deepEqual(f.calls.find(([m])=>m==='interrupt'),['interrupt',thread,'own']);
 }finally{f.cleanup();}
});
test('an externally owned active goal is never reported as paused by another client',async()=>{
 const f=fixture();try{
  f.setGoal({threadId:thread,objective:'External',status:'active',updatedAt:5,tokensUsed:1,timeUsedSeconds:1,createdAt:1});
  await f.controller.handle(command({action:'goal_pause',expected_goal_updated_at:5}));
  assert.equal(f.events.at(-1).error,'GOAL_NOT_OWNED');assert.equal(f.goal.status,'active');
  assert.equal(f.calls.some(([m])=>m==='thread/goal/set'),false);
 }finally{f.cleanup();}
});
