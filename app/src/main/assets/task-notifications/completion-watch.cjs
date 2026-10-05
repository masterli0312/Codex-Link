'use strict';
// Fast desktop activity/completion path; the existing scheduled scan remains recovery.
const fs=require('node:fs'),path=require('node:path');
const UUID=/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
class ActivityReader{
 constructor(root,file,startedAt){this.root=fs.realpathSync(root)+path.sep;this.file=file;this.startedAt=startedAt;this.offset=0;this.buffer=Buffer.alloc(0);this.seen=new Set();}
 read(){
  const real=fs.realpathSync(this.file),id=path.basename(real).match(/([a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12})\.jsonl$/i)?.[1];
  if(!real.startsWith(this.root)||!UUID.test(id||'')||fs.lstatSync(this.file).isSymbolicLink())return [];
  const fd=fs.openSync(real,'r'),rows=[];
  try{
   const size=fs.fstatSync(fd).size;
   if(!this.verified){
    const first=Buffer.alloc(Math.min(size,65536));fs.readSync(fd,first,0,first.length,0);
    const meta=first.toString('utf8').split('\n').map(line=>{try{return JSON.parse(line);}catch{return null;}}).find(r=>r?.type==='session_meta')?.payload;
    if(meta?.id!==id||meta.source?.subagent)return [];
    this.verified=true;this.offset=Math.max(0,size-1024*1024);this.skipFirst=this.offset>0;
   }
   if(size<this.offset){this.offset=0;this.buffer=Buffer.alloc(0);this.skipFirst=false;}
   if(size-this.offset>2*1024*1024){this.offset=size-2*1024*1024;this.buffer=Buffer.alloc(0);this.skipFirst=true;}
   while(this.offset<size){
    const bytes=Buffer.alloc(Math.min(65536,size-this.offset)),n=fs.readSync(fd,bytes,0,bytes.length,this.offset);if(!n)break;
    this.offset+=n;this.buffer=Buffer.concat([this.buffer,bytes.subarray(0,n)]);
    let newline;while((newline=this.buffer.indexOf(10))>=0){
     const line=this.buffer.subarray(0,newline);this.buffer=this.buffer.subarray(newline+1);
     if(this.skipFirst){this.skipFirst=false;continue;}
     if(line.length>1024*1024)continue;
     let r;try{r=JSON.parse(line.toString('utf8'));}catch{continue;}
     const p=r.payload,at=Date.parse(r.timestamp);
     if(r.type!=='event_msg'||!['task_started','task_complete','turn_aborted'].includes(p?.type)||typeof p.turn_id!=='string'||!p.turn_id||p.turn_id.length>128||!Number.isFinite(at))continue;
     const key=p.turn_id+':'+p.type;if(this.seen.has(key))continue;this.seen.add(key);
     rows.push({thread:id,turn:p.turn_id,running:p.type==='task_started',at,completed:p.type==='task_complete',complete:p.type==='task_complete'&&at>=this.startedAt,
      end:this.offset-this.buffer.length,payload:{type:'agent-turn-complete','thread-id':id,'turn-id':p.turn_id,'last-assistant-message':p.last_agent_message}});
    }
    if(this.buffer.length>1024*1024){this.buffer=Buffer.alloc(0);this.skipFirst=true;}
   }
   if(this.seen.size>256)this.seen=new Set([...this.seen].slice(-128));
   return rows;
  }finally{fs.closeSync(fd);}
 }
}
function watchCompletions(home,{onActivity=()=>{},onCompletion=()=>{}}={}){
 const root=path.join(home,'sessions'),startedAt=Date.now(),readers=new Map(),timers=new Map();let closed=false;
 if(!fs.existsSync(root))return {close(){}};
 const watcher=fs.watch(root,{recursive:true},(_,relative)=>{
  if(closed||typeof relative!=='string'||!relative.endsWith('.jsonl'))return;
  const file=path.resolve(root,relative),inside=path.relative(path.resolve(root),file);
  if(inside==='..'||inside.startsWith('..'+path.sep)||path.isAbsolute(inside))return;
  if(timers.has(file))return;
  timers.set(file,setTimeout(async()=>{
   timers.delete(file);if(closed)return;
   let reader=readers.get(file);if(!reader){reader=new ActivityReader(root,file,startedAt);readers.set(file,reader);}
   while(readers.size>64)readers.delete(readers.keys().next().value);
   try{for(const row of reader.read()){
    await onActivity(row);
    if(row.complete)await onCompletion(row.payload,file,row.end);
   }}catch{} // The durable scheduled scan recovers dropped filesystem events.
  },80));
 });
 watcher.on('error',()=>{});
 return {close(){closed=true;watcher.close();for(const timer of timers.values())clearTimeout(timer);timers.clear();readers.clear();}};
}
module.exports={ActivityReader,watchCompletions};
