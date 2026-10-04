'use strict';
// Protocol boundary: no arbitrary RPC, paths, shell arguments or credentials from the phone.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {encryptContent,decryptContent}=require('./task-content.cjs');
const library=require('./conversation-library.cjs');
const goals=require('./remote-goal.cjs');
const optionsProtocol=require('./remote-options.cjs');
const uploads=require('./remote-attachments.cjs');
const images=require('./conversation-images.cjs');
const {LIVE_FLUSH_MS,WATCH_POLL_MS}=require('./remote-stream.cjs');
const {LiveWatch,liveTail}=require('./live-watch.cjs');
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const isUuid=value=>typeof value==='string'&&UUID.test(value);
const hash=s=>crypto.createHash('sha256').update(s).digest('hex');
const bounded=(s,n)=>Buffer.from(String(s||'')).subarray(0,n).toString('utf8').replace(/\uFFFD$/,'');
function providerFailure(error){
  const info=error?.codexErrorInfo;
  const kind=typeof info==='string'?info:Object.keys(info||{})[0];
  const status=typeof info==='object'&&info?info[kind]?.httpStatusCode:undefined;
  if(status===429||['rateLimitExceeded','usageLimitExceeded'].includes(kind))return 'RATE_LIMIT';
  if(status===401||kind==='unauthorized')return 'AUTH_REQUIRED';
  if(status===403)return 'ACCESS_DENIED';
  if(kind==='contextWindowExceeded')return 'CONTEXT_FULL';
  if(status>=500||['serverOverloaded','internalServerError','flexUnavailable'].includes(kind))return 'PROVIDER_UNAVAILABLE';
  if(['httpConnectionFailed','responseStreamDisconnected','responseStreamConnectionFailed'].includes(kind))return 'PROVIDER_CONNECTION';
  return 'CODEX_ERROR';
}
function topic(endpoint,key,host,direction){
  const url=new URL(endpoint);
  if(url.protocol!=='https:'||url.username||url.password||url.search||url.hash||!UUID.test(host))throw Error('INVALID_PAIRING');
  // ntfy routes permit at most 64 characters; retain 240 bits of the keyed digest.
  url.pathname='/cu-'+crypto.createHmac('sha256',Buffer.from(key,'base64')).update('CodexUsage:2.0:'+direction+':'+host).digest('hex').slice(0,60);
  return url.href;
}
const commandTopic=(e,k,h)=>topic(e,k,h,'commands');
const eventTopic=(e,k,h)=>topic(e,k,h,'events');
function encode(direction,id,value,key){
  if(!['command','event'].includes(direction)||!UUID.test(id))throw Error('INVALID_PROTOCOL');
  return {schema_version:'2.0',id,encrypted:encryptContent(value,key,'CodexUsage:2.0:'+direction+':'+id)};
}
function decode(direction,wire,key){
  try{
    if(wire?.schema_version!=='2.0'||!UUID.test(wire.id)||typeof wire.encrypted!=='string')return null;
    const value=decryptContent(wire.encrypted,key,'CodexUsage:2.0:'+direction+':'+wire.id);
    return value?.id===wire.id?value:null;
  }catch{return null;}
}
function inputAnswersShape(answers){
  return !!(answers&&typeof answers==='object'&&!Array.isArray(answers)&&Object.keys(answers).length>=1&&Object.keys(answers).length<=6&&
    Object.entries(answers).every(([id,values])=>Buffer.byteLength(id)<=128&&Array.isArray(values)&&values.length===1&&
      values.every(v=>typeof v==='string'&&v.trim()&&Buffer.byteLength(v)<=4096))&&Buffer.byteLength(JSON.stringify(answers))<=16384);
}
function normalizeUserInput(p){
  if(typeof p.isBlocking!=='boolean'||typeof p.turnId!=='string'||p.turnId.length<1||p.turnId.length>128||
    !Array.isArray(p.questions)||p.questions.length<1||p.questions.length>6)return null;
  const ids=new Set(),questions=[];
  for(const q of p.questions){
    if(!q||typeof q.id!=='string'||!q.id||Buffer.byteLength(q.id)>128||ids.has(q.id)||
      typeof q.question!=='string'||!q.question.trim()||Buffer.byteLength(q.question)>7200||
      typeof q.header!=='string'||Buffer.byteLength(q.header)>160||
      (q.isOther!==undefined&&typeof q.isOther!=='boolean')||(q.isSecret!==undefined&&typeof q.isSecret!=='boolean')||
      (q.options!=null&&(!Array.isArray(q.options)||q.options.length>8)))return null;
    ids.add(q.id);const options=[];
    for(const o of q.options||[]){
      if(!o||typeof o.label!=='string'||!o.label.trim()||Buffer.byteLength(o.label)>160||
        typeof o.description!=='string'||Buffer.byteLength(o.description)>1024||options.some(v=>v.label===o.label))return null;
      options.push({label:o.label,description:o.description});
    }
    questions.push({id:q.id,header:q.header,question:q.question,is_other:q.isOther===true,is_secret:q.isSecret===true,options});
  }
  return {id:crypto.randomUUID(),turn_id:p.turnId,is_blocking:p.isBlocking,questions};
}
function validInputAnswers(input,answers){
  return inputAnswersShape(answers)&&Object.keys(answers).length===input.questions.length&&input.questions.every(q=>
    Object.hasOwn(answers,q.id)&&(q.is_other||!q.options.length||q.options.some(o=>o.label===answers[q.id][0])));
}
function validateCommand(c,host,now=Date.now()){
  return !!(c&&typeof c==='object'&&!Array.isArray(c)&&isUuid(c.id)&&c.host_id===host&&isUuid(c.thread_id)&&c.conversation_id===hash(c.thread_id)&&
    ['send','create','stop','approve','answer_input','status','models','threads','read','history','activities','rename','archive','unarchive','fork','queue','steer','cancel_queue','presence','session_info','skills','image',...goals.ACTIONS].includes(c.action)&&goals.validGoalCommand(c)&&Number.isSafeInteger(c.issued_at)&&now-c.issued_at<=120000&&c.issued_at-now<=15000&&
    (!['send','create','queue','steer'].includes(c.action)||(typeof c.text==='string'&&c.text.trim()&&Buffer.byteLength(c.text)<=16384&&typeof c.baseline_turn==='string'&&c.baseline_turn.length<=128))&&
    (!['queue','steer'].includes(c.action)||typeof c.expected_turn_id==='string'&&c.expected_turn_id.length>0&&c.expected_turn_id.length<=128)&&
    (c.action!=='cancel_queue'||isUuid(c.queue_id))&&
    (c.action!=='steer'||!c.queue_id||isUuid(c.queue_id))&&
    (c.action!=='image'||typeof c.image_id==='string'&&/^[a-f0-9]{64}$/.test(c.image_id))&&
    (c.action!=='answer_input'||isUuid(c.input_id)&&typeof c.expected_turn_id==='string'&&c.expected_turn_id.length>0&&c.expected_turn_id.length<=128&&inputAnswersShape(c.answers))&&
    (c.action!=='create'||typeof c.project_id==='string'&&(c.project_id==='default'||/^[a-f0-9]{64}$/.test(c.project_id)))&&
    (!['read','history','activities','rename','archive','unarchive'].includes(c.action)||isUuid(c.read_thread_id))&&
    (c.action!=='fork'||c.read_thread_id===c.thread_id&&typeof c.expected_turn_id==='string'&&c.expected_turn_id.length>0&&c.expected_turn_id.length<=128)&&
    (c.watch===undefined||typeof c.watch==='boolean'&&(!c.watch||c.action==='read'))&&
    (c.mode===undefined||['','default','plan'].includes(c.mode))&&
    (c.stream_version===undefined||c.stream_version===0||c.stream_version===1)&&
    (c.permission===undefined||optionsProtocol.PERMISSIONS.includes(c.permission))&&
    uploads.validAttachments(c.attachments)&&(!c.attachments?.length||['send','create'].includes(c.action))&&
    (c.skill_ids===undefined||Array.isArray(c.skill_ids)&&c.skill_ids.length<=4&&new Set(c.skill_ids).size===c.skill_ids.length&&c.skill_ids.every(v=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v)))&&
    (c.action!=='rename'||typeof c.text==='string'&&c.text.trim()&&Buffer.byteLength(c.text)<=240&&!/[\r\n\0]/.test(c.text))&&
    (c.cursor===undefined||typeof c.cursor==='string'&&Buffer.byteLength(c.cursor)<=4096)&&
    (c.archived===undefined||typeof c.archived==='boolean')&&
    (c.search===undefined||typeof c.search==='string'&&Buffer.byteLength(c.search)<=240&&!/[\r\n\0]/.test(c.search))&&
    ['model','effort'].every(k=>c[k]===undefined||(typeof c[k]==='string'&&c[k].length<=128&&!/[\r\n\0]/.test(c[k])))&&
    (['send','create','models','threads','read','history','activities','rename','archive','unarchive','fork','presence','session_info','skills','image',...goals.ACTIONS].includes(c.action)||isUuid(c.target_id))&&(c.action!=='approve'||(isUuid(c.approval_id)&&typeof c.allow==='boolean'&&typeof c.expected_turn_id==='string'&&c.expected_turn_id.length>0&&c.expected_turn_id.length<=128)));
}
function normalizeModels(models){
  const seen=new Set();return models.filter(m=>!m.hidden&&typeof m.model==='string'&&m.model.length<=128&&
    !/[\r\n\0]/.test(m.model)&&!seen.has(m.model)&&seen.add(m.model)).slice(0,100).map(m=>({
      model:m.model,name:bounded(m.displayName||m.model,160),
      efforts:(m.supportedReasoningEfforts||[]).map(e=>e.reasoningEffort).filter(e=>typeof e==='string'&&e.length<=32&&!/[\r\n\0]/.test(e)).slice(0,16),
      default_effort:typeof m.defaultReasoningEffort==='string'?m.defaultReasoningEffort:''
  }));
}
function normalizeModes(values){
  return [...new Set((Array.isArray(values)?values:[]).filter(v=>v&&['plan','default'].includes(v.mode)).map(v=>v.mode))];
}
async function selection(client,c,local){
  if(!c.model&&!c.effort&&!c.mode)return {};
  if(c.mode){let modes=[];try{modes=normalizeModes(await client.modes());}catch{}if(!modes.includes(c.mode))throw Error('MODE_UNAVAILABLE');}
  const raw=await client.models(),models=normalizeModels(raw);
  const selected=c.model||local.model||(c.mode?raw.find(m=>m.isDefault&&!m.hidden)?.model:'');
  const model=models.find(m=>m.model===selected);
  if(!model||(c.effort&&!model.efforts.includes(c.effort)))throw Error('INVALID_MODEL_SELECTION');
  const inherited=c.mode&&model.efforts.includes(local.effort)?local.effort:'';
  return {model:model.model,effort:c.effort||inherited||(model.efforts.includes(model.default_effort)?model.default_effort:''),...(c.mode?{mode:c.mode}:{})};
}
function resolveThread(config,c){
  if(!UUID.test(c.thread_id))throw Error('INVALID_THREAD');
  const root=path.join(config.codexHome,'sessions');let found;
  function walk(dir){for(const e of fs.readdirSync(dir,{withFileTypes:true})){
    if(e.isSymbolicLink())continue;const file=path.join(dir,e.name);
    if(e.isDirectory())walk(file);else if(e.isFile()&&e.name.endsWith(c.thread_id+'.jsonl'))found=file;
  }}
  walk(root);if(!found)throw Error('MISSING_THREAD');
  if(!fs.realpathSync(found).startsWith(fs.realpathSync(root)+path.sep))throw Error('INVALID_THREAD');
  // Metadata is at the beginning; final status is at the end. Bound large histories.
  const size=fs.statSync(found).size,fd=fs.openSync(found,'r');let first,tail;
  try{
    first=Buffer.alloc(Math.min(size,65536));fs.readSync(fd,first,0,first.length,0);
    tail=Buffer.alloc(Math.min(size,4*1024*1024));fs.readSync(fd,tail,0,tail.length,size-tail.length);
  }finally{fs.closeSync(fd);}
  let meta;for(const line of first.toString('utf8').split('\n')){try{const r=JSON.parse(line);if(r.type==='session_meta'){meta=r.payload;break;}}catch{}}
  if(meta?.id!==c.thread_id||typeof meta.cwd!=='string'||!path.isAbsolute(meta.cwd)||meta.source?.subagent||!fs.existsSync(meta.cwd))throw Error('INVALID_THREAD');
  let status,turn,model,effort;
  const lines=tail.toString('utf8').split('\n');if(size>tail.length)lines.shift();lines.pop();
  for(const line of lines){try{const r=JSON.parse(line),p=r.payload;
    if(r.type==='turn_context'){if(typeof p?.model==='string')model=p.model;if(typeof p?.effort==='string')effort=p.effort;if(p?.turn_id)turn=p.turn_id;}
    if(r.type==='event_msg'&&['task_started','task_complete','turn_aborted'].includes(p?.type)){status=p.type;if(p.turn_id)turn=p.turn_id;}
  }catch{}}
  if(status==='task_started')throw Error('BUSY');
  // Native paginated forks keep copied turns in history_base, not legacy event_msg rows.
  // Their terminal baseline must be checked with the public history API before every start.
  if(meta.history_mode==='paginated'&&UUID.test(meta.forked_from_id))return {cwd:meta.cwd,model,effort,modelProvider:meta.model_provider,requiresNativeBaseline:true};
  if(!['task_complete','turn_aborted'].includes(status)||turn!==c.baseline_turn)throw Error('STALE');
  return {cwd:meta.cwd,model,effort,modelProvider:meta.model_provider};
}
async function checkNativeBaseline(client,c,local){
  if(!local.requiresNativeBaseline)return;
  const page=await client.call('thread/turns/list',{threadId:c.thread_id,limit:1,sortDirection:'desc',itemsView:'summary'});
  const last=page.data?.[0];if(last?.status==='inProgress')throw Error('BUSY');
  if(!last||last.id!==c.baseline_turn||!['completed','interrupted','failed'].includes(last.status))throw Error('STALE');
}
const activity=require('./remote-activity.cjs');
class RemoteController{
  constructor({directory,key,hostId,clientFactory,resolve,emit,recover,readLocal,readRevision,readContext,endpoint,imageFetch}){
    Object.assign(this,{directory,key,hostId,clientFactory,resolve,emit,recover,readLocal,readRevision,readContext,endpoint,imageFetch});this.active=null;this.seq=Date.now();
    this.file=path.join(directory,'remote-journal.json');this.journal={};
    try{this.journal=JSON.parse(fs.readFileSync(this.file,'utf8'));}catch{}
    this.seq=Math.max(this.seq,...Object.values(this.journal).map(r=>Number.isSafeInteger(r.seq)?r.seq:0));
    for(const r of Object.values(this.journal)){
      if(['accepted','running','approval','input_required'].includes(r.status))r.status='unknown';
      if(r.status==='queued'){r.status='cancelled';r.error='QUEUE_INTERRUPTED';}
    }
  }
  persist(){
    fs.mkdirSync(this.directory,{recursive:true});
    const entries=Object.entries(this.journal).slice(-512);this.journal=Object.fromEntries(entries);
    fs.writeFileSync(this.file+'.tmp',JSON.stringify(this.journal),{mode:0o600});fs.renameSync(this.file+'.tmp',this.file);
  }
  stopWatching(){
    const w=this.watching;this.watching=null;
    if(w){clearInterval(w.timer);clearTimeout(w.flush);w.client?.close();}
  }
  async startWatching(c){
    // One viewed conversation per pairing. Shared mode rejoins the existing native
    // event subscription without any model/provider/permission overrides or generation.
    // Independent stdio mode remains a persisted-history reader and never resumes.
    this.stopWatching();
    const w={c,expiresAt:Date.now()+600000,fingerprint:'',revision:'',lastRead:0,busy:false};this.watching=w;
    await this.pollWatch(w);
    if(this.watching===w){w.timer=setInterval(()=>this.pollWatch(w).catch(()=>{}),WATCH_POLL_MS);w.timer.unref?.();}
  }
  onWatchEvent(w,method,p){
    if(this.watching!==w)return;
    if(method==='bridge/disconnected'){
      w.client=null;w.live=null;w.revision='';w.lastRead=0;return;
    }
    if(!w.live?.apply(method,p))return;
    if(method==='turn/completed'){
      clearTimeout(w.flush);w.flush=null;this.emitWatch(w,liveTail(w.live.value)).catch(()=>{});
    }else if(!w.flush){
      w.flush=setTimeout(()=>{w.flush=null;if(this.watching===w&&w.live)this.emitWatch(w,liveTail(w.live.value)).catch(()=>{});},200);
      w.flush.unref?.();
    }
  }
  async emitWatch(w,value){
    if(this.watching!==w||Date.now()>=w.expiresAt)return;
    const snapshot=structuredClone(value),fingerprint=hash(JSON.stringify(snapshot));
    if(fingerprint===w.fingerprint)return;w.fingerprint=fingerprint;
    await this.emit({id:crypto.randomUUID(),request_id:w.c.id,host_id:this.hostId,thread_id:w.c.thread_id,
      conversation_id:w.c.conversation_id,status:'snapshot',seq:++this.seq,at:Date.now(),reply:'',snapshot});
  }
  async pollWatch(w=this.watching){
    if(!w||this.watching!==w||w.busy)return;
    if(Date.now()>=w.expiresAt){this.stopWatching();return;}
    w.busy=true;
    try{
      if(!w.client){w.client=this.clientFactory();w.client.setHandlers((m,p)=>this.onWatchEvent(w,m,p),id=>w.client?.reject(id));await w.client.connect();}
      const meta=(await w.client.call('thread/read',{threadId:w.c.read_thread_id,includeTurns:false})).thread;
      if(meta?.id!==w.c.read_thread_id)throw Error('MISSING_THREAD');
      // Native events supply the live tail. A slower reconciliation is retained for
      // missed notifications; a persistence read must never overwrite newer deltas.
      const settling=w.live?.value.running&&['idle','notLoaded'].includes(meta.status?.type);
      const reconcileMs=settling?2000:60000;
      if(w.live&&Date.now()-w.lastRead<reconcileMs)return;
      const local=this.readRevision?.(w.c.read_thread_id)||'';
      const revision=JSON.stringify([meta.updatedAt,meta.name,meta.title,meta.status?.type,local]);
      if(revision===w.revision&&Date.now()-w.lastRead<reconcileMs)return;
      const live=w.live,version=live?.version;
      const snapshot=await library.readSnapshot(w.client,w.c.read_thread_id,this.hostId,this.readLocal,meta);
      if(this.watching!==w)return;
      w.revision=revision;w.lastRead=Date.now();
      const confirmsTerminal=settling&&!snapshot.running&&snapshot.remote_ref?.baseline_turn===live?.turn&&
        snapshot.remote_ref?.host_id===this.hostId&&snapshot.remote_ref?.thread_id===w.c.read_thread_id;
      if(live&&(live.version!==version||live.value.running&&!confirmsTerminal||
        live.version>0&&snapshot.remote_ref?.baseline_turn!==live.turn))return;
      await this.emitWatch(w,snapshot);
      if(w.client.supportsLiveWatch){
        w.live=new LiveWatch(w.c.read_thread_id,this.hostId,snapshot,meta.cwd);
        if(!live){
          // Public thread/resume subscribes this socket to the SAME loaded thread.
          // In particular do not use client.resume(), which applies execution settings.
          try{
            const subscribed=w.live,revision=subscribed.version;
            await w.client.call('thread/resume',{threadId:w.c.read_thread_id,excludeTurns:true});
            const fresh=await library.readSnapshot(w.client,w.c.read_thread_id,this.hostId,this.readLocal);
            if(this.watching===w&&w.live===subscribed&&subscribed.version===revision){
              w.live=new LiveWatch(w.c.read_thread_id,this.hostId,fresh,meta.cwd);await this.emitWatch(w,fresh);
            }
          }
          catch{w.live=null;} // Version incompatibility falls back to persisted history.
        }
      }
    }catch{
      if(this.watching===w){
        await this.emit({id:crypto.randomUUID(),request_id:w.c.id,host_id:this.hostId,thread_id:w.c.thread_id,
          conversation_id:w.c.conversation_id,status:'snapshot',seq:++this.seq,at:Date.now(),reply:'',error:'LIBRARY_UNAVAILABLE'});
        this.stopWatching();
      }
    }finally{w.busy=false;}
  }
  async update(c,status,extra={}){
    const active=this.active?.c.id===c.id?this.active:null;
    if(active)extra={active_input:active.input||c.text,
      ...(active.goalTask?{goal:active.goal,goal_waiting:active.goalWaiting===true}:{}),
      activities:active.activities||[],
      queued_entries:(active.queue||[]).map(q=>({id:q.id,text:bounded(q.text,512)})),...extra};
    const e={id:crypto.randomUUID(),request_id:c.id,host_id:this.hostId,thread_id:c.thread_id,conversation_id:c.conversation_id,
      status,seq:++this.seq,at:Date.now(),reply:'',stream_version:c.stream_version===1?1:0,...extra};
    e.reply=bounded(e.reply,65536);
    while(Buffer.byteLength(JSON.stringify(e))>196608){
      if(e.reply){e.reply=bounded(e.reply,Math.floor(Buffer.byteLength(e.reply)/2));e.partial=true;}
      else if(e.snapshot?.messages?.length)e.snapshot={...e.snapshot,messages:e.snapshot.messages.slice(1),truncated:true};
      else if(e.activities?.length)e.activities=e.activities.slice(1);
      else if(e.snapshot?.activities?.length)e.snapshot={...e.snapshot,activities:e.snapshot.activities.slice(1),truncated:true};
      else throw Error('EVENT_TOO_LARGE');
    }
    // The journal is metadata only. Detailed state is independently encrypted at rest.
    this.journal[c.id]={...this.journal[c.id],status,seq:e.seq,thread_id:c.thread_id,turn_id:e.turn_id,
      ...(this.active?.c.id===c.id&&this.active.actualThread?{actual_thread_id:this.active.actualThread}:{})};this.persist();
    fs.writeFileSync(path.join(this.directory,'remote-'+c.id+'.json'),JSON.stringify(encode('event',e.id,e,this.key)),{mode:0o600});
    const stateFiles=fs.readdirSync(this.directory).filter(n=>/^remote-[a-f0-9-]+\.json$/.test(n));
    for(const name of stateFiles.slice(0,Math.max(0,stateFiles.length-128)))fs.unlinkSync(path.join(this.directory,name));
    await this.emit(e);return e;
  }
  async replay(c,id){
    const old=this.journal[id];if(!old||old.thread_id!==c.thread_id)return;
    let state;try{state=decode('event',JSON.parse(fs.readFileSync(path.join(this.directory,'remote-'+id+'.json'),'utf8')),this.key);}catch{}
    if(old.status==='unknown'&&UUID.test(old.actual_thread_id)&&UUID.test(old.fork_source)&&old.fork_source===c.read_thread_id&&old.thread_id===old.fork_source){
      let client;
      try{
        client=this.clientFactory();client.setHandlers(()=>{},id=>client.reject(id));await client.connect();
        const snapshot=await library.readSnapshot(client,old.actual_thread_id,this.hostId);
        if(snapshot.remote_ref?.thread_id!==old.actual_thread_id)throw Error('FORK_SNAPSHOT_UNAVAILABLE');
        delete old.error;
        await this.update({...c,id},'forked',{snapshot,forked_from_thread_id:old.fork_source});return;
      }catch{}finally{client?.close();}
    }
    if(old.status==='unknown'&&old.goal_task)state={...state,error:'GOAL_UNCONFIRMED'};
    if(old.status==='unknown'&&!old.goal_task&&old.turn_id&&this.recover){
      try{const recovered=this.recover(old.actual_thread_id||c.thread_id,old.turn_id);if(recovered){old.status='completed';state={...state,reply:recovered.reply,turn_id:old.turn_id,...(old.actual_thread_id?{snapshot:recovered}:{})};}}catch{}
    }
    await this.update({...c,id},old.status,{...state,stream_reset:true,id:crypto.randomUUID(),seq:++this.seq,at:Date.now(),status:old.status,...(old.error?{error:old.error}:{})});
  }
  async handle(c){
    if(!validateCommand(c,this.hostId))return;
    if(goals.ACTIONS.includes(c.action)){await goals.handleGoal(this,c);return;}
    if(c.action==='image'){
      const base={id:crypto.randomUUID(),request_id:c.id,host_id:this.hostId,thread_id:c.thread_id,conversation_id:c.conversation_id,status:'image',seq:++this.seq,at:Date.now(),reply:''};
      if((this.activeImages||0)>=3){await this.emit({...base,error:'IMAGE_BUSY'});return;}
      this.activeImages=(this.activeImages||0)+1;let client;
      try{
        if(!images.hasImage(c.thread_id,c.image_id)){
          // A bridge restart clears the source cache. Recover only from bounded official history pages.
          client=this.clientFactory();client.setHandlers(()=>{},id=>client.reject(id));await client.connect();
          const meta=(await client.call('thread/read',{threadId:c.thread_id,includeTurns:false})).thread;
          if(meta?.id!==c.thread_id)throw Error('IMAGE_UNAVAILABLE');
          let cursor='';
          for(let n=0;n<12;n++){
            const page=await library.historyPage(client,c.thread_id,cursor);
            library.snapshot({...meta,turns:page.turns},this.hostId);
            if(page.messages.some(m=>m.images?.some(i=>i.id===c.image_id)))break;
            cursor=page.next_cursor;if(!cursor)break;
          }
        }
        await this.emit({...base,image:await images.publishImage(this,c,this.imageFetch)});
      }catch(e){await this.emit({...base,error:['IMAGE_TOO_LARGE','IMAGE_UNSUPPORTED','IMAGE_TRANSFER'].includes(e.message)?e.message:'IMAGE_UNAVAILABLE'});}
      finally{client?.close();this.activeImages--;}return;
    }
    if(['session_info','skills'].includes(c.action)){
      let client;const base={id:crypto.randomUUID(),request_id:c.id,host_id:this.hostId,thread_id:c.thread_id,conversation_id:c.conversation_id,status:c.action,seq:++this.seq,at:Date.now()};
      try{client=this.clientFactory();client.setHandlers(()=>{},id=>client.reject(id));await client.connect();
        await this.emit({...base,...(c.action==='session_info'?await optionsProtocol.status(this,client,c):await optionsProtocol.catalog(client,c))});
      }catch{await this.emit({...base,error:c.action==='session_info'?'STATUS_UNAVAILABLE':'SKILLS_UNAVAILABLE'});}
      finally{client?.close();}return;
    }
    if(c.action==='read'&&c.watch){await this.startWatching(c);return;}
    if(c.action==='presence'){
      await this.emit({id:crypto.randomUUID(),request_id:c.id,host_id:this.hostId,thread_id:c.thread_id,conversation_id:c.conversation_id,
        status:'presence',seq:++this.seq,at:Date.now(),reply:'',host_name:bounded(require('node:os').hostname(),160)});return;
    }
    if(['threads','read','history','activities','rename','archive','unarchive','fork'].includes(c.action)){
      const managing=['rename','archive','unarchive','fork'].includes(c.action);
      if(managing&&this.journal[c.id]){await this.replay(c,c.id);return;}
      if(this.libraryBusy)return;this.libraryBusy=true;let client;
      const base={id:crypto.randomUUID(),request_id:c.id,host_id:this.hostId,thread_id:c.thread_id,conversation_id:c.conversation_id,
        status:c.action==='threads'?'threads':c.action==='history'?'history':c.action==='activities'?'activities':['rename','archive','unarchive'].includes(c.action)?'managed':'snapshot',seq:++this.seq,at:Date.now(),reply:''};
      try{
        client=this.clientFactory();client.setHandlers(()=>{},id=>client.reject(id));await client.connect();
        if(c.action==='threads')await this.emit({...base,...await library.threadPage(client,{cursor:c.cursor,archived:c.archived,search:c.search})});
        else if(c.action==='fork'){
          this.journal[c.id]={status:'unknown',thread_id:c.thread_id,error:'FORK_UNCONFIRMED'};this.persist();
          const result=await library.forkThread(client,c.read_thread_id,c.expected_turn_id,this.hostId,actual=>{
            this.journal[c.id]={...this.journal[c.id],actual_thread_id:actual,fork_source:c.read_thread_id};this.persist();
          });
          delete this.journal[c.id].error;
          await this.update(c,'forked',result);
        }
        else if(['rename','archive','unarchive'].includes(c.action)){
          // Record before a mutation; replay must never restore an older name after reconnect.
          this.journal[c.id]={status:'unknown',thread_id:c.thread_id};this.persist();
          await this.update(c,'managed',await library.manageThread(client,c.action,c.read_thread_id,c.text));
        }
        else if(c.action==='activities')await this.emit({...base,...await library.activityPage(client,c.read_thread_id,c.cursor)});
        else if(c.action==='history'){
          const page=await library.historyPage(client,c.read_thread_id,c.cursor);
          await this.emit({...base,messages:page.messages,activities:page.activities,next_cursor:page.next_cursor,history_truncated:page.truncated});
        }else{
          await this.emit({...base,snapshot:await library.readSnapshot(client,c.read_thread_id,this.hostId,this.readLocal)});
        }
      }catch(e){
        if(c.action==='fork'){
          const known=['BUSY','STALE','MISSING_THREAD','INVALID_THREAD','FORK_MISMATCH'].includes(e.message);
          const error=known?e.message:e.rpcCode===-32601?'FORK_UNAVAILABLE':e.rpcCode?'FORK_FAILED':'FORK_UNCONFIRMED';
          if(this.journal[c.id])this.journal[c.id].error=error;
          await this.update(c,known||e.rpcCode?'failed':'unknown',{error});
        }else await this.emit({...base,error:e.message==='BUSY'?'BUSY':c.action==='history'&&e.rpcCode===-32601?'HISTORY_UNAVAILABLE':'LIBRARY_UNAVAILABLE'});
      }
      finally{client?.close();this.libraryBusy=false;}return;
    }
    if(c.action==='models'){
      if(this.catalogBusy)return;this.catalogBusy=true;let client;
      const base={id:crypto.randomUUID(),request_id:c.id,host_id:this.hostId,thread_id:c.thread_id,conversation_id:c.conversation_id,status:'models',seq:++this.seq,at:Date.now(),reply:'',host_name:bounded(require('node:os').hostname(),160)};
      try{
        let local={};try{local=this.resolve(c);}catch{}client=this.clientFactory();client.setHandlers(()=>{},id=>client.reject(id));await client.connect();
        const raw=await client.models(),models=normalizeModels(raw);let modes=[];
        try{modes=normalizeModes(await client.modes());}catch{} // Older hosts can still offer models.
        await this.emit({...base,models,modes,model:local.model||raw.find(m=>m.isDefault&&!m.hidden)?.model||'',effort:local.effort||''});
      }catch{await this.emit({...base,models:[],error:'MODEL_LIST_UNAVAILABLE'});}
      finally{client?.close();this.catalogBusy=false;}return;
    }
    if(c.action==='status'){await this.replay(c,c.target_id);return;}
    if(['queue','steer','cancel_queue','presence'].includes(c.action)){
      if(this.journal[c.id]){await this.replay(c,c.id);return;}
      const a=this.active;
      if(c.action==='steer'&&(!a||a.c.id!==c.target_id||a.c.thread_id!==c.thread_id)){
        const w=this.watching;
        if(c.queue_id||!w||w.c.id!==c.target_id||w.c.read_thread_id!==c.thread_id||w.c.thread_id!==c.thread_id||
          !w.client?.supportsLiveWatch||!w.live?.value.running||w.live.turn!==c.expected_turn_id){
          await this.update(c,'failed',{error:'STALE_ACTIVE_TURN'});return;
        }
        this.journal[c.id]={status:'unknown',thread_id:c.thread_id};this.persist();
        try{
          // Steer the already running desktop turn. Never defer to turn/start after completion.
          const result=await w.client.call('turn/steer',{threadId:c.thread_id,expectedTurnId:c.expected_turn_id,
            input:[{type:'text',text:c.text}],clientUserMessageId:c.id});
          if(result.turnId!==c.expected_turn_id)throw Error('STEER_MISMATCH');
          await this.update(c,'steered',{turn_id:result.turnId,parent_request_id:w.c.id});
        }catch(e){await this.update(c,e.rpcCode?'failed':'unknown',{error:'STEER_REJECTED'});}
        return;
      }
      if(!a||a.c.id!==c.target_id||a.c.thread_id!==c.thread_id||a.ending||a.stopping){await this.update(c,'failed',{error:'NO_ACTIVE_TASK'});return;}
      if(a.goalTask&&c.action==='queue'){await this.update(c,'failed',{error:'GOAL_USE_STEER'});return;}
      if(c.action==='cancel_queue'){
        const q=a.queue.find(q=>q.id===c.queue_id);
        if(!q){await this.update(c,'failed',{error:'QUEUE_ALREADY_STARTED'});return;}
        a.queue=a.queue.filter(q=>q.id!==c.queue_id);await this.update(q,'cancelled');
        await this.update(c,'cancelled');await this.refreshActive(a);return;
      }
      if(a.turn!==c.expected_turn_id){await this.update(c,'failed',{error:'STALE_ACTIVE_TURN'});return;}
      if(c.action==='queue'){
        if(a.queue.length>=4){await this.update(c,'failed',{error:'QUEUE_FULL'});return;}
        a.queue.push(c);
        await this.update(c,'queued',{parent_request_id:a.c.id});await this.refreshActive(a);return;
      }
      // Persist before the RPC. An ambiguous response never repeats the steering instruction.
      let promoted;
      if(c.queue_id){
        promoted=a.queue.find(q=>q.id===c.queue_id);
        if(!promoted||promoted.text!==c.text){await this.update(c,'failed',{error:'QUEUE_ALREADY_STARTED'});return;}
        // Claim before the RPC: a concurrent completion must not also start this text as a new turn.
        a.queue=a.queue.filter(q=>q.id!==promoted.id);
        await this.update(promoted,'unknown',{error:'STEER_UNCONFIRMED',parent_request_id:a.c.id});
      }
      this.journal[c.id]={status:'unknown',thread_id:c.thread_id};this.persist();
      try{
        const result=await a.client.steer(a.actualThread||a.c.thread_id,c.expected_turn_id,c.text,c.id);
        if(result.turnId!==c.expected_turn_id)throw Error('STEER_MISMATCH');
        if(promoted)await this.update(promoted,'steered',{turn_id:result.turnId,parent_request_id:a.c.id});
        await this.update(c,'steered',{turn_id:result.turnId,parent_request_id:a.c.id});
      }catch(e){
        if(promoted)await this.update(promoted,e.rpcCode?'failed':'unknown',{error:'STEER_REJECTED',parent_request_id:a.c.id});
        await this.update(c,e.rpcCode?'failed':'unknown',{error:'STEER_REJECTED'});
      }
      return;
    }
    if(['send','create'].includes(c.action)){
      if(this.journal[c.id]){await this.replay(c,c.id);return;}
      if(this.active||this.goalBusy){await this.update(c,'failed',{error:'HOST_BUSY'});return;}
      const active={c,client:null,turn:null,reply:'',pending:new Map(),items:new Map(),queue:[],activities:[],input:c.text};this.active=active;
      try{
        let local=c.action==='send'?this.resolve(c):{};await this.update(c,'accepted');
        const client=this.clientFactory();active.client=client;
        client.setHandlers((m,p)=>this.event(active,m,p).catch(()=>{}),(id,m,p)=>this.request(active,id,m,p).catch(()=>{}));
        await client.connect();
        const options={...await selection(client,c,local),permission:c.permission||'default'};local.permission=options.permission;active.local=local;
        if(this.active!==active||active.stopping)return;
        if(c.action==='create'){
          const p=await library.creationProject(client,c.project_id,this.directory);
          const result=await client.call('thread/start',{cwd:p.cwd,ephemeral:false,...optionsProtocol.permissionOptions(options.permission).resume});
          active.actualThread=result.thread.id;local={cwd:p.cwd,permission:options.permission};active.local=local;
          await this.update(c,'accepted'); // Persist the actual thread before starting generation.
          await client.call('thread/name/set',{threadId:active.actualThread,name:bounded(c.text.trim().split('\n')[0],160)});
        }else{
          const read=await client.read(c.thread_id);if(read?.thread?.status?.type==='active')throw Error('BUSY');
          this.resolve(c);await checkNativeBaseline(client,c,local);
          try{if((await goals.getGoal(client,c.thread_id))?.status==='active')throw Error('GOAL_ALREADY_ACTIVE');}catch(e){if(e.rpcCode!==-32601)throw e;}
          await client.resume(c.thread_id,local);
          this.resolve(c);await checkNativeBaseline(client,c,local);
        }
        options.inputs=[...await optionsProtocol.selectedSkills(client,c,local),...await uploads.prepareAttachments(this,c)];
        if(this.active!==active||active.stopping)return;
        await this.startTurn(active,active.actualThread||c.thread_id,c.text,c.id,options);
        if(this.active===active){
          if(active.stopping)await this.interruptActive(active);
          else await this.refreshActive(active);
        }
      }catch(e){
        if(this.active!==active)return; // An explicit pre-start stop is already terminal.
        const safe=e.rpcCode===-32600&&typeof e.rpcMessage==='string'&&/already has an active writer/.test(e.rpcMessage)?'DESKTOP_OWNS_THREAD':
          ['BUSY','STALE','MISSING_THREAD','INVALID_THREAD','INVALID_MODEL_SELECTION','MODE_UNAVAILABLE','GOAL_ALREADY_ACTIVE','SKILL_UNAVAILABLE','INVALID_PERMISSION','ATTACHMENT_INVALID','ATTACHMENT_DOWNLOAD','ATTACHMENT_TOO_LARGE','ATTACHMENT_STORAGE_FULL'].includes(e.message)?e.message:'CODEX_ERROR';
        await this.update(c,active.startAttempted?'unknown':'failed',{error:safe});active.client?.close();if(this.active===active)this.active=null;
      }
      return;
    }
    const a=this.active;
    if(!a||a.c.id!==c.target_id||a.c.thread_id!==c.thread_id)return;
    if(c.action==='stop'&&a.goalTask){
      await goals.handleGoal(this,{...c,action:'goal_pause',expected_goal_updated_at:a.goal?.updated_at||0,expected_goal_created_at:a.goal?.created_at||0,expected_goal_hash:a.goal?hash(a.goal.objective):''});return;
    }
    if(c.action==='stop'){
      a.stopping=true;await this.cancelQueue(a,'QUEUE_STOPPED');
      if(!a.startAttempted&&(!a.turn||a.ending)){
        await this.update(a.c,'interrupted');a.client?.close();if(this.active===a)this.active=null;
      }
      else if(a.turn)await this.interruptActive(a);
      // An in-flight turn/start is ambiguous until its real turn ID arrives. Do not claim it stopped yet.
      return;
    }
    if(c.action==='approve'){
      const pending=a.pending.get(c.approval_id);
      if(!pending||pending.kind!=='approval'||pending.turn!==a.turn||c.expected_turn_id!==a.turn||c.allow&&!pending.canAllow)return;
      a.pending.delete(c.approval_id);
      try{await a.client.answer(pending.id,{decision:c.allow?'accept':'decline'});await this.refreshActive(a);}
      catch{await this.cancelQueue(a,'QUEUE_INTERRUPTED');await this.update(a.c,'unknown',{error:'APPROVAL_REPLY_UNCONFIRMED',reply:a.reply});
        a.client.close();if(this.active===a)this.active=null;}
    }
    if(c.action==='answer_input'){
      const pending=a.pending.get(c.input_id);
      if(!pending||pending.kind!=='input'||pending.input.turn_id!==a.turn||c.expected_turn_id!==a.turn||
        !validInputAnswers(pending.input,c.answers))return;
      // Remove before writing: duplicate clicks or a reused native RPC id must never answer again.
      a.pending.delete(c.input_id);
      const answers=Object.fromEntries(Object.entries(c.answers).map(([id,values])=>[id,{answers:values}]));
      try{await a.client.answer(pending.id,{answers});await this.refreshActive(a);}
      catch{await this.cancelQueue(a,'QUEUE_INTERRUPTED');await this.update(a.c,'unknown',{error:'INPUT_REPLY_UNCONFIRMED',reply:a.reply});
        a.client.close();if(this.active===a)this.active=null;}
    }
  }
  holdStartFrame(a,kind,id,method,p){
    const gate=a.startGate;if(!gate)return false;
    const bytes=Buffer.byteLength(JSON.stringify(p));
    if(gate.frames.length>=128||gate.bytes+bytes>262144){gate.overflow=true;return true;}
    gate.frames.push({kind,id,method,p,bytes});gate.bytes+=bytes;return true;
  }
  async startTurn(a,thread,text,id,options){
    // Shared subscribers can see other desktop turns before turn/start replies.
    // Only its RPC response establishes which turn this phone request owns.
    const gate={frames:[],bytes:0,overflow:false};a.startGate=gate;a.startAttempted=true;
    try{
      const started=await a.client.start(thread,text,id,options);
      if(this.active!==a)return;
      if(typeof started?.turn?.id!=='string'||!started.turn.id||started.turn.id.length>128)throw Error('PROTOCOL');
      a.turn=started.turn.id;
      while(this.active===a&&a.startGate===gate){
        if(gate.overflow)throw Error('PROTOCOL');
        const frame=gate.frames.shift();if(!frame)break;gate.bytes-=frame.bytes;
        if(frame.kind==='request')await this.request(a,frame.id,frame.method,frame.p,true);
        else await this.event(a,frame.method,frame.p,true);
      }
    }finally{gate.frames.length=0;if(a.startGate===gate)delete a.startGate;}
  }
  async interruptActive(a){
    if(this.active!==a||!a.turn||a.stopRequestedTurn===a.turn)return;
    a.stopRequestedTurn=a.turn;
    try{await a.client.interrupt(a.actualThread||a.c.thread_id,a.turn);}
    catch{
      if(this.active!==a)return;
      await this.cancelQueue(a,'QUEUE_INTERRUPTED');
      await this.update(a.c,'unknown',{error:'STOP_UNCONFIRMED',reply:a.reply});
      a.client.close();if(this.active===a)this.active=null;
    }
  }
  async refreshActive(a){
    const pending=a.pending.entries().next().value;
    const input=pending?.[1].kind==='input';
    await this.update(a.c,input?'input_required':pending?'approval':'running',{reply:a.reply,turn_id:a.turn,
      ...(input?{user_input:pending[1].input}:pending?{approval_id:pending[0],approval_text:pending[1].text,
        approval_kind:pending[1].approvalKind,approval_item_id:pending[1].item,approval_can_allow:pending[1].canAllow}:{})});
  }
  async cancelQueue(a,error){
    const pending=a.queue.splice(0);for(const q of pending)await this.update(q,'cancelled',{error});
  }
  async request(a,id,method,p,confirmed=false){
    if(this.active!==a)return;
    if(p.threadId!==(a.actualThread||a.c.thread_id)){await a.client.reject(id);return;}
    if(!confirmed&&this.holdStartFrame(a,'request',id,method,p))return;
    if(!a.turn||p.turnId!==a.turn){await a.client.reject(id);return;}
    if(method==='item/tool/requestUserInput'){
      const input=normalizeUserInput(p);
      if(input){
        a.turn=a.turn||p.turnId;
        // Native request ids may be reused; expose a fresh opaque interaction id to the phone.
        a.pending.set(input.id,{id,kind:'input',input});await this.refreshActive(a);return;
      }
    }
    const details=[p.command,p.cwd,p.reason,p.grantRoot].filter(v=>typeof v==='string'&&v).join('\n');
    if(['item/commandExecution/requestApproval','item/fileChange/requestApproval'].includes(method)&&p.threadId===(a.actualThread||a.c.thread_id)&&
      (!p.kind||p.kind==='command')&&typeof p.turnId==='string'&&p.turnId.length>0&&p.turnId.length<=128&&
      typeof p.itemId==='string'&&p.itemId.length>0&&p.itemId.length<=256&&details.length<=1800){
      a.turn=a.turn||p.turnId;
      for(const [key,request] of a.pending)if(request.id===id)a.pending.delete(key);
      const approval=crypto.randomUUID();a.pending.set(approval,{id,kind:'approval',turn:p.turnId,item:p.itemId,
        approvalKind:method==='item/fileChange/requestApproval'?'fileChange':'command',canAllow:!p.grantRoot,text:details.slice(0,1800)});
      await this.refreshActive(a);return;
    }
    // Unsupported desktop tools/user input cannot be silently treated as successful.
    await a.client.reject(id);await this.cancelQueue(a,'UNSUPPORTED_TOOL');await this.update(a.c,'failed',{error:'UNSUPPORTED_TOOL',reply:a.reply});
    if(a.turn)await a.client.interrupt(a.actualThread||a.c.thread_id,a.turn);a.client.close();this.active=null;
  }
  async event(a,method,p,confirmed=false){
    if(this.active!==a)return;
    if(method==='bridge/disconnected'){
      await this.cancelQueue(a,'QUEUE_INTERRUPTED');if(this.active!==a)return;
      await this.update(a.c,'unknown',{reply:a.reply,turn_id:a.turn||a.lastTurn||'',error:'DISCONNECTED'});
      if(this.active===a)this.active=null;return;
    }
    if(p?.threadId!==(a.actualThread||a.c.thread_id))return;
    if(method==='thread/tokenUsage/updated'){a.context=optionsProtocol.normalizeContext(p.tokenUsage);return;}
    if(!confirmed&&this.holdStartFrame(a,'event',null,method,p))return;
    if(method==='serverRequest/resolved'){
      for(const [key,request] of a.pending)if(request.id===p.requestId)a.pending.delete(key);
      await this.refreshActive(a);return;
    }
    if(a.goalTask&&['thread/goal/updated','thread/goal/cleared'].includes(method)){
      a.goal=method==='thread/goal/cleared'?null:goals.normalizeGoal(p.goal,a.c.thread_id);
      if(!a.turn&&a.goal?.status!=='active'){await goals.finishGoal(this,a,a.goal?.status==='complete'?'completed':'interrupted');return;}
      await this.refreshActive(a);return;
    }
    if(method==='turn/started'){
      if(!a.goalTask&&(!a.turn||p.turn?.id!==a.turn)||a.goalTask&&a.turn&&p.turn?.id!==a.turn)return;
      if(a.goalTask){clearTimeout(a.timer);a.timer=null;a.reply='';a.items.clear();a.pending.clear();a.goalWaiting=false;a.ending=false;}
      a.turn=p.turn?.id||a.turn;
      if(a.stopping&&!a.goalTask)await this.interruptActive(a);
      if(a.goalTask)await this.refreshActive(a);return;
    }
    if(!a.turn||p.turnId&&p.turnId!==a.turn)return;
    if(['item/started','item/completed'].includes(method)){
      const item=activity.projectItem(p.turnId||a.turn,p.item);
      if(item){if(method==='item/started'&&!p.item.status)item.status='inProgress';a.activities=activity.mergeActivities(a.activities,[item]);await this.refreshActive(a);}
    }
    if(method==='turn/diff/updated'&&typeof p.diff==='string'){
      const item={id:a.turn+':diff',turn_id:a.turn,type:'diff',status:'inProgress',title:'',detail:activity.limit(p.diff,16384),files:[],truncated:Buffer.byteLength(p.diff)>16384};
      a.activities=activity.mergeActivities(a.activities,[item]);await this.refreshActive(a);
    }
    if(method==='item/agentMessage/delta'){
      const id=p.itemId||'message';a.items.set(id,bounded((a.items.get(id)||'')+String(p.delta||''),65536));
      if(a.items.size>60)a.items.delete(a.items.keys().next().value);
      a.reply=bounded([...a.items.values()].join('\n\n'),65536);
      if(!a.timer)a.timer=setTimeout(()=>{
        a.timer=null;if(this.active!==a)return;
        this.refreshActive(a).catch(()=>{});
      },LIVE_FLUSH_MS);
    }
    if(method==='item/completed'&&p.item?.type==='agentMessage'){
      a.items.set(p.item.id,bounded(p.item.text,65536));a.reply=bounded([...a.items.values()].join('\n\n'),65536);
    }
    if(method==='turn/completed'){
      if(a.turn&&p.turn?.id!==a.turn)return;
      a.ending=true;a.startAttempted=false;clearTimeout(a.timer);a.timer=null;const status=p.turn?.status;
      const terminal=status==='completed'?'completed':status==='interrupted'?'interrupted':'failed';
      a.activities=a.activities.map(item=>item.type==='diff'&&item.turn_id===a.turn?{...item,status:terminal==='failed'?'failed':'completed'}:item);
      let snapshot;
      try{snapshot=await library.readSnapshot(a.client,a.actualThread||a.c.thread_id,this.hostId,this.readLocal);}catch{}
      if(this.active!==a)return;
      if(a.goalTask){
        a.lastTurn=a.turn;a.turn=null;a.pending.clear();
        try{a.goal=await goals.getGoal(a.client,a.c.thread_id);}catch{
          await this.update(a.c,'unknown',{error:'GOAL_UNCONFIRMED',reply:a.reply,...(snapshot?{snapshot}:{})});
          a.client.close();if(this.active===a)this.active=null;return;
        }
        if(terminal==='completed'&&!a.stopping&&a.goal?.status==='active'){
          a.ending=false;a.goalWaiting=true;
          await this.update(a.c,'running',{reply:a.reply,turn_id:'',goal:a.goal,goal_waiting:true,...(snapshot?{snapshot}:{})});return;
        }
        await goals.finishGoal(this,a,a.goal?.status==='complete'?'completed':terminal==='failed'?'failed':'interrupted',snapshot);return;
      }
      if(a.currentQueued)await this.update(a.currentQueued,terminal,{turn_id:p.turn?.id||a.turn,
        ...(terminal==='failed'?{error:providerFailure(p.turn?.error)}:{})});
      if(terminal==='completed'&&!a.stopping&&a.queue.length){
        const next=a.queue.shift();a.currentQueued=next;let attempted=false;
        try{
          const actual=a.actualThread||a.c.thread_id;
          const local=this.resolve({...a.c,thread_id:actual,conversation_id:hash(actual),baseline_turn:p.turn.id});
          await checkNativeBaseline(a.client,{...a.c,thread_id:actual,baseline_turn:p.turn.id},local);
          const options={...await selection(a.client,next,local),permission:a.local?.permission};
          if(a.stopping||this.active!==a){
            await this.update(next,'cancelled',{error:'QUEUE_STOPPED'});
            await this.cancelQueue(a,'QUEUE_STOPPED');
            await this.update(a.c,'interrupted',{turn_id:a.turn,...(snapshot?{snapshot}:{})});
            a.client.close();if(this.active===a)this.active=null;return;
          }
          // Same app-server and Provider. Never use turn/start to steer another process.
          await this.update(next,'accepted',{parent_request_id:a.c.id});
          a.input=next.text;a.reply='';a.items.clear();a.pending.clear();
          await this.update(a.c,'accepted',{reply:'',turn_id:a.turn,...(snapshot?{snapshot}:{})});
          attempted=true;a.turn=null;a.stopRequestedTurn=null;
          await this.startTurn(a,actual,next.text,next.id,options);
          if(this.active!==a)return;
          a.ending=false;
          if(a.stopping)await this.interruptActive(a);
          else {await this.update(next,'running',{turn_id:a.turn,parent_request_id:a.c.id});await this.refreshActive(a);}
          return;
        }catch(e){
          await this.update(next,attempted?'unknown':'failed',{error:attempted?'QUEUE_START_UNCONFIRMED':'QUEUE_START_REJECTED'});
          await this.cancelQueue(a,'QUEUE_INTERRUPTED');
          await this.update(a.c,attempted?'unknown':'completed',{error:attempted?'QUEUE_START_UNCONFIRMED':'QUEUE_START_REJECTED',...(snapshot?{snapshot}:{})});
          a.client.close();if(this.active===a)this.active=null;return;
        }
      }
      await this.cancelQueue(a,terminal==='failed'?'PREVIOUS_TASK_FAILED':'QUEUE_STOPPED');
      await this.update(a.c,terminal,{reply:Buffer.from(a.reply).subarray(0,65536).toString('utf8'),turn_id:p.turn?.id||a.turn,...(snapshot?{snapshot}:{}),
        ...(terminal==='failed'?{error:providerFailure(p.turn?.error)}:{})});
      a.client.close();if(this.active===a)this.active=null;
    }
  }
  close(){this.stopWatching();if(this.active){
    for(const q of this.active.queue||[])if(this.journal[q.id])Object.assign(this.journal[q.id],{status:'cancelled',error:'QUEUE_INTERRUPTED'});
    this.persist();clearTimeout(this.active.timer);this.active.client?.close();this.active=null;
  }}
}
module.exports={commandTopic,eventTopic,encode,decode,validateCommand,resolveThread,RemoteController,normalizeModels,normalizeModes,selection,providerFailure,checkNativeBaseline};
