'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),crypto=require('node:crypto');
const {RemoteController}=require('../../app/src/main/assets/task-notifications/remote-core.cjs');
const uuid=()=>crypto.randomUUID(),hash=s=>crypto.createHash('sha256').update(s).digest('hex');
function fixture(execute){const dir=fs.mkdtempSync(path.join(os.tmpdir(),'remote-ocx-fixture-')),home=path.join(dir,'home'),ocx=path.join(dir,'opencodex'),tasks=path.join(dir,'tasks');for(const d of [home,ocx,tasks])fs.mkdirSync(d);const host=uuid(),events=[],calls=[];
 const runner=async args=>{calls.push(args);return execute(args);};
 const controller=new RemoteController({directory:dir,key:Buffer.alloc(32,7).toString('base64'),hostId:host,config:{codexHome:home,externalTasksDir:tasks,ocxHome:ocx},ocxRunner:runner,emit:async e=>events.push(e)});
 async function send(action,extra={}){const c={id:uuid(),host_id:host,thread_id:host,conversation_id:hash(host),baseline_turn:action,action,issued_at:Date.now(),...extra};await controller.handle(c);const e=events.at(-1);assert.equal(e.request_id,c.id);return e;}
 return {dir,ocx,controller,calls,send,close(){controller.close();fs.rmSync(dir,{recursive:true,force:true});}};
}
test('opencodex overview filters only excluded providers, aggregates logs and never emits secrets',async()=>{
 const now=Date.now(),f=fixture(async a=>a[0]==='account'?JSON.stringify({accounts:[{provider:'openai',id:'account-123456',accountLogLabel:'pf88341',plan:'plus',active:true,email:'private@example.com',key:'sk-secret'},
  {provider:'claude',id:'blocked1'}, {provider:'anthropic',id:'blocked2'}, {provider:'google-antigravity',id:'acct-abcdef',label:'private@example.com'}]}):JSON.stringify({logs:[
  {provider:'openai',model:'gpt-1',inboundProtocol:'responses',status:200,totalTokens:12,timestamp:now-60000},
  {provider:'openai',model:'gpt-1',inboundProtocol:'responses',status:429,totalTokens:3,timestamp:now,errorCode:'rate_limit',upstreamError:'private@example.com sk-secret'},
  {provider:'google-antigravity',model:'google-antigravity/claude-opus',inboundProtocol:'messages',status:200,totalTokens:7,timestamp:now},
  {provider:'claude',model:'hide',status:200,timestamp:now},{provider:'anthropic',model:'hide2',status:200,timestamp:now}]}));
 try{fs.writeFileSync(path.join(f.ocx,'codex-quota-cache.json'),JSON.stringify({quotas:{'account-123456':{shortPercent:25,weeklyPercent:80,shortResetAt:1700000000,weeklyResetAt:1700000100}}}));
  const p=await f.send('presence');assert.deepEqual(p.capabilities,['agents','tasks','ocx_overview']);
  const e=await f.send('ocx_overview');assert.equal(e.status,'ocx_overview');assert.equal(e.error,undefined);assert.equal(e.overview.accounts.length,2);const account=e.overview.accounts[0];
  assert.equal(account.label,'pf88341');assert.equal(account.plan,'plus');assert.equal(account.selected,true);assert.equal(account.short_left_percent,75);assert.equal(account.weekly_left_percent,20);assert.equal(account.short_reset_at,1700000000000);
  assert.equal(e.overview.accounts[1].label,'abcdef');assert.equal(e.overview.requests.length,2);const r=e.overview.requests.find(x=>x.provider==='openai');assert.equal(r.count,2);assert.equal(r.failures,1);assert.equal(r.tokens,15);assert.equal(r.last_error,'rate_limit');assert.equal(e.overview.requests.find(x=>x.provider==='google-antigravity').inbound,'messages');
  assert.equal(e.overview.window_minutes,1);assert.equal(JSON.stringify(e).includes('private@example.com'),false);assert.equal(JSON.stringify(e).includes('sk-secret'),false);
  assert.ok(f.calls.every(args=>JSON.stringify(args)==='["account","list","--json"]'||JSON.stringify(args)==='["logs","history","--json"]'));
 }finally{f.close();}
});
test('unavailable command omits the capability and returns the specified error',async()=>{const f=fixture(async()=>{throw Error('fixture unavailable');});try{
 const p=await f.send('presence');assert.deepEqual(p.capabilities,['agents','tasks']);const e=await f.send('ocx_overview');assert.equal(e.error,'OCX_UNAVAILABLE');assert.equal(e.status,'ocx_overview');
 f.controller.config.externalTasksDir='';const next=await f.send('presence');assert.deepEqual(next.capabilities,['agents']);
 }finally{f.close();}});
test('legacy JSON formats, missing quota fields and the latest 200 requests are handled safely',async()=>{
 const now=Date.now(),rows=Array.from({length:205},(_,i)=>({provider:'openai',model:'gpt',timestamp:now-i*1000,status:200,tokens:1}));
 const f=fixture(async a=>a[0]==='account'?JSON.stringify([{provider:'openai',id:'opaque-abcdef',selected:true}]):JSON.stringify(rows));
 try{const e=await f.send('ocx_overview');assert.equal(e.overview.accounts[0].short_left_percent,-1);assert.equal(e.overview.accounts[0].label,'abcdef');assert.equal(e.overview.requests[0].count,200);assert.equal(e.overview.requests[0].tokens,200);assert.equal(e.overview.requests[0].inbound,'other');}finally{f.close();}
});
test('unsupported history syntax falls back once to the equivalent read-only log command',async()=>{
 const f=fixture(async a=>{if(a[0]==='account')return JSON.stringify({accounts:[]});if(a[0]==='logs')throw Object.assign(Error('usage'),{stderr:'unexpected argument: history'});
  assert.deepEqual(a,['observe','logs','--limit','200','--json']);return JSON.stringify({entries:[{provider:'openai',model:'gpt',timestamp:Date.now(),status:200,usage:{inputTokens:4,outputTokens:3}}]});});
 try{const e=await f.send('ocx_overview');assert.equal(e.error,undefined);assert.equal(e.overview.requests[0].tokens,7);assert.equal(f.calls.length,3);}finally{f.close();}
});
test('untrusted model, account and error fields cannot smuggle email or key text',async()=>{
 const f=fixture(async a=>a[0]==='account'?JSON.stringify({accounts:[{provider:'openai',id:'sk-supersecret',plan:'sk-supersecret',email:'private@example.com'}]}):JSON.stringify({logs:[{provider:'openai',model:'private@example.com',status:401,errorCode:'sk-supersecret',timestamp:Date.now()}]}));
 try{const e=await f.send('ocx_overview');assert.equal(e.error,undefined);assert.equal(e.overview.accounts[0].label,'account');assert.equal(e.overview.requests[0].last_error,'');assert.equal(JSON.stringify(e).includes('private@example.com'),false);assert.equal(JSON.stringify(e).includes('sk-supersecret'),false);}finally{f.close();}
});
test('an oversized overview is truncated with a partial flag before transport',async()=>{
 const rows=Array.from({length:3000},(_,i)=>({provider:'openai',id:'account-'+String(i).padStart(8,'0'),plan:'plus'}));
 const f=fixture(async a=>a[0]==='account'?JSON.stringify({accounts:rows}):JSON.stringify({logs:[]}));
 try{const e=await f.send('ocx_overview');assert.equal(e.partial,true);assert.ok(e.overview.accounts.length<3000);assert.ok(Buffer.byteLength(JSON.stringify(e))<=196608);}finally{f.close();}
});
test('all shipped scripts are isolated from forbidden service and directory literals',()=>{
 const dir=path.join(__dirname,'../../app/src/main/assets/task-notifications');for(const name of fs.readdirSync(dir).filter(n=>/\.(?:cjs|ps1)$/i.test(n))){const text=fs.readFileSync(path.join(dir,name),'utf8');assert.doesNotMatch(text,/anthropic|\.claude|claude\.ai|api\.anthropic/i,name);}
});
test('the Windows launcher handles a fake command shim in a path containing spaces',async t=>{
 if(process.platform!=='win32'){t.skip('Windows-specific launcher');return;}
 const {execute}=require('../../app/src/main/assets/task-notifications/ocx-overview.cjs'),dir=fs.mkdtempSync(path.join(os.tmpdir(),'fake-ocx path-')),previous=process.env.PATH;
 try{fs.writeFileSync(path.join(dir,'ocx.cmd'),'@echo off\r\necho {"fixture":true}\r\n');process.env.PATH=dir;
  const result=await execute(['account','list','--json']);assert.equal(JSON.parse(result).fixture,true);
 }finally{process.env.PATH=previous;fs.rmSync(dir,{recursive:true,force:true});}
});
