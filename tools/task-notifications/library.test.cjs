'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {snapshot,selectedProject,readSnapshot}=require('../../app/src/main/assets/task-notifications/conversation-library.cjs');
const thread='11111111-2222-3333-4444-555555555555',host='22222222-2222-3333-4444-555555555555';
test('tool history uses scoped native items, chronological order and opaque pagination',async()=>{
 const {activityPage}=require('../../app/src/main/assets/task-notifications/conversation-library.cjs');
 const client={call:async(method,params)=>{
  assert.equal(method,'thread/items/list');assert.deepEqual(params,{threadId:thread,limit:12,sortDirection:'desc',cursor:'opaque older'});
  return {data:[
   {turnId:'new',item:{id:'1',type:'commandExecution',command:'test new',status:'completed',exitCode:2}},
   {turnId:'old',item:{id:'secret',type:'reasoning',text:'PRIVATE'}},
   {turnId:'old',item:{id:'1',type:'commandExecution',command:'test old',status:'completed',exitCode:0}}],nextCursor:'next opaque'};
 }};
 const page=await activityPage(client,thread,'opaque older');
 assert.deepEqual(page.activities.map(a=>a.id),['old:1','new:1']);
 assert.deepEqual(page.activities.map(a=>a.exit_code),[0,2]);assert.equal(page.next_cursor,'next opaque');
 assert.equal(JSON.stringify(page).includes('PRIVATE'),false);
});
test('official thread projection contains only visible user and assistant messages',()=>{
 const value=snapshot({id:thread,title:'Example',updatedAt:1000,turns:[{id:'turn',status:'completed',items:[
   {type:'userMessage',content:[{type:'text',text:'visible question'},{type:'image',url:'PRIVATE'}]},
   {type:'reasoning',text:'PRIVATE'},{type:'commandExecution',aggregatedOutput:'PRIVATE'},
   {type:'agentMessage',text:'visible reply'}]}]},host);
 assert.deepEqual(value.messages.map(({images,...message})=>message),[{role:'user',text:'visible question'},{role:'assistant',text:'visible reply'}]);
 assert.match(value.messages[0].images[0].id,/^[a-f0-9]{64}$/);
 assert.equal(value.remote_ref.baseline_turn,'turn');assert.equal(JSON.stringify(value).includes('PRIVATE'),false);
 const active=snapshot({id:thread,updatedAt:1000,turns:[{id:'running',status:'inProgress',items:[]}]},host);
 assert.equal(active.remote_ref,undefined);
 assert.equal(active.running,true);assert.equal(active.thread_ref.baseline_turn,'running');
 const failed=snapshot({id:thread,updatedAt:1000,turns:[{id:'failed-turn',status:'failed',items:[]}]},host);
 assert.equal(failed.remote_ref.baseline_turn,'failed-turn');
});
test('phone cannot create a conversation in an arbitrary filesystem directory',async()=>{
 const client={call:async()=>({data:[]})};await assert.rejects(selectedProject(client,'arbitrary'),/INVALID_PROJECT/);
});

test('no-project uses a dedicated bridge-owned workspace and does not query another project',async()=>{
 const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
 const {creationProject}=require('../../app/src/main/assets/task-notifications/conversation-library.cjs');
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'codex-usage-env-test-'));
 try{
  const client={call:async()=>{throw Error('No project must not inherit a recent directory');}};
  const selected=await creationProject(client,'default',dir);
  assert.equal(selected.cwd,fs.realpathSync(path.join(dir,'conversation-workspace')));
  assert.notEqual(selected.cwd,fs.realpathSync(dir));
  assert.equal((await creationProject(client,'default',dir)).id,selected.id);
  await assert.rejects(creationProject({call:async()=>({data:[]})},'arbitrary/path',dir),/INVALID_PROJECT/);
 }finally{
  assert.equal(path.dirname(dir),fs.realpathSync(os.tmpdir()));
  assert.ok(path.basename(dir).startsWith('codex-usage-env-test-'));fs.rmSync(dir,{recursive:true,force:true});
 }
});

test('project selectors receive real canonical paths and distinct IDs even with the same name',()=>{
 const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
 const {summaries}=require('../../app/src/main/assets/task-notifications/conversation-library.cjs');
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'codex-usage-env-test-'));
 try{
  const dirs=['one','two'].map(parent=>path.join(dir,parent,'same'));
  for(const cwd of dirs)fs.mkdirSync(cwd,{recursive:true});
  const rows=summaries(dirs.map((cwd,i)=>({id:i?host:thread,cwd,updatedAt:1})));
  assert.equal(rows[0].project_name,rows[1].project_name);assert.notEqual(rows[0].project_id,rows[1].project_id);
  assert.deepEqual(rows.map(p=>p.project_path),dirs.map(cwd=>fs.realpathSync(cwd)));
 }finally{
  assert.equal(path.dirname(dir),fs.realpathSync(os.tmpdir()));assert.ok(path.basename(dir).startsWith('codex-usage-env-test-'));
  fs.rmSync(dir,{recursive:true,force:true});
 }
});
test('full history projects the same native order for messages and automatic tool rows',async()=>{
 const {historyPage}=require('../../app/src/main/assets/task-notifications/conversation-library.cjs');
 const client={call:async(m,p)=>{
  assert.equal(m,'thread/turns/list');assert.equal(p.itemsView,'full');
  return {data:[{id:'turn',status:'completed',items:[
   {id:'q',type:'userMessage',content:[{type:'text',text:'question'}]},
   {id:'comment',type:'agentMessage',text:'checking'},
   {id:'cmd',type:'commandExecution',command:'test',status:'completed',aggregatedOutput:'passed',exitCode:0},
   {id:'patch',type:'fileChange',status:'completed',changes:[{path:'fixture.txt',kind:{type:'add'},diff:'+fixture'}]},
   {id:'reply',type:'agentMessage',text:'finished'}]}],nextCursor:''};
 }};
 const page=await historyPage(client,thread);
 assert.deepEqual(page.messages.map(x=>x.position),[0,1,4]);assert.deepEqual(page.activities.map(x=>x.position),[2,3]);
 assert.equal(page.activities[0].exit_code,0);assert.equal(page.activities[1].files[0].diff,'+fixture');
});

test('reading large local history never requests the full app-server tool transcript',async()=>{
 const client={call:async(method,params)=>{
  assert.equal(method,'thread/read');assert.equal(params.includeTurns,false);
  return {thread:{id:thread,name:'Real title',updatedAt:1000,status:{type:'notLoaded'}}};
 }};
 const snap=await readSnapshot(client,thread,host,()=>({conversation_id:'hash',title:'old',reply:'reply',messages:[],completed_at:'',truncated:true}));
 assert.equal(snap.title,'Real title');assert.equal(snap.completed_at,new Date(1000*1000).toISOString());
});

test('thread pagination preserves opaque provider cursors and archived filter',async()=>{
 const {threadPage}=require('../../app/src/main/assets/task-notifications/conversation-library.cjs');
 const c={call:async(m,p)=>{assert.equal(m,'thread/list');assert.equal(p.cursor,'provider cursor');assert.equal(p.archived,true);assert.equal(p.limit,30);
  assert.deepEqual(p.sourceKinds,['cli','vscode','exec','appServer','unknown']);
  return {data:[{id:thread,updatedAt:1000}],nextCursor:'next provider cursor'};}};
 const page=await threadPage(c,{cursor:'provider cursor',archived:true});
 assert.equal(page.next_cursor,'next provider cursor');assert.equal(page.threads[0].updated_at,1000000);
});

test('paged history remains chronological, keeps stable message identities and never resumes',async()=>{
 const {historyPage}=require('../../app/src/main/assets/task-notifications/conversation-library.cjs');
 const c={call:async(m,p)=>{assert.equal(m,'thread/turns/list');assert.equal(p.cursor,'older');assert.equal(p.itemsView,'full');
  return {data:[{id:'new',items:[{id:'q2',type:'userMessage',content:[{type:'text',text:'second'}]}]},
   {id:'old',items:[{id:'q1',type:'userMessage',content:[{type:'text',text:'first'}]},{id:'a1',type:'agentMessage',text:'answer'}]}],nextCursor:'oldest'};}};
 const page=await historyPage(c,thread,'older');
 assert.deepEqual(page.messages.map(x=>x.text),['first','answer','second']);
 assert.deepEqual(page.messages.map(x=>x.id),['old:q1','old:a1','new:q2']);assert.equal(page.next_cursor,'oldest');
});

test('management uses only official scoped operations and refuses active threads and invalid names',async()=>{
 const {manageThread}=require('../../app/src/main/assets/task-notifications/conversation-library.cjs');
 const calls=[];const client={call:async(m,p)=>{calls.push([m,p]);return m==='thread/read'?{thread:{id:thread,status:{type:'notLoaded'}}}:{};}};
 const renamed=await manageThread(client,'rename',thread,' New name ');
 assert.equal(renamed.managed_name,'New name');assert.deepEqual(calls[1],['thread/name/set',{threadId:thread,name:'New name'}]);
 await manageThread(client,'archive',thread);assert.deepEqual(calls.at(-1),['thread/archive',{threadId:thread}]);
 await manageThread(client,'unarchive',thread);assert.deepEqual(calls.at(-1),['thread/unarchive',{threadId:thread}]);
 await assert.rejects(manageThread(client,'delete',thread),/INVALID_MANAGEMENT/);
 await assert.rejects(manageThread(client,'rename',thread,'x'.repeat(241)),/INVALID_NAME/);
 await assert.rejects(manageThread(client,'rename',thread,'\n'),/INVALID_NAME/);
 const busy={call:async()=>({thread:{id:thread,status:{type:'active'}}})};
 await assert.rejects(manageThread(busy,'archive',thread),/BUSY/);
});
