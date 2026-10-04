'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {LiveWatch,liveTail}=require('../../app/src/main/assets/task-notifications/live-watch.cjs');
const {snapshot}=require('../../app/src/main/assets/task-notifications/conversation-library.cjs');
const thread='11111111-2222-3333-4444-555555555555',host='host';
function fixture(){return new LiveWatch(thread,host,snapshot({id:thread,updatedAt:1000,turns:[{id:'old',status:'completed',items:[{id:'old-a',type:'agentMessage',text:'old reply'}]}]},host));}
const event=(extra={})=>({threadId:thread,turnId:'new',...extra});
test('live desktop messages stream in native order and complete on the same thread',()=>{
 const w=fixture();w.apply('turn/started',event({turn:{id:'new',status:'inProgress'}}));
 assert.equal(w.value.remote_ref,undefined);assert.equal(w.value.running,true);
 w.apply('item/started',event({item:{id:'q',type:'userMessage',content:[{type:'text',text:'question'}]}}));
 w.apply('item/started',event({item:{id:'a',type:'agentMessage',text:''}}));
 w.apply('item/agentMessage/delta',event({itemId:'a',delta:'hello '}));
 w.apply('item/agentMessage/delta',event({itemId:'a',delta:'world'}));
 assert.equal(w.value.messages.at(-1).text,'hello world');assert.equal(w.value.messages.at(-1).position,1);
 w.apply('item/completed',event({item:{id:'a',type:'agentMessage',text:'hello world!'}}));
 w.apply('turn/completed',event({turn:{id:'new',status:'completed',items:[]}}));
 assert.deepEqual(w.value.messages.map(m=>m.text),['old reply','question','hello world!']);
 assert.equal(w.value.remote_ref.baseline_turn,'new');assert.equal(w.value.running,false);
});
test('foreign, previous, completed and unconfirmed turns cannot overwrite the live conversation',()=>{
 const w=fixture();assert.equal(w.apply('item/agentMessage/delta',event({itemId:'a',delta:'unconfirmed'})),false);
 w.apply('turn/started',event({turn:{id:'new'}}));
 assert.equal(w.apply('turn/started',event({turn:{id:'old'}})),false);
 for(const p of [event({threadId:'other'}),event({turnId:'old'})])assert.equal(w.apply('item/agentMessage/delta',{...p,itemId:'a',delta:'foreign'}),false);
 w.apply('turn/completed',event({turn:{id:'new',status:'completed'}}));
 assert.equal(w.apply('item/agentMessage/delta',event({itemId:'a',delta:'late'})),false);
 assert.equal(w.value.reply,'old reply');
});
test('public tool progress is visible while reasoning and MCP arguments are never retained',()=>{
 const w=fixture();w.apply('turn/started',event({turn:{id:'new'}}));
 w.apply('item/started',event({item:{id:'r',type:'reasoning',text:'SECRET'}}));
 w.apply('item/started',event({item:{id:'cmd',type:'commandExecution',command:'test',status:'inProgress',aggregatedOutput:''}}));
 w.apply('item/commandExecution/outputDelta',event({itemId:'cmd',delta:'passed'}));
 w.apply('item/completed',event({item:{id:'mcp',type:'mcpToolCall',server:'s',tool:'t',arguments:'SECRET',result:'SECRET'}}));
 assert.equal(w.value.activities[0].detail,'passed');assert.equal(w.value.activities[0].position,1);
 assert.equal(JSON.stringify(w).includes('SECRET'),false);
});
test('live projection stays bounded across large deltas and many messages',()=>{
 const w=fixture();w.apply('turn/started',event({turn:{id:'new'}}));
 for(let n=0;n<200;n++)w.apply('item/agentMessage/delta',event({itemId:'a'+n,delta:'x'.repeat(20000)}));
 assert.ok(w.value.messages.length<=60);assert.ok(Buffer.byteLength(JSON.stringify(w.value))<=160000);
 assert.equal(w.value.truncated,true);
});
test('live tail avoids retransmitting old messages while preserving native identity and recovery cursor',()=>{
 const w=fixture();w.value.history_cursor='older cursor';w.apply('turn/started',event({turn:{id:'new'}}));
 w.apply('item/agentMessage/delta',event({itemId:'a',delta:'new reply'}));
 const tail=liveTail(w.value);
 assert.deepEqual(tail.messages.map(m=>m.id),['new:a']);assert.equal(tail.thread_ref.baseline_turn,'new');
 assert.equal(tail.history_cursor,'older cursor');assert.equal(tail.running,true);
 assert.equal(w.value.messages[0].text,'old reply'); // recovery source is retained
 w.apply('turn/completed',event({turn:{id:'new',status:'completed'}}));
 assert.equal(liveTail(w.value).remote_ref.baseline_turn,'new');
});
