'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {selection,normalizeModes,validateCommand}=require('../../app/src/main/assets/task-notifications/remote-core.cjs');
const {CodexClient}=require('../../app/src/main/assets/task-notifications/codex-client.cjs');
const crypto=require('node:crypto'),thread=crypto.randomUUID(),host=crypto.randomUUID();
const models=[{model:'provider/model',isDefault:true,defaultReasoningEffort:'medium',supportedReasoningEfforts:[{reasoningEffort:'low'},{reasoningEffort:'medium'}]}];
const client={models:async()=>models,modes:async()=>[{mode:'plan'},{mode:'default'},{mode:'future'}]};
test('only discovered supported protocol modes are exposed',()=>{
 assert.deepEqual(normalizeModes([{mode:'plan'},{mode:'plan'},{mode:'future'},{mode:'default'},null]),['plan','default']);
});
test('Plan validates real modes and uses a real model without changing the provider',async()=>{
 assert.deepEqual(await selection(client,{mode:'plan',effort:'low'},{}),{model:'provider/model',effort:'low',mode:'plan'});
 await assert.rejects(selection({...client,modes:async()=>[]},{mode:'plan'},{}),/MODE_UNAVAILABLE/);
 await assert.rejects(selection({...client,models:async()=>[]},{mode:'plan'},{}),/INVALID_MODEL_SELECTION/);
 const neverCall={models:async()=>{throw Error('should not run');},modes:async()=>{throw Error('should not run');}};
 assert.deepEqual(await selection(neverCall,{mode:''},{}),{});
});
test('Plan uses the actual collaborationMode protocol with built-in instructions',async()=>{
 const c=new CodexClient('fixture','fixture');let call;c.call=async(m,p)=>{call=[m,p];return {};};
 await c.start(thread,'plain input','id',{model:'provider/model',effort:'low',mode:'plan'});
 assert.equal(call[0],'turn/start');assert.deepEqual(call[1].input,[{type:'text',text:'plain input'}]);
 assert.deepEqual(call[1].collaborationMode,{mode:'plan',settings:{model:'provider/model',reasoning_effort:'low',developer_instructions:null}});
 assert.equal(call[1].modelProvider,undefined);
 await c.start(thread,'plain input','id',{});assert.equal(call[1].collaborationMode,undefined);
 c.close();
});
test('mode is a closed value rather than instructions or arbitrary RPC',()=>{
 const base={id:crypto.randomUUID(),host_id:host,thread_id:thread,conversation_id:crypto.createHash('sha256').update(thread).digest('hex'),
  issued_at:Date.now(),action:'send',text:'continue',baseline_turn:'last'};
 assert.equal(validateCommand({...base,mode:'plan'},host),true);
 assert.equal(validateCommand({...base,mode:'default'},host),true);
 assert.equal(validateCommand({...base,mode:'arbitrary instructions'},host),false);
 assert.equal(validateCommand({...base,mode:{mode:'plan'}},host),false);
});
