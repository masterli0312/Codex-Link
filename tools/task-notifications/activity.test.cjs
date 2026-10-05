'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {projectItem,mergeActivities,projectTurns}=require('../../app/src/main/assets/task-notifications/remote-activity.cjs');
test('delayed live items cannot undo a completed or failed provider result',()=>{
 const done=projectItem('turn',{type:'commandExecution',id:'cmd',command:'test',status:'completed',exitCode:0,aggregatedOutput:'passed'});
 const stale={...done,status:'inProgress',exit_code:undefined,detail:''};
 assert.deepEqual(mergeActivities([done],[stale]),[done]);
 assert.deepEqual(mergeActivities([stale],[done]),[done]);
 assert.deepEqual(mergeActivities([done],[{...done,type:'plan'}]),[done]);
 const failed={...done,status:'failed',exit_code:2};
 assert.deepEqual(mergeActivities([failed],[stale]),[failed]);
 assert.equal(mergeActivities([done],[{...done,detail:'full output'}])[0].detail,'full output');
});
test('native command output and exit status are preserved without arguments or reasoning leakage',()=>{
 const item={type:'commandExecution',id:'cmd',command:'gradle test',status:'completed',aggregatedOutput:'147 tests passed',exitCode:0,durationMs:32,credentials:'PRIVATE'};
 const a=projectItem('turn',item);assert.equal(a.id,'turn:cmd');assert.equal(a.detail,'147 tests passed');assert.equal(a.exit_code,0);assert.equal(a.duration_ms,32);
 assert.equal(projectItem('turn',{type:'reasoning',id:'hidden',text:'PRIVATE'}),null);
 assert.equal(JSON.stringify(a).includes('PRIVATE'),false);
 assert.equal(projectItem('turn',{type:'commandExecution',aggregatedOutput:'PRIVATE'}),null);
});
test('native item positions survive an unpositioned live update',()=>{
 const native={id:'cmd',type:'commandExecution',command:'test',status:'completed',aggregatedOutput:'done'};
 const positioned=projectItem('turn',native,4);
 assert.equal(positioned.position,4);
 const merged=mergeActivities([positioned],[projectItem('turn',{...native,aggregatedOutput:'more output'})]);
 assert.equal(merged[0].position,4);assert.equal(merged[0].detail,'more output');
});
test('real file changes preserve paths and diff while bounded output signals truncation',()=>{
 const a=projectItem('turn',{type:'fileChange',id:'patch',status:'completed',changes:[{path:'src/app.kt',kind:{type:'update'},diff:'@@ -1 +1 @@\n-old\n+new'}]});
 assert.equal(a.files[0].path,'src/app.kt');assert.match(a.files[0].diff,/\+new/);assert.equal(a.files[0].kind,'update');
 const long=projectItem('turn',{type:'commandExecution',id:'cmd',command:'test',status:'failed',aggregatedOutput:'文'.repeat(20000),exitCode:2});
 assert.ok(Buffer.byteLength(long.detail)<=8192);assert.equal(long.truncated,true);assert.equal(long.exit_code,2);
});
test('activities are ordered and updated by turn plus item identity, never by matching text',()=>{
 const old=projectItem('first',{type:'commandExecution',id:'1',command:'test',status:'inProgress'});
 const done=projectItem('first',{type:'commandExecution',id:'1',command:'test',status:'completed',exitCode:0});
 const later=projectItem('second',{type:'commandExecution',id:'1',command:'test',status:'completed',exitCode:1});
 assert.deepEqual(mergeActivities([old],[done,later]),[done,later]);
 const projected=projectTurns([{id:'first',items:[{type:'reasoning',text:'PRIVATE'},{type:'plan',id:'p',text:'Real plan'}]}]);
 assert.equal(projected.length,1);assert.equal(projected[0].type,'plan');
 const bounded=mergeActivities([],Array.from({length:80},(_,i)=>projectItem('turn',{id:String(i),type:'commandExecution',command:'test',status:'completed',aggregatedOutput:'x'.repeat(8192)})));
 assert.ok(bounded.length<=40);assert.ok(Buffer.byteLength(JSON.stringify(bounded))<=65536);
});

test('older tool pages cannot evict the newest activities and make the live transcript oscillate',()=>{
 const {mergeActivities}=require('../../app/src/main/assets/task-notifications/remote-activity.cjs');
 const item=n=>({id:'turn:'+n,turn_id:'turn',type:'commandExecution',status:'completed',position:n,title:'command',detail:'x'.repeat(2000),files:[]});
 const recent=Array.from({length:40},(_,n)=>item(60+n)),history=Array.from({length:20},(_,n)=>item(n));
 let values=mergeActivities([],recent);const latest=values.map(x=>x.id);
 for(let n=0;n<3;n++){values=mergeActivities(values,history);assert.deepEqual(values.map(x=>x.id),latest);values=mergeActivities(values,recent);assert.deepEqual(values.map(x=>x.id),latest);}
});
