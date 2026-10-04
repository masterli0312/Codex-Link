'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const hash=s=>crypto.createHash('sha256').update(s).digest('hex');
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const activity=require('./remote-activity.cjs');
const images=require('./conversation-images.cjs');
// Omitted sourceKinds defaults to interactive origins and can hide mobile-created threads.
const sourceKinds=['cli','vscode','exec','appServer','unknown'];
const bounded=(text,bytes)=>Buffer.from(String(text||'')).subarray(0,bytes).toString('utf8').replace(/\uFFFD$/,'');
function project(cwd){
 if(typeof cwd!=='string'||!path.isAbsolute(cwd)||!fs.existsSync(cwd))return null;
 const canonical=fs.realpathSync(cwd);if(!fs.statSync(canonical).isDirectory())return null;
 return {id:hash(process.platform==='win32'?canonical.toLowerCase():canonical),name:bounded(path.basename(canonical)||canonical,160),cwd:canonical};
}
async function threads(client){
 const result=await client.call('thread/list',{limit:100,archived:false,modelProviders:[],sourceKinds,sortKey:'updated_at',sortDirection:'desc'});
 return (result.data||[]).filter(t=>UUID.test(t.id)&&!String(t.source||'').toLowerCase().includes('subagent')).slice(0,100);
}
async function threadPage(client,{cursor='',archived=false,search=''}={}){
 const result=await client.call('thread/list',{limit:30,archived,modelProviders:[],sourceKinds,sortKey:'updated_at',sortDirection:'desc',
   ...(cursor?{cursor}:{}),...(search?{searchTerm:search}:{})});
 return {threads:summaries((result.data||[]).filter(t=>UUID.test(t.id)&&!String(t.source||'').toLowerCase().includes('subagent'))),
   next_cursor:typeof result.nextCursor==='string'?result.nextCursor:''};
}
async function historyPage(client,id,cursor=''){
 // Summary omits intermediate assistant messages. Full, bounded pages keep the actual
 // conversation; tool/reasoning items are projected separately and never become chat text.
 const result=await client.call('thread/turns/list',{threadId:id,limit:4,sortDirection:'desc',itemsView:'full',...(cursor?{cursor}:{})});
 const turns=(result.data||[]).slice().reverse();
 const projection=snapshot({id,turns},'');
 return {messages:projection.messages,activities:projection.activities,next_cursor:typeof result.nextCursor==='string'?result.nextCursor:'',
   truncated:projection.truncated,turns};
}
async function activityPage(client,id,cursor=''){
 const result=await client.call('thread/items/list',{threadId:id,limit:12,sortDirection:'desc',...(cursor?{cursor}:{})});
 const activities=activity.mergeActivities([],(result.data||[]).slice().reverse().map(e=>activity.projectItem(e.turnId,e.item)).filter(Boolean));
 return {activities,next_cursor:typeof result.nextCursor==='string'?result.nextCursor:''};
}
function summaries(rows){return rows.map(t=>{
 let p;try{p=project(t.cwd);}catch{}
 return {thread_id:t.id,title:bounded(t.name||t.title||t.preview,240),updated_at:Math.floor(Number(t.updatedAt||t.createdAt||0)*1000),
   project_id:p?.id||'',project_name:p?.name||'',project_path:bounded(p?.cwd||'',1024),running:t.status?.type==='active'};
});}
function snapshot(thread,host){
 const messages=[];let truncated=false;
 for(const turn of thread.turns||[])for(const [position,item] of (turn.items||[]).entries()){
   let role,text;
   if(item.type==='userMessage'){role='user';text=(item.content||[]).filter(c=>c.type==='text'&&typeof c.text==='string').map(c=>c.text).join('\n');}
   else if(item.type==='agentMessage'){role='assistant';text=item.text;}
   else if(['imageGeneration','imageView'].includes(item.type)){role='assistant';text='';}
   const pictures=images.projectImages(thread.id,item,thread.cwd);
   if((typeof text!=='string'||!text.trim())&&!pictures.length)continue;
   const limited=bounded(text,16384);truncated ||= limited!==text;
   messages.push({role,text:limited,...(pictures.length?{images:pictures}:{}),...(typeof item.phase==='string'?{phase:item.phase}:{}),...(item.id?{id:turn.id+':'+item.id,position}:{})});if(messages.length>60){messages.shift();truncated=true;}
 }
 const turns=thread.turns||[],last=turns.at(-1),completed=last&&['completed','interrupted','failed'].includes(last.status);
 const reply=[...messages].reverse().find(m=>m.role==='assistant')?.text||'';
 const result={conversation_id:hash(thread.id),title:bounded(thread.name||thread.title||thread.preview,240),reply,messages,truncated,activities:activity.projectTurns(turns),
   completed_at:new Date(Number(thread.updatedAt||thread.createdAt||0)*1000).toISOString()};
 result.running=last?.status==='inProgress';
 const durations=Object.fromEntries(turns.map(t=>[t.id,activity.turnDuration(t)]).filter(([id,d])=>id&&d!==null).slice(-40));
 if(Object.keys(durations).length)result.turn_durations=durations;
 if((completed||result.running)&&last?.id)result.thread_ref={host_id:host,thread_id:thread.id,baseline_turn:last.id};
 if(completed)result.remote_ref=result.thread_ref;
 while(Buffer.byteLength(JSON.stringify(result))>160000&&result.messages.length){result.messages.shift();result.truncated=true;}
 return result;
}
async function selectedProject(client,id){
 for(const t of await threads(client)){let p;try{p=project(t.cwd);}catch{}if(p?.id===id)return p;}
 throw Error('INVALID_PROJECT');
}
async function creationProject(client,id,directory){
 if(id!=='default')return selectedProject(client,id);
 // A no-project conversation has its own workspace, never the bridge's credential directory.
 // The phone sends only a closed identity, not an arbitrary path.
 const workspace=path.join(path.resolve(directory),'conversation-workspace');
 fs.mkdirSync(workspace,{recursive:true});
 const p=project(workspace);
 if(!p||path.dirname(p.cwd)!==fs.realpathSync(directory))throw Error('INVALID_PROJECT');
 return p;
}
async function readSnapshot(client,id,host,readLocal,metadata){
 const meta=metadata||(await client.call('thread/read',{threadId:id,includeTurns:false})).thread;
 if(meta?.id!==id)throw Error('MISSING_THREAD');
 let result;
 try{
   const page=await historyPage(client,id);
   result=snapshot({...meta,turns:page.turns},host);result.history_cursor=page.next_cursor;
 }catch{}
 if(!result)try{result=readLocal?.(id);}catch{}
 if(!result)result=snapshot((await client.call('thread/read',{threadId:id,includeTurns:true})).thread,host);
 result.title=bounded(meta.name||meta.title||meta.preview||result.title,240);
 if(Number(meta.updatedAt)>0)result.completed_at=new Date(Number(meta.updatedAt)*1000).toISOString();
 result.running=meta.status?.type==='active'||result.running===true;
 if(result.running)delete result.remote_ref;
 result.reply=bounded(result.reply,16384);
 while(Buffer.byteLength(JSON.stringify(result))>160000&&result.messages.length){result.messages.shift();result.truncated=true;}
 return result;
}
async function manageThread(client,action,id,name=''){
 if(!['rename','archive','unarchive'].includes(action)||!UUID.test(id))throw Error('INVALID_MANAGEMENT');
 const trimmed=typeof name==='string'?name.trim():'';
 if(action==='rename'&&(!trimmed||Buffer.byteLength(trimmed)>240||/[\r\n\0]/.test(trimmed)))throw Error('INVALID_NAME');
 const meta=(await client.call('thread/read',{threadId:id,includeTurns:false})).thread;
 if(meta?.id!==id)throw Error('MISSING_THREAD');
 if(meta.status?.type==='active')throw Error('BUSY');
 await client.call(action==='rename'?'thread/name/set':action==='archive'?'thread/archive':'thread/unarchive',
   {threadId:id,...(action==='rename'?{name:trimmed}:{})});
 return {managed_thread_id:id,managed_action:action,managed_name:action==='rename'?trimmed:''};
}
async function forkThread(client,id,turnId,host,onCreated=()=>{}){
 if(!UUID.test(id)||typeof turnId!=='string'||!turnId||turnId.length>128)throw Error('INVALID_THREAD');
 const meta=(await client.call('thread/read',{threadId:id,includeTurns:false})).thread;
 if(meta?.id!==id)throw Error('MISSING_THREAD');
 if(meta.status?.type==='active')throw Error('BUSY');
 const tail=await historyPage(client,id),last=tail.turns.at(-1);
 if(!last||last.id!==turnId||!['completed','interrupted','failed'].includes(last.status))throw Error('STALE');
 // Explicit branch only: no generation, arbitrary paths, Provider or permission overrides.
 const result=await client.call('thread/fork',{threadId:id,lastTurnId:turnId,excludeTurns:true,ephemeral:false,deferGoalContinuation:true});
 const child=result?.thread;
 if(!UUID.test(child?.id)||child.id===id||child.forkedFromId&&child.forkedFromId!==id||
   result.modelProvider!==meta.modelProvider||typeof meta.cwd!=='string'||typeof result.cwd!=='string'||
   path.resolve(result.cwd)!==path.resolve(meta.cwd))throw Error('FORK_MISMATCH');
 onCreated(child.id);
 const copied=await readSnapshot(client,child.id,host);
 if(copied.remote_ref?.thread_id!==child.id)throw Error('FORK_SNAPSHOT_UNAVAILABLE');
 return {forked_from_thread_id:id,snapshot:copied};
}
module.exports={threads,threadPage,historyPage,activityPage,summaries,snapshot,selectedProject,creationProject,project,readSnapshot,manageThread,forkThread};
