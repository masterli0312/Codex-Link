'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),crypto=require('node:crypto');
const files=require('../../app/src/main/assets/task-notifications/conversation-files.cjs');
const {snapshot}=require('../../app/src/main/assets/task-notifications/conversation-library.cjs');
const {RemoteController,validateCommand}=require('../../app/src/main/assets/task-notifications/remote-core.cjs');
const host=crypto.randomUUID(),thread=crypto.randomUUID(),key=Buffer.alloc(32,6).toString('base64');
function fixture(){const dir=fs.mkdtempSync(path.join(os.tmpdir(),'codex-file-'));return {dir,close(){assert.equal(path.dirname(dir),path.resolve(os.tmpdir()));assert.ok(path.basename(dir).startsWith('codex-file-'));fs.rmSync(dir,{recursive:true,force:true});}};}
test('only native assistant workspace file links become bounded opaque download references',async()=>{
 const f=fixture();try{
  fs.writeFileSync(path.join(f.dir,'Report.pdf'),'fixture');
  const item={type:'agentMessage',text:'[Report](Report.pdf) [outside](../outside.txt) [auth](.codex/auth.json) [env](.env) [web](https://example.com/a.pdf)'};
  const refs=files.projectFiles(thread,item,f.dir);assert.equal(refs.length,1);assert.equal(refs[0].name,'Report.pdf');assert.match(refs[0].id,/^[a-f0-9]{64}$/);
  assert.deepEqual(files.projectFiles(thread,{type:'userMessage',text:item.text},f.dir),[]);
  const read=await files.fileBytes(thread,refs[0].id);assert.equal(read.bytes.toString(),'fixture');
  await assert.rejects(files.fileBytes(crypto.randomUUID(),refs[0].id),/FILE_UNAVAILABLE/);
  const s=snapshot({id:thread,cwd:f.dir,turns:[{id:'turn',status:'completed',items:[{...item,id:'answer'}]}]},host);
  assert.deepEqual(s.messages[0].files,refs);
 }finally{f.close();}
});
test('export rejects directories, oversize files and symlinks escaping the real workspace',async()=>{
 const f=fixture();try{
  const ref=files.projectFiles(thread,{type:'agentMessage',text:'[large](large.bin) [dir](folder)'},f.dir);
  const handle=fs.openSync(path.join(f.dir,'large.bin'),'w');fs.ftruncateSync(handle,files.MAX+1);fs.closeSync(handle);
  fs.mkdirSync(path.join(f.dir,'folder'));
  for(const r of ref)await assert.rejects(files.fileBytes(thread,r.id),/FILE_TOO_LARGE/);
  const outside=path.join(f.dir,'outside'),workspace=path.join(f.dir,'workspace');fs.mkdirSync(outside);fs.mkdirSync(workspace);
  fs.writeFileSync(path.join(outside,'secret.txt'),'private');
  fs.symlinkSync(outside,path.join(workspace,'link'),process.platform==='win32'?'junction':'dir');
  const escape=files.projectFiles(thread,{type:'agentMessage',text:'[escape](link/secret.txt)'},workspace)[0];
  await assert.rejects(files.fileBytes(thread,escape.id),/FILE_UNAVAILABLE/);
 }finally{f.close();}
});
test('file transport preserves exact bytes and is authenticated to this host/thread/file',async()=>{
 const f=fixture();let controller;try{
  fs.writeFileSync(path.join(f.dir,'report.txt'),'exact exported content');
  const ref=files.projectFiles(thread,{type:'agentMessage',text:'[report](report.txt)'},f.dir)[0],events=[];let encrypted;
  controller=new RemoteController({directory:f.dir,key,hostId:host,endpoint:'https://relay.invalid/pair',emit:async e=>events.push(e),imageFetch:async(_,options)=>{
    const chunks=[];for await(const chunk of options.body)chunks.push(chunk);encrypted=Buffer.concat(chunks);return {ok:true,body:[Buffer.from('{"attachment":{"url":"https://relay.invalid/file/report.bin"}}')]};
  }});
  const command={id:crypto.randomUUID(),action:'file',host_id:host,thread_id:thread,conversation_id:crypto.createHash('sha256').update(thread).digest('hex'),issued_at:Date.now(),file_id:ref.id};
  assert.equal(validateCommand(command,host),true);assert.equal(validateCommand({...command,file_id:'../../secret'},host),false);
  await controller.handle(command);assert.equal(events.length,1);assert.equal(events[0].file.name,'report.txt');assert.equal(events[0].error,undefined);
  const decrypt=(h,t)=>{const decipher=crypto.createDecipheriv('aes-256-ctr',files.streamKey(key,h,t,ref.id),encrypted.subarray(0,16));return Buffer.concat([decipher.update(encrypted.subarray(16)),decipher.final()]);};
  const plain=decrypt(host,thread);assert.equal(plain.toString(),'exact exported content');
  assert.equal(events[0].file.format,'stream-v1');assert.equal(crypto.createHash('sha256').update(plain).digest('hex'),events[0].file.sha256);
  assert.notEqual(crypto.createHash('sha256').update(decrypt(host,crypto.randomUUID())).digest('hex'),events[0].file.sha256);
 }finally{controller?.close();f.close();}
});
test('large artifacts stream in bounded chunks, reuse uploads and invalidate changed files',async()=>{
 const f=fixture();try{
  const file=path.join(f.dir,'large.apk'),handle=fs.openSync(file,'w');fs.ftruncateSync(handle,40*1024*1024);fs.closeSync(handle);
  const ref=files.projectFiles(thread,{type:'agentMessage',text:'[APK](large.apk)'},f.dir)[0];
  const controller={key,endpoint:'https://relay.invalid/pair'},command={host_id:host,thread_id:thread,file_id:ref.id};let calls=0;
  const fetch=async(_,options)=>{calls++;let bytes=0;for await(const chunk of options.body){assert.ok(chunk.length<=65536);bytes+=chunk.length;}
   assert.equal(bytes,40*1024*1024+16);assert.equal(String(bytes),options.headers['Content-Length']);
   return {ok:true,body:[Buffer.from('{"attachment":{"url":"https://relay.invalid/file/large.bin"}}')]};};
  const info=await files.publishFile(controller,command,fetch);assert.equal(info.size,40*1024*1024);assert.equal(info.format,'stream-v1');
  await files.publishFile(controller,command,fetch);assert.equal(calls,1);
  const now=new Date(Date.now()+5000);fs.utimesSync(file,now,now);await files.publishFile(controller,command,fetch);assert.equal(calls,2);
 }finally{f.close();}
});
