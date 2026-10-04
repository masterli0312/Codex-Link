'use strict';
// Same stable stdio lifecycle used by slopus/happy's Codex app-server integration (MIT).
const {spawn}=require('node:child_process');
const {StringDecoder}=require('node:string_decoder');
const {permissionOptions}=require('./remote-options.cjs');
const {resolveNativeExecutable}=require('./native-runtime.cjs');
class CodexClient{
  constructor(executable,home,spawnImpl=spawn){this.executable=executable;this.home=home;this.spawn=spawnImpl;this.pending=new Map();this.started=new Set();this.startWaiters=new Map();this.next=0;this.buffer='';this.closed=false;}
  setHandlers(event,request){this.event=event;this.request=request;}
  async connect(){
    this.child=this.spawn(this.executable,['app-server','--listen','stdio://'],{env:{...process.env,CODEX_HOME:this.home},stdio:['pipe','pipe','pipe'],windowsHide:true});
    const disconnected=()=>{
      if(this.closed)return;
      this.close(); // Reject both RPC calls and turn-start waiters, exactly once.
      this.event?.('bridge/disconnected',{});
    };
    this.child.stderr.on('data',()=>{}); // May contain local details; never persist or publish stderr.
    this.child.stdin.on('error',disconnected);this.child.stdout.on('error',disconnected);this.child.stderr.on('error',disconnected);
    const decoder=new StringDecoder('utf8');
    this.child.stdout.on('data',bytes=>{
      if(this.closed)return;
      this.buffer+=decoder.write(bytes);if(Buffer.byteLength(this.buffer)>16*1024*1024){disconnected();return;}
      let n;while((n=this.buffer.indexOf('\n'))>=0){const line=this.buffer.slice(0,n);this.buffer=this.buffer.slice(n+1);try{this.message(JSON.parse(line));}catch{}}
    });
    this.child.on('exit',disconnected);this.child.on('error',disconnected);
    await this.call('initialize',{clientInfo:{name:'codex_usage_remote',title:'Codex Usage',version:'1.2.0'},capabilities:{experimentalApi:true}});
    this.write({method:'initialized'});
  }
  message(row){
    if(this.closed)return;
    if(row.method==='turn/started'){
      const id=row.params?.turn?.id;this.started.add(id);
      const waiter=this.startWaiters.get(id);if(waiter){clearTimeout(waiter.timer);this.startWaiters.delete(id);waiter.resolve();}
    }
    if(row.id!==undefined&&!row.method){const p=this.pending.get(row.id);if(!p)return;this.pending.delete(row.id);clearTimeout(p.timer);if(row.error)p.reject(Object.assign(Error('CODEX_RPC'),{rpcCode:row.error.code,rpcMessage:row.error.message}));else p.resolve(row.result);}
    else if(row.id!==undefined)this.request?.(row.id,row.method,row.params||{});
    else if(row.method)this.event?.(row.method,row.params||{});
  }
  write(row){if(this.closed||!this.child?.stdin.writable)throw Error('CODEX_DISCONNECTED');this.child.stdin.write(JSON.stringify(row)+'\n');}
  call(method,params){return new Promise((resolve,reject)=>{const id=++this.next;
    const timer=setTimeout(()=>{this.pending.delete(id);reject(Error('CODEX_TIMEOUT'));},30000);
    this.pending.set(id,{resolve,reject,timer});try{this.write({id,method,params});}catch(e){clearTimeout(timer);this.pending.delete(id);reject(e);}
  });}
  read(threadId){return this.call('thread/read',{threadId,includeTurns:false});}
  async models(){
    const models=[];let cursor;
    for(let page=0;page<5;page++){
      const result=await this.call('model/list',{limit:100,includeHidden:false,...(cursor?{cursor}:{})});
      models.push(...(result.data||[]));cursor=result.nextCursor;
      if(!cursor)break;
    }
    return models;
  }
  resume(threadId,local){this.local=local;return this.call('thread/resume',{threadId,excludeTurns:true,cwd:local.cwd,...(local.model?{model:local.model}:{}),...(local.modelProvider?{modelProvider:local.modelProvider}:{}),...permissionOptions(local.permission).resume});}
  async modes(){return (await this.call('collaborationMode/list',{})).data||[];}
  start(threadId,text,id,options={}){const effort=options.model?options.effort:this.local?.effort;return this.call('turn/start',{threadId,input:[{type:'text',text},...(options.inputs||[])],clientUserMessageId:id,
    ...(options.model?{model:options.model}:{}),...(effort?{effort}:{}),
    ...(options.mode?{collaborationMode:{mode:options.mode,settings:{model:options.model,reasoning_effort:effort||null,developer_instructions:null}}}:{}),
    ...permissionOptions(options.permission||this.local?.permission).turn});}
  steer(threadId,turnId,text,id){return this.call('turn/steer',{threadId,expectedTurnId:turnId,
    input:[{type:'text',text}],clientUserMessageId:id});}
  async interrupt(threadId,turnId){
    if(this.closed)throw Error('CODEX_DISCONNECTED');
    // turn/start may return before the engine installs its active turn. Wait for the real event.
    if(!this.started.has(turnId))await new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>{this.startWaiters.delete(turnId);reject(Error('CODEX_TIMEOUT'));},15000);
      this.startWaiters.set(turnId,{resolve,reject,timer});
    });
    return this.call('turn/interrupt',{threadId,turnId});
  }
  answer(id,result){this.write({id,result});}
  reject(id){this.write({id,error:{code:-32601,message:'Unsupported remote interaction'}});}
  close(){
    if(this.closed)return;
    this.closed=true;
    this.buffer='';this.started.clear();
    // EOF permits the app-server to flush task_complete/turn_aborted before exiting.
    this.child?.stdin.end();const child=this.child;if(child)setTimeout(()=>{if(child.exitCode===null)child.kill();},1500).unref();
    for(const p of this.pending.values()){clearTimeout(p.timer);p.reject(Error('CODEX_DISCONNECTED'));}this.pending.clear();
    for(const p of this.startWaiters.values()){clearTimeout(p.timer);p.reject(Error('CODEX_DISCONNECTED'));}this.startWaiters.clear();
  }
}
function localSharedEndpoint(value){
  if(typeof value!=='string')throw Error('INVALID_SHARED_SERVER');
  let url;try{url=new URL(value);}catch{throw Error('INVALID_SHARED_SERVER');}
  if(url.protocol!=='ws:'||url.hostname!=='127.0.0.1'||!url.port||url.pathname!=='/'||
    url.username||url.password||url.search||url.hash)throw Error('INVALID_SHARED_SERVER');
  return url.href;
}
// Separate clients subscribe to the SAME native server; closing one client never
// terminates the server or releases another client's live conversation.
class SharedCodexClient extends CodexClient{
  constructor(endpoint,WebSocketImpl=globalThis.WebSocket){
    super('', '');this.endpoint=localSharedEndpoint(endpoint);this.WebSocket=WebSocketImpl;this.supportsLiveWatch=true;
  }
  async connect(){
    if(!this.WebSocket)throw Error('SHARED_SERVER_REQUIRES_NODE22');
    const socket=this.socket=new this.WebSocket(this.endpoint);
    const disconnected=()=>{if(this.closed)return;this.close();this.event?.('bridge/disconnected',{});};
    socket.addEventListener('message',e=>{
      if(this.closed)return;
      if(typeof e.data!=='string'||Buffer.byteLength(e.data)>16*1024*1024){disconnected();return;}
      try{this.message(JSON.parse(e.data));}catch{disconnected();}
    });
    socket.addEventListener('close',disconnected);socket.addEventListener('error',disconnected);
    try{
      await new Promise((resolve,reject)=>{
        const timer=setTimeout(()=>finish(Error('CODEX_TIMEOUT')),10000);
        const open=()=>finish(),error=()=>finish(Error('CODEX_DISCONNECTED'));
        const finish=err=>{clearTimeout(timer);socket.removeEventListener('open',open);
          socket.removeEventListener('error',error);socket.removeEventListener('close',error);err?reject(err):resolve();};
        socket.addEventListener('open',open);socket.addEventListener('error',error);socket.addEventListener('close',error);
      });
      await this.call('initialize',{clientInfo:{name:'codex_usage_remote',title:'Codex Usage',version:'1.2.0'},capabilities:{experimentalApi:true}});
      this.write({method:'initialized'});
    }catch(e){this.close();throw e;}
  }
  write(row){if(this.closed||this.socket?.readyState!==1)throw Error('CODEX_DISCONNECTED');this.socket.send(JSON.stringify(row));}
  // Shared requests can also be handled by the desktop. Read-only clients must
  // abstain rather than reject another subscriber's approval or native UI request.
  reject(){}
  close(){if(this.closed)return;super.close();try{this.socket?.close();}catch{}}
}
function createCodexClient(config){return config.appServerUrl?new SharedCodexClient(config.appServerUrl):new CodexClient(resolveNativeExecutable(config.codexExecutable),config.codexHome);}
module.exports={CodexClient,SharedCodexClient,localSharedEndpoint,createCodexClient};
