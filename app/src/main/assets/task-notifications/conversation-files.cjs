'use strict';
// The phone addresses opaque IDs from real assistant file links, never supplies a path.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const MAX=256*1024*1024,sources=new Map(),hash=value=>crypto.createHash('sha256').update(value).digest('hex');
const uploads=new WeakMap();
const protectedPath=relative=>relative.split(/[\\/]/).some(p=>/^\.(?:git|ssh|codex|config|aws|azure|kube)$|^\.env(?:\.|$)/i.test(p))||/^(?:auth|credentials|secrets)(?:\.(?:json|ya?ml|toml|ini)|$)/i.test(path.basename(relative));
const documents=/\.(?:pdf|pptx?|docx?|xlsx?|odt|ods|odp|rtf|csv)$/i;
const samePath=(a,b)=>process.platform==='win32'?a.toLowerCase()===b.toLowerCase():a===b;
const within=(root,file)=>{const relative=path.relative(root,file);return !!relative&&relative!=='..'&&!relative.startsWith('..'+path.sep)&&!path.isAbsolute(relative);};
function projectFiles(thread,item,cwd=''){
 if(item?.type!=='agentMessage'||typeof item.text!=='string'||!path.isAbsolute(cwd))return [];
 const files=[];
 for(const match of item.text.matchAll(/(?<!!)\[([^\]\n]+)\]\((?:<([^>]+)>|([^\s)]+))(?:\s+"[^"\n]*")?\)/g)){
  let source=match[2]||match[3];
  if(source.startsWith('file:')){try{source=require('node:url').fileURLToPath(source);}catch{continue;}}
  try{source=decodeURIComponent(source);}catch{continue;}
  if(/^\/[A-Za-z]:[/\\]/.test(source))source=source.slice(1);
  source=source.replace(/:\d+(?::\d+)?$/,'');
  if(/^[a-z][a-z0-9+.-]*:/i.test(source)&&!path.isAbsolute(source)||source.includes('\0'))continue;
  const file=path.resolve(cwd,source),relative=path.relative(cwd,file);
  let exact='';
  if(!within(cwd,file)){
   // An explicit native assistant document link can refer to a desktop/export folder.
   // Pin that existing exact file; a phone cannot choose any path or traverse out of cwd.
   if(!path.isAbsolute(source)||!documents.test(file))continue;
   try{exact=fs.realpathSync(file);if(!samePath(exact,file)||!fs.statSync(exact).isFile())continue;}catch{continue;}
  }
  const name=path.basename(file);
  // Never expose authentication/configuration stores as downloadable artifacts.
  if(protectedPath(relative)||protectedPath(file))continue;
  const id=hash(thread+':file:'+file),ref={id,name};
  if(Buffer.byteLength(name)>240)continue;
  sources.delete(id);sources.set(id,{thread,file,cwd,exact});
  while(sources.size>512)sources.delete(sources.keys().next().value);
  if(!files.some(f=>f.id===id))files.push(ref);
  if(files.length>=10)break;
 }
 return files;
}
const hasFile=(thread,id)=>sources.get(id)?.thread===thread;
async function verifiedPath(entry){
 const real=await fs.promises.realpath(entry.file);
 if(protectedPath(real))throw Error('FILE_UNAVAILABLE');
 if(entry.exact){if(!samePath(real,entry.exact))throw Error('FILE_UNAVAILABLE');}
 else if(!within(await fs.promises.realpath(entry.cwd),real))throw Error('FILE_UNAVAILABLE');
 return real;
}
async function fileBytes(thread,id){
 const entry=sources.get(id);if(!entry||entry.thread!==thread)throw Error('FILE_UNAVAILABLE');
 const real=await verifiedPath(entry);
 const handle=await fs.promises.open(real,'r');
 try{
  const stat=await handle.stat();if(!stat.isFile()||stat.size<1||stat.size>MAX)throw Error('FILE_TOO_LARGE');
  const bytes=Buffer.alloc(stat.size);let offset=0;
  while(offset<bytes.length){const r=await handle.read(bytes,offset,bytes.length-offset,offset);if(!r.bytesRead)break;offset+=r.bytesRead;}
  if(offset!==bytes.length)throw Error('FILE_UNAVAILABLE');
  return {bytes,name:path.basename(entry.file)};
 }finally{await handle.close();}
}
const aad=(host,thread,id)=>'CodexUsage:file:'+host+':'+thread+':'+id;
const streamKey=(key,host,thread,id)=>crypto.createHmac('sha256',Buffer.from(key,'base64')).update(aad(host,thread,id)+':stream-v1').digest();
async function publishFile(controller,c,fetchImpl=fetch){
 const entry=sources.get(c.file_id);if(!entry||entry.thread!==c.thread_id)throw Error('FILE_UNAVAILABLE');
 const real=await verifiedPath(entry);
 const handle=await fs.promises.open(real,'r');
 try{
 const stat=await handle.stat();if(!stat.isFile()||stat.size<1||stat.size>MAX)throw Error('FILE_TOO_LARGE');
 let cache=uploads.get(controller);if(!cache){cache=new Map();uploads.set(controller,cache);}
 const identity=hash(c.host_id+':'+c.thread_id+':'+c.file_id+':'+stat.size+':'+stat.mtimeMs+':'+stat.ctimeMs),now=Date.now();
 for(const [id,entry] of cache)if(entry.until<=now&&!entry.pending)cache.delete(id);
 const old=cache.get(identity);if(old)return old.pending||old.info;
 while(cache.size>=64){const idle=[...cache].find(([,entry])=>!entry.pending);if(!idle)break;cache.delete(idle[0]);}
 const uploaded={until:now+600000,pending:null};
 uploaded.pending=(async()=>{
 const iv=crypto.randomBytes(16),cipher=crypto.createCipheriv('aes-256-ctr',streamKey(controller.key,c.host_id,c.thread_id,c.file_id),iv),digest=crypto.createHash('sha256');
 let complete=false;
 async function* stream(){
  yield iv;let offset=0;
  while(offset<stat.size){const buffer=Buffer.alloc(Math.min(65536,stat.size-offset));const result=await handle.read(buffer,0,buffer.length,offset);
   if(!result.bytesRead)throw Error('FILE_UNAVAILABLE');const bytes=buffer.subarray(0,result.bytesRead);offset+=bytes.length;digest.update(bytes);yield cipher.update(bytes);}
  yield cipher.final();complete=true;
 }
 const topic=require('./remote-core.cjs').eventTopic(controller.endpoint,controller.key,c.host_id);
 const res=await fetchImpl(topic,{method:'POST',headers:{Filename:'codex-file.bin',Message:'Encrypted conversation file','Content-Type':'application/octet-stream','Content-Length':String(stat.size+16)},body:stream(),duplex:'half',redirect:'error',signal:AbortSignal.timeout(180000)});
 if(!res.ok||!complete)throw Error('FILE_TRANSFER');
 let text='';for await(const chunk of res.body){text+=Buffer.from(chunk).toString('utf8');if(Buffer.byteLength(text)>16384)throw Error('FILE_TRANSFER');}
 const attachment=JSON.parse(text).attachment,url=attachment?.url,parsed=new URL(url),origin=new URL(controller.endpoint);
 if(parsed.protocol!=='https:'||parsed.origin!==origin.origin||parsed.username||parsed.password||parsed.search||parsed.hash||!/^\/file\/[A-Za-z0-9_.-]{1,128}$/.test(parsed.pathname))throw Error('FILE_TRANSFER');
 // The checksum and identity are carried by the existing AES-GCM authenticated event.
 // Plaintext stays private until the phone checks the full length and authenticated digest.
 if(Number.isFinite(attachment.expires))uploaded.until=Math.min(uploaded.until,attachment.expires*1000-30000);
 return {file_id:c.file_id,name:path.basename(entry.file),url,mime:'application/octet-stream',size:stat.size,sha256:digest.digest('hex'),format:'stream-v1'};
 })();cache.set(identity,uploaded);
 try{uploaded.info=await uploaded.pending;uploaded.pending=null;return uploaded.info;}
 catch(e){cache.delete(identity);throw e;}
 }finally{await handle.close();}
}
module.exports={projectFiles,hasFile,fileBytes,publishFile,aad,streamKey,MAX};
