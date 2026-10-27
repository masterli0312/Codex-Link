'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),{execFile}=require('node:child_process'),{promisify}=require('node:util');
const {UUID,clip,bytes,time,project,snapshot}=require('./rollout-reader.cjs');
const run=promisify(execFile),ID=/^[A-Za-z0-9_-]{1,128}$/,WEEK=7*24*60*60*1000;
const fields=['id','title','model','effort','worktree','branch','codex_session_id','rollout','resumed','status','started_at','ended_at','process_started_at','exit_code','pid','launcher_pid'];
const statuses=['starting','running','succeeded','failed','stopped','unknown'];
function normStatus(v){if(statuses.includes(v))return v;return ({pending:'starting',active:'running',completed:'succeeded',complete:'succeeded',success:'succeeded',errored:'failed',error:'failed',cancelled:'stopped',interrupted:'stopped'})[v]||'unknown';}
function safeDir(config){try{const d=config.externalTasksDir;if(typeof d!=='string'||!d||!fs.statSync(d).isDirectory())return '';return fs.realpathSync(d);}catch{return '';}}
function readManifest(dir,id){
 if(!ID.test(id))return null;const file=path.join(dir,id+'.json');
 try{const stat=fs.lstatSync(file);if(!stat.isFile()||stat.isSymbolicLink()||stat.size>1024*1024||path.dirname(fs.realpathSync(file))!==dir)return null;const raw=JSON.parse(fs.readFileSync(file,'utf8').replace(/^\uFEFF/,''));
  if(!raw||typeof raw!=='object'||Array.isArray(raw)||raw.id!==id)return null;const value={};for(const k of fields)if(Object.hasOwn(raw,k))value[k]=raw[k];return value;
 }catch{return null;}
}
function pathName(p){if(typeof p!=='string')return '';return clip(path.win32.basename(path.posix.basename(p.replace(/\\/g,'/'))),160);}
function kind(model,meta){const p=String(meta?.model_provider||'').toLowerCase(),m=String(model||'').toLowerCase();if(p.includes('antigravity')||m.includes('antigravity'))return 'antigravity';if(p==='openai'||/^(?:gpt|o[1-9])/.test(m))return 'openai';return 'other';}
function enumerate(controller){
 const dir=safeDir(controller.config);if(!dir)throw Error('TASKS_DISABLED');let index=new Map();try{index=controller.rollouts.scan();}catch{}
 const manifests=[];for(const entry of fs.readdirSync(dir,{withFileTypes:true})){
  if(!entry.isFile()||!entry.name.endsWith('.json'))continue;const id=entry.name.slice(0,-5),m=readManifest(dir,id);if(!m)continue;
  let session=UUID.test(m.codex_session_id||'')?m.codex_session_id:'';let match=index.get(session);
  if(!session&&typeof m.rollout==='string'&&controller.rollouts.safe(m.rollout)){try{const real=fs.realpathSync(m.rollout);match=[...index.values()].find(x=>fs.realpathSync(x.file)===real);if(match)session=match.meta.id;}catch{}}
  if(match&&m.rollout){try{if(!controller.rollouts.safe(m.rollout)||fs.realpathSync(m.rollout)!==fs.realpathSync(match.file))match=null;}catch{match=null;}}
  let mtime=0;try{mtime=fs.statSync(path.join(dir,id+'.json')).mtimeMs;}catch{continue;}manifests.push({m,id,session,entry:match,started:time(m.started_at),mtime});
 }
 const groups=new Map();for(const item of manifests){const key=item.session||'id:'+item.id;if(!groups.has(key))groups.set(key,[]);groups.get(key).push(item);}
 const output=[];for(const group of groups.values()){
  group.sort((a,b)=>(b.started||b.mtime)-(a.started||a.mtime)||a.id.localeCompare(b.id));const v=group[0],m=v.m;if((v.started||v.mtime)<Date.now()-WEEK)continue;
  let parsed=project([]),partial=false;if(v.entry)try{parsed=controller.rollouts.summary(v.entry.file);partial=parsed.partial;}catch{}
  const status=normStatus(m.status),model=clip(parsed.model||m.model,128);
  output.push({value:{id:v.id,session_id:v.session,title:clip(m.title,120),model,effort:clip(parsed.effort||m.effort,64),provider_kind:kind(model,v.entry?.meta),worktree_name:pathName(m.worktree),branch:clip(m.branch,160),status,
   started_at:v.started,ended_at:time(m.ended_at),runs:group.length,resumed:m.resumed===true,can_stop:['starting','running'].includes(status),last_reply:parsed.last_reply,last_step:parsed.last_step,tokens:parsed.tokens},entry:v.entry,manifest:m,aliases:group.map(x=>x.id),partial});
 }
 for(const [id,entry] of index){const m=entry.meta;if(m.originator!=='codex_exec'||m.source!=='exec'||groups.has(id))continue;
  const started=time(m._timestamp)||entry.mtime;if(started<Date.now()-WEEK)continue;let parsed;try{parsed=controller.rollouts.summary(entry.file);}catch{continue;}const model=parsed.model;
  const title=m._first_user||(!parsed.partial?parsed.first_user:''),status=parsed.status==='completed'?'succeeded':parsed.status==='interrupted'?'stopped':parsed.status==='running'?'running':'unknown';
  output.push({value:{id,session_id:id,title,model,effort:parsed.effort,provider_kind:kind(model,m),worktree_name:pathName(m.cwd),branch:'',status,started_at:started,ended_at:status==='succeeded'||status==='stopped'?parsed.updated_at:0,runs:1,resumed:false,can_stop:false,last_reply:parsed.last_reply,last_step:parsed.last_step,tokens:parsed.tokens},entry,partial:parsed.partial});
 }
 output.sort((a,b)=>Number(['running','starting'].includes(b.value.status))-Number(['running','starting'].includes(a.value.status))||b.value.started_at-a.value.started_at||a.value.id.localeCompare(b.value.id));
 return output;
}
function list(controller){const all=enumerate(controller),selected=all.slice(0,50);return {tasks:selected.map(x=>x.value),partial:all.length>50||selected.some(x=>x.partial&&!x.value.title)};}
function read(controller,id){let all;try{all=enumerate(controller);}catch{throw Error('TASK_NOT_FOUND');}const task=all.find(x=>x.value.id===id||x.value.session_id===id||x.aliases?.includes(id));if(!task)throw Error('TASK_NOT_FOUND');
 if(!task.entry)throw Error('ROLLOUT_UNAVAILABLE');try{const tail=controller.rollouts.tail(task.entry.file),value=snapshot(task.entry.meta.id,task.entry.meta,tail);value.title=bytes(task.value.title,240);return {snapshot:value,partial:value.truncated};}catch{throw Error('ROLLOUT_UNAVAILABLE');}}
const positive=n=>Number.isSafeInteger(n)&&n>0;
function processQuery(pid){if(!positive(pid))throw Error('unverified');return `$q=@(${pid});$seen=@{};$out=@();while($q.Count -gt 0 -and $seen.Count -lt 500){$i=[int]$q[0];$q=@($q|Select-Object -Skip 1);if($seen.ContainsKey($i)){continue};$seen[$i]=$true;$p=Get-CimInstance Win32_Process -Filter (\"ProcessId=$i\") -ErrorAction Stop;if(-not $p){continue};$out+=@{pid=[int]$p.ProcessId;parent_pid=[int]$p.ParentProcessId;created_at=[long]([DateTimeOffset]$p.CreationDate).ToUnixTimeMilliseconds();command_line=[string]$p.CommandLine};$children=Get-CimInstance Win32_Process -Filter (\"ParentProcessId=$i\") -ErrorAction Stop;foreach($c in $children){$q+=@([int]$c.ProcessId)}};ConvertTo-Json -InputObject @($out) -Compress`;}
function defaultAdapter(){return {
 async exists(pid){if(!positive(pid))throw Error('unverified');try{process.kill(pid,0);return true;}catch(e){if(e.code==='ESRCH')return false;if(e.code==='EPERM')return true;throw e;}},
 async inspect(pid){if(process.platform!=='win32'||!positive(pid))throw Error('unverified');
  const script=processQuery(pid);
  const result=await run('powershell.exe',['-NoProfile','-NonInteractive','-Command',script],{windowsHide:true,timeout:8000,maxBuffer:1024*1024});const parsed=JSON.parse(result.stdout);return Array.isArray(parsed)?parsed:[];
 },
 async kill(pid){if(process.platform!=='win32'||!positive(pid))throw Error('unverified');await run('taskkill.exe',['/T','/F','/PID',String(pid)],{windowsHide:true,timeout:8000,maxBuffer:1024*1024});}
};}
function matchesWorktree(p,worktree){if(typeof worktree!=='string'||!path.isAbsolute(worktree))return false;const wanted=path.resolve(worktree).replace(/\\/g,'/').toLowerCase();
 if(typeof p.cwd==='string'){const cwd=path.resolve(p.cwd).replace(/\\/g,'/').toLowerCase();if(cwd===wanted||cwd.startsWith(wanted+'/'))return true;}
 if(typeof p.command_line!=='string')return false;const cmd=p.command_line.replace(/\\/g,'/').toLowerCase(),escaped=wanted.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');return new RegExp('(?:^|[\\s\"\'=])'+escaped+'(?=$|[\\s\"\'])').test(cmd);
}
async function verified(adapter,m){
 if(!positive(m.pid)||!positive(m.launcher_pid)||!time(m.process_started_at)||typeof m.worktree!=='string')return false;
 if(await adapter.exists(m.launcher_pid)||!await adapter.exists(m.pid))return false;const rows=await adapter.inspect(m.pid);if(!Array.isArray(rows))return false;
 const root=rows.find(p=>p.pid===m.pid);if(!root||!time(root.created_at)||Math.abs(time(root.created_at)-time(m.process_started_at))>5000)return false;
 const members=new Set([m.pid]);for(let n=0;n<rows.length;n++){let changed=false;for(const p of rows)if(members.has(p.parent_pid)&&!members.has(p.pid)){members.add(p.pid);changed=true;}if(!changed)break;}
 return rows.some(p=>members.has(p.pid)&&typeof p.command_line==='string'&&/\bcodex\b/i.test(p.command_line)&&/\bexec\b/i.test(p.command_line)&&matchesWorktree(p,m.worktree));
}
async function stop(controller,id){
 const dir=safeDir(controller.config);if(!dir)throw Error('TASKS_DISABLED');let m=readManifest(dir,id);if(!m)throw Error('TASK_NOT_FOUND');
 if(['stopped','failed','succeeded'].includes(normStatus(m.status)))return {reply:'already_finished'};if(!['starting','running'].includes(normStatus(m.status)))throw Error('STOP_UNVERIFIED');
 const marker=path.join(dir,id+'.stop');try{fs.writeFileSync(marker,'',{flag:'wx',mode:0o600});}catch(e){if(e.code!=='EEXIST')throw Error('STOP_UNVERIFIED');try{const stat=fs.lstatSync(marker);if(!stat.isFile()||stat.isSymbolicLink()||path.dirname(fs.realpathSync(marker))!==dir)throw Error('invalid');}catch{throw Error('STOP_UNVERIFIED');}}
 const deadline=Date.now()+controller.taskWaitMs;while(Date.now()<deadline){await new Promise(r=>setTimeout(r,Math.min(250,Math.max(1,deadline-Date.now()))));m=readManifest(dir,id);if(!m)throw Error('TASK_NOT_FOUND');if(['stopped','failed','succeeded'].includes(normStatus(m.status)))return {reply:normStatus(m.status)==='stopped'?'stopped':'already_finished'};}
 m=readManifest(dir,id);if(!m)throw Error('TASK_NOT_FOUND');if(['stopped','failed','succeeded'].includes(normStatus(m.status)))return {reply:normStatus(m.status)==='stopped'?'stopped':'already_finished'};
 const adapter=controller.processAdapter;try{
  if(!positive(m.launcher_pid))throw Error('STOP_UNVERIFIED');if(await adapter.exists(m.launcher_pid))throw Error('STOP_TIMEOUT');if(normStatus(m.status)!=='running'||!await verified(adapter,m))throw Error('STOP_UNVERIFIED');
  const latest=readManifest(dir,id);if(!latest||normStatus(latest.status)!=='running'||['pid','launcher_pid','process_started_at','worktree'].some(k=>latest[k]!==m[k])||!await verified(adapter,latest))throw Error('STOP_UNVERIFIED');
  await adapter.kill(m.pid);
  const file=path.join(dir,id+'.json'),raw=JSON.parse(fs.readFileSync(file,'utf8').replace(/^\uFEFF/,''));raw.status='stopped';raw.ended_at=new Date().toISOString();raw.exit_code=-1;
  const temp=path.join(dir,'.'+id+'.'+crypto.randomUUID()+'.tmp');try{fs.writeFileSync(temp,JSON.stringify(raw),{flag:'wx',mode:0o600});fs.renameSync(temp,file);}finally{try{fs.unlinkSync(temp);}catch{}}
  return {reply:'stopped'};
 }catch(e){if(['STOP_TIMEOUT','STOP_UNVERIFIED'].includes(e.message))throw e;throw Error('STOP_UNVERIFIED');}
}
module.exports={defaultAdapter,enumerate,list,read,stop,verified,safeDir,processQuery};
