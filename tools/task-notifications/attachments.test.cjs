'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),crypto=require('node:crypto'),fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {validAttachments,prepareAttachments,attachmentAad}=require('../../app/src/main/assets/task-notifications/remote-attachments.cjs');
const host=crypto.randomUUID(),thread=crypto.randomUUID(),key=Buffer.alloc(32,3).toString('base64');
function attachment(data=Buffer.from('Selected fixture data'),extra={}){return {id:crypto.randomUUID(),name:'note.txt',mime:'text/plain',url:'https://ntfy.sh/file/fixture.bin',size:data.length,sha256:crypto.createHash('sha256').update(data).digest('hex'),...extra};}
function seal(data,a,otherThread=thread){const iv=crypto.randomBytes(12),c=crypto.createCipheriv('aes-256-gcm',Buffer.from(key,'base64'),iv);c.setAAD(Buffer.from(attachmentAad(host,otherThread,a.id)));return Buffer.concat([iv,c.update(data),c.final(),c.getAuthTag()]);}
test('attachments reject paths, executable types, oversized values and duplicate identities',()=>{
 assert.equal(validAttachments([attachment()]),true);
 for(const extra of [{name:'../note.txt'},{name:'note.exe'},{name:'note.pdf'},{name:'note.docx'},{name:'note.xlsx'},{name:'note.pptx'},{size:8*1024*1024+1},{sha256:'invalid'},{name:'note.txt\0'}])assert.equal(validAttachments([attachment(undefined,extra)]),false);
 const a=attachment();assert.equal(validAttachments([a,a]),false);
});
test('malformed attachment collections are rejected without throwing out of the receive loop',()=>{
 for(const values of [[null],[42],[[]],[{}],[attachment(),null],false,'wrong']){
  assert.doesNotThrow(()=>assert.equal(validAttachments(values),false));
 }
 assert.equal(validAttachments([attachment(undefined,{id:[crypto.randomUUID()]})]),false);
});
test('malformed authenticated thread identities are rejected without throwing before dispatch',()=>{
 const {validateCommand}=require('../../app/src/main/assets/task-notifications/remote-core.cjs');
 const valid={id:crypto.randomUUID(),action:'presence',host_id:host,thread_id:thread,
  conversation_id:crypto.createHash('sha256').update(thread).digest('hex'),issued_at:Date.now()};
 for(const malformed of [{...valid,thread_id:[thread]},{...valid,thread_id:null},{...valid,attachments:[null]},
  {...valid,action:'read',read_thread_id:[thread]},{...valid,id:[valid.id]}]){
  assert.doesNotThrow(()=>assert.equal(validateCommand(malformed,host),false));
 }
 assert.equal(validateCommand(valid,host),true);
});
test('only authenticated user-selected bytes from the paired relay become local inputs',async()=>{
 const directory=fs.mkdtempSync(path.join(os.tmpdir(),'codex-usage-attachment-'));
 const data=Buffer.from('SELECTED_FIXTURE'),a=attachment(data),controller={directory,key,endpoint:'https://ntfy.sh/paired'};
 const fetchImpl=async()=>({ok:true,headers:new Map(),body:[seal(data,a)]});
 try{
  const input=await prepareAttachments(controller,{host_id:host,thread_id:thread,attachments:[a]},fetchImpl);
  assert.equal(input[0].type,'text');assert.equal(input[0].text.includes('SELECTED_FIXTURE'),false);
  const staged=path.join(directory,'attachments',thread,a.id+'.txt');assert.equal(fs.readFileSync(staged,'utf8'),'SELECTED_FIXTURE');
  let contacted=false;
  await assert.rejects(prepareAttachments(controller,{host_id:host,thread_id:thread,attachments:[{...a,url:'https://other.invalid/file/fixture.bin'}]},async()=>{contacted=true;}),/ATTACHMENT_INVALID/);
  assert.equal(contacted,false);
  await assert.rejects(prepareAttachments(controller,{host_id:host,thread_id:thread,attachments:[a]},async()=>({ok:true,headers:new Map(),body:[seal(data,a,crypto.randomUUID())]})),/ATTACHMENT_INVALID/);
  await assert.rejects(prepareAttachments(controller,{host_id:host,thread_id:thread,attachments:[{...a,sha256:'0'.repeat(64)}]},fetchImpl),/ATTACHMENT_INVALID/);
 }finally{assert.equal(path.dirname(directory),path.resolve(os.tmpdir()));assert.ok(path.basename(directory).startsWith('codex-usage-attachment-'));fs.rmSync(directory,{recursive:true,force:true});}
});
