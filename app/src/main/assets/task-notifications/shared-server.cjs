'use strict';
// A single machine-local native server, independent of relay reconnections.
const fs=require('node:fs'),path=require('node:path'),{spawn}=require('node:child_process');
const {localSharedEndpoint}=require('./codex-client.cjs');
const {resolveNativeExecutable}=require('./native-runtime.cjs');
const DEFAULT_SHARED_URL='ws://127.0.0.1:18475/';
async function main(directory=__dirname){
  const cfg=JSON.parse(fs.readFileSync(path.join(directory,'connection.json'),'utf8').replace(/^\uFEFF/,''));
  const endpoint=localSharedEndpoint(cfg.appServerUrl||DEFAULT_SHARED_URL);
  const lock=path.join(directory,'shared-server.lock');
  try{process.kill(Number(fs.readFileSync(lock,'utf8')),0);return;}catch{}
  try{fs.unlinkSync(lock);}catch{}
  const lease=fs.openSync(lock,'wx');fs.writeFileSync(lease,String(process.pid));
  let child,closing=false,failures=0,timer;
  const health=state=>fs.writeFileSync(path.join(directory,'shared-server-health.json'),JSON.stringify({supervisorPid:process.pid,nativePid:child?.pid||0,nativeExecutable:cfg.codexExecutable,...state}));
  const start=()=>{
    if(closing)return;
    try{cfg.codexExecutable=resolveNativeExecutable(cfg.codexExecutable);}catch{
      health({state:'unavailable',error:'CODEX_MISSING'});failures++;
      timer=setTimeout(start,Math.min(30000,1000*2**Math.min(failures,5)));return;
    }
    child=spawn(cfg.codexExecutable,['-c','features.code_mode_host=true','app-server','--listen',endpoint.replace(/\/$/, '')],{
      env:{...process.env,CODEX_HOME:cfg.codexHome},stdio:['ignore','ignore','pipe'],windowsHide:true});
    const born=Date.now();health({state:'starting'});child.stderr.on('data',()=>{});
    let finished=false;const exit=()=>{
      if(finished)return;finished=true;health({state:closing?'stopped':'reconnecting'});
      if(!closing){failures=Date.now()-born>30000?0:failures+1;
        timer=setTimeout(start,Math.min(30000,1000*2**Math.min(failures,5)));}
    };
    child.on('error',exit);child.on('exit',exit);
  };
  const close=()=>{if(closing)return;closing=true;clearTimeout(timer);child?.kill();
    try{fs.closeSync(lease);if(fs.readFileSync(lock,'utf8')===String(process.pid))fs.unlinkSync(lock);}catch{}};
  process.on('SIGINT',close);process.on('SIGTERM',close);process.on('exit',close);
  start();
}
if(require.main===module)main().catch(()=>{process.exitCode=1;});
module.exports={main,DEFAULT_SHARED_URL};
