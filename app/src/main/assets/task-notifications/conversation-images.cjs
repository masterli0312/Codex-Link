'use strict';
// Only sources found in real native messages are addressable. The phone never sends paths/URLs.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const sources=new Map(),MAX=8*1024*1024;let retained=0;
const uploads=new WeakMap();
const hash=s=>crypto.createHash('sha256').update(s).digest('hex');
function register(thread,source,workspaceOnly=false,cwd=''){
 if(typeof source!=='string'||!source||source.length>MAX*4/3+256)return null;
 const id=hash(thread+':'+source+':'+workspaceOnly);
 const previous=sources.get(id);if(previous){cwd=cwd||previous.cwd;retained-=Buffer.byteLength(previous.source);sources.delete(id);}
 const size=Buffer.byteLength(source);
 while(sources.size&&(sources.size>=256||retained+size>32*1024*1024)){
  const oldest=sources.keys().next().value;retained-=Buffer.byteLength(sources.get(oldest).source);sources.delete(oldest);
 }
 sources.set(id,{thread,source,workspaceOnly,cwd});retained+=size;return {id};
}
function projectImages(thread,item,cwd=''){
 const images=[];
 if(item.type==='userMessage')for(const c of item.content||[]){
  if(!['localImage','image'].includes(c?.type))continue;
  const value=register(thread,c.type==='localImage'?c.path:c.url||(c.fileId?'official-file:'+c.fileId:''));
  if(value)images.push(value);if(images.length===10)break;
 }
 if(item.type==='userMessage'&&images.length<10)for(const c of item.content||[]){
  if(c?.type!=='text'||typeof c.text!=='string'||!c.text.trimStart().startsWith('# Files mentioned by the user:'))continue;
  const wrapper=c.text.split(/^## My request(?: for Codex)?:/m)[0];
  for(const match of wrapper.matchAll(/^## [^\r\n:]+:\s+([^\r\n]+)$/gm)){
   const source=match[1].trim();
   if(!/[/\\]codex-remote-attachments[/\\]/.test(source)||!(/\.(?:png|jpe?g|webp|gif)$/i.test(source)))continue;
   const value=register(thread,source);if(value)images.push(value);if(images.length===10)break;
  }
 }
 if(item.type==='imageGeneration'&&item.status==='completed'){
  const source=item.savedPath||(typeof item.result==='string'&&item.result?
   (item.result.startsWith('data:')?item.result:'data:image/png;base64,'+item.result):'');
  const value=register(thread,source,false,cwd);if(value)images.push(value);
 }
 if(item.type==='imageView'){
  const value=register(thread,item.path,false,cwd);if(value)images.push(value);
 }
 if(item.type==='agentMessage'&&typeof item.text==='string'){
  // Markdown file links are restricted to the thread workspace (never arbitrary assistant paths).
  for(const match of item.text.matchAll(/!\[[^\]\n]*\]\((?:<([^>]+)>|([^\s)]+))(?:\s+"[^"\n]*")?\)/g)){
   const value=register(thread,match[1]||match[2],true,cwd);
   if(value)images.push(value);if(images.length===10)break;
  }
 }
 return [...new Map(images.map(i=>[i.id,i])).values()].slice(0,10);
}
function mime(bytes){
 if(bytes.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])))return 'image/png';
 if(bytes[0]===255&&bytes[1]===216&&bytes[2]===255)return 'image/jpeg';
 if(bytes.toString('ascii',0,4)==='RIFF'&&bytes.toString('ascii',8,12)==='WEBP')return 'image/webp';
 if(['GIF87a','GIF89a'].includes(bytes.toString('ascii',0,6)))return 'image/gif';
 throw Error('IMAGE_UNSUPPORTED');
}
async function imageBytes(thread,id){
 const entry=sources.get(id);if(!entry||entry.thread!==thread)throw Error('IMAGE_UNAVAILABLE');
 let bytes;
 if(/^data:image\/(?:png|jpeg|webp|gif);base64,/.test(entry.source)){
  const data=entry.source.slice(entry.source.indexOf(',')+1);
  if(data.length>Math.ceil(MAX/3)*4||!/^[A-Za-z0-9+/]*={0,2}$/.test(data))throw Error('IMAGE_TOO_LARGE');
  bytes=Buffer.from(data,'base64');
 }else{
  let file=entry.source;
  if(file.startsWith('file:'))try{file=require('node:url').fileURLToPath(file);}catch{throw Error('IMAGE_UNAVAILABLE');}
  // No network fetching, private endpoints or SVG execution from Markdown.
  if(/^[a-z][a-z0-9+.-]*:/i.test(file)&&!path.isAbsolute(file))throw Error('IMAGE_UNAVAILABLE');
  if(!path.isAbsolute(file)){
   if(!entry.workspaceOnly||!path.isAbsolute(entry.cwd))throw Error('IMAGE_UNAVAILABLE');
   file=path.resolve(entry.cwd,file);
  }
  const real=await fs.promises.realpath(file);
  if(entry.workspaceOnly){
   if(!path.isAbsolute(entry.cwd))throw Error('IMAGE_UNAVAILABLE');
   const root=await fs.promises.realpath(entry.cwd),relative=path.relative(root,real);
   if(!relative||relative.startsWith('..'+path.sep)||relative==='..'||path.isAbsolute(relative))throw Error('IMAGE_UNAVAILABLE');
  }
  // File replacement cannot turn a checked image into an unbounded read.
  const handle=await fs.promises.open(real,'r');try{
   const stat=await handle.stat();if(!stat.isFile()||stat.size<1||stat.size>MAX)throw Error('IMAGE_TOO_LARGE');
   bytes=Buffer.alloc(stat.size);let offset=0;
   while(offset<bytes.length){const r=await handle.read(bytes,offset,bytes.length-offset,offset);if(!r.bytesRead)break;offset+=r.bytesRead;}
   if(offset!==bytes.length)throw Error('IMAGE_UNAVAILABLE');
  }finally{await handle.close();}
 }
 if(bytes.length<1||bytes.length>MAX)throw Error('IMAGE_TOO_LARGE');
 return {bytes,mime:mime(bytes)};
}
function aad(host,thread,id){return 'CodexUsage:image:'+host+':'+thread+':'+id;}
async function publishImage(controller,c,fetchImpl=fetch){
 const value=await imageBytes(c.thread_id,c.image_id),digest=hash(value.bytes);
 let cache=uploads.get(controller);if(!cache){cache=new Map();uploads.set(controller,cache);}
 const identity=hash(controller.endpoint+':'+controller.key)+':'+c.host_id+':'+c.thread_id+':'+c.image_id+':'+digest,now=Date.now();
 for(const [id,entry]of cache)if(entry.until<=now&&!entry.pending)cache.delete(id);
 const previous=cache.get(identity);if(previous)return previous.pending||previous.info;
 while(cache.size>=128){const idle=[...cache].find(([,entry])=>!entry.pending);if(!idle)break;cache.delete(idle[0]);}
 const entry={pending:null,until:now+60000};
 entry.pending=(async()=>{
 const iv=crypto.randomBytes(12);
 const cipher=crypto.createCipheriv('aes-256-gcm',Buffer.from(controller.key,'base64'),iv);
 cipher.setAAD(Buffer.from(aad(c.host_id,c.thread_id,c.image_id)));
 const encrypted=Buffer.concat([iv,cipher.update(value.bytes),cipher.final(),cipher.getAuthTag()]);
 const topic=require('./remote-core.cjs').eventTopic(controller.endpoint,controller.key,c.host_id);
 const res=await fetchImpl(topic,{method:'POST',headers:{Filename:'codex-image.bin',Message:'Encrypted conversation image','Content-Type':'application/octet-stream'},body:encrypted,redirect:'error',signal:AbortSignal.timeout(30000)});
 if(!res.ok)throw Error('IMAGE_TRANSFER');
 let body='';for await(const chunk of res.body){body+=Buffer.from(chunk).toString('utf8');if(Buffer.byteLength(body)>16384)throw Error('IMAGE_TRANSFER');}
 const attachment=JSON.parse(body).attachment,url=attachment?.url,parsed=new URL(url),origin=new URL(controller.endpoint);
 if(parsed.protocol!=='https:'||parsed.origin!==origin.origin||parsed.username||parsed.password||parsed.search||parsed.hash||!/^\/file\/[A-Za-z0-9_.-]{1,128}$/.test(parsed.pathname))throw Error('IMAGE_TRANSFER');
 entry.until=Number.isFinite(attachment.expires)?Math.min(Date.now()+600000,attachment.expires*1000-30000):Date.now()+60000;
 return {image_id:c.image_id,url,mime:value.mime,size:value.bytes.length,sha256:digest};
 })();cache.set(identity,entry);
 try{entry.info=await entry.pending;entry.pending=null;return entry.info;}
 catch(e){cache.delete(identity);throw Error('IMAGE_TRANSFER');}
}
const hasImage=(thread,id)=>sources.get(id)?.thread===thread;
module.exports={projectImages,imageBytes,publishImage,aad,MAX,hasImage};
