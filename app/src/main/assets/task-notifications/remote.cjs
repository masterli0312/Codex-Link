'use strict';
const fs=require('node:fs'),path=require('node:path');
const {commandTopic,eventTopic,encode,decode,resolveThread,validateCommand,RemoteController}=require('./remote-core.cjs');
const {createCodexClient}=require('./codex-client.cjs');
const {compactFrame,SENDER_IDLE_MS,livePublishInterval,StreamResetTracker}=require('./remote-stream.cjs');
const {validEndpoint}=require('./notify.cjs');
const {readSnapshot,readLatestSnapshot,rolloutRevision}=require('./task-content.cjs');
const wait=(ms,signal)=>new Promise(resolve=>{
  if(signal?.aborted){resolve();return;}
  const finish=()=>{clearTimeout(timer);signal?.removeEventListener('abort',finish);resolve();};
  const timer=setTimeout(finish,ms);signal?.addEventListener('abort',finish,{once:true});
});
function httpFailure(response,code){
  const raw=response.headers?.get('retry-after');
  let millis=raw&&/^\d+$/.test(raw)?Number(raw)*1000:raw?Date.parse(raw)-Date.now():NaN;
  if(!Number.isFinite(millis))millis=code===42908?300000:60000;
  return Object.assign(Error('TRANSPORT'),{status:response.status,
    ...(code===42908?{dailyLimit:true}:{}),
    retryAfter:response.status===429?Math.max(1000,Math.min(3600000,millis)):5000});
}
async function responseFailure(response){
  let code;
  // Inspect only the documented numeric error; never retain URLs or the response message.
  if(response.status===429&&response.body){
    try{
      const chunks=[];let size=0;
      for await(const chunk of response.body){size+=chunk.length;if(size>16384)break;chunks.push(chunk);}
      if(size<=16384)code=JSON.parse(Buffer.concat(chunks).toString('utf8')).code;
    }catch{} // An unreadable error body still receives ordinary rate-limit backoff.
  }
  return httpFailure(response,code);
}
function pruneOutbox(outbox,now=Date.now()){
  for(const [id,event] of outbox){
    const ttl=event.status==='presence'?25000:event.status==='snapshot'?60000:
      ['threads','models','history','activities','session_info','skills'].includes(event.status)?45000:0;
    if(ttl&&now-event.at>ttl)outbox.delete(id);
  }
}
function retryDelay(error,backoff){return error?.status===429?(error.retryAfter||60000):backoff;}
// Incoming native preparation must not occupy the receive loop: Stop and host presence
// remain available. Bounded, fail-fast dispatch never queues a new generation for later.
class CommandDispatcher {
  constructor(handle,onError=()=>{},limit=8){this.handle=handle;this.onError=onError;this.limit=limit;this.pending=new Map();this.closed=false;}
  submit(c){
    if(this.closed||this.pending.has(c.id))return false;
    const control=['presence','stop','status','approve','answer_input','cancel_queue','steer','goal_pause','goal_clear'].includes(c.action);
    if([...this.pending.values()].filter(p=>p.control===control).length>=this.limit)throw Error('STREAM_OVERLOAD');
    const entry={control,promise:null};this.pending.set(c.id,entry);
    // Enter the controller synchronously so its active/journal guards preserve receive order.
    try{entry.promise=Promise.resolve(this.handle(c));}catch(e){entry.promise=Promise.reject(e);}
    entry.promise=entry.promise.catch(e=>{try{this.onError(e);}catch{}}).finally(()=>this.pending.delete(c.id));
    return true;
  }
  close(){this.closed=true;}
  async idle(){await Promise.all([...this.pending.values()].map(p=>p.promise));}
}
// A steer is already committed to the native turn before this receipt exists.
// Publish it immediately on a separate bounded lane, so a large snapshot POST
// cannot delay the phone's confirmation. No command is replayed here.
class SteerReceiptPublisher {
  constructor(publish,onFailure=()=>{},limit=8){this.publish=publish;this.onFailure=onFailure;this.limit=limit;this.pending=new Map();this.closed=false;}
  submit(event){
    if(this.closed||event.status!=='steered')return false;
    if(this.pending.has(event.request_id))return true;
    if(this.pending.size>=this.limit)return false;
    const entry={promise:null};this.pending.set(event.request_id,entry);
    try{entry.promise=Promise.resolve(this.publish(event));}catch(error){entry.promise=Promise.reject(error);}
    entry.promise=entry.promise.catch(error=>this.onFailure(event,error)).finally(()=>this.pending.delete(event.request_id));
    return true;
  }
  close(){this.closed=true;}
  async idle(){await Promise.all([...this.pending.values()].map(entry=>entry.promise));}
}
async function subscribeCommands(url,onRow,{signal,fetchImpl=fetch,headerMs=15000,idleMs=75000}={}){
  const abort=new AbortController();let timer,response;
  const timeout=()=>abort.abort(Object.assign(Error('TRANSPORT_TIMEOUT'),{name:'TimeoutError'}));
  const renew=ms=>{clearTimeout(timer);timer=setTimeout(timeout,ms);};
  const close=()=>abort.abort(signal.reason);
  if(signal?.aborted)close();else signal?.addEventListener('abort',close,{once:true});
  try{
    renew(headerMs);response=await fetchImpl(url,{signal:abort.signal});
    if(!response.ok||!response.body)throw httpFailure(response);
    renew(idleMs);
    await readStream(response,row=>{renew(idleMs);return onRow(row);});
  }catch(e){throw abort.signal.aborted?abort.signal.reason:e;}
  finally{clearTimeout(timer);signal?.removeEventListener('abort',close);abort.abort();try{await response?.body?.cancel();}catch{}}
}
function transportFailure(error){
  if(error?.status===429&&error.dailyLimit)return 'DAILY_LIMIT';
  if(error?.status===429)return 'RATE_LIMIT';
  if(Number.isInteger(error?.status))return 'HTTP';
  if(['TimeoutError','AbortError'].includes(error?.name))return 'TIMEOUT';
  if(error?.message==='OVERSIZE')return 'OVERSIZE';
  return 'NETWORK';
}
function createHealth(directory,clock=Date.now){
  // Local diagnostics have no URLs, identities, credentials, prompts or provider error text.
  const state={startedAt:clock(),connected:false,lastConnectAt:0,lastCommandAt:0,lastPublishedAt:0,
    commandsReceived:0,commandsRejected:0,eventsPublished:0,queuedEvents:0,lastReceiveError:'',lastPublishError:'',lastPublishStatus:0,lastCommandKind:'',lastEventKind:'',lastThreadCount:0};
    const counters=new Set(['lastConnectAt','lastCommandAt','lastPublishedAt','commandsReceived','commandsRejected','eventsPublished','queuedEvents','lastThreadCount','lastPublishStatus']);
  const errors=new Set(['','NETWORK','TIMEOUT','HTTP','RATE_LIMIT','DAILY_LIMIT','OVERSIZE']);
  const kinds=new Set(['','send','create','stop','approve','answer_input','status','models','threads','read','history','activities','rename','archive','unarchive','queue','steer','cancel_queue','presence',
    'fork','forked','accepted','running','approval','input_required','completed','interrupted','failed','unknown','snapshot','managed','queued','steered','cancelled',
    'goal_read','goal_start','goal_pause','goal_resume','goal_clear','goal','session_info','skills','image','file','thread_activity','compact','compacting','compacted']);
  return {snapshot:()=>({...state}),update(patch){
    for(const [key,value] of Object.entries(patch)){
      if(counters.has(key)&&Number.isSafeInteger(value)&&value>=0)state[key]=value;
      else if(key==='connected'&&typeof value==='boolean')state[key]=value;
      else if(['lastReceiveError','lastPublishError'].includes(key)&&errors.has(value))state[key]=value;
      else if(['lastCommandKind','lastEventKind'].includes(key)&&kinds.has(value))state[key]=value;
    }
    try{
      const file=path.join(directory,'remote-health.json');
      fs.writeFileSync(file+'.tmp',JSON.stringify(state),{mode:0o600});fs.renameSync(file+'.tmp',file);
    }catch{} // A diagnostic write must never interrupt a task.
  }};
}
async function publishEvent(endpoint,key,event,fetchImpl=fetch){
  // Public ntfy has a 4 KiB message limit. Large snapshots use its bounded attachment route.
  const full=encode('event',event.id,event,key);
  const large=JSON.stringify(full).length>3500;
  const preview={id:event.id,request_id:event.request_id,host_id:event.host_id,thread_id:event.thread_id,
    conversation_id:event.conversation_id,status:event.status,seq:event.seq,at:event.at,
    turn_id:event.turn_id||'',error:event.error||'',approval_id:event.approval_id||'',
    approval_kind:event.approval_kind||'',approval_item_id:event.approval_item_id||'',approval_can_allow:event.approval_can_allow!==false,
    parent_request_id:event.parent_request_id||'',reply:event.reply||'',approval_text:event.approval_text||'',
    active_input:event.active_input||'',host_name:event.host_name||'',managed_thread_id:event.managed_thread_id||'',
    managed_action:event.managed_action||'',managed_name:event.managed_name||'',partial:true,attachment_pending:true,
    reply_patch_pending:!!event.reply_patch};
  // Only routing and a bounded preview belong in the 4 KiB header. Full state stays encrypted in the attachment.
  let previewWire=encode('event',event.id,preview,key);
  if(large){
    const fields=['reply','approval_text','active_input','managed_name','host_name'];
    while(Buffer.byteLength(JSON.stringify(previewWire))>3500){
      const field=fields.sort((a,b)=>Buffer.byteLength(preview[b])-Buffer.byteLength(preview[a]))[0];
      const length=Buffer.byteLength(preview[field]);
      if(!length)throw Error('PREVIEW_TOO_LARGE');
      preview[field]=Buffer.from(preview[field]).subarray(0,Math.floor(length/2)).toString('utf8').replace(/\uFFFD$/,'');
      previewWire=encode('event',event.id,preview,key);
    }
    // A complete short question is control state, not a history download. Keep it
    // authenticated in the inline frame when it fits; never truncate questions/options.
    if(event.status==='input_required'&&event.user_input){
      const inline=encode('event',event.id,{...preview,user_input:event.user_input},key);
      if(Buffer.byteLength(JSON.stringify(inline))<=3500)previewWire=inline;
    }
  }
  const response=await fetchImpl(endpoint,{method:'POST',headers:large?{'Filename':'codex-remote.bin','Message':JSON.stringify(previewWire),'Content-Type':'application/octet-stream'}:{'Content-Type':'text/plain'},
    body:large?Buffer.from(full.encrypted,'base64'):JSON.stringify(full),signal:AbortSignal.timeout(15000)});
  if(!response.ok)throw await responseFailure(response);
  await response.arrayBuffer();
}
async function readStream(response,onRow){
  if(!response.ok||!response.body)throw Object.assign(Error('TRANSPORT'),{status:response.status});
  const decoder=new TextDecoder();let buffer='';
  for await(const chunk of response.body){buffer+=decoder.decode(chunk,{stream:true});if(Buffer.byteLength(buffer)>40000)throw Error('OVERSIZE');
    let n;while((n=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,n);buffer=buffer.slice(n+1);let row;try{row=JSON.parse(line);}catch{continue;}await onRow(row);}
  }
}
async function downloadCommand(base,row,key,fetchImpl=fetch){
  let wire;try{wire=JSON.parse(row.message);}catch{return null;}
  let c=decode('command',wire,key);if(c&&!c.attachment)return c;
  if(!c||!c.attachment||!row.attachment)return null;
  const url=new URL(row.attachment.url),origin=new URL(base);
  if(url.origin!==origin.origin||url.search||url.hash||url.username||url.password||!/^\/file\/[A-Za-z0-9_.-]{1,128}$/.test(url.pathname)||row.attachment.name!=='codex-command.bin'||row.attachment.size>196636)return null;
  const res=await fetchImpl(url,{redirect:'error',signal:AbortSignal.timeout(10000)});if(!res.ok)return null;
  const chunks=[];let size=0;for await(const chunk of res.body){size+=chunk.length;if(size>196636)return null;chunks.push(chunk);}
  return decode('command',{...wire,encrypted:Buffer.concat(chunks).toString('base64')},key);
}
async function main(directory=__dirname){
  const file=path.join(directory,'connection.json');let config;
  try{config=JSON.parse(fs.readFileSync(file,'utf8').replace(/^\uFEFF/,''));}catch{return;}
  if(!config.remoteEnabled||!config.contentKey||!config.remoteHostId||!validEndpoint(config.endpoint))return;
  if(!config.appServerUrl)try{config.codexExecutable=require('./native-runtime.cjs').resolveNativeExecutable(config.codexExecutable);}catch{return;}
  // Per-host single process lease; stale PIDs are reclaimed after restart, never by age alone.
  const lock=path.join(directory,'remote.lock');try{const pid=Number(fs.readFileSync(lock,'utf8'));process.kill(pid,0);return;}catch{}
  let lease;try{fs.unlinkSync(lock);}catch{}try{lease=fs.openSync(lock,'wx');fs.writeFileSync(lease,String(process.pid));}catch{return;}
  const abort=new AbortController(),outbox=new Map(),resets=new StreamResetTracker(),health=createHealth(directory);let closing=false;
  health.update({});
  const events=eventTopic(config.endpoint,config.contentKey,config.remoteHostId),commands=commandTopic(config.endpoint,config.contentKey,config.remoteHostId);
  const receipts=new SteerReceiptPublisher(async event=>{
    await publishEvent(events,config.contentKey,event);
    health.update({lastPublishedAt:Date.now(),eventsPublished:health.snapshot().eventsPublished+1,lastEventKind:event.status});
  },(event,error)=>{
    outbox.set(event.request_id,event);
    health.update({queuedEvents:outbox.size,lastPublishError:transportFailure(error),lastPublishStatus:Number.isInteger(error.status)?error.status:0});
  });
  const controller=new RemoteController({directory,key:config.contentKey,hostId:config.remoteHostId,endpoint:config.endpoint,
    questionFactory:thread=>new (require('./conversation-questions.cjs').DesktopQuestionReader)(config.codexHome,thread),
    clientFactory:()=>createCodexClient(config),resolve:c=>resolveThread(config,c),
    recover:(thread,turn)=>readSnapshot(config,thread,turn),readLocal:thread=>readLatestSnapshot(config,thread),
    readRevision:thread=>rolloutRevision(config,thread),readContext:thread=>require('./task-content.cjs').readContextUsage(config,thread),emit:async event=>{
      if(receipts.submit(event))return;
      pruneOutbox(outbox);resets.request(event);outbox.set(event.request_id,event);health.update({queuedEvents:outbox.size,lastEventKind:event.status,lastThreadCount:event.threads?.length||0});}});
  if(config.appServerUrl)await controller.restoreSharedTask();
  const dispatcher=new CommandDispatcher(c=>controller.handle(c),()=>health.update({commandsRejected:health.snapshot().commandsRejected+1}));
  const activityWatcher=require('./completion-watch.cjs').watchCompletions(config.codexHome,{
    onActivity:row=>controller.emit({id:require('node:crypto').randomUUID(),request_id:row.thread,host_id:config.remoteHostId,
      thread_id:row.thread,conversation_id:require('node:crypto').createHash('sha256').update(row.thread).digest('hex'),
      status:'thread_activity',seq:++controller.seq,at:row.at,reply:'',turn_id:row.turn,activity_running:row.running,activity_completed:row.completed}),
    onCompletion:async(payload,file,end)=>{const notify=require('./notify.cjs');notify.enqueueCompletion(config,directory,payload,file,end);await notify.main(['--drain'],directory);}
  });
  const shutdown=()=>{if(closing)return;closing=true;health.update({connected:false});activityWatcher.close();dispatcher.close();receipts.close();abort.abort();controller.close();fs.closeSync(lease);try{if(fs.readFileSync(lock,'utf8')===String(process.pid))fs.unlinkSync(lock);}catch{}};
  process.on('SIGINT',shutdown);process.on('SIGTERM',shutdown);
  const monitor=setInterval(()=>{try{const latest=JSON.parse(fs.readFileSync(file,'utf8').replace(/^\uFEFF/,''));
    if(!latest.remoteEnabled||latest.contentKey!==config.contentKey||latest.remoteHostId!==config.remoteHostId||latest.endpoint!==config.endpoint||latest.appServerUrl!==config.appServerUrl)shutdown();
  }catch{shutdown();}},2000);
  const published=new Map(),liveInterval=livePublishInterval(config.endpoint);let lastLivePublish=0;
  const sender=(async()=>{while(!closing){const before=outbox.size;pruneOutbox(outbox);if(outbox.size!==before)health.update({queuedEvents:outbox.size});for(const [id,e] of outbox){if(closing)break;try{
    if(e.status==='running'&&Date.now()-lastLivePublish<liveInterval)continue;
    await publishEvent(events,config.contentKey,compactFrame(published.get(id),resets.frame(e)));resets.acknowledge(e);published.set(id,e);
    if(published.size>64)published.delete(published.keys().next().value);
    if(e.status==='running')lastLivePublish=Date.now();if(outbox.get(id)===e)outbox.delete(id);
    health.update({lastPublishedAt:Date.now(),eventsPublished:health.snapshot().eventsPublished+1,queuedEvents:outbox.size,lastPublishError:'',lastPublishStatus:0});
  }catch(error){health.update({lastPublishError:transportFailure(error),lastPublishStatus:Number.isInteger(error.status)?error.status:0});await wait(error.retryAfter||5000,abort.signal);break;}}await wait(SENDER_IDLE_MS,abort.signal);}})();
  let backoff=2000;
  try{while(!closing){try{
    await subscribeCommands(commands+'/json?since='+Math.floor((Date.now()-120000)/1000),async row=>{
      backoff=2000;
      if(!health.snapshot().connected)health.update({connected:true,lastConnectAt:Date.now(),lastReceiveError:''});
      if(row.event!=='message'||closing)return;
      const c=await downloadCommand(commands,row,config.contentKey);
      if(c){
        if(validateCommand(c,config.remoteHostId)){
          if(dispatcher.submit(c))health.update({lastCommandAt:Date.now(),commandsReceived:health.snapshot().commandsReceived+1,lastCommandKind:c.action});
        }else health.update({commandsRejected:health.snapshot().commandsRejected+1});
      }
    },{signal:abort.signal});
    health.update({connected:false});if(!closing)await wait(backoff,abort.signal);
  }catch(error){health.update({connected:false,...(!closing?{lastReceiveError:transportFailure(error)}:{})});if(!closing)await wait(retryDelay(error,backoff),abort.signal);backoff=Math.min(backoff*2,60000);}}}
  finally{clearInterval(monitor);shutdown();await Promise.all([sender,receipts.idle()]);}
}
if(require.main===module)main().catch(()=>{process.exitCode=1;});
module.exports={main,publishEvent,readStream,downloadCommand,createHealth,transportFailure,subscribeCommands,CommandDispatcher,SteerReceiptPublisher,retryDelay,pruneOutbox};
