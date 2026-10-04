'use strict';
const {limit,projectItem,mergeActivities}=require('./remote-activity.cjs');
const {projectImages}=require('./conversation-images.cjs');
// A display-only projection of public native events. Never retains reasoning or MCP payloads.
class LiveWatch {
 constructor(threadId,hostId,snapshot,cwd=''){
  this.cwd=cwd;
  this.threadId=threadId;this.hostId=hostId;this.value=structuredClone(snapshot);this.version=0;
  this.turn=snapshot.thread_ref?.baseline_turn||snapshot.remote_ref?.baseline_turn||'';
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
   this.value.running=true;delete this.value.remote_ref;
   this.value.thread_ref={host_id:this.hostId,thread_id:this.threadId,baseline_turn:turn};
  }else{
   if(!turn||turn!==this.turn||!this.value.running)return false;
   if(method==='item/started'||method==='item/completed')this.item(p.item);
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
   ['imageGeneration','imageView'].includes(item.type)?'':null;
  const images=projectImages(this.threadId,item,this.cwd);
  if(typeof text==='string'&&(text.trim()||images.length)){
   const message={id,position,role:item.type==='userMessage'?'user':'assistant',text:limit(text,16384),...(images.length?{images}:{})};
   this.value.truncated ||= message.text!==text;
   const n=this.value.messages.findIndex(m=>m.id===id);
   if(n>=0)this.value.messages[n]=message;else this.value.messages.push(message);
  }
  const a=projectItem(this.turn,item,position);
  if(a)this.value.activities=mergeActivities(this.value.activities||[],[a]);
 }
}
function liveTail(value){
 const turn=value.thread_ref?.baseline_turn||value.remote_ref?.baseline_turn;
 if(!turn)return value;
 // The initial/recovery snapshot contains history. Subsequent live snapshots
 // carry only this native turn; the phone merges previous stable IDs into history.
 return {...value,messages:value.messages.filter(m=>m.id?.startsWith(turn+':')),
  activities:(value.activities||[]).filter(a=>a.turn_id===turn)};
}
module.exports={LiveWatch,liveTail};
