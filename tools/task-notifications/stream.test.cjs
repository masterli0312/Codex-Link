'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {compactFrame,LIVE_FLUSH_MS,WATCH_POLL_MS,SENDER_IDLE_MS,livePublishInterval,StreamResetTracker}=require('../../app/src/main/assets/task-notifications/remote-stream.cjs');
const base={request_id:'one',host_id:'host',thread_id:'thread',turn_id:'turn',status:'running',stream_version:1,seq:1,reply:'hello '.repeat(8000),activities:[{id:'tool',detail:'output'}]};
test('live frames use the last successfully published base and avoid repeated full attachments',()=>{
 const next={...base,seq:8,reply:base.reply+'新增回复'};
 const frame=compactFrame(base,next);assert.equal(frame.reply,'');assert.equal(frame.reply_patch.base_seq,1);
 assert.equal(base.reply.slice(0,frame.reply_patch.prefix_length)+frame.reply_patch.suffix,next.reply);
 assert.deepEqual(frame.activities,[]);assert.ok(Buffer.byteLength(JSON.stringify(frame))<1000);
 assert.equal(compactFrame(base,{...next,status:'completed'}).reply,next.reply);
 assert.equal(compactFrame(base,{...next,turn_id:'other'}).reply,next.reply);
});
test('Unicode boundaries and rewritten replies round trip without splitting a surrogate pair',()=>{
 const previous={...base,reply:'hello 😀'};const next={...previous,seq:2,reply:'hello 😁 tail'};
 const frame=compactFrame(previous,next);assert.equal(previous.reply.slice(0,frame.reply_patch.prefix_length)+frame.reply_patch.suffix,next.reply);
});
test('live and desktop synchronization timers meet the sub-second to one-second scheduling target',()=>{
 assert.ok(LIVE_FLUSH_MS<=500);assert.ok(WATCH_POLL_MS<=1000);assert.ok(SENDER_IDLE_MS<=100);
});
test('self-hosted live streams avoid a one-second hold while public relay pacing is preserved',()=>{
 assert.equal(livePublishInterval('https://ntfy.sh/topic'),1000);
 assert.equal(livePublishInterval('https://example.test/topic'),300);
 assert.ok(LIVE_FLUSH_MS+livePublishInterval('https://example.test/topic')+SENDER_IDLE_MS<1000);
});
test('a status recovery is always a full frame even when the previous base still exists on the computer',()=>{
 const next={...base,seq:9,reply:base.reply+'recovered',stream_reset:true};
 assert.equal(compactFrame(base,next),next);
});
test('older installed phone clients always receive complete replies, never undecodable patches',()=>{
 const next={...base,seq:9,reply:base.reply+'legacy',stream_version:0};
 assert.equal(compactFrame(base,next),next);
 assert.equal(compactFrame(base,{...next,stream_version:undefined}).reply,next.reply);
});
test('coalescing newer live text cannot swallow a requested full-state recovery',()=>{
 const tracker=new StreamResetTracker();tracker.request({...base,seq:5,stream_reset:true});
 const live={...base,seq:6,reply:base.reply+'newer'};
 assert.equal(compactFrame(base,tracker.frame(live)).reply,live.reply);
 tracker.request({...base,seq:7,stream_reset:true});tracker.acknowledge(live);
 assert.equal(tracker.frame({...live,seq:8}).stream_reset,true);
 tracker.acknowledge({...live,seq:8});assert.equal(tracker.frame({...live,seq:9}).stream_reset,undefined);
 assert.ok(compactFrame(live,tracker.frame({...live,seq:9})).reply_patch);
});
