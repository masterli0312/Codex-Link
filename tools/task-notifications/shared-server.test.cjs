'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),net=require('node:net'),http=require('node:http');
const {EventEmitter}=require('node:events');
const assets=path.join(__dirname,'../../app/src/main/assets/task-notifications');
const {main,probeShared,acquireSupervisor}=require(path.join(assets,'shared-server.cjs'));
let lockCounter=0;
const lockName=()=>process.platform==='win32'?`\\\\.\\pipe\\codex-link-shared-test-${process.pid}-${++lockCounter}`:path.join(os.tmpdir(),`codex-link-shared-test-${process.pid}-${++lockCounter}.sock`);
function runtime(health){
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'shared-server-'));
  fs.writeFileSync(path.join(dir,'connection.json'),'\uFEFF'+JSON.stringify({appServerUrl:'ws://127.0.0.1:18475/',codexExecutable:'C:\\fixture\\codex.exe',codexHome:'C:\\fixture'}));
  if(health)fs.writeFileSync(path.join(dir,'shared-server-health.json'),JSON.stringify(health));
  return dir;
}
const readHealth=dir=>JSON.parse(fs.readFileSync(path.join(dir,'shared-server-health.json'),'utf8'));
function natives(){
  const children=[];
  const spawnNative=(file,args)=>{const child=new EventEmitter();child.pid=40000+children.length;child.args=args;child.exitCode=null;
    child.stderr=new EventEmitter();child.killed=0;child.kill=()=>{child.killed++;};children.push(child);return child;};
  return {children,spawnNative};
}
const scripted=states=>{const seen=[];const probe=async()=>{const state=states.length>1?states.shift():states[0];seen.push(state);return state;};probe.seen=seen;return probe;};
const options=extra=>({lockName:lockName(),resolveNative:file=>file,busyInterval:1,readyInterval:1,handleSignals:false,...extra});
const until=async predicate=>{for(let i=0;i<200&&!predicate();i++)await new Promise(r=>setTimeout(r,5));assert.ok(predicate());};

test('a ready listener is reused without starting a competitor and keeps its owner PIDs',async()=>{
  const dir=runtime({supervisorPid:13752,nativePid:17860,nativeExecutable:'C:\\fixture\\codex.exe',state:'starting'});
  const {children,spawnNative}=natives();
  const result=await main(dir,options({spawnNative,probe:scripted(['ready'])}));
  assert.equal(result.state,'reused');assert.equal(children.length,0);
  const health=readHealth(dir);assert.equal(health.state,'reused');assert.equal(health.nativePid,17860);assert.equal(health.supervisorPid,13752);
});
test('an occupied port that never becomes ready is reported, not competed with',async()=>{
  const dir=runtime(),{children,spawnNative}=natives(),probe=scripted(['busy']);
  const result=await main(dir,options({spawnNative,probe,busyAttempts:3}));
  assert.equal(result.state,'blocked');assert.equal(children.length,0);assert.equal(probe.seen.length,3);
  assert.equal(readHealth(dir).state,'blocked');assert.equal(readHealth(dir).error,'PORT_IN_USE_NOT_READY');
});
test('a server still binding is waited for and then reused',async()=>{
  const dir=runtime(),{children,spawnNative}=natives();
  const result=await main(dir,options({spawnNative,probe:scripted(['busy','busy','ready'])}));
  assert.equal(result.state,'reused');assert.equal(children.length,0);
});
test('a free port starts one native server and reports ready only after /readyz succeeds',async()=>{
  const dir=runtime(),{children,spawnNative}=natives();
  const result=await main(dir,options({spawnNative,probe:scripted(['free','busy','ready'])}));
  try{
    assert.equal(result.state,'supervising');assert.equal(children.length,1);
    assert.deepEqual(children[0].args,['-c','features.code_mode_host=true','app-server','--listen','ws://127.0.0.1:18475']);
    assert.equal(readHealth(dir).state,'starting');
    await until(()=>readHealth(dir).state==='ready');
    assert.equal(readHealth(dir).nativePid,children[0].pid);
  }finally{result.close();}
  assert.equal(children[0].killed,1);assert.equal((await result.done).state,'stopped');
});
test('concurrent starts elect one supervisor and the other exits without spawning',async()=>{
  const dir=runtime(),name=lockName(),{children,spawnNative}=natives();
  const [a,b]=await Promise.all([main(dir,options({lockName:name,spawnNative,probe:scripted(['free'])})),
    main(dir,options({lockName:name,spawnNative,probe:scripted(['free'])}))]);
  const states=[a.state,b.state].sort();
  try{assert.deepEqual(states,['supervised','supervising']);assert.equal(children.length,1);}
  finally{a.close?.();b.close?.();}
});
test('losing the port to another ready server stops supervision instead of looping',async()=>{
  const dir=runtime(),{children,spawnNative}=natives();
  const result=await main(dir,options({spawnNative,probe:scripted(['free','ready'])}));
  await until(()=>children.length===1);children[0].emit('exit',1);
  assert.equal((await result.done).state,'reused');assert.equal(children.length,1);assert.equal(children[0].killed,0);
  assert.equal(readHealth(dir).state,'reused');
});
test('a crashed native server on a free port is restarted by the same supervisor',async()=>{
  const dir=runtime(),{children,spawnNative}=natives();
  const result=await main(dir,options({spawnNative,probe:scripted(['free'])}));
  try{
    children[0].emit('exit',1);
    await until(()=>children.length===2);assert.equal(readHealth(dir).nativePid,children[1].pid);
  }finally{result.close();}
});
test('the lease is released by the OS when its owner closes',async()=>{
  const name=lockName(),first=await acquireSupervisor(name);
  assert.ok(first);assert.equal(await acquireSupervisor(name),null);
  await new Promise(resolve=>first.close(resolve));
  const second=await acquireSupervisor(name);assert.ok(second);second.close();
});
test('probe distinguishes ready, not-ready, silent and closed listeners',async()=>{
  const listen=server=>new Promise(resolve=>server.listen(0,'127.0.0.1',()=>resolve(server.address().port)));
  const ready=http.createServer((req,res)=>{res.statusCode=req.url==='/readyz'?200:404;res.end();});
  const notReady=http.createServer((req,res)=>{res.statusCode=503;res.end();});
  const sockets=[],silent=net.createServer(socket=>sockets.push(socket));
  const [p1,p2,p3]=[await listen(ready),await listen(notReady),await listen(silent)];
  const free=net.createServer(),p4=await listen(free);await new Promise(resolve=>free.close(resolve));
  try{
    assert.equal(await probeShared(`ws://127.0.0.1:${p1}/`),'ready');
    assert.equal(await probeShared(`ws://127.0.0.1:${p2}/`),'busy');
    assert.equal(await probeShared(`ws://127.0.0.1:${p3}/`,200),'busy');
    assert.equal(await probeShared(`ws://127.0.0.1:${p4}/`),'free');
  }finally{for(const socket of sockets)socket.destroy();ready.close();notReady.close();silent.close();}
});
test('installer copy list and App export list ship every computer component',()=>{
  const install=fs.readFileSync(path.join(assets,'Install.ps1'),'utf8');
  const copied=[...install.match(/foreach \(\$name in @\(([^)]*)\)\)/)[1].matchAll(/'([^']+)'/g)].map(m=>m[1]);
  const kotlin=fs.readFileSync(path.join(__dirname,'../../app/src/main/java/com/codex/quota/ui/feature/settings/TaskNotificationSettingsPanel.kt'),'utf8');
  const exported=[...kotlin.match(/for \(name in listOf\(([^)]*)\)\)/)[1].matchAll(/"([^"]+)"/g)].map(m=>m[1]);
  const shipped=fs.readdirSync(assets).filter(name=>fs.statSync(path.join(assets,name)).isFile());
  assert.deepEqual([...exported].sort(),[...shipped].sort());
  for(const name of copied)assert.ok(exported.includes(name),name+' is installed but not exported');
  for(const name of shipped.filter(name=>name.endsWith('.cjs')&&!['notify.cjs','task-content.cjs'].includes(name)))
    assert.ok(copied.includes(name),name+' is exported but never installed');
  for(const name of shipped.filter(name=>name.endsWith('.cjs')))
    for(const [,dependency] of fs.readFileSync(path.join(assets,name),'utf8').matchAll(/require\('\.\/([^']+)'\)/g))
      assert.ok(shipped.includes(dependency),`${name} requires missing ${dependency}`);
});
