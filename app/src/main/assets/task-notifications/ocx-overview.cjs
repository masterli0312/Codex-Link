'use strict';
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),{execFile}=require('node:child_process'),{promisify}=require('node:util');
const {clip,time}=require('./rollout-reader.cjs');
const run=promisify(execFile),excluded=/^(?:c[l]aude|a[n]thropic)$/i;
const secretLike=s=>/(?:^|[/:])(?:sk|key|token|bearer)[-_]/i.test(s);
const validProvider=s=>typeof s==='string'&&/^[a-z0-9_.-]{1,128}$/i.test(s)&&!excluded.test(s)&&!secretLike(s);
const allowedArgs=[['account','list','--json'],['logs','history','--json'],['observe','logs','--limit','200','--json']];
function executable(){
 for(const dir of (process.env.PATH||'').split(path.delimiter)){if(!dir||/(?:c[l]aude|a[n]thropic)/i.test(dir))continue;
  for(const name of process.platform==='win32'?['ocx.exe','ocx.cmd','ocx.bat']:['ocx']){
   const file=path.resolve(dir,name);if(/[&|<>%!\r\n^]/.test(file))continue;try{if(fs.statSync(file).isFile())return file;}catch{}
  }
 }return '';
}
async function execute(args){
 if(!allowedArgs.some(a=>JSON.stringify(a)===JSON.stringify(args)))throw Error('OCX_UNAVAILABLE');const file=executable();if(!file)throw Error('OCX_UNAVAILABLE');
 const options={windowsHide:true,timeout:8000,maxBuffer:4*1024*1024};let result;
 if(process.platform==='win32'&&/\.(?:cmd|bat)$/i.test(file))result=await run(process.env.ComSpec||'cmd.exe',['/d','/s','/c','""'+file+'" '+args.join(' ')+'"'],{...options,windowsVerbatimArguments:true});
 else result=await run(file,args,options);return result.stdout;
}
function parse(value,names){const raw=typeof value==='string'?JSON.parse(value):value;if(Array.isArray(raw))return raw;
 for(const name of names)if(Array.isArray(raw?.[name]))return raw[name];throw Error('OCX_UNAVAILABLE');}
function readCache(controller){try{const home=controller.config.ocxHome||path.join(os.homedir(),'.opencodex'),file=path.join(home,'codex-quota-cache.json');if(fs.statSync(file).size>4*1024*1024)return {};const data=JSON.parse(fs.readFileSync(file,'utf8').replace(/^\uFEFF/,''));return data?.quotas&&typeof data.quotas==='object'?data.quotas:{};}catch{return {};}}
function label(row){const v=row.accountLogLabel||row.account_log_label||row.label;if(typeof v==='string'&&/^(?:main|[po][a-f0-9]{6}|[a-z]{1,8}[0-9]{3,16})$/i.test(v))return v;
 const id=row.id||row.accountId||row.account_id;return typeof id==='string'&&/^[a-z0-9_-]{1,128}$/i.test(id)&&!/^sk[-_]/i.test(id)?id.replace(/^_+|_+$/g,'').slice(-6):'account';}
function pct(q,kind){const direct=q?.[kind+'_left_percent'];if(typeof direct==='number'&&Number.isFinite(direct))return Math.max(0,Math.min(100,Math.round(direct)));
 const used=q?.[kind+'Percent']??q?.[kind+'_percent'];return typeof used==='number'&&Number.isFinite(used)?Math.max(0,Math.min(100,Math.round(100-used))):-1;}
// Pool accounts only: relay API keys are not quota accounts and are not listed.
function accounts(rows,cache){return rows.filter(r=>r&&validProvider(r.provider)&&r.type!=='api-key').map(r=>{
 const q=cache[r.id]||r.quota||{},plan=typeof r.plan==='string'&&/^[a-z0-9_.-]{1,32}$/i.test(r.plan)&&!/^sk[-_]/i.test(r.plan)?r.plan:'';
 return {label:label(r),plan,selected:r.active===true||r.selected===true,short_left_percent:pct(q,'short'),weekly_left_percent:pct(q,'weekly'),short_reset_at:time(q.shortResetAt||q.short_reset_at),weekly_reset_at:time(q.weeklyResetAt||q.weekly_reset_at)};
 });}
function requests(rows){
 const recent=rows.filter(r=>r&&typeof r==='object').sort((a,b)=>time(b.timestamp||b.at||b.created_at)-time(a.timestamp||a.at||a.created_at)).slice(0,200),group=new Map(),times=[];
 for(const r of recent){if(!validProvider(r.provider))continue;const model=typeof r.model==='string'&&/^[a-z0-9_.:/-]{1,128}$/i.test(r.model)&&!secretLike(r.model)?r.model:'',provider=r.provider,incoming=r.protocolTrace?.inbound||r.inboundProtocol||r.inbound||r.inbound_protocol;
  const inbound=['responses','messages','chat'].includes(incoming)?incoming:'other',at=time(r.timestamp||r.at||r.created_at),key=JSON.stringify([model,provider,inbound]);if(at)times.push(at);
  if(!group.has(key))group.set(key,{model,provider,inbound,count:0,failures:0,tokens:0,last_at:0,last_error:''});const item=group.get(key);item.count++;
  const failure=Number(r.status??r.statusCode)>=400||r.success===false||r.failed===true;if(failure)item.failures++;
  const input=r.usage?.inputTokens??r.usage?.input_tokens,output=r.usage?.outputTokens??r.usage?.output_tokens;
  const tokens=r.totalTokens??r.tokens??r.usage?.total_tokens??r.usage?.totalTokens??(Number.isSafeInteger(input)&&Number.isSafeInteger(output)?input+output:0);if(Number.isSafeInteger(tokens)&&tokens>=0)item.tokens=Math.min(Number.MAX_SAFE_INTEGER,item.tokens+tokens);
  if(at>=item.last_at){item.last_at=at;const error=r.errorCode||r.error_code||r.last_error;item.last_error=typeof error==='string'&&/^[a-z0-9_-]{1,80}$/i.test(error)&&!secretLike(error)?error:'';}
 }
 return {requests:[...group.values()],window_minutes:times.length?Math.ceil((Math.max(...times)-Math.min(...times))/60000):0};
}
async function overview(controller){
 if(!controller.ocxRunner&&!controller.config.codexHome)throw Error('OCX_UNAVAILABLE');const executor=controller.ocxRunner||execute;
 try{const call=async args=>{let timer;try{return await Promise.race([executor(args),new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('timeout')),8000);})]);}finally{clearTimeout(timer);}};
  const logs=async()=>{try{return await call(allowedArgs[1]);}catch(e){const message=String(e?.stderr||e?.message||'');if(!/(?:unknown|unexpected|invalid|unsupported)/i.test(message)||!/history/i.test(message))throw e;return call(allowedArgs[2]);}};
  const [a,l]=await Promise.all([call(allowedArgs[0]),logs()]);const result={accounts:accounts(parse(a,['accounts','data']),readCache(controller)),...requests(parse(l,['logs','requests','entries','data']))};
  return result;
 }catch{throw Error('OCX_UNAVAILABLE');}
}
// Presence must stay cheap: report the capability when ocx is installed, without running it.
function available(){return Boolean(executable());}
module.exports={overview,execute,available};
