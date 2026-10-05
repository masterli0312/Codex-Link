'use strict';
// Only public question calls and explicit question replies are projected. Other
// tool arguments, outputs and reasoning never cross this boundary.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const UUID=/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
const OPEN='<send_user_message_question_reply>',CLOSE='</send_user_message_question_reply>';
function replies(text){
 if(typeof text!=='string'||Buffer.byteLength(text)>65536)return null;
 const s=text.trim();if(!s.startsWith(OPEN)||!s.endsWith(CLOSE))return null;
 try{
  const rows=JSON.parse(s.slice(OPEN.length,-CLOSE.length));
  if(!Array.isArray(rows)||rows.length<1||rows.length>6||!rows.every(r=>r&&typeof r.questionItemId==='string'&&Buffer.byteLength(r.questionItemId)<=128&&
   typeof r.question==='string'&&Buffer.byteLength(r.question)<=7200&&typeof r.answer==='string'&&r.answer.trim()&&Buffer.byteLength(r.answer)<=4096))return null;
  return rows;
 }catch{return null;}
}
function normalizeDesktopQuestion(call,turn){
 if(call?.type!=='function_call'||!['request_user_input_async','functions.request_user_input_async'].includes(call.name)||
  typeof call.call_id!=='string'||call.call_id.length>80||!call.call_id||typeof turn!=='string'||!turn||turn.length>128)return null;
 let a;try{a=typeof call.arguments==='string'?JSON.parse(call.arguments):call.arguments;}catch{return null;}
 if(!Array.isArray(a?.questions)||a.questions.length<1||a.questions.length>6)return null;
 const questions=[];
 for(const [index,q] of a.questions.entries()){
  if(typeof q?.title!=='string'||!q.title.trim()||Buffer.byteLength(q.title)>7200||q.options!=null&&(!Array.isArray(q.options)||q.options.length>8))return null;
  const options=[];for(const label of q.options||[]){
   if(typeof label!=='string'||!label.trim()||Buffer.byteLength(label)>160||options.some(o=>o.label===label))return null;
   options.push({label,description:''});
  }
  const id=JSON.stringify(['request_user_input_async',call.call_id,index]);if(Buffer.byteLength(id)>128)return null;
  questions.push({id,header:'',question:q.title,is_other:true,is_secret:false,options});
 }
 const h=crypto.createHash('sha256').update(turn+':'+call.call_id).digest('hex');
 const id=[h.slice(0,8),h.slice(8,12),h.slice(12,16),h.slice(16,20),h.slice(20,32)].join('-');
 return {id,turn_id:turn,is_blocking:false,questions};
}
function answerText(input,answers){
 return OPEN+'\n'+JSON.stringify(input.questions.map(q=>({questionItemId:q.id,question:q.question,answer:answers[q.id][0]})))+'\n'+CLOSE;
}
class DesktopQuestionReader{
 constructor(home,threadId){this.home=home;this.threadId=threadId;this.offset=0;this.buffer=Buffer.alloc(0);this.turn='';this.questions=new Map();this.resolved=new Set();}
 read(file,turn){
  if(!UUID.test(this.threadId)||typeof file!=='string'||!file.endsWith(this.threadId+'.jsonl'))return [];
  try{
   const root=fs.realpathSync(path.join(this.home,'sessions'))+path.sep,real=fs.realpathSync(file);
   if(!real.startsWith(root)||fs.lstatSync(file).isSymbolicLink())return [];
   const size=fs.statSync(file).size;
   if(this.file!==real||size<this.offset){this.file=real;this.offset=Math.max(0,size-4*1024*1024);this.buffer=Buffer.alloc(0);this.turn=turn;this.questions.clear();this.resolved.clear();this.skipFirst=this.offset>0;}
   if(size>this.offset){
    if(size-this.offset>4*1024*1024){this.offset=size-4*1024*1024;this.buffer=Buffer.alloc(0);this.skipFirst=true;}
    const bytes=Buffer.alloc(size-this.offset),fd=fs.openSync(real,'r');
    let nread;try{nread=fs.readSync(fd,bytes,0,bytes.length,this.offset);}finally{fs.closeSync(fd);}
    this.offset+=nread;this.buffer=Buffer.concat([this.buffer,bytes.subarray(0,nread)]);
    let n;while((n=this.buffer.indexOf(10))>=0){const line=this.buffer.slice(0,n);this.buffer=this.buffer.slice(n+1);
     if(this.skipFirst){this.skipFirst=false;continue;}
     if(line.length>1024*1024)continue;
     try{this.row(JSON.parse(line.toString('utf8')));}catch{}
    }
    if(this.buffer.length>1024*1024){this.buffer=Buffer.alloc(0);this.skipFirst=true;}
   }
   return [...this.questions.values()].map(entry=>entry.input);
  }catch{return [];}
 }
 row(row){
  const p=row.payload||{};
  if(row.type==='turn_context'&&typeof p.turn_id==='string')this.turn=p.turn_id;
  if(row.type==='event_msg'&&p.type==='task_started'&&typeof p.turn_id==='string')this.turn=p.turn_id;
  if(row.type!=='response_item')return;
  const input=normalizeDesktopQuestion(p,this.turn);
  if(input){
   const questions=input.questions.filter(q=>!this.resolved.has(q.id));
   if(questions.length)this.questions.set(input.id,{input:{...input,questions}});
   if(this.questions.size>32)this.questions.delete(this.questions.keys().next().value);
  }
  if(p.type==='message'&&p.role==='user'){
   const text=(Array.isArray(p.content)?p.content:[]).filter(c=>['input_text','text'].includes(c.type)&&typeof c.text==='string').map(c=>c.text).join('\n');
   for(const reply of replies(text)||[]){
    this.resolved.add(reply.questionItemId);
    for(const [id,entry] of this.questions){entry.input.questions=entry.input.questions.filter(q=>q.id!==reply.questionItemId);if(!entry.input.questions.length)this.questions.delete(id);}
   }
   if(this.resolved.size>256)this.resolved=new Set([...this.resolved].slice(-128));
  }
 }
}
module.exports={DesktopQuestionReader,normalizeDesktopQuestion,replies,answerText};
