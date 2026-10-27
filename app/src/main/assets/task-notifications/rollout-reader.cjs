'use strict';
// Only session metadata and bounded rollout tails are inspected. Never follow links out of sessions.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_TAIL=4*1024*1024;
const clip=(v,n)=>typeof v==='string'?Array.from(v).slice(0,n).join(''):'';
const bytes=(v,n)=>typeof v==='string'?Buffer.from(v).subarray(0,n).toString('utf8').replace(/\uFFFD$/,''):'';
function time(v){if(typeof v==='string'&&/^\d{1,16}$/.test(v))v=Number(v);if(typeof v==='number'&&Number.isFinite(v)){const n=Math.floor(v<1e11?v*1000:v);return n>=0&&n<=8640000000000000?n:0;}const n=Date.parse(v);return Number.isFinite(n)&&n>=0?n:0;}
function parent(meta){const s=meta?.source?.subagent||meta?.source?.subAgent;const spawn=s?.thread_spawn||s?.threadSpawn||s;return spawn?.parent_thread_id||spawn?.parentThreadId||'';}
function isSub(meta){return !!(meta?.source&&typeof meta.source==='object'&&(meta.source.subagent||meta.source.subAgent));}
function identity(meta){const s=meta?.source?.subagent||meta?.source?.subAgent;const v=s?.thread_spawn||s?.threadSpawn||s||{};return {nickname:clip(v.agent_nickname||v.agentNickname||v.nickname,160),role:clip(v.agent_role||v.agentRole||v.role,160)};}
class Rollouts{
 constructor(home){this.root=home?path.join(home,'sessions'):'';this.metadata=new Map();this.tails=new Map();this.summaries=new Map();}
 safe(file){
  if(!this.root||typeof file!=='string')return false;
  try{const root=fs.realpathSync(this.root),real=fs.realpathSync(file),rel=path.relative(root,real);return !!rel&&!rel.startsWith('..'+path.sep)&&rel!=='..'&&!path.isAbsolute(rel)&&fs.statSync(real).isFile();}catch{return false;}
 }
 meta(file){
  if(!this.safe(file))return null;
  try{const stat=fs.statSync(file),cached=this.metadata.get(file);if(cached&&cached.mtime===stat.mtimeMs&&cached.size===stat.size)return cached.value;
   const fd=fs.openSync(file,'r'),bytes=Buffer.alloc(Math.min(stat.size,65536));try{fs.readSync(fd,bytes,0,bytes.length,0);}finally{fs.closeSync(fd);}
   const end=bytes.indexOf(10);if(end<0)return null;const first=JSON.parse(bytes.subarray(0,end).toString('utf8'));
   const value=first?.type==='session_meta'&&UUID.test(first.payload?.id||'')?{...first.payload,_timestamp:first.timestamp}:null;
   if(value){const lines=bytes.subarray(end+1).toString('utf8').split('\n');lines.pop();const prefix=[];for(const line of lines){try{prefix.push(JSON.parse(line));}catch{}}value._first_user=project(prefix).first_user;}
   this.metadata.set(file,{mtime:stat.mtimeMs,size:stat.size,value});return value;
  }catch{return null;}
 }
 scan(){
  if(!this.root||!fs.existsSync(this.root))throw Error('ROLLOUT_UNAVAILABLE');
  const found=new Map();const walk=dir=>{for(const e of fs.readdirSync(dir,{withFileTypes:true})){
   if(e.isSymbolicLink())continue;const file=path.join(dir,e.name);if(e.isDirectory())walk(file);else if(e.isFile()&&e.name.endsWith('.jsonl')){
    const meta=this.meta(file);if(!meta)continue;const old=found.get(meta.id);const mtime=fs.statSync(file).mtimeMs;
    if(!old||mtime>old.mtime)found.set(meta.id,{file,meta,mtime});
   }
  }};walk(this.root);return found;
 }
 tail(file){
  if(!this.safe(file))throw Error('ROLLOUT_UNAVAILABLE');
  const stat=fs.statSync(file),old=this.tails.get(file);let start,bytes;
  if(old&&old.size===stat.size&&old.mtime===stat.mtimeMs){this.tails.delete(file);this.tails.set(file,old);return old.value;}
  if(old&&stat.size>old.size&&stat.size-old.size<MAX_TAIL){start=old.size;bytes=old.bytes;}
  else{start=Math.max(0,stat.size-MAX_TAIL);bytes=Buffer.alloc(0);}
  const add=Buffer.alloc(stat.size-start),fd=fs.openSync(file,'r');try{let n=0;while(n<add.length){const count=fs.readSync(fd,add,n,add.length-n,start+n);if(!count)break;n+=count;}bytes=Buffer.concat([bytes,add.subarray(0,n)]);}finally{fs.closeSync(fd);}
  if(bytes.length>MAX_TAIL)bytes=bytes.subarray(bytes.length-MAX_TAIL);
  const partial=stat.size>bytes.length,lines=bytes.toString('utf8').split('\n');if(partial)lines.shift();lines.pop();
  const rows=[];for(const line of lines){if(line.length>1024*1024)continue;try{rows.push(JSON.parse(line));}catch{}}
  const value={rows,partial};this.tails.delete(file);this.tails.set(file,{size:stat.size,mtime:stat.mtimeMs,bytes,value});while(this.tails.size>8)this.tails.delete(this.tails.keys().next().value);return value;
 }
 summary(file){
  if(!this.safe(file))throw Error('ROLLOUT_UNAVAILABLE');const stat=fs.statSync(file),old=this.summaries.get(file);
  if(old&&old.size===stat.size&&old.mtime===stat.mtimeMs)return old.value;
  const append=old&&stat.ino===old.ino&&stat.size>old.size&&stat.size-old.size<MAX_TAIL;
  const start=append?old.size:Math.max(0,stat.size-MAX_TAIL),add=Buffer.alloc(stat.size-start),fd=fs.openSync(file,'r');let read=0;
  try{while(read<add.length){const n=fs.readSync(fd,add,read,add.length-read,start+read);if(!n)break;read+=n;}}finally{fs.closeSync(fd);}
  let buffer=Buffer.concat([append?old.pending:Buffer.alloc(0),add.subarray(0,read)]),discard=append?old.discard:start>0;const rows=[];
  let end;while((end=buffer.indexOf(10))>=0){const line=buffer.subarray(0,end);buffer=buffer.subarray(end+1);if(discard){discard=false;continue;}if(line.length>1024*1024)continue;try{rows.push(JSON.parse(line.toString('utf8')));}catch{}}
  if(buffer.length>1024*1024){buffer=Buffer.alloc(0);discard=true;}else buffer=Buffer.from(buffer);
  const delta=project(rows),prior=append?old.value:null,value=prior?{...prior}:delta;
  if(prior){for(const key of ['model','effort','turn_id','last_reply','last_step'])if(delta[key])value[key]=delta[key];
   if(delta.status!=='unknown')value.status=delta.status;if(!value.first_user&&delta.first_user)value.first_user=delta.first_user;
   if(rows.some(r=>r.type==='event_msg'&&r.payload?.type==='token_count'&&Number.isSafeInteger(r.payload.info?.total_token_usage?.total_tokens)))value.tokens=delta.tokens;
   value.updated_at=Math.max(value.updated_at,delta.updated_at);
  }
  value.messages=[];value.activities=[];value.partial=append?old.value.partial:start>0;
  this.summaries.set(file,{size:stat.size,mtime:stat.mtimeMs,ino:stat.ino,pending:buffer,discard,value});return value;
 }
}
function textParts(p){const kinds=p.internal_chat_message_metadata_passthrough?.content_item_kinds;return (Array.isArray(p?.content)?p.content:[]).filter((v,i)=>['input_text','output_text','text'].includes(v?.type)&&typeof v.text==='string'&&(p.role!=='user'||!Array.isArray(kinds)||kinds[i]==='user.text')).map(v=>v.text).join('\n');}
function project(rows){
 const result={model:'',effort:'',turn_id:'',status:'unknown',last_reply:'',last_step:'',tokens:0,first_user:'',messages:[],activities:[],updated_at:0,truncated:false};const activities=new Map();
 for(const row of rows){const p=row?.payload||{};result.updated_at=Math.max(result.updated_at,time(row.timestamp));
  if(row.type==='turn_context'){if(typeof p.model==='string')result.model=clip(p.model,128);if(typeof p.effort==='string')result.effort=clip(p.effort,64);if(typeof p.turn_id==='string')result.turn_id=clip(p.turn_id,128);}
  if(row.type==='event_msg'){
   if(['task_started','task_complete','turn_aborted'].includes(p.type)){result.status=p.type==='task_started'?'running':p.type==='task_complete'?'completed':'interrupted';if(p.turn_id)result.turn_id=clip(p.turn_id,128);if(typeof p.last_agent_message==='string')result.last_reply=clip(p.last_agent_message,400);}
   if(p.type==='token_count'){const v=p.info?.total_token_usage?.total_tokens;if(Number.isSafeInteger(v)&&v>=0)result.tokens=v;}
   if(p.type==='exec_command_begin'){const cmd=Array.isArray(p.command)?p.command.join(' '):p.command;if(typeof cmd==='string'){
    result.last_step=clip('执行：'+cmd,200);const turn=clip(p.turn_id||result.turn_id,128),item=clip(p.call_id||p.item_id,128);
    if(turn&&item)activities.set(turn+':'+item,{id:turn+':'+item,turn_id:turn,type:'commandExecution',status:'inProgress',title:bytes(cmd,512),detail:'',files:[],truncated:Buffer.byteLength(cmd)>512});
   }}
   if(p.type==='exec_command_end'){const turn=clip(p.turn_id||result.turn_id,128),item=clip(p.call_id||p.item_id,128),a=activities.get(turn+':'+item);
    if(a){a.status=Number.isInteger(p.exit_code)&&p.exit_code!==0?'failed':'completed';if(Number.isInteger(p.exit_code)&&p.exit_code>=-2147483648&&p.exit_code<=2147483647)a.exit_code=p.exit_code;}}
   if(p.type==='patch_apply_begin'&&p.changes&&typeof p.changes==='object')result.last_step=clip('修改 '+Object.keys(p.changes).length+' 个文件',200);
  }
  if(row.type==='response_item'&&p.type==='message'&&['user','assistant'].includes(p.role)){
   let text=textParts(p);const typed=Array.isArray(p.internal_chat_message_metadata_passthrough?.content_item_kinds);if(p.role==='user'&&!typed&&/^\s*(?:# AGENTS\.md instructions\b|<environment_context>|<permissions instructions>)/.test(text))continue;
   if(p.role==='assistant'&&p.phase&&!['commentary','final','final_answer'].includes(p.phase))continue;
   if(text.trim()){if(p.role==='assistant')result.last_reply=clip(text,400);else if(!result.first_user)result.first_user=clip(text,120);const limited=bytes(text,16384);if(limited!==text)result.truncated=true;result.messages.push({role:p.role,text:limited});if(result.messages.length>60){result.messages.shift();result.truncated=true;}}
  }
 }
 result.activities=[...activities.values()].slice(-40);return result;
}
function snapshot(id,meta,tail){const p=project(tail.rows),value={conversation_id:crypto.createHash('sha256').update(id).digest('hex'),title:'',reply:clip(p.last_reply,400),messages:p.messages,activities:p.activities,truncated:tail.partial||p.truncated,running:p.status==='running',completed_at:new Date(p.updated_at||time(meta._timestamp)||0).toISOString()};
 while(Buffer.byteLength(JSON.stringify(value))>160000&&value.messages.length){value.messages.shift();value.truncated=true;}return value;}
module.exports={Rollouts,UUID,clip,bytes,time,parent,isSub,identity,project,snapshot};
