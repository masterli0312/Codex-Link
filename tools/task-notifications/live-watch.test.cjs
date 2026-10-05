'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {LiveWatch,liveTail}=require('../../app/src/main/assets/task-notifications/live-watch.cjs');
const {snapshot}=require('../../app/src/main/assets/task-notifications/conversation-library.cjs');
const thread='11111111-2222-3333-4444-555555555555',host='host';
function fixture(){return new LiveWatch(thread,host,snapshot({id:thread,updatedAt:1000,turns:[{id:'old',status:'completed',items:[{id:'old-a',type:'agentMessage',text:'old reply'}]}]},host));}
const event=(extra={})=>({threadId:thread,turnId:'new',...extra});
test('turn start publishes embedded desktop input immediately and later item events keep its stable identity',()=>{
 const w=fixture(),input={id:'q',type:'userMessage',content:[{type:'text',text:'new desktop question'}]};
 w.apply('turn/started',event({turn:{id:'new',items:[input,{id:'private',type:'reasoning',text:'SECRET'}]}}));
 assert.deepEqual(liveTail(w.value).messages.map(m=>[m.id,m.text,m.position]),[['new:q','new desktop question',0]]);
 w.apply('item/started',event({item:input}));w.apply('item/completed',event({item:input}));
 w.apply('item/agentMessage/delta',event({itemId:'a',delta:'response'}));
 assert.equal(w.value.messages.filter(m=>m.id==='new:q').length,1);
 assert.equal(w.value.messages.at(-1).position,2);
 assert.equal(JSON.stringify(w).includes('SECRET'),false);
 assert.equal(w.apply('turn/started',event({turn:{id:'old',items:[{...input,id:'wrong'}]}})),false);
 assert.equal(w.apply('turn/started',event({threadId:'other',turn:{id:'foreign',items:[input]}})),false);
 assert.equal(w.value.messages.some(m=>m.id==='old:wrong'),false);
});

test('context compaction lifecycle is visible live and survives final history projection',()=>{
 const w=fixture();w.apply('turn/started',event({turn:{id:'new'}}));
 w.apply('item/started',event({item:{id:'compact',type:'contextCompaction'}}));
 assert.equal(w.value.activities[0].type,'compaction');assert.equal(w.value.activities[0].status,'inProgress');
 w.apply('item/completed',event({item:{id:'compact',type:'contextCompaction'}}));
 assert.equal(w.value.activities[0].status,'completed');
 w.apply('turn/completed',event({turn:{id:'new',status:'completed'}}));
 assert.equal(w.value.activities[0].type,'compaction');
});
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
 assert.equal(liveTail(w.value).reply,''); // Previous reply must not force a new input into the attachment path.
 assert.equal(w.value.reply,'old reply'); // The full recovery snapshot still owns the history.
 w.apply('item/agentMessage/delta',event({itemId:'a',delta:'new reply'}));
 const tail=liveTail(w.value);
 assert.deepEqual(tail.messages.map(m=>m.id),['new:a']);assert.equal(tail.thread_ref.baseline_turn,'new');
 assert.equal(tail.history_cursor,'older cursor');assert.equal(tail.running,true);
 assert.equal(w.value.messages[0].text,'old reply'); // recovery source is retained
 w.apply('turn/completed',event({turn:{id:'new',status:'completed'}}));
 assert.equal(liveTail(w.value).remote_ref.baseline_turn,'new');
});

test('active reconciliation recovers missing items without replacing newer streamed text',()=>{
 const {reconcileSnapshot}=require('../../app/src/main/assets/task-notifications/live-watch.cjs');
 const w=fixture();w.apply('turn/started',event({turn:{id:'new'}}));
 w.apply('item/started',event({item:{id:'a',type:'agentMessage',phase:'commentary',text:'Checking everything'}}));
 const saved=snapshot({id:thread,turns:[{id:'new',status:'inProgress',items:[
  {id:'a',type:'agentMessage',phase:'commentary',text:'Checking'},
  {id:'missed',type:'commandExecution',command:'test',status:'completed',aggregatedOutput:'OK'}]}]},host);
 const recovered=reconcileSnapshot(w,saved);
 assert.equal(recovered.running,true);assert.equal(recovered.messages.at(-1).text,'Checking everything');
 assert.equal(recovered.messages.at(-1).phase,'commentary');assert.equal(recovered.activities.at(-1).id,'new:missed');
 w.apply('turn/completed',event({turn:{id:'new',status:'completed',durationMs:0}}));
 const terminal=reconcileSnapshot(w,saved);
 assert.equal(terminal.running,false);assert.equal(terminal.turn_durations.new,0);
 assert.equal(reconcileSnapshot(w,{...saved,thread_ref:{...saved.thread_ref,thread_id:'other'}}),null);
});
test('reconciliation accepts a missed new turn and rejects a known historical turn',()=>{
 const {reconcileSnapshot}=require('../../app/src/main/assets/task-notifications/live-watch.cjs');
 const w=fixture();
 const saved=snapshot({id:thread,updatedAt:2000,turns:[{id:'missed',status:'inProgress',items:[{id:'a',type:'agentMessage',text:'Latest'}]}]},host);
 assert.equal(reconcileSnapshot(w,saved).thread_ref.baseline_turn,'missed');
 w.apply('turn/started',event({turn:{id:'new'}}));
 assert.equal(reconcileSnapshot(w,fixture().value),null);
});

test('viewed images are activities in both history and live stream, not assistant image messages',()=>{
 const image={id:'view',type:'imageView',path:'C:/fixture/inspection.png'};
 const native={id:thread,updatedAt:1000,turns:[{id:'new',status:'inProgress',items:[image,{id:'generated',type:'imageGeneration',status:'completed',savedPath:'C:/fixture/generated.png'},{id:'user',type:'userMessage',content:[{type:'localImage',path:'C:/fixture/input.png'}]}]}]};
 const historic=snapshot(native,host);
 assert.ok(!historic.messages.some(m=>m.id==='new:view'));
 assert.equal(historic.activities[0].type,'imageView');assert.equal(historic.activities[0].images.length,1);
 assert.ok(historic.messages.some(m=>m.id==='new:generated'&&m.images.length));
 assert.ok(historic.messages.some(m=>m.role==='user'&&m.images.length));
 const w=fixture();w.apply('turn/started',event({turn:{id:'new'}}));w.apply('item/completed',event({item:image}));
 assert.ok(!w.value.messages.some(m=>m.id==='new:view'));
 assert.equal(w.value.activities[0].images[0].id,historic.activities[0].images[0].id);
});
