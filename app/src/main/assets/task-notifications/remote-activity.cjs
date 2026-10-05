'use strict';
// Only public App Server item summaries are projected. No reasoning, arguments or local file reads.
const limit=(value,bytes)=>typeof value==='string'?Buffer.from(value).subarray(0,bytes).toString('utf8').replace(/\uFFFD$/,''):'';
function projectItem(turn,item,position=-1,thread='',cwd=''){
 if(typeof turn!=='string'||!turn||turn.length>128||typeof item?.id!=='string'||!item.id||item.id.length>128)return null;
 const a={id:turn+':'+item.id,turn_id:turn,type:item.type,status:['inProgress','completed','failed','declined'].includes(item.status)?item.status:'completed',title:'',detail:'',files:[],truncated:false};
 if(Number.isSafeInteger(position)&&position>=0)a.position=position;
 if(item.type==='commandExecution'){
  if(Array.isArray(item.commandActions)&&item.commandActions.length&&item.commandActions.every(x=>x?.type==='read'))a.type='fileRead';
  if(typeof item.command!=='string')return null;
  a.title=limit(item.command,512);a.detail=limit(item.aggregatedOutput,8192);
  if(Number.isInteger(item.exitCode)&&item.exitCode>=-2147483648&&item.exitCode<=2147483647)a.exit_code=item.exitCode;
  if(Number.isSafeInteger(item.durationMs)&&item.durationMs>=0)a.duration_ms=item.durationMs;
  a.truncated=a.title!==item.command||typeof item.aggregatedOutput==='string'&&a.detail!==item.aggregatedOutput;
 }else if(item.type==='fileChange'){
  const changes=Array.isArray(item.changes)?item.changes:[];
  for(const f of changes.slice(0,12)){
   if(typeof f?.path!=='string'||!f.path||typeof f.diff!=='string')continue;
   const kind=typeof f.kind==='object'?f.kind?.type:f.kind;
   const file={path:limit(f.path,1024),diff:limit(f.diff,8192),kind:['add','delete','update'].includes(kind)?kind:'unknown'};
   a.truncated ||= file.path!==f.path||file.diff!==f.diff;
   if(Buffer.byteLength(JSON.stringify(a.files.concat(file)))>32768){a.truncated=true;break;}
   a.files.push(file);
  }
  a.truncated ||= changes.length>a.files.length;
 }else if(item.type==='mcpToolCall'){
  a.title=limit([item.server,item.tool].filter(x=>typeof x==='string').join(' / '),512);
  // Arbitrary MCP arguments/results can contain secrets; expose only typed lifecycle status.
 }else if(item.type==='plan'){
  a.detail=limit(item.text,8192);a.truncated=a.detail!==item.text;
 }else if(item.type==='imageView'){
  a.images=thread?require('./conversation-images.cjs').projectImages(thread,item,cwd):[];
 }else if(item.type==='contextCompaction')a.type='compaction';
 else return null;
 return a;
}
function mergeActivities(older=[],newer=[]){
 const all=new Map();for(const a of [...older,...newer])if(a?.id){
  const previous=all.get(a.id);
  // A native item cannot restart. Paged history may race an older live projection.
  if(previous&&(previous.type!==a.type||previous.turn_id!==a.turn_id||
    previous.status!=='inProgress'&&previous.status!==a.status))continue;
  all.set(a.id,previous?.position>=0&&!(a.position>=0)?{...a,position:previous.position}:a);
 }
 const turns=new Map();for(const item of all.values()){
  if(!turns.has(item.turn_id))turns.set(item.turn_id,[]);turns.get(item.turn_id).push(item);
 }
 const values=[...turns.values()].flatMap(items=>items.sort((a,b)=>(a.position>=0?a.position:Number.MAX_SAFE_INTEGER)-(b.position>=0?b.position:Number.MAX_SAFE_INTEGER))).slice(-40);
 while(Buffer.byteLength(JSON.stringify(values))>65536)values.shift();
 return values;
}
function projectTurns(turns=[],thread='',cwd=''){
 return mergeActivities([],turns.flatMap(t=>(t.items||[]).map((i,n)=>projectItem(t.id,i,n,thread,cwd)).filter(Boolean)));
}
function turnDuration(turn){
 if(Number.isSafeInteger(turn.durationMs)&&turn.durationMs>=0)return turn.durationMs;
 const millis=n=>Number.isSafeInteger(n)&&n>0?(n<1e11?n*1000:n):null;
 const start=millis(turn.startedAt),end=millis(turn.completedAt);
 return start!==null&&end!==null&&end>=start?end-start:null;
}
module.exports={projectItem,mergeActivities,projectTurns,limit,turnDuration};
