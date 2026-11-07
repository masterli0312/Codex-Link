'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),net=require('node:net'),http=require('node:http'),crypto=require('node:crypto');
const {EventEmitter,once}=require('node:events'),{spawn,spawnSync}=require('node:child_process');
const assets=path.join(__dirname,'../../app/src/main/assets/task-notifications');
const {main,probeShared,acquireSupervisor,nativeActive}=require(path.join(assets,'shared-server.cjs'));
let lockCounter=0;
const lockName=()=>process.platform==='win32'?`\\\\.\\pipe\\codex-link-shared-test-${process.pid}-${++lockCounter}`:path.join(os.tmpdir(),`codex-link-shared-test-${process.pid}-${++lockCounter}.sock`);
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(predicate,ms=3000){const end=Date.now()+ms;while(Date.now()<end){if(await predicate())return;await delay(10);}assert.ok(await predicate(),'timed out waiting for condition');}
async function listen(server){const port=await freePort();await new Promise((resolve,reject)=>{server.once('error',reject);server.listen({host:'127.0.0.1',port,exclusive:true},resolve);});return port;}
async function freePort(){for(;;){const server=net.createServer(),port=crypto.randomInt(49152,65536);try{await new Promise((resolve,reject)=>{server.once('error',reject);server.listen({host:'127.0.0.1',port,exclusive:true},resolve);});}catch{continue;}await new Promise(resolve=>server.close(resolve));return port;}}
async function fixture(t,health){
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'shared-server-')),port=await freePort(),endpoint=`ws://127.0.0.1:${port}/`;
 fs.mkdirSync(path.join(dir,'old'));fs.writeFileSync(path.join(dir,'old','codex.exe'),'fixture');fs.writeFileSync(path.join(dir,'old','codex-code-mode-host.exe'),'fixture');
 fs.mkdirSync(path.join(dir,'new'));fs.writeFileSync(path.join(dir,'new','codex.exe'),'fixture');fs.writeFileSync(path.join(dir,'new','codex-code-mode-host.exe'),'fixture');
 fs.writeFileSync(path.join(dir,'connection.json'),'\uFEFF'+JSON.stringify({appServerUrl:endpoint,codexExecutable:path.join(dir,'old','codex.exe'),codexHome:path.join(dir,'home')}));
 fs.writeFileSync(path.join(dir,'remote-journal.json'),'{}');
 if(health)fs.writeFileSync(path.join(dir,'shared-server-health.json'),JSON.stringify(health));
 const f={dir,port,endpoint,health:()=>{try{return JSON.parse(fs.readFileSync(path.join(dir,'shared-server-health.json'),'utf8'));}catch{return {};}}};
 t.after(async()=>{if(f.result?.close){f.result.close();await f.result.done;}assert.equal(path.dirname(dir),path.resolve(os.tmpdir()));fs.rmSync(dir,{recursive:true,force:true});});
 return f;
}
const options=extra=>({lockName:lockName(),resolveNative:file=>file,resolveAsync:async file=>file,selectPort:freePort,nativeOptions:()=>({}),busyInterval:1,readyInterval:10,readyTimeout:1000,holdTimeout:1000,stopTimeout:100,runtimeInterval:0,retryDelays:[20,40,60],stableDelay:10,handleSignals:false,...extra});
function natives({delayListen=0,neverListen=false,onSpawn}={}){
 const children=[];
 const spawnNative=(file,args)=>{
  const child=new EventEmitter(),port=Number(new URL(args.at(-1)).port),sockets=new Set();child.sockets=sockets;
  child.pid=40000+children.length;child.file=file;child.args=args;child.port=port;child.stderr=new EventEmitter();child.exitCode=null;child.kills=0;
  const server=child.server=net.createServer(socket=>{
   sockets.add(socket);socket.on('error',()=>{});socket.on('close',()=>sockets.delete(socket));
   socket.once('data',chunk=>{
    if(chunk.toString('ascii').startsWith('GET /readyz '))socket.end('HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK');
    else if(chunk.toString()==='WHO')socket.end(String(children.indexOf(child)+1));
    else{socket.pause();socket.unshift(chunk);socket.pipe(socket);socket.resume();}
   });
  });
  let ended=false,timer;
  child.exit=()=>{if(ended)return;ended=true;clearTimeout(timer);for(const socket of sockets)socket.destroy();
   const finish=()=>{child.exitCode=1;child.emit('exit',1,null);};if(server.listening)server.close(finish);else finish();};
  child.kill=()=>{child.kills++;child.exit();return true;};
  server.on('error',()=>child.exit());children.push(child);onSpawn?.(child,children.length);
  if(!(typeof neverListen==='function'?neverListen(children.length):neverListen))timer=setTimeout(()=>{if(!ended)server.listen({port,host:'127.0.0.1',exclusive:true});},delayListen);
  return child;
 };
 return {children,spawnNative};
}
async function start(t,f,opts){const result=await main(f.dir,options(opts));f.result=result;return result;}
function connect(port){const socket=net.connect({host:'127.0.0.1',port});socket.on('error',()=>{});return socket;}
async function identify(port){const socket=connect(port),chunks=[];socket.on('data',b=>chunks.push(b));await once(socket,'connect');socket.write('WHO');await once(socket,'end');socket.destroy();return Buffer.concat(chunks).toString();}

test('new clients during a native restart reach the new generation without refusal',async t=>{
 const f=await fixture(t),fake=natives({delayListen:80});await start(t,f,{spawnNative:fake.spawnNative});
 await until(()=>f.health().state==='ready');assert.equal(f.health().mode,'relay');assert.equal(f.health().relayPid,process.pid);assert.equal(f.health().nativePid,fake.children[0].pid);
 assert.equal(f.health().nativePort,fake.children[0].port);assert.notEqual(f.health().nativePort,f.port);assert.equal(await identify(f.port),'1');
 fake.children[0].exit();await until(()=>f.health().state==='reconnecting');
 const client=connect(f.port),data=[];client.on('data',b=>data.push(b));await once(client,'connect');client.write('WHO');
 await once(client,'end');assert.equal(Buffer.concat(data).toString(),'2');client.destroy();
});
for(const removed of ['executable','sidecar'])test(`restart resolves a new runtime after the ${removed} is removed`,async t=>{
 const f=await fixture(t),fake=natives(),next=path.join(f.dir,'new','codex.exe');let resolutions=0;
 await start(t,f,{spawnNative:fake.spawnNative,resolveAsync:async()=>{resolutions++;return next;}});await until(()=>f.health().state==='ready');
 fs.unlinkSync(path.join(f.dir,'old',removed==='executable'?'codex.exe':'codex-code-mode-host.exe'));fake.children[0].exit();
 await until(()=>fake.children.length===2&&f.health().state==='ready');assert.equal(fake.children[1].file,next);assert.ok(resolutions>0);
});
test('resolver failures continue retrying with no native generation',async t=>{
 const f=await fixture(t),fake=natives(),next=path.join(f.dir,'new','codex.exe');let attempts=0;
 await start(t,f,{spawnNative:fake.spawnNative,resolveNative:()=>{throw Error('fixture');},runtimeInterval:15,resolveAsync:async()=>{if(++attempts<4)throw Error('fixture');return next;}});
 await until(()=>fake.children.length===1&&f.health().state==='ready');assert.ok(attempts>=4);assert.equal(fake.children[0].file,next);
});
test('resolver failures after sidecar pruning never respawn the incomplete runtime',async t=>{
 const f=await fixture(t),fake=natives(),next=path.join(f.dir,'new','codex.exe');let attempts=0;
 await start(t,f,{spawnNative:fake.spawnNative,runtimeInterval:15,resolveAsync:async()=>{if(++attempts<4)throw Error('fixture');return next;}});
 await until(()=>f.health().state==='ready');fs.unlinkSync(path.join(f.dir,'old','codex-code-mode-host.exe'));fake.children[0].exit();
 await until(()=>fake.children.length===2&&f.health().state==='ready');assert.ok(attempts>=4);assert.equal(fake.children[1].file,next);
});
test('a spawn failure clears the runtime and re-resolves before retrying',async t=>{
 const f=await fixture(t),fake=natives(),next=path.join(f.dir,'new','codex.exe');let attempts=0,resolutions=0;
 const spawnNative=(...args)=>{if(++attempts===1){const child=new EventEmitter();child.stderr=new EventEmitter();process.nextTick(()=>child.emit('error',Error('fixture')));return child;}return fake.spawnNative(...args);};
 await start(t,f,{spawnNative,resolveAsync:async()=>{resolutions++;return next;}});await until(()=>fake.children.length===1&&f.health().state==='ready');
 assert.equal(resolutions,1);assert.equal(fake.children[0].file,next);
});
test('never-ready timeouts double and reset after a ready generation',async t=>{
 const f=await fixture(t),born=[],stopped=[],fake=natives({neverListen:n=>[1,2,4].includes(n),onSpawn:(child,n)=>{
  born[n]=Date.now();const kill=child.kill;child.kill=()=>{stopped[n]=Date.now();return kill();};
 }});
 await start(t,f,{spawnNative:fake.spawnNative,readyTimeout:80,readyTimeoutMax:320,readyInterval:5,retryDelays:[10]});
 await until(()=>fake.children.length===3&&f.health().state==='ready');fake.children[2].exit();await until(()=>fake.children.length===5&&f.health().state==='ready');
 const first=stopped[1]-born[1],second=stopped[2]-born[2],reset=stopped[4]-born[4];
 assert.ok(first>=65&&second>=140,`${first},${second},${reset}`);assert.ok(second>first*1.4&&reset<second*0.8,`${first},${second},${reset}`);
});
test('a request half-close can receive a delayed response',async t=>{
 const f=await fixture(t),sockets=new Set();let server;
 const spawnNative=(file,args)=>{const child=new EventEmitter();child.pid=41000;child.stderr=new EventEmitter();
  server=net.createServer({allowHalfOpen:true},socket=>{sockets.add(socket);socket.on('error',()=>{});socket.on('close',()=>sockets.delete(socket));socket.resume();socket.on('end',()=>setTimeout(()=>socket.end('delayed response'),60));});
  server.listen({host:'127.0.0.1',port:Number(new URL(args.at(-1)).port),exclusive:true});child.kill=()=>{for(const socket of sockets)socket.destroy();server.close(()=>child.emit('exit',0));return true;};return child;};
 await start(t,f,{spawnNative,probe:async endpoint=>endpoint===f.endpoint?'free':'ready'});await until(()=>server?.listening&&f.health().state==='ready');
 const socket=net.connect({host:'127.0.0.1',port:f.port,allowHalfOpen:true}),chunks=[];socket.on('error',()=>{});socket.on('data',b=>chunks.push(b));await once(socket,'connect');
 const ended=once(socket,'end');socket.end('request');await ended;assert.equal(Buffer.concat(chunks).toString(),'delayed response');socket.destroy();
});
test('a failed upstream before admission preserves initial bytes for the next generation',async t=>{
 const f=await fixture(t),fake=natives({neverListen:n=>n===1});let attempts=0;
 const connectUpstream=opts=>{const socket=net.connect(opts);if(++attempts===1)socket.once('error',()=>fake.children[0].exit());return socket;};
 await start(t,f,{spawnNative:fake.spawnNative,connectUpstream,probe:async endpoint=>endpoint===f.endpoint?'free':'ready',admissionRetry:15});
 await until(()=>f.health().state==='ready');const socket=connect(f.port),chunks=[];socket.on('data',b=>chunks.push(b));await once(socket,'connect');
 const end=new Promise(resolve=>socket.once('end',resolve));socket.write('WHO');await end;assert.equal(Buffer.concat(chunks).toString(),'2');socket.destroy();
});
test('a slow reader receives a complete large response after a half-close',async t=>{
 const f=await fixture(t),payload=crypto.randomBytes(8*1024*1024),expected=crypto.createHash('sha256').update(payload).digest('hex'),sockets=new Set();let server,count=0,closedAt=-1;
 const spawnNative=(file,args)=>{const child=new EventEmitter();child.pid=41000;child.stderr=new EventEmitter();server=net.createServer({allowHalfOpen:true},socket=>{
  sockets.add(socket);socket.on('error',()=>{});socket.on('close',()=>{sockets.delete(socket);closedAt=count;});socket.resume();socket.on('end',()=>socket.end(payload));
 });server.listen({host:'127.0.0.1',port:Number(new URL(args.at(-1)).port),exclusive:true});child.kill=()=>{for(const socket of sockets)socket.destroy();server.close(()=>child.emit('exit',0));return true;};return child;};
 await start(t,f,{spawnNative,probe:async endpoint=>endpoint===f.endpoint?'free':'ready'});await until(()=>server?.listening&&f.health().state==='ready');
 const socket=net.connect({host:'127.0.0.1',port:f.port,allowHalfOpen:true}),hash=crypto.createHash('sha256');socket.on('error',()=>{});
 socket.on('data',chunk=>{count+=chunk.length;hash.update(chunk);socket.pause();setTimeout(()=>socket.resume(),1);});socket.pause();await once(socket,'connect');
 const ended=once(socket,'end');socket.end('request');await delay(120);socket.resume();await ended;await until(()=>closedAt>=0);assert.ok(closedAt<payload.length);assert.equal(count,payload.length);assert.equal(hash.digest('hex'),expected);socket.destroy();
});
test('a half-closed pair is bounded and both relay sockets disable Nagle',async t=>{
 const f=await fixture(t),sockets=new Set(),flags=[];let server;
 const spawnNative=(file,args)=>{const child=new EventEmitter();child.pid=41000;child.stderr=new EventEmitter();server=net.createServer({allowHalfOpen:true},socket=>{sockets.add(socket);socket.on('error',()=>{});socket.on('close',()=>sockets.delete(socket));socket.resume();});
  server.listen({host:'127.0.0.1',port:Number(new URL(args.at(-1)).port),exclusive:true});child.kill=()=>{for(const socket of sockets)socket.destroy();server.close(()=>child.emit('exit',0));return true;};return child;};
 const wrap=(socket,side)=>{const original=socket.setNoDelay;socket.setNoDelay=function(value){flags.push([side,value]);return original.call(this,value);};return socket;};
 await start(t,f,{spawnNative,drainTimeout:80,probe:async endpoint=>endpoint===f.endpoint?'free':'ready',
  createRelay:handler=>net.createServer({pauseOnConnect:true,allowHalfOpen:true},socket=>handler(wrap(socket,'client'))),connectUpstream:opts=>wrap(net.connect(opts),'upstream')});
 await until(()=>server?.listening&&f.health().state==='ready');const socket=net.connect({host:'127.0.0.1',port:f.port,allowHalfOpen:true});socket.on('error',()=>{});await once(socket,'connect');
 const closed=new Promise(resolve=>socket.once('close',resolve));socket.end('request');await closed;assert.deepEqual(flags,[['client',true],['upstream',true]]);
});

test('held clients time out while a native is not ready',async t=>{
 const f=await fixture(t),fake=natives({neverListen:true});await start(t,f,{spawnNative:fake.spawnNative,holdTimeout:70,readyTimeout:1000});
 const socket=connect(f.port);await once(socket,'connect');const closed=new Promise(resolve=>socket.once('close',resolve));socket.write('waiting');await closed;
 assert.equal(fake.children.length,1);assert.notEqual(f.health().state,'ready');
});
test('native exit closes existing relayed sockets',async t=>{
 const f=await fixture(t),fake=natives();await start(t,f,{spawnNative:fake.spawnNative});await until(()=>f.health().state==='ready');
 const socket=connect(f.port);await once(socket,'connect');const data=once(socket,'data');socket.write('echo');assert.equal((await data)[0].toString(),'echo');
 const closed=once(socket,'close');fake.children[0].exit();await closed;await until(()=>fake.children.length===2);
});
test('/readyz and a 16 MB stream pass through the relay with backpressure',async t=>{
 const f=await fixture(t),fake=natives();await start(t,f,{spawnNative:fake.spawnNative});await until(()=>f.health().state==='ready');
 const status=await new Promise((resolve,reject)=>http.get({host:'127.0.0.1',port:f.port,path:'/readyz',agent:false},res=>{res.resume();res.on('end',()=>resolve(res.statusCode));}).on('error',reject));
 assert.equal(status,200);
 const payload=crypto.randomBytes(16*1024*1024),expected=crypto.createHash('sha256').update(payload).digest('hex'),socket=connect(f.port);
 await once(socket,'connect');let count=0;const hash=crypto.createHash('sha256');
 const received=new Promise((resolve,reject)=>{socket.on('data',chunk=>{count+=chunk.length;hash.update(chunk);socket.pause();setImmediate(()=>socket.resume());if(count===payload.length)resolve();});socket.once('error',reject);socket.once('close',()=>{if(count!==payload.length)reject(Error('truncated stream'));});});
 for(let i=0;i<payload.length;i+=65536)if(!socket.write(payload.subarray(i,i+65536)))await once(socket,'drain');
 await received;assert.equal(count,payload.length);assert.equal(hash.digest('hex'),expected);socket.destroy();
});
test('a real WebSocket handshake and masked message pass through the relay',async t=>{
 const f=await fixture(t),children=[];
 const spawnNative=(file,args)=>{const child=new EventEmitter(),port=Number(new URL(args.at(-1)).port),sockets=new Set();child.pid=41000;child.stderr=new EventEmitter();
  const server=http.createServer((req,res)=>res.end('ready'));server.on('connection',socket=>{sockets.add(socket);socket.on('error',()=>{});socket.on('close',()=>sockets.delete(socket));});
  server.on('upgrade',(req,socket)=>{const accept=crypto.createHash('sha1').update(req.headers['sec-websocket-key']+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
   socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: '+accept+'\r\n\r\n');
   socket.once('data',frame=>{const length=frame[1]&127,mask=frame.subarray(2,6),payload=Buffer.from(frame.subarray(6,6+length));for(let i=0;i<payload.length;i++)payload[i]^=mask[i%4];socket.write(Buffer.concat([Buffer.from([129,payload.length]),payload]));});
  });
  child.kill=()=>{for(const socket of sockets)socket.destroy();server.close(()=>child.emit('exit',0));return true;};server.listen({host:'127.0.0.1',port,exclusive:true});children.push(child);return child;};
 await start(t,f,{spawnNative});await until(()=>f.health().state==='ready');const socket=new WebSocket(f.endpoint);
 await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
 const message=new Promise(resolve=>socket.addEventListener('message',event=>resolve(event.data),{once:true}));socket.send('fixture');assert.equal(await message,'fixture');socket.close();
});
test('never-ready natives are stopped and replaced',async t=>{
 const f=await fixture(t),fake=natives({neverListen:n=>n===1});await start(t,f,{spawnNative:fake.spawnNative,readyTimeout:80});
 await until(()=>fake.children.length===2&&f.health().state==='ready');assert.equal(fake.children[0].kills,1);assert.notEqual(fake.children[0].port,fake.children[1].port);
});
test('an internal bind collision selects a fresh port for the next generation',async t=>{
 const f=await fixture(t),occupied=net.createServer(),busy=await listen(occupied),fake=natives();t.after(()=>occupied.close());let calls=0;
 await start(t,f,{spawnNative:fake.spawnNative,selectPort:()=>++calls<=2?busy:freePort()});
 await until(()=>fake.children.length>=2&&f.health().state==='ready');assert.equal(fake.children[0].port,busy);assert.notEqual(fake.children[1].port,busy);assert.ok(calls>=3);
});
test('failed kill escalates and never starts a replacement before confirmed exit',async t=>{
 const f=await fixture(t),fake=natives({neverListen:n=>n===1,onSpawn:(child,n)=>{if(n===1)child.kill=()=>{child.kills++;return false;};}}),escalations=[];
 await start(t,f,{spawnNative:fake.spawnNative,platform:'win32',killNativeTree:async pid=>escalations.push(pid),readyTimeout:60,stopTimeout:40,holdTimeout:100});
 await until(()=>f.health().error==='NATIVE_STOP_FAILED');assert.equal(fake.children.length,1);assert.deepEqual(escalations,[fake.children[0].pid]);
 const socket=connect(f.port);await once(socket,'connect');await once(socket,'close');assert.equal(fake.children.length,1);
 fake.children[0].exit();await until(()=>fake.children.length===2&&f.health().state==='ready');
});
test('spawn failure with no pid releases ownership and retries',async t=>{
 const f=await fixture(t),fake=natives();let attempts=0,escalations=0;
 const spawnNative=(...args)=>{if(++attempts===1){const child=new EventEmitter();child.stderr=new EventEmitter();process.nextTick(()=>child.emit('error',Error('fixture')));return child;}return fake.spawnNative(...args);};
 await start(t,f,{spawnNative,platform:'win32',killNativeTree:()=>{escalations++;}});
 await until(()=>attempts===2&&f.health().state==='ready');assert.equal(escalations,0);assert.equal(f.health().nativePid,fake.children[0].pid);
});
test('stale readiness results cannot make an old generation ready',async t=>{
 const f=await fixture(t),fake=natives();let release,oldPort,requested=false,publicProbes=0;
 const probe=endpoint=>{if(endpoint===f.endpoint){publicProbes++;return Promise.resolve('free');}if(Number(new URL(endpoint).port)===oldPort&&!requested){requested=true;return new Promise(resolve=>{release=resolve;});}return probeShared(endpoint);};
 await start(t,f,{spawnNative:fake.spawnNative,probe});await until(()=>fake.children.length===1);oldPort=fake.children[0].port;await until(()=>requested);
 fake.children[0].exit();await until(()=>fake.children.length===2&&f.health().state==='ready');const before=f.health();release('ready');await delay(30);
 assert.equal(f.health().generation,before.generation);assert.equal(f.health().nativePid,before.nativePid);assert.equal(f.health().readyAt,before.readyAt);assert.equal(publicProbes,1);
});

test('a missing sidecar causes an urgent stop even with clients and activity',async t=>{
 const f=await fixture(t),fake=natives();let updated=false;
 await start(t,f,{spawnNative:fake.spawnNative,runtimeInterval:30,resolveAsync:async()=>updated?path.join(f.dir,'new','codex.exe'):path.join(f.dir,'old','codex.exe'),checkPhone:()=>true,checkNative:async()=>true});
 await until(()=>f.health().state==='ready');const socket=connect(f.port);await once(socket,'connect');const response=once(socket,'data');socket.write('echo');await response;
 const closed=once(socket,'close');fs.unlinkSync(path.join(f.dir,'old','codex-code-mode-host.exe'));updated=true;await closed;await until(()=>fake.children.length===2&&f.health().state==='ready');
 assert.equal(fake.children[0].kills,1);assert.equal(fake.children[1].file,path.join(f.dir,'new','codex.exe'));
});
test('a complete runtime waits for clients, then switches on the last close',async t=>{
 const f=await fixture(t),fake=natives();let updated=false,checks=0;
 const old=path.join(f.dir,'old','codex.exe'),next=path.join(f.dir,'new','codex.exe');
 await start(t,f,{spawnNative:fake.spawnNative,runtimeInterval:25,resolveAsync:async()=>updated?next:old,checkNative:async()=>{checks++;return false;}});
 await until(()=>f.health().state==='ready');const socket=connect(f.port);await once(socket,'connect');const response=once(socket,'data');socket.write('echo');await response;
 updated=true;await until(()=>f.health().pendingExecutable===next);assert.equal(fake.children.length,1);assert.equal(fake.children[0].kills,0);
 socket.destroy();await until(()=>fake.children.length===2&&f.health().state==='ready');assert.ok(checks>0);assert.equal(fake.children[1].file,next);
});
for(const [name,journal] of [['active','{"task":{"status":"running"}}'],['unreadable','invalid json'],['missing',null]])test(`a ${name} phone journal keeps a complete runtime pending`,async t=>{
 const f=await fixture(t),fake=natives(),next=path.join(f.dir,'new','codex.exe');let checks=0;
 const journalFile=path.join(f.dir,'remote-journal.json');if(journal===null)fs.unlinkSync(journalFile);else fs.writeFileSync(journalFile,journal);
 await start(t,f,{spawnNative:fake.spawnNative,runtimeInterval:20,resolveAsync:async()=>next,checkNative:async()=>{checks++;return false;}});
 await until(()=>f.health().pendingExecutable===next);await delay(100);assert.equal(fake.children.length,1);assert.equal(checks,0);
});
for(const [name,checkNative] of [['active',async()=>true],['unknown',async()=>{throw Error('fixture');}],['indeterminate',async()=>undefined]])test(`${name} native activity keeps a complete runtime pending`,async t=>{
 const f=await fixture(t),fake=natives(),next=path.join(f.dir,'new','codex.exe');let checks=0;
 await start(t,f,{spawnNative:fake.spawnNative,runtimeInterval:20,resolveAsync:async()=>next,checkNative:async endpoint=>{checks++;return checkNative(endpoint);}});
 await until(()=>f.health().pendingExecutable===next&&checks>0);await delay(80);assert.equal(fake.children.length,1);assert.equal(fake.children[0].kills,0);
});
test('native activity checks every loaded page and treats unknown statuses as active',async()=>{
 let status='idle',calls=[];const endpoint=`ws://127.0.0.1:${await freePort()}/`;
 class Client{setHandlers(){}async connect(){}close(){}async call(method,params){
  calls.push([method,params]);if(method==='thread/loaded/list')return params.cursor?{data:['second'],nextCursor:null}:{data:['first'],nextCursor:'next'};
  if(method==='thread/read')return {thread:{id:params.threadId,status:{type:params.threadId==='second'?status:'idle'}}};throw Error('fixture');
 }}
 assert.equal(await nativeActive(endpoint,500,Client),false);assert.equal(calls.filter(c=>c[0]==='thread/read').length,2);
 for(const value of ['active','unknown']){status=value;calls=[];assert.equal(await nativeActive(endpoint,500,Client),true);}
});
test('native activity uses real WebSockets, closes on timeout, and never answers server requests',async t=>{
 const frame=value=>{const body=Buffer.from(JSON.stringify(value));return Buffer.concat([body.length<126?Buffer.from([129,body.length]):Buffer.from([129,126,body.length>>8,body.length&255]),body]);};
 for(const mode of ['idle','active','timeout','malformed','invalid-json']){
  const sockets=new Set(),responses=[];let requests=0;
  const server=http.createServer();server.on('upgrade',(req,socket)=>{
   sockets.add(socket);socket.on('error',()=>{});socket.on('close',()=>sockets.delete(socket));
   const accept=crypto.createHash('sha1').update(req.headers['sec-websocket-key']+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
   socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: '+accept+'\r\n\r\n');let buffer=Buffer.alloc(0);
   socket.on('data',chunk=>{buffer=Buffer.concat([buffer,chunk]);while(buffer.length>=2){
    const opcode=buffer[0]&15;let length=buffer[1]&127,offset=2;if(length===126){if(buffer.length<4)return;length=buffer.readUInt16BE(2);offset=4;}
    if(length===127){socket.destroy();return;}const masked=!!(buffer[1]&128);if(!masked){socket.destroy();return;}if(buffer.length<offset+4+length)return;
    const mask=buffer.subarray(offset,offset+4),body=Buffer.from(buffer.subarray(offset+4,offset+4+length));buffer=buffer.subarray(offset+4+length);
    for(let i=0;i<body.length;i++)body[i]^=mask[i%4];if(opcode===8){socket.end(Buffer.from([136,0]));return;}if(opcode!==1)continue;
    let row;try{row=JSON.parse(body.toString());}catch{socket.destroy();return;}if(row.id===9000&&!row.method)responses.push(row);
    if(row.method==='initialize'){
     if(mode==='timeout')continue;
     socket.write(Buffer.concat([frame({id:row.id,result:{}}),frame({id:9000,method:'item/commandExecution/requestApproval',params:{}})]));requests++;
    }else if(row.method==='thread/loaded/list')socket.write(mode==='invalid-json'?Buffer.from([129,1,123]):frame({id:row.id,result:mode==='malformed'?{data:'bad',nextCursor:null}:{data:['fixture-thread'],nextCursor:null}}));
    else if(row.method==='thread/read')socket.write(frame({id:row.id,result:{thread:{id:'fixture-thread',status:{type:mode}}}}));
   }});
  });
  const port=await listen(server);t.after(()=>{for(const socket of sockets)socket.destroy();if(server.listening)server.close();});
  const born=Date.now(),active=await nativeActive(`ws://127.0.0.1:${port}/`,mode==='timeout'?150:1000);
  assert.equal(active,mode!=='idle',mode);if(mode==='timeout')assert.ok(Date.now()-born<1000);
  await until(()=>sockets.size===0,2000);assert.deepEqual(responses,[]);if(mode!=='timeout')assert.equal(requests,1);
  await new Promise(resolve=>server.close(resolve));
 }
});
test('slow runtime resolution does not block an existing relay',async t=>{
 const f=await fixture(t),fake=natives();let release,started=false;
 await start(t,f,{spawnNative:fake.spawnNative,runtimeInterval:20,resolveAsync:()=>{started=true;return new Promise(resolve=>{release=resolve;});}});
 await until(()=>f.health().state==='ready');const socket=connect(f.port);await once(socket,'connect');await until(()=>started);
 const response=once(socket,'data');socket.write('still flowing');assert.equal((await response)[0].toString(),'still flowing');
 release(path.join(f.dir,'old','codex.exe'));socket.destroy();
});
test('a stale runtime resolution cannot switch a newer generation',async t=>{
 const f=await fixture(t),fake=natives(),old=path.join(f.dir,'old','codex.exe'),next=path.join(f.dir,'new','codex.exe');let release,started=false;
 await start(t,f,{spawnNative:fake.spawnNative,runtimeInterval:20,resolveAsync:()=>{if(!started){started=true;return new Promise(resolve=>{release=resolve;});}return Promise.resolve(old);}});
 await until(()=>f.health().state==='ready'&&started);fake.children[0].exit();await until(()=>fake.children.length===2&&f.health().state==='ready');
 release(next);await delay(80);assert.equal(fake.children.length,2);assert.equal(fake.children[1].kills,0);assert.equal(f.health().pendingExecutable,null);
});
test('a client arriving during an activity check prevents a pending switch',async t=>{
 const f=await fixture(t),fake=natives(),next=path.join(f.dir,'new','codex.exe');let release,checking=false;
 await start(t,f,{spawnNative:fake.spawnNative,runtimeInterval:20,resolveAsync:async()=>next,checkNative:()=>{checking=true;return new Promise(resolve=>{release=resolve;});}});
 await until(()=>f.health().state==='ready'&&checking);const socket=connect(f.port);await once(socket,'connect');const response=once(socket,'data');socket.write('echo');await response;
 release(false);await delay(60);assert.equal(fake.children.length,1);assert.equal(fake.children[0].kills,0);socket.destroy();
});

test('a ready public listener is reused with its earlier PIDs',async t=>{
 const f=await fixture(t,{supervisorPid:13752,nativePid:17860}),server=http.createServer((req,res)=>{res.statusCode=req.url==='/readyz'?200:404;res.end();});
 await new Promise(resolve=>server.listen({host:'127.0.0.1',port:f.port,exclusive:true},resolve));t.after(()=>server.close());
 const fake=natives(),result=await start(t,f,{spawnNative:fake.spawnNative});assert.equal(result.state,'reused');assert.equal(fake.children.length,0);
 assert.equal(f.health().mode,'reused');assert.equal(f.health().supervisorPid,13752);assert.equal(f.health().nativePid,17860);
 for(const key of ['relayPid','nativePort','nativeExecutable','generation','clients','heldClients','pendingExecutable','readyAt','updatedAt'])assert.ok(Object.hasOwn(f.health(),key),key);
});
test('an occupied public listener is blocked and never stopped',async t=>{
 const f=await fixture(t),server=http.createServer((req,res)=>{res.statusCode=503;res.end();});
 await new Promise(resolve=>server.listen({host:'127.0.0.1',port:f.port,exclusive:true},resolve));t.after(()=>server.close());
 const fake=natives(),result=await start(t,f,{spawnNative:fake.spawnNative,busyAttempts:2});assert.equal(result.state,'blocked');assert.equal(fake.children.length,0);
 assert.equal(f.health().error,'PORT_IN_USE_NOT_READY');assert.equal(server.listening,true);
});
test('a busy listener becoming ready is waited for and reused',async t=>{
 const f=await fixture(t),fake=natives();let attempts=0;
 const result=await start(t,f,{spawnNative:fake.spawnNative,probe:async()=>++attempts<3?'busy':'ready'});
 assert.equal(result.state,'reused');assert.equal(attempts,3);assert.equal(fake.children.length,0);
});
test('concurrent starts elect one supervisor',async t=>{
 const f=await fixture(t),fake=natives(),name=lockName();
 const [a,b]=await Promise.all([main(f.dir,options({lockName:name,spawnNative:fake.spawnNative})),main(f.dir,options({lockName:name,spawnNative:fake.spawnNative}))]);
 f.result=a.close?a:b;assert.deepEqual([a.state,b.state].sort(),['supervised','supervising']);await until(()=>fake.children.length===1);
});
test('the lease is released when its owner closes',async()=>{
 const name=lockName(),first=await acquireSupervisor(name);assert.ok(first);assert.equal(await acquireSupervisor(name),null);
 await new Promise(resolve=>first.close(resolve));const second=await acquireSupervisor(name);assert.ok(second);await new Promise(resolve=>second.close(resolve));
});
test('probe distinguishes ready, not-ready, silent and closed listeners',async t=>{
 const ready=http.createServer((req,res)=>{res.statusCode=200;res.end();}),busy=http.createServer((req,res)=>{res.statusCode=503;res.end();}),sockets=new Set();
 const silent=net.createServer(socket=>{sockets.add(socket);socket.on('close',()=>sockets.delete(socket));});
 const ports=[await listen(ready),await listen(busy),await listen(silent),await freePort()];
 t.after(()=>{for(const socket of sockets)socket.destroy();ready.close();busy.close();silent.close();});
 assert.equal(await probeShared(`ws://127.0.0.1:${ports[0]}/`),'ready');assert.equal(await probeShared(`ws://127.0.0.1:${ports[1]}/`),'busy');
 assert.equal(await probeShared(`ws://127.0.0.1:${ports[2]}/`,50),'busy');assert.equal(await probeShared(`ws://127.0.0.1:${ports[3]}/`),'free');
});
for(const kind of ['lease','listener'])test(`${kind} failure stops the owned native and fails closed`,async t=>{
 const f=await fixture(t),fake=natives();let lease,relay;
 const result=await start(t,f,{spawnNative:fake.spawnNative,acquireLease:async name=>lease=await acquireSupervisor(name),createRelay:handler=>relay=net.createServer({pauseOnConnect:true},handler)});
 await until(()=>f.health().state==='ready');(kind==='lease'?lease:relay).emit('error',Error('fixture'));
 assert.equal((await result.done).state,'blocked');assert.equal(fake.children[0].kills,1);assert.equal(f.health().error,kind==='lease'?'LEASE_LOST':'RELAY_ERROR');
});
for(const kind of ['lease','listener'])test(`${kind} failure upgrades a graceful shutdown to blocked`,async t=>{
 const f=await fixture(t),fake=natives({onSpawn:child=>{child.kill=()=>{child.kills++;return false;};}});let lease,relay;
 const result=await start(t,f,{spawnNative:fake.spawnNative,acquireLease:async name=>lease=await acquireSupervisor(name),createRelay:handler=>relay=net.createServer({pauseOnConnect:true,allowHalfOpen:true},handler)});
 await until(()=>f.health().state==='ready');result.close();assert.equal(fake.children[0].kills,1);
 (kind==='lease'?lease:relay).emit('error',Error('fixture'));assert.equal(f.health().state,'blocked');assert.equal(f.health().error,kind==='lease'?'LEASE_LOST':'RELAY_ERROR');
 fake.children[0].exit();assert.equal((await result.done).state,'blocked');
});
test('unexpected lease close during graceful shutdown upgrades the outcome',async t=>{
 const f=await fixture(t),fake=natives({onSpawn:child=>{child.kill=()=>false;}});let lease;
 const result=await start(t,f,{spawnNative:fake.spawnNative,acquireLease:async name=>lease=await acquireSupervisor(name)});
 await until(()=>f.health().state==='ready');result.close();lease.close();await until(()=>f.health().error==='LEASE_LOST');
 fake.children[0].exit();assert.equal((await result.done).state,'blocked');
});
test('unexpected listener close racing graceful shutdown upgrades the outcome',async t=>{
 const f=await fixture(t),fake=natives({onSpawn:child=>{child.kill=()=>false;}});let relay;
 const result=await start(t,f,{spawnNative:fake.spawnNative,createRelay:handler=>relay=net.createServer({pauseOnConnect:true,allowHalfOpen:true},handler)});
 await until(()=>f.health().state==='ready');relay.close();result.close();await until(()=>f.health().error==='RELAY_ERROR');
 fake.children[0].exit();assert.equal((await result.done).state,'blocked');
});
test('lease loss keeps ownership while the native has not confirmed exit',async t=>{
 const f=await fixture(t),fake=natives({onSpawn:child=>{child.kill=()=>false;}}),name=lockName();let lease;
 const result=await start(t,f,{lockName:name,spawnNative:fake.spawnNative,platform:'win32',stopTimeout:30,killNativeTree:async()=>{},acquireLease:async n=>lease=await acquireSupervisor(n)});
 await until(()=>f.health().state==='ready');lease.emit('error',Error('fixture'));await until(()=>f.health().error==='NATIVE_STOP_FAILED');
 assert.equal(await acquireSupervisor(name),null);assert.equal(fake.children.length,1);fake.children[0].exit();assert.equal((await result.done).state,'blocked');
 const next=await acquireSupervisor(name);assert.ok(next);await new Promise(resolve=>next.close(resolve));
});
test('a relayed socket error is contained',async t=>{
 const f=await fixture(t),fake=natives();await start(t,f,{spawnNative:fake.spawnNative});await until(()=>f.health().state==='ready');
 const socket=connect(f.port);await once(socket,'connect');const response=once(socket,'data');socket.write('echo');await response;
 const closed=once(socket,'close');for(const peer of fake.children[0].sockets)peer.destroy(Error('fixture'));await closed;
 assert.equal(await identify(f.port),'1');assert.equal(f.health().state,'ready');
});
test('an uncaught supervisor exception stops its native and exits nonzero',async t=>{
 const f=await fixture(t),port=await freePort(),name=lockName();
 const script=`const {run}=require(process.argv[1]),{EventEmitter}=require('node:events');let count=0;
 run(process.argv[2],{lockName:process.argv[3],resolveNative:f=>f,selectPort:async()=>Number(process.argv[4]),nativeOptions:()=>({}),handleSignals:false,runtimeInterval:0,readyInterval:5,
 probe:async()=>++count===1?'free':'ready',spawnNative:()=>{const child=new EventEmitter();child.pid=43210;child.stderr=new EventEmitter();child.kill=()=>{process.stdout.write('STOPPED');process.nextTick(()=>child.emit('exit',1));return true;};return child;}});
 setTimeout(()=>{throw Error('fixture');},150);`;
 const child=spawn(process.execPath,['-e',script,path.join(assets,'shared-server.cjs'),f.dir,name,String(port)],{stdio:['ignore','pipe','pipe'],windowsHide:true});
 let output='';child.stdout.on('data',b=>output+=b);const [code]=await once(child,'exit');assert.equal(code,1);assert.ok(output.includes('STOPPED'));assert.equal(f.health().error,'UNCAUGHT_EXCEPTION');
});
test('an uncaught exception after SIGTERM exits nonzero even during graceful shutdown',async t=>{
 const f=await fixture(t),port=await freePort(),name=lockName();
 const script=`const {run}=require(process.argv[1]),{EventEmitter}=require('node:events');let count=0;
 run(process.argv[2],{lockName:process.argv[3],resolveNative:f=>f,selectPort:async()=>Number(process.argv[4]),nativeOptions:()=>({}),runtimeInterval:0,readyInterval:5,
 probe:async()=>++count===1?'free':'ready',spawnNative:()=>{const child=new EventEmitter();child.pid=43210;child.stderr=new EventEmitter();child.kill=()=>{process.stdout.write('STOPPING');setTimeout(()=>child.emit('exit',1),100);return true;};return child;}});
 setTimeout(()=>{process.emit('SIGTERM');setTimeout(()=>{throw Error('fixture');},20);},150);`;
 const child=spawn(process.execPath,['-e',script,path.join(assets,'shared-server.cjs'),f.dir,name,String(port)],{stdio:['ignore','pipe','pipe'],windowsHide:true});
 let output='';child.stdout.on('data',b=>output+=b);const [code]=await once(child,'exit');assert.equal(code,1);assert.ok(output.includes('STOPPING'));assert.equal(f.health().error,'UNCAUGHT_EXCEPTION');
});
test('hard-killing the supervisor also ends its real direct native child on Windows',async t=>{
 if(process.platform!=='win32'){t.skip('Windows-only child lifetime');return;}
 const f=await fixture(t),port=await freePort(),name=lockName();
 const native=`const net=require('node:net');const s=net.createServer(c=>{c.on('error',()=>{});c.once('data',()=>c.end('HTTP/1.1 200 OK\\r\\nContent-Length: 0\\r\\n\\r\\n'));});s.listen(Number(process.argv[1]),'127.0.0.1');setTimeout(()=>process.exit(0),8000);`;
 const script=`const {main}=require(process.argv[1]),{spawn}=require('node:child_process'),fs=require('node:fs'),path=require('node:path');
 const port=Number(process.argv[4]);main(process.argv[2],{lockName:process.argv[3],resolveNative:f=>f,selectPort:async()=>port,nativeOptions:()=>({}),
 spawnNative:()=>spawn(process.execPath,['-e',${JSON.stringify(native)},String(port)],{stdio:'ignore',windowsHide:true}),readyInterval:10,runtimeInterval:0,handleSignals:false})
 .then(()=>{const timer=setInterval(()=>{try{if(JSON.parse(fs.readFileSync(path.join(process.argv[2],'shared-server-health.json'))).state==='ready'){clearInterval(timer);process.stdout.write('READY');}}catch{}},10);});`;
 const supervisor=spawn(process.execPath,['-e',script,path.join(assets,'shared-server.cjs'),f.dir,name,String(port)],{stdio:['ignore','pipe','pipe'],windowsHide:true});
 let output='',diagnostic='';supervisor.stdout.on('data',b=>output+=b);supervisor.stderr.on('data',b=>diagnostic+=b);
 t.after(async()=>{if(supervisor.exitCode===null&&supervisor.signalCode===null){const exited=once(supervisor,'exit');supervisor.kill('SIGKILL');await exited;}await until(async()=>{const s=net.createServer();try{await new Promise((resolve,reject)=>{s.once('error',reject);s.listen({host:'127.0.0.1',port,exclusive:true},resolve);});await new Promise(resolve=>s.close(resolve));return true;}catch{return false;}},9000);});
 await until(()=>output.includes('READY')||supervisor.exitCode!==null,5000);assert.ok(output.includes('READY'),diagnostic);
 const exited=once(supervisor,'exit');supervisor.kill('SIGKILL');await exited;
 await until(async()=>{const s=net.createServer();try{await new Promise((resolve,reject)=>{s.once('error',reject);s.listen({host:'127.0.0.1',port,exclusive:true},resolve);});await new Promise(resolve=>s.close(resolve));return true;}catch{return false;}},2500);
});
test('PowerShell ownership checks accept only the expected relay and legacy fixtures',async t=>{
 if(process.platform!=='win32'){t.skip('Windows-specific ownership checks');return;}
 const file=path.join(assets,'Enable-SharedConversations.ps1').replace(/'/g,"''"),port=await freePort(),publicPort=await freePort();
 const script=`Set-StrictMode -Version Latest;$ErrorActionPreference='Stop';$tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile('${file}',[ref]$tokens,[ref]$errors);if($errors.Count){exit 10}
 foreach($name in @('Get-SharedProperty','Test-SharedSupervisor','Test-SharedNative','Test-SharedOwnership','Stop-SharedOwned')){$fn=$ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name},$true);if(-not $fn){exit 11};Invoke-Expression $fn.Extent.Text}
 function Get-CimInstance {param($Class,$Filter,$ErrorAction) return $script:rows[$Filter]}
 function Stop-Process {param([int]$Id,$ErrorAction) $script:stops+= $Id}
 $root=Join-Path ([IO.Path]::GetTempPath()) 'fixture-runtime';$exe=Join-Path $root 'codex.exe';$url='ws://127.0.0.1:${publicPort}/'
 $script:rows=@{'ProcessId=100'=[pscustomobject]@{ProcessId=100;Name='node.exe';CommandLine=('node.exe "'+(Join-Path $root 'shared-start-if-needed.cjs')+'"')};
 'ProcessId=200'=[pscustomobject]@{ProcessId=200;Name='codex.exe';ExecutablePath=$exe;CommandLine='codex.exe app-server --listen ws://127.0.0.1:${port}'}}
 $health=[pscustomobject]@{mode='relay';relayPid=100;supervisorPid=100;nativePid=200;nativePort=${port};nativeExecutable=$exe}
 $listeners=@([pscustomobject]@{OwningProcess=100;LocalAddress='127.0.0.1'})
 if(-not (Test-SharedOwnership $listeners $health $exe $url $root)){exit 1}
 $script:stops=@();Stop-SharedOwned $health ([pscustomobject]@{codexExecutable=$exe}) $url $root;if(($script:stops -join ',') -ne '100,200'){exit 7}
 if(Test-SharedOwnership $listeners ([pscustomobject]@{}) $exe $url $root){exit 8};$script:stops=@();Stop-SharedOwned ([pscustomobject]@{supervisorPid=100;nativePid=200;mode='relay'}) ([pscustomobject]@{}) $url $root;if(($script:stops -join ',') -ne '100'){exit 9}
 if(Test-SharedNative ([pscustomobject]@{}) ([pscustomobject]@{}) $exe $url $true){exit 14};if(Test-SharedSupervisor ([pscustomobject]@{}) $root){exit 15};if(Test-SharedOwnership @([pscustomobject]@{OwningProcess=100}) $health $exe $url $root){exit 16}
 $script:rows['ProcessId=100'].CommandLine=('node.exe "'+(Join-Path $root 'shared-server.cjs.bak')+'"');if(Test-SharedOwnership $listeners $health $exe $url $root){exit 2}
 $script:rows['ProcessId=100'].CommandLine=('node.exe "'+(Join-Path $root 'shared-server.cjs')+'"');$script:rows['ProcessId=200'].CommandLine='codex.exe --listen ws://127.0.0.1:${port}0';if(Test-SharedOwnership $listeners $health $exe $url $root){exit 3}
 $script:rows['ProcessId=200'].CommandLine='codex.exe --listen ws://127.0.0.1:${port}';$script:rows['ProcessId=200'].ExecutablePath=Join-Path $root 'other.exe';if(Test-SharedOwnership $listeners $health $exe $url $root){exit 6};$script:rows['ProcessId=200'].ExecutablePath=$exe
 $script:rows['ProcessId=200'].CommandLine='codex.exe --listen ws://127.0.0.1:${publicPort}';$health.mode='reused';$listeners[0].OwningProcess=200
 if(-not (Test-SharedOwnership $listeners $health $exe $url $root)){exit 4}
 $legacy=[pscustomobject]@{nativePid=200};if(-not (Test-SharedOwnership $listeners $legacy $exe $url $root)){exit 12};$script:stops=@();Stop-SharedOwned $legacy ([pscustomobject]@{codexExecutable=$exe}) $url $root;if(($script:stops -join ',') -ne '200'){exit 13}
 $listeners[0].LocalAddress='0.0.0.0';if(Test-SharedOwnership $listeners $health $exe $url $root){exit 5}`;
 const result=spawnSync('powershell.exe',['-NoProfile','-NonInteractive','-EncodedCommand',Buffer.from(script,'utf16le').toString('base64')],{encoding:'utf8',windowsHide:true,timeout:10000});
 assert.equal(result.status,0,result.stdout+result.stderr);
});

test('installer copy list and App export list ship every computer component',()=>{
 const install=fs.readFileSync(path.join(assets,'Install.ps1'),'utf8');
 const copied=[...install.match(/foreach \(\$name in @\(([^)]*)\)\)/)[1].matchAll(/'([^']+)'/g)].map(m=>m[1]);
 const kotlin=fs.readFileSync(path.join(__dirname,'../../app/src/main/java/com/codex/quota/ui/feature/settings/TaskNotificationSettingsPanel.kt'),'utf8');
 const exported=[...kotlin.match(/for \(name in listOf\(([^)]*)\)\)/)[1].matchAll(/"([^"]+)"/g)].map(m=>m[1]);
 const shipped=fs.readdirSync(assets).filter(name=>fs.statSync(path.join(assets,name)).isFile());
 assert.deepEqual([...exported].sort(),[...shipped].sort());
 for(const name of copied)assert.ok(exported.includes(name),name+' is installed but not exported');
 for(const name of shipped.filter(name=>name.endsWith('.cjs')&&!['notify.cjs','task-content.cjs'].includes(name)))assert.ok(copied.includes(name),name+' is exported but never installed');
 for(const name of shipped.filter(name=>name.endsWith('.cjs')))for(const [,dependency] of fs.readFileSync(path.join(assets,name),'utf8').matchAll(/require\('\.\/([^']+)'\)/g))assert.ok(shipped.includes(dependency),`${name} requires missing ${dependency}`);
});
