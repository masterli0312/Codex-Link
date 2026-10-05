'use strict';
const {limit,projectItem,mergeActivities,turnDuration}=require('./remote-activity.cjs');
const {projectImages}=require('./conversation-images.cjs');
// A display-only projection of public native events. Never retains reasoning or MCP payloads.
class LiveWatch {
 constructor(threadId,hostId,snapshot,cwd=''){
  this.cwd=cwd;
  this.threadId=threadId;this.hostId=hostId;this.value=structuredClone(snapshot);this.version=0;
  this.turn=snapshot.thread_ref?.baseline_turn||snapshot.remote_ref?.baseline_turn||'';
  this.startedAt=snapshot.turn_started_at?.[this.turn]||null;
  this.seenTurns=new Set([this.turn,...snapshot.messages.map(m=>m.id?.split(':')[0]).filter(Boolean)]);
  this.positions=new Map();this.nextPosition=0;
  for(const item of [...snapshot.messages,...(snapshot.activities||[])])if(item.id?.startsWith(this.turn+':')){
   const pos=Number.isInteger(item.position)?item.position:this.nextPosition;
   this.positions.set(item.id,pos);this.nextPosition=Math.max(this.nextPosition,pos+1);
  }
 }
 apply(method,p){
  if(p.threadId!==this.threadId)return false;
  const turn=p.turn?.id||p.turnId;
  if(method==='turn/started'){
   if(!turn||this.seenTurns.has(turn))return false;
   this.seenTurns.add(turn);if(this.seenTurns.size>128)this.seenTurns.delete(this.seenTurns.values().next().value);
   this.turn=turn;this.positions.clear();this.nextPosition=0;
   this.startedAt=Number.isSafeInteger(p.turn?.startedAt)?(p.turn.startedAt<1e11?p.turn.startedAt*1000:p.turn.startedAt):Date.now();
   this.value.turn_started_at=Object.fromEntries(Object.entries({...this.value.turn_started_at,[turn]:this.startedAt}).slice(-40));
   this.value.running=true;delete this.value.remote_ref;
   this.value.thread_ref={host_id:this.hostId,thread_id:this.threadId,baseline_turn:turn};
   // Native turn/started can be the only event carrying the desktop user input.
   // Use the same bounded public projection and stable IDs as later item events.
   for(const item of (Array.isArray(p.turn?.items)?p.turn.items:[]).slice(0,512))this.item(item);
  }else{
   if(!turn||turn!==this.turn||!this.value.running)return false;
   if(method==='item/started'||method==='item/completed')this.item(p.item && {...p.item,status:p.item.status||(method==='item/started'?'inProgress':'completed')});
   else if(method==='thread/compacted'){
    // Older native servers expose only this confirmed completion notification.
    const compact=this.value.activities?.findLast(a=>a.turn_id===turn&&a.type==='compaction');
    this.item({id:compact?compact.id.slice(turn.length+1):'context-compacted',type:'contextCompaction',status:'completed'});
   }
   else if(method==='item/agentMessage/delta'){
    if(typeof p.itemId!=='string'||typeof p.delta!=='string')return false;
    const id=turn+':'+p.itemId,old=this.value.messages.find(m=>m.id===id);
    this.item({id:p.itemId,type:'agentMessage',text:(old?.text||'')+p.delta});
   }else if(method==='item/commandExecution/outputDelta'){
    const id=turn+':'+p.itemId,old=this.value.activities?.find(a=>a.id===id);
    if(!old||typeof p.delta!=='string')return false;
    this.value.activities=mergeActivities(this.value.activities,[{...old,detail:limit(old.detail+p.delta,8192)}]);
   }else if(method==='turn/completed'){
    for(const item of p.turn.items||[])this.item(item);
    this.value.running=false;this.value.remote_ref=this.value.thread_ref;
    const duration=turnDuration(p.turn)?? (this.startedAt?Math.max(0,Date.now()-this.startedAt):null);
    if(duration!==null)this.value.turn_durations=Object.fromEntries(Object.entries({...this.value.turn_durations,[this.turn]:duration}).slice(-40));
   }else return false;
  }
  this.value.completed_at=new Date().toISOString();
  this.value.reply=[...this.value.messages].reverse().find(m=>m.role==='assistant')?.text||'';
  while(this.value.messages.length>60||Buffer.byteLength(JSON.stringify(this.value))>160000){
   if(!this.value.messages.length)break;this.value.messages.shift();this.value.truncated=true;
  }
  this.version++;return true;
 }
 item(item){
  if(typeof item?.id!=='string'||item.id.length>128)return;
  const id=this.turn+':'+item.id;
  if(!this.positions.has(id)){
   if(this.positions.size>=512)return;this.positions.set(id,this.nextPosition++);
  }
  const position=this.positions.get(id);
  const text=item.type==='agentMessage'?item.text:item.type==='userMessage'?
   (item.content||[]).filter(c=>c.type==='text'&&typeof c.text==='string').map(c=>c.text).join('\n'):
   item.type==='imageGeneration'?'':null;
  const images=item.type==='imageView'?[]:projectImages(this.threadId,item,this.cwd);
  const files=require('./conversation-files.cjs').projectFiles(this.threadId,item,this.cwd);
  if(typeof text==='string'&&(text.trim()||images.length)){
   const previous=this.value.messages.find(m=>m.id===id);
   const phase=typeof item.phase==='string'?item.phase:previous?.phase;
   const message={id,position,role:item.type==='userMessage'?'user':'assistant',text:limit(text,16384),...(phase?{phase}:{}),...(images.length?{images}:{}),...(files.length?{files}:{})};
   this.value.truncated ||= message.text!==text;
   const n=this.value.messages.findIndex(m=>m.id===id);
   if(n>=0)this.value.messages[n]=message;else this.value.messages.push(message);
  }
  const a=projectItem(this.turn,item,position,this.threadId,this.cwd);
  if(a)this.value.activities=mergeActivities(this.value.activities||[],[a]);
 }
}
function liveTail(value){
 const turn=value.thread_ref?.baseline_turn||value.remote_ref?.baseline_turn;
 if(!turn)return value;
 // The initial/recovery snapshot contains history. Subsequent live snapshots
 // carry only this native turn; the phone merges previous stable IDs into history.
 const messages=value.messages.filter(m=>m.id?.startsWith(turn+':'));
 return {...value,messages,reply:[...messages].reverse().find(m=>m.role==='assistant')?.text||'',
  activities:(value.activities||[]).filter(a=>a.turn_id===turn)};
}
function reconcileSnapshot(live,snapshot){
 const ref=snapshot.thread_ref||snapshot.remote_ref;
 if(!ref||ref.host_id!==live.hostId||ref.thread_id!==live.threadId)return null;
 const turn=ref.baseline_turn;
 if(turn!==live.turn){
  if(!turn||live.seenTurns.has(turn)||Date.parse(snapshot.completed_at)+1000<Date.parse(live.value.completed_at))return null;
  return snapshot;
 }
 // A read can recover missed items while the turn is still active. Preserve
 // already received deltas when persisted text is shorter, and merge completion.
 const messages=snapshot.messages.slice();
 for(const old of live.value.messages.filter(m=>m.id?.startsWith(turn+':'))){
  const n=messages.findIndex(m=>m.id===old.id);
  if(n<0)messages.push(old);
  else if(old.text?.length>messages[n].text?.length&&old.text.startsWith(messages[n].text))messages[n]=old;
 }
 messages.sort((a,b)=>{
  if(!a.id?.startsWith(turn+':')||!b.id?.startsWith(turn+':'))return Number(!!a.id?.startsWith(turn+':'))-Number(!!b.id?.startsWith(turn+':'));
  return (a.position??1e6)-(b.position??1e6);
 });
 const value={...snapshot,messages,turn_started_at:Object.fromEntries(Object.entries({...live.value.turn_started_at,...snapshot.turn_started_at}).slice(-40)),turn_durations:Object.fromEntries(Object.entries({...live.value.turn_durations,...snapshot.turn_durations}).slice(-40)),activities:mergeActivities(live.value.activities,snapshot.activities)};
 if(!live.value.running&&snapshot.running){value.running=false;value.remote_ref=live.value.remote_ref;}
 value.reply=[...messages].reverse().find(m=>m.role==='assistant')?.text||'';
 while(value.messages.length>60||Buffer.byteLength(JSON.stringify(value))>160000){
  if(!value.messages.length)break;value.messages.shift();value.truncated=true;
 }
 return value;
}
module.exports={LiveWatch,liveTail,reconcileSnapshot};
