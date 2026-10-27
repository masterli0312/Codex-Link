'use strict';
// A single machine-local native server, independent of relay reconnections.
// Coordination: one supervisor per endpoint (an OS-released pipe, not a PID file
// that Windows may reuse); a listener answering /readyz is reused as-is; a port
// held by anything else is reported, never competed with, and never stopped.
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),net=require('node:net'),http=require('node:http');
const {spawn}=require('node:child_process');
const {localSharedEndpoint}=require('./codex-client.cjs');
const {resolveNativeExecutable}=require('./native-runtime.cjs');
const DEFAULT_SHARED_URL='ws://127.0.0.1:18475/';

// 'ready' only for an HTTP 200 from /readyz; an open port alone proves nothing.
function probeShared(endpoint,timeout=3000){
  const url=new URL(endpoint);
  return new Promise(resolve=>{
    const request=http.get({host:url.hostname,port:Number(url.port),path:'/readyz',timeout,agent:false},response=>{
      response.resume();resolve(response.statusCode===200?'ready':'busy');
    });
    request.on('timeout',()=>request.destroy(Error('SHARED_PROBE_TIMEOUT')));
    request.on('error',error=>resolve(error.code==='ECONNREFUSED'?'free':'busy'));
  });
}
// A native server that is still binding answers late; give it time before calling the port foreign.
async function settledProbe(endpoint,{probe=probeShared,attempts=8,interval=1000}={}){
  let state=await probe(endpoint);
  for(let attempt=1;state==='busy'&&attempt<attempts;attempt++){
    await new Promise(resolve=>setTimeout(resolve,interval));state=await probe(endpoint);
  }
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
      // A Unix socket file outlives a crashed owner; reclaim it only when nobody answers.
      const peer=net.connect(name);
      peer.once('connect',()=>{peer.destroy();resolve(null);});
      peer.once('error',()=>{try{fs.unlinkSync(name);}catch{}acquireSupervisor(name).then(resolve);});
    });
    server.listen(name,()=>{server.unref();resolve(server);});
  });
}
async function main(directory=__dirname,{
  spawnNative=spawn,probe=probeShared,resolveNative=resolveNativeExecutable,lockName,
  busyAttempts=8,busyInterval=1000,readyInterval=500,readyTimeout=60000,handleSignals=true,
}={}){
  const cfg=JSON.parse(fs.readFileSync(path.join(directory,'connection.json'),'utf8').replace(/^﻿/,''));
  const endpoint=localSharedEndpoint(cfg.appServerUrl||DEFAULT_SHARED_URL);
  const healthFile=path.join(directory,'shared-server-health.json');
  const previous=()=>{try{return JSON.parse(fs.readFileSync(healthFile,'utf8'));}catch{return {};}};
  const writeHealth=state=>{try{fs.writeFileSync(healthFile,JSON.stringify({...state,updatedAt:Date.now()}));}catch{}};
  const lease=await acquireSupervisor(lockName||supervisorLockName(endpoint));
  if(!lease)return {state:'supervised'};
  // Keep the earlier owner's PIDs so ownership checks still match the real listener.
  const external=(state,error)=>writeHealth({...previous(),state,error,checkedAt:Date.now()});
  const initial=await settledProbe(endpoint,{probe,attempts:busyAttempts,interval:busyInterval});
  if(initial!=='free'){
    lease.close();
    if(initial==='ready'){external('reused');return {state:'reused'};}
    external('blocked','PORT_IN_USE_NOT_READY');return {state:'blocked'};
  }
  let child,closing=false,failures=0,timer,readyTimer,finish;
  const done=new Promise(resolve=>{finish=resolve;});
  const health=state=>writeHealth({supervisorPid:process.pid,nativePid:child?.pid||0,nativeExecutable:cfg.codexExecutable,...state});
  const close=(state='stopped')=>{
    if(closing)return;closing=true;clearTimeout(timer);clearTimeout(readyTimer);
    if(state==='stopped')child?.kill();lease.close();finish({state});
  };
  const retry=()=>{timer=setTimeout(start,Math.min(30000,1000*2**Math.min(failures,5)));};
  const watchReady=(current,born)=>{
    readyTimer=setTimeout(async()=>{
      if(closing||child!==current)return;
      if(await probe(endpoint)==='ready'){if(!closing&&child===current)health({state:'ready',readyAt:Date.now()});return;}
      if(Date.now()-born<readyTimeout)watchReady(current,born);
      else if(!closing&&child===current)health({state:'starting',error:'NOT_READY'});
    },readyInterval);
  };
  function start(){
    if(closing)return;
    try{cfg.codexExecutable=resolveNative(cfg.codexExecutable);}catch{
      health({state:'unavailable',error:'CODEX_MISSING'});failures++;retry();return;
    }
    const current=child=spawnNative(cfg.codexExecutable,['-c','features.code_mode_host=true','app-server','--listen',endpoint.replace(/\/$/, '')],{
      env:{...process.env,CODEX_HOME:cfg.codexHome},stdio:['ignore','ignore','pipe'],windowsHide:true});
    const born=Date.now();health({state:'starting'});current.stderr?.on('data',()=>{});watchReady(current,born);
    let exited=false;const exit=async()=>{
      if(exited)return;exited=true;
      if(child===current){child=undefined;clearTimeout(readyTimer);}
      if(closing){health({state:'stopped'});return;}
      // Lost a start race or the port was taken meanwhile: step aside instead of looping.
      const now=await probe(endpoint);
      if(closing)return;
      if(now==='ready'){external('reused');close('reused');return;}
      if(now==='busy'){external('blocked','PORT_IN_USE_NOT_READY');close('blocked');return;}
      health({state:'reconnecting'});failures=Date.now()-born>30000?0:failures+1;retry();
    };
    current.on('error',exit);current.on('exit',exit);
  }
  if(handleSignals){process.on('SIGINT',()=>close());process.on('SIGTERM',()=>close());process.on('exit',()=>close());}
  start();
  return {state:'supervising',done,close:()=>close()};
}
function run(directory){
  main(directory).then(result=>result.done||result).then(result=>{if(result.state==='blocked')process.exitCode=1;},()=>{process.exitCode=1;});
}
if(require.main===module)run(__dirname);
module.exports={main,run,probeShared,settledProbe,acquireSupervisor,supervisorLockName,DEFAULT_SHARED_URL};
