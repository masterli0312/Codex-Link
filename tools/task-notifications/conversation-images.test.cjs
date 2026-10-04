'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),crypto=require('node:crypto');
const {projectImages,imageBytes,publishImage,aad,MAX}=require('../../app/src/main/assets/task-notifications/conversation-images.cjs');
const {snapshot}=require('../../app/src/main/assets/task-notifications/conversation-library.cjs');
const {LiveWatch}=require('../../app/src/main/assets/task-notifications/live-watch.cjs');
const {RemoteController,validateCommand}=require('../../app/src/main/assets/task-notifications/remote-core.cjs');
const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lWQAAAAASUVORK5CYII=','base64');
const data='data:image/png;base64,'+png.toString('base64'),host=crypto.randomUUID(),key=Buffer.alloc(32,5).toString('base64');
test('three simultaneous photo requests complete instead of failing with IMAGE_BUSY',async()=>{
 const f=fixture(),thread=crypto.randomUUID(),events=[];
 const refs=[0,1,2].map(n=>projectImages(thread,{type:'userMessage',content:[{type:'image',url:'data:image/png;base64,'+Buffer.concat([png,Buffer.from([n])]).toString('base64')}]}).at(0));
 let uploading=0,maximum=0;
 const controller=new RemoteController({directory:f.dir,key,hostId:host,endpoint:'https://relay.invalid/pair',emit:async e=>events.push(e),
  imageFetch:async()=>{uploading++;maximum=Math.max(maximum,uploading);await new Promise(r=>setTimeout(r,10));uploading--;return {ok:true,body:[Buffer.from('{"attachment":{"url":"https://relay.invalid/file/image.bin"}}')]};}});
 try{
  await Promise.all(refs.map(ref=>controller.handle({id:crypto.randomUUID(),host_id:host,thread_id:thread,conversation_id:crypto.createHash('sha256').update(thread).digest('hex'),issued_at:Date.now(),action:'image',image_id:ref.id})));
  assert.equal(events.length,3);assert.ok(events.every(e=>e.image&&!e.error));assert.equal(maximum,3);assert.equal(controller.activeImages,0);
 }finally{controller.close();f.close();}
});
test('repeated photo requests reuse an encrypted upload, while changed bytes and failed uploads do not',async()=>{
 const f=fixture(),thread=crypto.randomUUID(),file=path.join(f.dir,'photo.png');fs.writeFileSync(file,png);
 const ref=projectImages(thread,{type:'userMessage',content:[{type:'localImage',path:file}]}).at(0);
 const controller={key,endpoint:'https://relay.invalid/pair'},command={host_id:host,thread_id:thread,image_id:ref.id};let calls=0;
 const upload=async()=>{calls++;await new Promise(r=>setTimeout(r,5));return {ok:true,body:[Buffer.from('{"attachment":{"url":"https://relay.invalid/file/image.bin"}}')]};};
 try{
  const [a,b]=await Promise.all([publishImage(controller,command,upload),publishImage(controller,command,upload)]);
  assert.deepEqual(a,b);await publishImage(controller,command,upload);assert.equal(calls,1);
  fs.writeFileSync(file,Buffer.concat([png,Buffer.from([1])]));await publishImage(controller,command,upload);assert.equal(calls,2);
  fs.writeFileSync(file,Buffer.concat([png,Buffer.from([2])]));
  await assert.rejects(publishImage(controller,command,async()=>{throw Error('network');}),/IMAGE_TRANSFER/);
  await publishImage(controller,command,upload);assert.equal(calls,3);
 }finally{f.close();}
});
function fixture(){const dir=fs.mkdtempSync(path.join(os.tmpdir(),'codex-image-'));return {dir,close(){assert.equal(path.dirname(dir),path.resolve(os.tmpdir()));assert.ok(path.basename(dir).startsWith('codex-image-'));fs.rmSync(dir,{recursive:true,force:true});}};}
test('pure image messages survive native history and live projection without leaking paths or bytes',()=>{
 const thread=crypto.randomUUID(),item={id:'photo',type:'userMessage',content:[{type:'image',url:data}]};
 const initial=snapshot({id:thread,turns:[{id:'turn',status:'completed',items:[item]}]},host);
 assert.equal(initial.messages.length,1);assert.equal(initial.messages[0].text,'');assert.equal(initial.messages[0].images.length,1);
 assert.equal(JSON.stringify(initial).includes('base64'),false);
 const live=new LiveWatch(thread,host,snapshot({id:thread,turns:[]},host));
 live.apply('turn/started',{threadId:thread,turn:{id:'turn'}});
 live.apply('item/started',{threadId:thread,turnId:'turn',item});
 assert.deepEqual(live.value.messages[0].images,initial.messages[0].images);
});
test('native generated images and view-image items retain bounded image references',()=>{
 const thread=crypto.randomUUID(),messages=snapshot({id:thread,turns:[{id:'t',items:[
  {id:'g',type:'imageGeneration',status:'completed',result:png.toString('base64')},
  {id:'v',type:'imageView',path:'/workspace/fixture.png'},
  {id:'pending',type:'imageGeneration',status:'inProgress',result:''}]}]},host).messages;
 assert.equal(messages.length,2);assert.ok(messages.every(m=>m.role==='assistant'&&m.images.length===1));
 assert.ok(!JSON.stringify(messages).includes('/workspace'));
});
test('image source resolution is thread-bound, bounded and never executes/fetches URLs',async()=>{
 const thread=crypto.randomUUID(),ref=projectImages(thread,{type:'userMessage',content:[{type:'image',url:data}]}).at(0);
 assert.deepEqual((await imageBytes(thread,ref.id)).bytes,png);
 await assert.rejects(imageBytes(crypto.randomUUID(),ref.id),/IMAGE_UNAVAILABLE/);
 await assert.rejects(imageBytes(thread,'0'.repeat(64)),/IMAGE_UNAVAILABLE/);
 for(const url of ['https://127.0.0.1/private','https://public.invalid/photo.png','data:image/svg+xml;base64,PHN2Zz4=']){
  const i=projectImages(thread,{type:'userMessage',content:[{type:'image',url}]}).at(0);
  await assert.rejects(imageBytes(thread,i.id),/IMAGE_UNAVAILABLE/);
 }
 const f=fixture();try{
  const file=path.join(f.dir,'fixture.png');fs.writeFileSync(file,png);
  const local=projectImages(thread,{type:'userMessage',content:[{type:'localImage',path:file}]}).at(0);
  assert.deepEqual((await imageBytes(thread,local.id)).bytes,png);
  const generated=projectImages(thread,{type:'imageGeneration',status:'completed',savedPath:file},path.join(f.dir,'other-workspace')).at(0);
  assert.deepEqual((await imageBytes(thread,generated.id)).bytes,png);
  const big=path.join(f.dir,'large.png');fs.writeFileSync(big,Buffer.alloc(MAX+1));
  const b=projectImages(thread,{type:'userMessage',content:[{type:'localImage',path:big}]}).at(0);
  await assert.rejects(imageBytes(thread,b.id),/IMAGE_TOO_LARGE/);
 }finally{f.close();}
});
test('assistant Markdown images cannot read outside their workspace',async()=>{
 const f=fixture(),thread=crypto.randomUUID();try{
  const inside=path.join(f.dir,'workspace');fs.mkdirSync(inside);fs.writeFileSync(path.join(inside,'yes.png'),png);fs.writeFileSync(path.join(f.dir,'private.png'),png);
  const allowed=projectImages(thread,{type:'agentMessage',text:'![ok](yes.png)'},inside).at(0);
  assert.deepEqual((await imageBytes(thread,allowed.id)).bytes,png);
  const denied=projectImages(thread,{type:'agentMessage',text:'![no](../private.png)'},inside).at(0);
  await assert.rejects(imageBytes(thread,denied.id),/IMAGE_UNAVAILABLE/);
 }finally{f.close();}
});
test('image downlink is independently encrypted and bound to host, thread and image',async()=>{
 const thread=crypto.randomUUID(),ref=projectImages(thread,{type:'userMessage',content:[{type:'image',url:data}]}).at(0);let uploaded;
 const fetchImpl=async(url,options)=>{uploaded=options.body;assert.ok(!uploaded.equals(png));assert.equal(options.redirect,'error');return {ok:true,body:[Buffer.from(JSON.stringify({attachment:{url:'https://relay.invalid/file/image.bin'}}))]};};
 const info=await publishImage({key,endpoint:'https://relay.invalid/pair'},{host_id:host,thread_id:thread,image_id:ref.id},fetchImpl);
 assert.equal(info.sha256,crypto.createHash('sha256').update(png).digest('hex'));assert.equal(info.size,png.length);
 const c=crypto.createDecipheriv('aes-256-gcm',Buffer.from(key,'base64'),uploaded.subarray(0,12));c.setAAD(Buffer.from(aad(host,thread,ref.id)));c.setAuthTag(uploaded.subarray(-16));
 assert.deepEqual(Buffer.concat([c.update(uploaded.subarray(12,-16)),c.final()]),png);
 await assert.rejects(publishImage({key,endpoint:'https://relay.invalid/pair'},{host_id:host,thread_id:thread,image_id:ref.id},async()=>({ok:true,body:[Buffer.from('{"attachment":{"url":"https://evil.invalid/file/x"}}')]})),/IMAGE_TRANSFER/);
});
test('image request is read-only, validates closed IDs and never starts or replaces a conversation',async()=>{
 const f=fixture(),thread=crypto.randomUUID(),events=[];
 const ref=projectImages(thread,{type:'userMessage',content:[{type:'image',url:data}]}).at(0);
 const command={id:crypto.randomUUID(),host_id:host,thread_id:thread,conversation_id:crypto.createHash('sha256').update(thread).digest('hex'),issued_at:Date.now(),action:'image',image_id:ref.id};
 assert.equal(validateCommand(command,host),true);assert.equal(validateCommand({...command,image_id:'../secret'},host),false);
 assert.equal(validateCommand({...command,host_id:crypto.randomUUID()},host),false);
 const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>{throw Error('No native generation or resume allowed');},endpoint:'https://relay.invalid/pair',emit:async e=>events.push(e),
  imageFetch:async()=>({ok:true,body:[Buffer.from('{"attachment":{"url":"https://relay.invalid/file/image.bin"}}')]})});
 try{await controller.handle(command);assert.equal(events.at(-1).status,'image');assert.equal(events.at(-1).image.image_id,ref.id);assert.equal(controller.active,null);assert.equal(controller.watching,undefined);}
 finally{controller.close();f.close();}
});
test('notification rollout fallback retains pure user images but excludes injected image content and later turns',()=>{
 const f=fixture(),thread=crypto.randomUUID();try{
  fs.mkdirSync(path.join(f.dir,'sessions'));
  const row=(type,payload)=>JSON.stringify({type,payload})+'\n';
  const image=kind=>row('response_item',{type:'message',role:'user',content:[{type:'input_image',image_url:data}],internal_chat_message_metadata_passthrough:{content_item_kinds:[kind]}});
  fs.writeFileSync(path.join(f.dir,'sessions','rollout-'+thread+'.jsonl'),row('session_meta',{id:thread,cwd:f.dir})+
   image('environment.image')+image('user.image')+row('event_msg',{type:'task_complete',turn_id:'complete'})+image('user.image'));
  const value=require('../../app/src/main/assets/task-notifications/task-content.cjs').readSnapshot({codexHome:f.dir},thread,'complete');
  assert.equal(value.messages.length,1);assert.equal(value.messages[0].images.length,1);assert.equal(value.messages[0].text,'');
 }finally{f.close();}
});
test('computer component export and installer both include the image module',()=>{
 const base=path.resolve(__dirname,'../../app/src/main');
 assert.match(fs.readFileSync(path.join(base,'java/com/codex/quota/ui/feature/settings/TaskNotificationSettingsPanel.kt'),'utf8'),/"conversation-images\.cjs"/);
 assert.match(fs.readFileSync(path.join(base,'assets/task-notifications/Install.ps1'),'utf8'),/'conversation-images\.cjs'/);
});
test('after a bridge restart, image lookup reprojects bounded native history without starting a turn',async()=>{
 const f=fixture(),thread=crypto.randomUUID(),calls=[],events=[];
 const id=crypto.createHash('sha256').update(thread+':'+data+':false').digest('hex');
 const client={setHandlers(){},async connect(){},close(){},reject(){},async call(method){calls.push(method);
  if(method==='thread/read')return {thread:{id:thread,cwd:f.dir}};
  assert.equal(method,'thread/turns/list');return {data:[{id:'turn',status:'completed',items:[{id:'image',type:'userMessage',content:[{type:'image',url:data}]}]}]};
 }};
 const controller=new RemoteController({directory:f.dir,key,hostId:host,clientFactory:()=>client,endpoint:'https://relay.invalid/pair',emit:async e=>events.push(e),
  imageFetch:async()=>({ok:true,body:[Buffer.from('{"attachment":{"url":"https://relay.invalid/file/image.bin"}}')]})});
 try{
  await controller.handle({id:crypto.randomUUID(),host_id:host,thread_id:thread,conversation_id:crypto.createHash('sha256').update(thread).digest('hex'),issued_at:Date.now(),action:'image',image_id:id});
  assert.deepEqual(calls,['thread/read','thread/turns/list']);assert.equal(events.at(-1).image.image_id,id);
 }finally{controller.close();f.close();}
});

test('the Cloud attachment manifest resolves real image references without publishing temporary paths',async()=>{
 const f=fixture(),thread=crypto.randomUUID();try{
  const dir=path.join(f.dir,'codex-remote-attachments','session');fs.mkdirSync(dir,{recursive:true});
  const file=path.join(dir,'1-photo.png');fs.writeFileSync(file,png);
  const item={id:'u',type:'userMessage',content:[{type:'text',text:'# Files mentioned by the user:\n\n## photo.png: '+file+'\n\n## My request for Codex:\nFix the image display'}]};
  const references=projectImages(thread,item);assert.equal(references.length,1);
  assert.deepEqual((await imageBytes(thread,references[0].id)).bytes,png);
  assert.equal(JSON.stringify(references).includes(f.dir),false);
  assert.equal(projectImages(thread,{...item,content:[{type:'text',text:'Example: '+item.content[0].text}]}).length,0);
 }finally{f.close();}
});
