'use strict';
// Read-only Codex rollout projection. No tool output, reasoning, credentials or files are exported.
const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const {projectImages}=require('./conversation-images.cjs');
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_SNAPSHOT=196608;
const rolloutPaths=new Map();
function rolloutRevision(config,id){
  if(!UUID.test(id)||!config.codexHome)return '';
  const key=config.codexHome+':'+id;
  let file=rolloutPaths.get(key);
  if(!file||!fs.existsSync(file)){
    file=findRollout(config.codexHome,id);if(!file)return '';
    const root=fs.realpathSync(path.join(config.codexHome,'sessions'))+path.sep;
    if(!fs.realpathSync(file).startsWith(root))return '';
    if(rolloutPaths.size>=128)rolloutPaths.delete(rolloutPaths.keys().next().value);
    rolloutPaths.set(key,file);
  }
  const stat=fs.statSync(file);return stat.size+':'+stat.mtimeMs;
}
function limitText(value,bytes){
  if(typeof value!=='string') return '';
  return Buffer.from(value,'utf8').subarray(0,bytes).toString('utf8').replace(/\uFFFD$/,'');
}
function keyBytes(key){
  if(typeof key!=='string'||!/^[A-Za-z0-9+/]{43}=$/.test(key)) throw Error('Invalid content key');
  const result=Buffer.from(key,'base64');
  if(result.length!==32) throw Error('Invalid content key');
  return result;
}
function encryptContent(value,key,aad){
  const iv=crypto.randomBytes(12),cipher=crypto.createCipheriv('aes-256-gcm',keyBytes(key),iv);
  cipher.setAAD(Buffer.from(aad,'utf8'));
  return Buffer.concat([iv,cipher.update(JSON.stringify(value),'utf8'),cipher.final(),cipher.getAuthTag()]).toString('base64');
}
function decryptContent(value,key,aad){
  const bytes=Buffer.from(value,'base64');
  if(bytes.length<28||bytes.length>MAX_SNAPSHOT+28) throw Error('Invalid encrypted content');
  const decipher=crypto.createDecipheriv('aes-256-gcm',keyBytes(key),bytes.subarray(0,12));
  decipher.setAAD(Buffer.from(aad,'utf8')); decipher.setAuthTag(bytes.subarray(-16));
  return JSON.parse(Buffer.concat([decipher.update(bytes.subarray(12,-16)),decipher.final()]).toString('utf8'));
}
function tailLines(file,end,max=4*1024*1024){
  const size=fs.statSync(file).size,stop=Math.min(end??size,size),start=Math.max(0,stop-max);
  const bytes=Buffer.alloc(stop-start),fd=fs.openSync(file,'r');
  try{let offset=0;while(offset<bytes.length){const n=fs.readSync(fd,bytes,offset,bytes.length-offset,start+offset);if(!n)break;offset+=n;}}
  finally{fs.closeSync(fd);}
  const lines=bytes.toString('utf8').split('\n');
  if(start>0) lines.shift();
  lines.pop(); // Never parse an incomplete line.
  return {lines,partial:start>0};
}
function findRollout(home,id){
  let newest=null;
  function walk(folder){
    if(!fs.existsSync(folder)) return;
    for(const e of fs.readdirSync(folder,{withFileTypes:true})){
      if(e.isSymbolicLink())continue;
      const file=path.join(folder,e.name);
      if(e.isDirectory())walk(file);
      else if(e.isFile()&&e.name.endsWith(id+'.jsonl')){
        const modified=fs.statSync(file).mtimeMs;
        if(!newest||modified>newest.modified)newest={file,modified};
      }
    }
  }
  walk(path.join(home,'sessions'));
  return newest?.file;
}
function readTitle(home,id){
  // The official append-only name index works without any extra runtime dependencies.
  const index=path.join(home,'session_index.jsonl');
  if(fs.existsSync(index)){
    const lines=tailLines(index).lines;
    for(let i=lines.length-1;i>=0;i--){
      try{const row=JSON.parse(lines[i]);if(row.id===id&&typeof row.thread_name==='string'&&row.thread_name.trim())return limitText(row.thread_name.trim(),240);}catch{}
    }
  }
  // Some desktop versions store the displayed title only in the state database.
  // Built-in SQLite is optional (Node 22.13+); never create/migrate a Codex database.
  let db;
  try{
    const {DatabaseSync}=require('node:sqlite');
    const file=fs.readdirSync(home).filter(n=>/^state_\d+\.sqlite$/.test(n))
      .sort((a,b)=>Number(b.match(/\d+/)[0])-Number(a.match(/\d+/)[0]))[0];
    if(!file)return '';
    db=new DatabaseSync(path.join(home,file),{readOnly:true});
    const columns=db.prepare('PRAGMA table_info(threads)').all().map(r=>r.name);
    const fields=['name','title'].filter(n=>columns.includes(n));
    if(!fields.length||!columns.includes('id'))return '';
    const row=db.prepare('SELECT '+fields.join(',')+' FROM threads WHERE id = ?').get(id);
    for(const field of fields)if(typeof row?.[field]==='string'&&row[field].trim())return limitText(row[field].trim(),240);
  }catch{}finally{if(db)db.close();}
  return '';
}
function readSnapshot(config,id,turn,knownFile,end){
  if(!UUID.test(id)||typeof turn!=='string'||!config.codexHome)return null;
  const file=knownFile||findRollout(config.codexHome,id);
  if(!file)return null;
  const root=fs.realpathSync(path.join(config.codexHome,'sessions'))+path.sep;
  if(!fs.realpathSync(file).startsWith(root))return null;
  const tail=tailLines(file,end),messages=[];
  let truncated=tail.partial,found=false,reply='',completedAt='',cwd='';
  for(const line of tail.lines){
    if(line.length>1024*1024){truncated=true;continue;}
    let row;try{row=JSON.parse(line);}catch{continue;}
    const p=row.payload||{};
    if(row.type==='session_meta'&&p.id&&p.id!==id)return null;
    if(row.type==='session_meta'&&typeof p.cwd==='string')cwd=p.cwd;
    if(row.type==='session_meta'&&p.source?.subagent)return null;
    if(row.type==='response_item'&&p.type==='message'&&['user','assistant'].includes(p.role)&&
      (p.role==='user'||!p.phase||['commentary','final','final_answer'].includes(p.phase))){
      const kinds=p.internal_chat_message_metadata_passthrough?.content_item_kinds;
      const text=(Array.isArray(p.content)?p.content:[]).filter((b,index)=>{
        if(!['input_text','output_text','text'].includes(b?.type)||typeof b.text!=='string')return false;
        if(p.role!=='user')return true;
        // Codex may persist project instructions/environment as role=user.
        // Prefer its explicit content origin over role or text heuristics.
        if(Array.isArray(kinds))return kinds[index]==='user.text';
        return !/^\s*(?:# AGENTS\.md instructions\b|<environment_context>|<permissions instructions>)/.test(b.text);
      }).map(b=>b.text).join('\n');
      const images=projectImages(id,{type:'userMessage',content:(Array.isArray(p.content)?p.content:[]).filter((b,index)=>
        p.role==='user'&&b?.type==='input_image'&&(!Array.isArray(kinds)||String(kinds[index]).startsWith('user.')))
        .map(b=>({type:'image',url:b.image_url}))},cwd);
      if(p.role==='assistant')images.push(...projectImages(id,{type:'agentMessage',text},cwd));
      if(text.trim()||images.length){
        const bounded=limitText(text,16384);if(bounded!==text)truncated=true;
        messages.push({role:p.role,text:bounded,...(images.length?{images:images.slice(0,3)}:{})});
        if(messages.length>60){messages.shift();truncated=true;}
      }
    }
    if(row.type==='event_msg'&&['task_complete','turn_aborted'].includes(p.type)&&p.turn_id===turn){
      reply=limitText(p.last_agent_message,65536);
      if(reply!==p.last_agent_message&&p.last_agent_message)truncated=true;
      completedAt=typeof row.timestamp==='string'?row.timestamp:'';
      found=true;break;
    }
  }
  if(!found)return null; // Never include a later or still-running turn in an earlier completion.
  const result={conversation_id:crypto.createHash('sha256').update(id).digest('hex'),title:readTitle(config.codexHome,id),reply,messages,truncated,completed_at:completedAt};
  if(config.remoteEnabled&&UUID.test(config.remoteHostId||''))result.remote_ref={host_id:config.remoteHostId,thread_id:id,baseline_turn:turn};
  while(Buffer.byteLength(JSON.stringify(result))>MAX_SNAPSHOT&&result.messages.length){result.messages.shift();result.truncated=true;}
  return result;
}
function readLatestSnapshot(config,id){
  if(!UUID.test(id)||!config.codexHome)return null;
  const file=findRollout(config.codexHome,id);if(!file)return null;
  const root=fs.realpathSync(path.join(config.codexHome,'sessions'))+path.sep;
  if(!fs.realpathSync(file).startsWith(root))return null;
  const fd=fs.openSync(file,'r'),first=Buffer.alloc(Math.min(65536,fs.statSync(file).size));
  try{fs.readSync(fd,first,0,first.length,0);}finally{fs.closeSync(fd);}
  let meta;for(const l of first.toString('utf8').split('\n')){try{const r=JSON.parse(l);if(r.type==='session_meta'){meta=r.payload;break;}}catch{}}
  if(meta?.id!==id||meta.source?.subagent)return null;
  let latest,completed;
  for(const l of tailLines(file).lines){if(l.length>1024*1024)continue;try{const r=JSON.parse(l),p=r.payload;
    if(r.type==='event_msg'&&['task_started','task_complete','turn_aborted'].includes(p?.type)){
      latest=p.type;if(p.type!=='task_started'&&typeof p.turn_id==='string')completed=p.turn_id;
    }
  }catch{}}
  if(!completed)return null;
  const value=readSnapshot(config,id,completed,file);
  if(value&&latest==='task_started'){delete value.remote_ref;value.truncated=true;}
  return value;
}
function enrichCompletion(metadata,config,payload,file,end){
  if(!metadata||!config.contentKey)return metadata;
  try{
    keyBytes(config.contentKey);
    const id=payload['thread-id'],turn=payload['turn-id'];
    let snapshot;
    try{snapshot=readSnapshot(config,id,turn,file,end);}catch{}
    if(!snapshot)snapshot={conversation_id:UUID.test(id||'')?crypto.createHash('sha256').update(id).digest('hex'):'',title:'',reply:limitText(payload['last-assistant-message'],65536),messages:[],truncated:true,completed_at:''};
    if(!snapshot.reply)snapshot.reply=limitText(payload['last-assistant-message'],65536);
    const preview={...snapshot,messages:[],reply:limitText(snapshot.reply,1000),truncated:snapshot.truncated||Buffer.byteLength(snapshot.reply)>1000};
    const aad='CodexUsage:1.1:'+metadata.session_id+':'+metadata.turn_id;
    const wire={...metadata,schema_version:'1.1',encrypted_content:encryptContent(preview,config.contentKey,aad)};
    // JSON escaping can expand even a short reply (control characters, backslashes).
    while(Buffer.byteLength(JSON.stringify(wire))>3500&&preview.reply.length){
      preview.reply=limitText(preview.reply,Math.floor(Buffer.byteLength(preview.reply)/2));preview.truncated=true;
      wire.encrypted_content=encryptContent(preview,config.contentKey,aad);
    }
    return {...wire,encrypted_snapshot:encryptContent(snapshot,config.contentKey,aad)};
  }catch{return metadata;} // Basic completion notifications survive missing content or unsupported formats.
}
function readContextUsage(config,id){
  if(!UUID.test(id)||!config.codexHome)return null;const file=findRollout(config.codexHome,id);if(!file)return null;
  const root=fs.realpathSync(path.join(config.codexHome,'sessions'))+path.sep;if(!fs.realpathSync(file).startsWith(root))return null;
  let context=null;for(const line of tailLines(file).lines){if(line.length>1024*1024)continue;try{const row=JSON.parse(line),info=row.payload?.info;
    if(row.type==='event_msg'&&row.payload?.type==='token_count'&&info){
      context=require('./remote-options.cjs').normalizeContext({last:{totalTokens:info.last_token_usage?.total_tokens},modelContextWindow:info.model_context_window});
    }}catch{}}return context;
}
module.exports={readContextUsage,encryptContent,decryptContent,readSnapshot,readLatestSnapshot,enrichCompletion,rolloutRevision};
