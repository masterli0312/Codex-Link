'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const crypto=require('node:crypto');
const {encryptContent, decryptContent, readSnapshot, readLatestSnapshot, enrichCompletion}=require('../../app/src/main/assets/task-notifications/task-content.cjs');
const {publish}=require('../../app/src/main/assets/task-notifications/notify.cjs');
const thread='01a00000-0000-0000-0000-000000000001';
const metadata={schema_version:'1.0',agent_source:'codex',status:'turn_complete',session_id:'codex',turn_id:'a'.repeat(64)};
const key=Buffer.alloc(32,7).toString('base64');
const aad='CodexUsage:1.1:codex:'+metadata.turn_id;

test('AES-GCM matches the Java wire layout and rejects wrong identity, key and tampering',()=>{
  const a=encryptContent({title:'中文',reply:'Done 🔔'},key,aad);
  const b=encryptContent({title:'中文',reply:'Done 🔔'},key,aad);
  assert.notEqual(a,b);
  assert.equal(decryptContent(a,key,aad).title,'中文');
  assert.throws(()=>decryptContent(a,key,aad+'wrong'));
  assert.throws(()=>decryptContent(a,Buffer.alloc(32,8).toString('base64'),aad));
  const bytes=Buffer.from(a,'base64'); bytes[15]^=1;
  assert.throws(()=>decryptContent(bytes.toString('base64'),key,aad));
  const iv=Buffer.alloc(12,3),c=crypto.createCipheriv('aes-256-gcm',Buffer.from(key,'base64'),iv);
  c.setAAD(Buffer.from(aad));
  const wire=Buffer.concat([iv,c.update('{"title":"fixture"}'),c.final(),c.getAuthTag()]);
  assert.equal(decryptContent(wire.toString('base64'),key,aad).title,'fixture');
});

function fixture(){
  const home=fs.mkdtempSync(path.join(os.tmpdir(),'codex-usage-content-test-'));
  const dir=path.join(home,'sessions'); fs.mkdirSync(dir);
  const file=path.join(dir,'rollout-'+thread+'.jsonl');
  const line=(type,payload)=>JSON.stringify({timestamp:'2026-10-01T01:00:00Z',type,payload})+'\n';
  const message=(role,text,phase)=>line('response_item',{type:'message',role,phase,content:[{type:role==='user'?'input_text':'output_text',text}]});
  fs.writeFileSync(path.join(home,'session_index.jsonl'),[JSON.stringify({id:thread,thread_name:'old'}),JSON.stringify({id:thread,thread_name:'工作对话'})].join('\n')+'\n');
  fs.writeFileSync(file,line('session_meta',{id:thread,source:'vscode'})+message('system','NEVER SEND SYSTEM')+message('developer','NEVER SEND DEVELOPER')+message('user','帮我修复')+message('assistant','NEVER SEND ANALYSIS','analysis')+line('response_item',{type:'function_call_output',output:'NEVER SEND TOOL'})+message('assistant','已经修复','final_answer')+line('event_msg',{type:'task_complete',turn_id:'finished',last_agent_message:'已经修复'})+message('user','NEWER TURN MUST NOT LEAK'));
  return {home,file,message,line,cleanup:()=>{assert.equal(path.dirname(home),path.resolve(os.tmpdir()));assert.ok(path.basename(home).startsWith('codex-usage-content-test-'));fs.rmSync(home,{recursive:true,force:true});}};
}

test('large old histories use a bounded visible tail and never export tool output',()=>{
 const f=fixture();try{
  fs.appendFileSync(f.file,f.line('response_item',{type:'function_call_output',output:'X'.repeat(17*1024*1024)})+
    f.message('user','recent question')+f.message('assistant','recent reply','final_answer')+
    f.line('event_msg',{type:'task_complete',turn_id:'latest',last_agent_message:'recent reply'}));
  const config={codexHome:f.home,remoteEnabled:true,remoteHostId:'22222222-2222-3333-4444-555555555555'};
  const snap=readLatestSnapshot(config,thread);
  assert.equal(snap.remote_ref.baseline_turn,'latest');assert.equal(snap.reply,'recent reply');assert.equal(snap.truncated,true);
  assert.deepEqual(snap.messages,[{role:'user',text:'recent question'},{role:'assistant',text:'recent reply'}]);
  fs.appendFileSync(f.file,f.line('event_msg',{type:'task_started',turn_id:'running'}));
  assert.equal(readLatestSnapshot(config,thread).remote_ref,undefined);
 }finally{f.cleanup();}
});

test('bounded rollout tails recover the original workspace for document links',()=>{
 const f=fixture();try{
  fs.writeFileSync(path.join(f.home,'Report.pdf'),'document content');
  fs.writeFileSync(f.file,f.line('session_meta',{id:thread,source:'vscode',cwd:f.home})+
   f.line('response_item',{type:'function_call_output',output:'X'.repeat(5*1024*1024)})+
   f.message('assistant','[Report](Report.pdf)','final_answer')+
   f.line('event_msg',{type:'task_complete',turn_id:'document',last_agent_message:'[Report](Report.pdf)'}));
  const value=readSnapshot({codexHome:f.home},thread,'document',f.file);
  assert.equal(value.messages[0].files[0].name,'Report.pdf');
  assert.equal(JSON.stringify(value).includes(f.home),false);
 }finally{f.cleanup();}
});

test('snapshot uses latest actual title and stops at this completion, excluding tools and hidden roles',()=>{
  const f=fixture(); try{
    const result=readSnapshot({codexHome:f.home},thread,'finished');
    assert.equal(result.title,'工作对话');
    assert.equal(result.reply,'已经修复');
    assert.deepEqual(result.messages.map(m=>m.text),['帮我修复','已经修复']);
    assert.ok(!JSON.stringify(result).includes('NEVER SEND'));
    assert.ok(!JSON.stringify(result).includes('NEWER TURN'));
    assert.equal(readSnapshot({codexHome:f.home},'../../secret','finished'),null);
  } finally {f.cleanup();}
});

test('role=user injected instructions are excluded while explicitly typed user text is retained',()=>{
 const f=fixture();try{
   const typed=(text,kind)=>f.line('response_item',{type:'message',role:'user',content:[{type:'input_text',text}],
     internal_chat_message_metadata_passthrough:{content_item_kinds:[kind]}});
   fs.writeFileSync(f.file,f.line('session_meta',{id:thread,source:'vscode'})+
     typed('PRIVATE PROJECT INSTRUCTIONS','agents_md.instructions')+typed('PRIVATE ENVIRONMENT','environments.environment_context')+
     f.message('user','<environment_context>legacy injected details</environment_context>')+
     typed('# AGENTS.md instructions intentionally pasted by user','user.text')+
     f.line('event_msg',{type:'task_complete',turn_id:'typed',last_agent_message:'done'}));
   const result=readSnapshot({codexHome:f.home},thread,'typed');
   assert.deepEqual(result.messages.map(m=>m.text),['# AGENTS.md instructions intentionally pasted by user']);
   assert.ok(!JSON.stringify(result).includes('PRIVATE'));
 }finally{f.cleanup();}
});

test('content is opt-in, ciphertext only, with a small inline preview and an encrypted full snapshot',()=>{
  const f=fixture();try{
    const payload={'thread-id':thread,'turn-id':'finished','last-assistant-message':'已经修复'};
    assert.deepEqual(enrichCompletion(metadata,{codexHome:f.home},payload),metadata);
    const result=enrichCompletion(metadata,{codexHome:f.home,contentKey:key},payload);
    assert.equal(result.schema_version,'1.1');
    assert.ok(!JSON.stringify(result).includes('工作对话'));
    assert.ok(!JSON.stringify(result).includes('已经修复'));
    assert.equal(decryptContent(result.encrypted_content,key,aad).title,'工作对话');
    assert.equal(decryptContent(result.encrypted_snapshot,key,aad).messages.length,2);
    assert.ok(Buffer.byteLength(JSON.stringify({...result,encrypted_snapshot:undefined}))<4096);
  }finally{f.cleanup();}
});

test('large conversations are bounded and explicitly marked as partial',()=>{
  const f=fixture();try{
    const content=Array.from({length:100},(_,i)=>f.message('user','第'+i+'条 '+('文'.repeat(8000)))).join('')+f.line('event_msg',{type:'task_complete',turn_id:'large',last_agent_message:'最后结果'});
    fs.writeFileSync(f.file,content);
    const result=readSnapshot({codexHome:f.home},thread,'large');
    assert.ok(result.truncated);
    assert.ok(result.messages.length<=60);
    assert.ok(Buffer.byteLength(JSON.stringify(result))<=196608);
  }finally{f.cleanup();}
});

test('missing logs retain the actual callback reply without inventing a title or history',()=>{
  const result=enrichCompletion(metadata,{contentKey:key},{'thread-id':thread,'turn-id':'missing','last-assistant-message':'真实回复'});
  const decoded=decryptContent(result.encrypted_content,key,aad);
  assert.equal(decoded.reply,'真实回复');
  assert.equal(decoded.title,'');
  assert.equal(decoded.truncated,true);
});

test('JSON-escaped replies cannot overflow the inline notification size',()=>{
  const message=enrichCompletion(metadata,{contentKey:key},{'thread-id':thread,'turn-id':'done','last-assistant-message':'\u0000'.repeat(2000)});
  assert.ok(Buffer.byteLength(JSON.stringify({...message,encrypted_snapshot:undefined}))<=3500);
  assert.equal(decryptContent(message.encrypted_content,key,aad).truncated,true);
});

test('attachment transport has no plaintext headers and falls back to encrypted preview when files are rejected',async()=>{
  const message=enrichCompletion(metadata,{contentKey:key},{'thread-id':thread,'turn-id':'done','last-assistant-message':'PRIVATE_REPLY'});
  const requests=[];
  await publish('https://ntfy.sh/example',message,async(_,args)=>{
    requests.push(args);
    return {ok:requests.length>1,status:413,arrayBuffer:async()=>new ArrayBuffer(0)};
  });
  assert.equal(requests.length,2);
  assert.equal(requests[0].headers.Filename,'codex-usage.bin');
  assert.equal(JSON.stringify(requests[0].headers).includes('PRIVATE_REPLY'),false);
  assert.equal(Buffer.isBuffer(requests[0].body),true);
  assert.equal(requests[1].body.includes('PRIVATE_REPLY'),false);
  assert.equal(JSON.parse(requests[1].body).encrypted_snapshot,undefined);
  assert.equal(decryptContent(JSON.parse(requests[1].body).encrypted_content,key,aad).reply,'PRIVATE_REPLY');
});
