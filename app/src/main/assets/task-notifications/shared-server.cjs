'use strict';
// The lease owns the stable public listener; every native generation has a private port.
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),net=require('node:net'),http=require('node:http');
const {spawn,execFile}=require('node:child_process');
const {localSharedEndpoint,SharedCodexClient}=require('./codex-client.cjs');
const {resolveNativeExecutable}=require('./native-runtime.cjs');
const DEFAULT_SHARED_URL='ws://127.0.0.1:18475/';

function probeShared(endpoint,timeout=3000){
 const url=new URL(endpoint);
 return new Promise(resolve=>{
  const request=http.get({host:url.hostname,port:Number(url.port),path:'/readyz',timeout,agent:false},response=>{response.resume();resolve(response.statusCode===200?'ready':'busy');});
  request.on('timeout',()=>request.destroy(Error('SHARED_PROBE_TIMEOUT')));
  request.on('error',error=>resolve(error.code==='ECONNREFUSED'?'free':'busy'));
 });
}
async function settledProbe(endpoint,{probe=probeShared,attempts=8,interval=1000}={}){
 let state=await probe(endpoint);
 for(let attempt=1;state==='busy'&&attempt<attempts;attempt++){await new Promise(resolve=>setTimeout(resolve,interval));state=await probe(endpoint);}
 return state;
}
function supervisorLockName(endpoint){
 const port=new URL(endpoint).port;
 return process.platform==='win32'?'\\\\.\\pipe\\codex-link-shared-server-'+port:path.join(os.tmpdir(),'codex-link-shared-server-'+port+'.sock');
}
function acquireSupervisor(name){
 return new Promise(resolve=>{
  const server=net.createServer(socket=>socket.destroy());
  server.once('error',error=>{
   if(process.platform==='win32'||error.code!=='EADDRINUSE'){resolve(null);return;}
   const peer=net.connect(name);
   peer.once('connect',()=>{peer.destroy();resolve(null);});
   peer.once('error',()=>{try{fs.unlinkSync(name);}catch{}acquireSupervisor(name).then(resolve);});
  });
  server.listen(name,()=>{server.unref();resolve(server);});
 });
}
function selectNativePort(){
 return new Promise((resolve,reject)=>{
  const server=net.createServer();server.once('error',reject);
  server.listen({host:'127.0.0.1',port:0,exclusive:true},()=>{const port=server.address().port;server.close(error=>error?reject(error):resolve(port));});
 });
}
function resolveRuntime(pinned,timeout=10000){
 return new Promise((resolve,reject)=>execFile(process.execPath,[path.join(__dirname,'native-runtime.cjs'),'--resolve',pinned||''],
  {encoding:'utf8',windowsHide:true,timeout,maxBuffer:32768},(error,stdout)=>error?reject(error):resolve(stdout.trim())));
}
function killTree(pid){return new Promise(resolve=>execFile('taskkill',['/PID',String(pid),'/T','/F'],{windowsHide:true,timeout:10000},()=>resolve()));}
function runtimeComplete(file){try{return fs.statSync(path.join(path.dirname(file),'codex-code-mode-host.exe')).isFile();}catch{return false;}}
function phoneActive(directory){
 try{
  const entries=JSON.parse(fs.readFileSync(path.join(directory,'remote-journal.json'),'utf8').replace(/^\uFEFF/,''));
  if(!entries||typeof entries!=='object'||Array.isArray(entries))return true;
  return Object.values(entries).some(entry=>!entry||typeof entry!=='object'||typeof entry.status!=='string'||['accepted','running','approval','input_required','queued'].includes(entry.status));
 }catch{return true;}
}
// Unlike thread/list, the loaded list covers all in-memory origins, including subagents.
async function nativeActive(endpoint,timeout=10000,Client=SharedCodexClient){
 const client=new Client(endpoint);let changed=false,timer;
 client.setHandlers((method,params)=>{if(method==='turn/started'||method==='thread/started'||method==='thread/status/changed'&&params?.status?.type==='active')changed=true;},()=>{});
 try{
  return await Promise.race([(async()=>{
   await client.connect();let cursor;const seen=new Set();
   for(let page=0;page<100;page++){
    const result=await client.call('thread/loaded/list',{limit:100,...(cursor?{cursor}:{})});
    if(!Array.isArray(result?.data)||!Object.hasOwn(result,'nextCursor'))return true;
    for(const id of result.data){
     if(typeof id!=='string'||!id)return true;
     const thread=(await client.call('thread/read',{threadId:id,includeTurns:false}))?.thread;
     if(thread?.id!==id||!['idle','notLoaded','systemError'].includes(thread.status?.type))return true;
    }
    if(!result.nextCursor)return changed;
    if(typeof result.nextCursor!=='string'||seen.has(result.nextCursor))return true;
    cursor=result.nextCursor;seen.add(cursor);
   }
   return true;
  })(),new Promise(resolve=>{timer=setTimeout(()=>resolve(true),timeout);})]);
 }catch{return true;}finally{clearTimeout(timer);client.close();}
}
async function main(directory=__dirname,{
 spawnNative=spawn,probe=probeShared,resolveNative=resolveNativeExecutable,resolveAsync=resolveRuntime,selectPort=selectNativePort,
 acquireLease=acquireSupervisor,createRelay=handler=>net.createServer({pauseOnConnect:true,allowHalfOpen:true},handler),connectUpstream=options=>net.connect(options),killNativeTree=killTree,
 nativeOptions=cfg=>({env:{...process.env,CODEX_HOME:cfg.codexHome},stdio:['ignore','ignore','pipe'],windowsHide:true}),
 completeRuntime=runtimeComplete,checkPhone=phoneActive,checkNative=nativeActive,lockName,platform=process.platform,
 busyAttempts=8,busyInterval=1000,readyInterval=500,readyTimeout=12000,readyTimeoutMax=60000,holdTimeout=30000,drainTimeout=30000,admissionRetry=50,stopTimeout=10000,
 runtimeInterval=300000,retryDelays=[250,1000,2000,5000],stableDelay=250,handleSignals=true,setFailClosed=()=>{},
}={}){
 const cfg=JSON.parse(fs.readFileSync(path.join(directory,'connection.json'),'utf8').replace(/^\uFEFF/,''));
 const pinned=cfg.codexExecutable,endpoint=localSharedEndpoint(cfg.appServerUrl||DEFAULT_SHARED_URL),publicUrl=new URL(endpoint);
 const healthFile=path.join(directory,'shared-server-health.json');
 const previous=()=>{try{return JSON.parse(fs.readFileSync(healthFile,'utf8'));}catch{return {};}};
 const writeHealth=state=>{try{fs.writeFileSync(healthFile,JSON.stringify({...state,updatedAt:Date.now()}));}catch{}};
 const lease=await acquireLease(lockName||supervisorLockName(endpoint));
 if(!lease)return {state:'supervised'};
 let relay,current,currentExecutable='',pendingExecutable=null,generation=0,lastPort=0,failures=0,closing=false,finalizing=false,starting=false,binding=false,releasingLease=false,releasingRelay=false,closingError=null;
 let retryTimer,runtimeTimer,checking=false,resolving=false,exitState='stopped',state='starting',error=null,readyAt=null,finish,readinessBudget=readyTimeout;
 const clients=new Map(),done=new Promise(resolve=>{finish=resolve;});
 const external=(value,reason)=>writeHealth({supervisorPid:0,relayPid:0,nativePid:0,nativePort:0,nativeExecutable:'',generation:0,clients:0,heldClients:0,
  pendingExecutable:null,readyAt:null,...previous(),mode:'reused',state:value,error:reason||null,checkedAt:Date.now()});
 function health(next=state,reason=error){
  state=next;error=reason;
  writeHealth({mode:'relay',supervisorPid:process.pid,relayPid:process.pid,nativePid:current?.child?.pid||0,nativePort:current?.port||0,
   nativeExecutable:current?.executable||currentExecutable||pinned,generation,clients:clients.size,heldClients:[...clients.values()].filter(c=>!c.admitted).length,
   pendingExecutable,state,error,readyAt,updatedAt:Date.now()});
 }
 function destroyClient(socket){const item=clients.get(socket);if(!item)return;clearTimeout(item.timer);clearTimeout(item.retryTimer);clearTimeout(item.drainTimer);item.upstream?.destroy();socket.destroy();}
 function drain(socket,item){if(!item.drainTimer)item.drainTimer=setTimeout(()=>destroyClient(socket),drainTimeout);}
 function unadmit(socket,item,upstream){
  if(item.upstream!==upstream||item.admitted)return;
  item.upstream=null;item.generation=null;upstream.destroy();
  if(!closing&&!socket.destroyed){clearTimeout(item.retryTimer);item.retryTimer=setTimeout(()=>{item.retryTimer=null;admit(socket,item);},admissionRetry);}
 }
 function dropGeneration(gen){for(const [socket,item] of clients)if(item.generation===gen){if(item.admitted)destroyClient(socket);else unadmit(socket,item,item.upstream);}}
 function admit(socket,item){
  const gen=current;if(closing||!gen?.ready||gen.stopping||item.upstream||socket.destroyed)return;
  clearTimeout(item.retryTimer);item.retryTimer=null;
  const upstream=item.upstream=connectUpstream({host:'127.0.0.1',port:gen.port,allowHalfOpen:true});item.generation=gen;upstream.setNoDelay(true);
  upstream.on('error',()=>{if(item.upstream!==upstream)return;if(item.admitted)destroyClient(socket);else unadmit(socket,item,upstream);});
  upstream.on('end',()=>{if(item.upstream===upstream&&item.admitted)drain(socket,item);});
  upstream.on('close',hadError=>{
   if(item.upstream!==upstream)return;
   if(!item.admitted){unadmit(socket,item,upstream);return;}
   if(hadError||!upstream.readableEnded){destroyClient(socket);return;}
   if(socket.writableFinished)socket.destroy();else socket.once('finish',()=>socket.destroy());
  });
  upstream.once('connect',()=>{
   if(item.upstream!==upstream)return;
   if(closing||socket.destroyed){destroyClient(socket);return;}
   if(current!==gen||!gen.ready||gen.stopping){unadmit(socket,item,upstream);return;}
   item.admitted=true;clearTimeout(item.timer);socket.pipe(upstream);upstream.pipe(socket);socket.resume();
  });
 }
 function accept(socket){
  if(closing){socket.destroy();return;}
  socket.pause();socket.setNoDelay(true);const item={upstream:null,generation:null,timer:setTimeout(()=>destroyClient(socket),holdTimeout)};clients.set(socket,item);
  socket.on('end',()=>{if(item.admitted)drain(socket,item);});
  socket.on('error',()=>destroyClient(socket));socket.on('close',()=>{
   clearTimeout(item.timer);clearTimeout(item.retryTimer);clearTimeout(item.drainTimer);item.upstream?.destroy();clients.delete(socket);if(!clients.size)maybeSwitch();
  });admit(socket,item);
 }
 function retry(ms){clearTimeout(retryTimer);retryTimer=setTimeout(()=>{retryTimer=null;start();},ms);}
 function onExit(gen,spawnFailed=false){
  if(current!==gen)return;
  clearTimeout(gen.readyTimer);clearTimeout(gen.deadlineTimer);clearTimeout(gen.stopTimer);gen.ready=false;dropGeneration(gen);current=null;readyAt=null;
  if(closing){finalize();return;}
  if(spawnFailed){currentExecutable='';pendingExecutable=null;}
  if(!gen.everReady&&!spawnFailed)readinessBudget=Math.min(readyTimeoutMax,readinessBudget*2);
  if(pendingExecutable){currentExecutable=pendingExecutable;pendingExecutable=null;if(gen.switching){failures=0;health('reconnecting',null);retry(stableDelay);return;}}
  failures=Date.now()-gen.born>30000?0:failures+1;health('reconnecting',spawnFailed?'SPAWN_FAILED':null);
  retry(failures?retryDelays[Math.min(failures-1,retryDelays.length-1)]:stableDelay);
 }
 function escalate(gen){
  if(current!==gen||!gen.stopping)return;
  health('blocked','NATIVE_STOP_FAILED');
  try{if(platform==='win32'&&gen.child.pid)Promise.resolve(killNativeTree(gen.child.pid)).catch(()=>{});else gen.child.kill('SIGKILL');}catch{}
  if(current===gen)gen.stopTimer=setTimeout(()=>escalate(gen),stopTimeout);
 }
 function stopGeneration(gen,switching=false){
  if(current!==gen||gen.stopping)return;
  gen.stopping=true;gen.switching=switching;gen.ready=false;clearTimeout(gen.readyTimer);clearTimeout(gen.deadlineTimer);dropGeneration(gen);
  health(switching?'switching':closing?exitState:'stopping',closing?closingError:null);
  try{gen.child.kill();}catch{}
  if(current===gen)gen.stopTimer=setTimeout(()=>escalate(gen),stopTimeout);
 }
 function finalize(){
  if(finalizing||current||binding)return;finalizing=true;clearTimeout(retryTimer);clearTimeout(runtimeTimer);
  for(const socket of clients.keys())destroyClient(socket);
  health(exitState,exitState==='blocked'?closingError:null);
  const release=()=>{releasingLease=true;try{lease.close();}catch{}finish({state:exitState});};
  if(relay?.listening){releasingRelay=true;try{relay.close(release);}catch{release();}}else release();
 }
 function failClosed(reason='STOPPED',failed=true){
  if(closing){if(failed&&exitState!=='blocked'){exitState='blocked';closingError=reason;health('blocked',reason);}return;}
  closing=true;exitState=failed?'blocked':'stopped';closingError=failed?reason:null;
  clearTimeout(retryTimer);clearTimeout(runtimeTimer);if(relay?.listening){releasingRelay=true;try{relay.close();}catch{}}
  for(const socket of clients.keys())destroyClient(socket);
  if(current)stopGeneration(current);else finalize();
 }
 setFailClosed(failClosed);
 lease.on('error',()=>failClosed('LEASE_LOST'));lease.on('close',()=>{if(!releasingLease)failClosed('LEASE_LOST');});
 const initial=await settledProbe(endpoint,{probe,attempts:busyAttempts,interval:busyInterval});
 if(closing){finalize();return {state:'supervising',done,close:()=>failClosed('STOPPED',false)};}
 if(initial!=='free'){
  closing=true;releasingLease=true;try{lease.close();}catch{}
  if(initial==='ready'){external('reused');return {state:'reused'};}
  external('blocked','PORT_IN_USE_NOT_READY');return {state:'blocked'};
 }
 try{currentExecutable=resolveNative(pinned);}catch{}
 relay=createRelay(accept);
 let bound=false;
 relay.on('error',()=>{if(bound)failClosed('RELAY_ERROR');});
 binding=true;try{await new Promise((resolve,reject)=>{
  const failed=e=>{relay.removeListener('listening',ready);reject(e);},ready=()=>{relay.removeListener('error',failed);bound=true;resolve();};
  relay.once('error',failed);relay.once('listening',ready);relay.listen({host:'127.0.0.1',port:Number(publicUrl.port),exclusive:true});
 });}catch{binding=false;closing=true;exitState='blocked';closingError='PORT_IN_USE_NOT_READY';health('blocked',closingError);finalize();return {state:'blocked'};}binding=false;
 relay.on('close',()=>{if(!releasingRelay)failClosed('RELAY_ERROR');});
 if(closing){finalize();return {state:'supervising',done,close:()=>failClosed('STOPPED',false)};}
 function watchReady(gen){
  gen.readyTimer=setTimeout(async()=>{
   if(closing||current!==gen||gen.stopping)return;
   let result;try{result=await probe(`ws://127.0.0.1:${gen.port}/`);}catch{}
   if(closing||current!==gen||gen.stopping)return;
   if(result==='ready'){
    gen.ready=true;gen.everReady=true;readinessBudget=readyTimeout;clearTimeout(gen.deadlineTimer);readyAt=Date.now();health('ready',null);
    for(const [socket,item] of clients)admit(socket,item);maybeSwitch();
   }else watchReady(gen);
  },readyInterval);
 }
 async function start(){
  if(closing||current||starting)return;starting=true;
  try{
   if(!currentExecutable||!fs.existsSync(currentExecutable)||!completeRuntime(currentExecutable)){
    const previous=currentExecutable;
    try{const next=await resolveAsync(pinned);if(!next||!fs.existsSync(next))throw Error('CODEX_MISSING');if(closing||current)return;currentExecutable=next;}
    catch(e){if(!previous||!fs.existsSync(previous)||!completeRuntime(previous))currentExecutable='';throw e;}
   }
   if(closing||current)return;
   if(!currentExecutable)throw Error('CODEX_MISSING');
   let port;for(let attempt=0;attempt<8;attempt++){port=await selectPort();if(closing||current)return;if(port!==lastPort)break;}
   if(port===lastPort)throw Error('NATIVE_PORT_UNAVAILABLE');lastPort=port;
   const gen=current={number:++generation,port,executable:currentExecutable,born:Date.now(),ready:false,stopping:false,child:null};
   try{gen.child=spawnNative(gen.executable,['-c','features.code_mode_host=true','app-server','--listen',`ws://127.0.0.1:${port}`],nativeOptions(cfg));}
   catch{onExit(gen,true);return;}
   gen.child.stderr?.on('data',()=>{});gen.child.on('error',()=>{if(current===gen&&!gen.child.pid)onExit(gen,true);});
   gen.child.on('exit',()=>onExit(gen));health('starting',null);
   // Desktop gives its handshake 15 s; allow an initial attempt within that budget, then grow for cold starts.
   gen.deadlineTimer=setTimeout(()=>{if(current===gen&&!gen.ready)stopGeneration(gen);},readinessBudget);watchReady(gen);
  }catch{if(!closing){failures++;health('unavailable',currentExecutable?'NATIVE_PORT_UNAVAILABLE':'CODEX_MISSING');retry(retryDelays[Math.min(failures-1,retryDelays.length-1)]);}}
  finally{starting=false;}
 }
 async function maybeSwitch(){
  const gen=current,next=pendingExecutable;
  if(checking||closing||!next||!gen?.ready||gen.stopping||clients.size)return;
  checking=true;
  try{
   if(await checkPhone(directory)!==false)return;
   const active=await checkNative(`ws://127.0.0.1:${gen.port}/`);
   const phoneNow=await checkPhone(directory);
   if(closing||current!==gen||gen.stopping||!gen.ready||pendingExecutable!==next||clients.size||active!==false||phoneNow!==false)return;
   stopGeneration(gen,true);
  }catch{}finally{checking=false;}
 }
 async function runtimeTick(){
  if(closing)return;
  const gen=current;
  if(gen&&!gen.stopping&&!resolving){
   resolving=true;
   try{
    const next=await resolveAsync(pinned);
    if(!closing&&current===gen&&!gen.stopping&&next){
     if(next!==gen.executable){
      if(pendingExecutable!==next){pendingExecutable=next;health(state,error);}
      if(!completeRuntime(gen.executable))stopGeneration(gen,true);else maybeSwitch();
     }else if(pendingExecutable){pendingExecutable=null;health(state,error);}
    }
   }catch{}finally{resolving=false;}
  }
  if(!current&&!starting&&!retryTimer)start();
  if(!closing){maybeSwitch();if(runtimeInterval)runtimeTimer=setTimeout(runtimeTick,runtimeInterval);}
 }
 if(handleSignals){
  const stop=()=>failClosed('STOPPED',false);process.once('SIGINT',stop);process.once('SIGTERM',stop);
  done.then(()=>{process.removeListener('SIGINT',stop);process.removeListener('SIGTERM',stop);});
 }
 start();if(runtimeInterval)runtimeTimer=setTimeout(runtimeTick,runtimeInterval);
 return {state:'supervising',done,close:()=>failClosed('STOPPED',false)};
}
function run(directory,options={}){
 let failClosed;const guard=()=>{process.exitCode=1;if(failClosed)failClosed('UNCAUGHT_EXCEPTION');};
 process.on('uncaughtException',guard);
 main(directory,{...options,setFailClosed:fn=>{failClosed=fn;}}).then(result=>result.done||result).then(result=>{
  process.removeListener('uncaughtException',guard);if(result.state==='blocked')process.exitCode=1;
 },()=>{if(failClosed)failClosed('UNCAUGHT_EXCEPTION');process.exitCode=1;});
}
if(require.main===module)run(__dirname);
module.exports={main,run,probeShared,settledProbe,acquireSupervisor,supervisorLockName,nativeActive,DEFAULT_SHARED_URL};
