'use strict';
const crypto=require('node:crypto');
const PERMISSIONS=['','default','readonly','review','full'];
function permissionOptions(mode=''){
 if(!PERMISSIONS.includes(mode))throw Error('INVALID_PERMISSION');
 const sandbox=mode==='readonly'?'read-only':mode==='full'?'danger-full-access':'workspace-write';
 const common={approvalPolicy:mode==='full'?'never':mode==='review'?'on-request':'untrusted',approvalsReviewer:mode==='review'?'auto_review':'user'};
 return {resume:{...common,sandbox},turn:{...common,sandboxPolicy:sandbox==='read-only'?{type:'readOnly',networkAccess:false}:
  sandbox==='danger-full-access'?{type:'dangerFullAccess'}:{type:'workspaceWrite',writableRoots:[],networkAccess:false,excludeSlashTmp:false,excludeTmpdirEnvVar:false}}};
}
function normalizeContext(usage){
 const used=usage?.last?.totalTokens,max=usage?.modelContextWindow;
 return Number.isSafeInteger(used)&&used>=0&&Number.isSafeInteger(max)&&max>0?{used_tokens:used,window_tokens:max}:null;
}
function classifyLimits(response){
 const result={five_hour:null,weekly:null},candidates={five_hour:[],weekly:[]};
 const value=response?.rateLimits||response?.rateLimitsByLimitId?.codex;
 if(!value||value.limitId&&!['codex','codex-main'].includes(value.limitId))return result;
 for(const win of [value.primary,value.secondary]){
  if(!win||typeof win.usedPercent!=='number'||!Number.isFinite(win.usedPercent)||win.usedPercent<0||win.usedPercent>100||
   !Number.isSafeInteger(win.windowDurationMins)||win.windowDurationMins<=0||win.resetsAt!=null&&(!Number.isSafeInteger(win.resetsAt)||win.resetsAt<0))continue;
  const duration=win.windowDurationMins,type=Math.abs(duration-300)<=3?'five_hour':Math.abs(duration-10080)<=100?'weekly':null;
  if(type)candidates[type].push({used_percent:win.usedPercent,duration_mins:duration,reset_at:win.resetsAt??null});
 }
 for(const type of Object.keys(result))if(candidates[type].length===1)result[type]=candidates[type][0];
 return result;
}
const skillId=s=>crypto.createHash('sha256').update(s.name+'\0'+s.path).digest('hex');
async function availableSkills(client,cwd){
 const result=await client.call('skills/list',{cwds:[cwd],forceReload:false});
 const seen=new Set();return (result.data||[]).filter(v=>v.cwd===cwd).flatMap(v=>v.skills||[]).filter(s=>s.enabled&&typeof s.name==='string'&&
  s.name.length>0&&Buffer.byteLength(s.name)<=160&&typeof s.path==='string'&&s.path.length<=4096&&
  !seen.has(skillId(s))&&seen.add(skillId(s))).slice(0,100);
}
async function status(controller,client,c){
 const context=controller.active?.c.thread_id===c.thread_id?controller.active.context:null;
 let saved=null;try{saved=controller.readContext?.(c.thread_id)||null;}catch{}
 let limits={five_hour:null,weekly:null},quotaError='';
 try{limits=classifyLimits(await client.call('account/rateLimits/read',{}));}catch{quotaError='QUOTA_UNAVAILABLE';}
 return {context_usage:context||saved,...limits,quota_error:quotaError};
}
async function catalog(client,c){
 const thread=(await client.read(c.thread_id)).thread;
 if(thread?.id!==c.thread_id||!thread.cwd)throw Error('INVALID_THREAD');
 const skills=await availableSkills(client,thread.cwd);
 return {skills:skills.map(s=>({id:skillId(s),name:s.name,description:String(s.interface?.shortDescription||s.shortDescription||s.description||'').slice(0,400),plugin:!!s.pluginId}))};
}
async function selectedSkills(client,c,local){
 if(!c.skill_ids?.length)return [];
 const available=await availableSkills(client,local.cwd);
 return c.skill_ids.map(id=>{const skill=available.find(s=>skillId(s)===id);if(!skill)throw Error('SKILL_UNAVAILABLE');return {type:'skill',name:skill.name,path:skill.path};});
}
module.exports={PERMISSIONS,permissionOptions,normalizeContext,classifyLimits,status,catalog,availableSkills,selectedSkills};
