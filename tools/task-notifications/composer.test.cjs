'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {selection,normalizeModels}=require('../../app/src/main/assets/task-notifications/remote-core.cjs');
const {LiveWatch,reconcileSnapshot}=require('../../app/src/main/assets/task-notifications/live-watch.cjs');
test('model display metadata is bounded and only reflects provider fields',()=>{
 const models=normalizeModels([{model:'actual',displayName:'Actual',description:'x'.repeat(900),isDefault:true},
   {model:'hidden',hidden:true},{model:'actual',displayName:'duplicate'}]);
 assert.equal(models.length,1);assert.equal(models[0].model,'actual');assert.equal(models[0].description.length,800);assert.equal(models[0].is_default,true);
});
test('Plan model and mode validation overlap without weakening either gate',async()=>{
 let modelResolve,modeResolve;const calls=[];
 const pending=selection({models:()=>{calls.push('models');return new Promise(r=>modelResolve=r);},
   modes:()=>{calls.push('modes');return new Promise(r=>modeResolve=r);}}, {model:'native',effort:'high',mode:'plan'},{});
 assert.deepEqual(calls,['models','modes']);
 modelResolve([{model:'native',supportedReasoningEfforts:[{reasoningEffort:'high'}]}]);modeResolve([{mode:'plan'}]);
 assert.deepEqual(await pending,{model:'native',effort:'high',mode:'plan'});
 await assert.rejects(selection({models:async()=>[{model:'native'}],modes:async()=>[]},{model:'native',mode:'plan'},{}),/MODE_UNAVAILABLE/);
});
test('live elapsed time survives re-open and snapshot reconciliation without resetting',()=>{
 const started=Date.now()-125000;
 const snapshot={messages:[],activities:[],running:true,thread_ref:{host_id:'host',thread_id:'thread',baseline_turn:'turn'},turn_started_at:{turn:started}};
 const live=new LiveWatch('thread','host',snapshot);
 assert.equal(live.startedAt,started);
 const merged=reconcileSnapshot(live,{...snapshot,turn_started_at:{} });
 assert.equal(merged.turn_started_at.turn,started);
 live.apply('turn/started',{threadId:'thread',turn:{id:'next',startedAt:1760000000}});
 assert.equal(live.value.turn_started_at.next,1760000000000);
});
