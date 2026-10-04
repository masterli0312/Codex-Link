'use strict';
const crypto=require('node:crypto');
const hash=s=>crypto.createHash('sha256').update(s).digest('hex');
const LIVE_FLUSH_MS=250,WATCH_POLL_MS=1000,SENDER_IDLE_MS=100;
function livePublishInterval(endpoint){
 const host=new URL(endpoint).hostname.toLowerCase();
 // Retain the public relay's message budget. Self-hosted streams batch at most
 // about three live frames/second; the sender still coalesces to the newest frame.
 return host==='ntfy.sh'||host.endsWith('.ntfy.sh')?1000:300;
}
class StreamResetTracker {
 constructor(){this.requests=new Map();}
 request(event){if(event.stream_reset){this.requests.set(event.request_id,event.seq);if(this.requests.size>128)this.requests.delete(this.requests.keys().next().value);}}
 frame(event){const seq=this.requests.get(event.request_id);return seq&&seq<=event.seq?{...event,stream_reset:true}:event;}
 acknowledge(event){const seq=this.requests.get(event.request_id);if(seq&&seq<=event.seq)this.requests.delete(event.request_id);}
}
function compactFrame(previous,event){
 if(!previous||event.stream_version!==1||event.stream_reset||event.status!=='running'||previous.status!=='running'||event.request_id!==previous.request_id||
  event.host_id!==previous.host_id||event.thread_id!==previous.thread_id||event.turn_id!==previous.turn_id||!event.turn_id||
  event.goal_waiting||event.snapshot||event.seq<=previous.seq)return event;
 let prefix=0;const old=previous.reply||'',next=event.reply||'';
 while(prefix<Math.min(old.length,next.length)&&old[prefix]===next[prefix])prefix++;
 if(prefix&&/[\uD800-\uDBFF]/.test(old[prefix-1]))prefix--;
 const suffix=next.slice(prefix);
 if(Buffer.byteLength(suffix)>1800)return event;
 const previousItems=new Map((previous.activities||[]).map(item=>[item.id,JSON.stringify(item)]));
 return {...event,reply:'',reply_patch:{base_seq:previous.seq,prefix_length:prefix,suffix,sha256:hash(next)},
  activities:(event.activities||[]).filter(item=>previousItems.get(item.id)!==JSON.stringify(item))};
}
module.exports={LIVE_FLUSH_MS,WATCH_POLL_MS,SENDER_IDLE_MS,livePublishInterval,compactFrame,StreamResetTracker};
