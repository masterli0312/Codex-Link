'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),crypto=require('node:crypto');
const {RemoteController}=require('../../app/src/main/assets/task-notifications/remote-core.cjs');
const uuid=()=>crypto.randomUUID(),hash=s=>crypto.createHash('sha256').update(s).digest('hex');
function fixture(options={}){
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'remote-tasks-fixture-')),home=path.join(dir,'home'),sessions=path.join(home,'sessions'),tasks=path.join(dir,'tasks'),worktree=path.join(dir,'worktree');
 for(const d of [sessions,tasks,worktree])fs.mkdirSync(d,{recursive:true});const host=uuid(),events=[],killed=[];
 const processAdapter={exists:async pid=>pid===10||pid===20,inspect:async pid=>[{pid,created_at:100000,command_line:`codex exec --cd "${worktree}"`}],kill:async pid=>killed.push(pid),...options.processAdapter};
 const controller=new RemoteController({directory:dir,key:Buffer.alloc(32,3).toString('base64'),hostId:host,config:{codexHome:home,externalTasksDir:tasks},processAdapter,taskWaitMs:options.taskWaitMs??0,emit:async e=>events.push(e)});
 function rollout(id,rows=[],source='exec',originator='codex_exec'){
  const file=path.join(sessions,`rollout-${id}.jsonl`);fs.writeFileSync(file,[{type:'session_meta',timestamp:new Date().toISOString(),payload:{id,source,originator,cwd:worktree}},...rows].map(JSON.stringify).join('\n')+'\n');return file;
 }
 function manifest(id,extra={}){const obj={id,title:'fixture task',worktree,status:'running',started_at:Date.now(),process_started_at:100000,pid:20,launcher_pid:10,...extra};fs.writeFileSync(path.join(tasks,id+'.json'),JSON.stringify(obj));return obj;}
 async function send(action,extra={}){const c={id:uuid(),host_id:host,thread_id:host,conversation_id:hash(host),baseline_turn:action,issued_at:Date.now(),action,...extra};await controller.handle(c);const e=events.at(-1);assert.equal(e.request_id,c.id);return e;}
 return {dir,home,sessions,tasks,worktree,controller,events,killed,rollout,manifest,send,close(){controller.close();fs.rmSync(dir,{recursive:true,force:true});}};
}
test('task manifests merge resumes, use latest rollout and never forward non-whitelisted fields',async()=>{
 const f=fixture();try{const id=uuid(),file=f.rollout(id,[{type:'turn_context',payload:{model:'gpt-test',effort:'high'}},{type:'event_msg',payload:{type:'token_count',info:{total_token_usage:{total_tokens:123}}}},{type:'event_msg',payload:{type:'exec_command_begin',command:['npm','test']}},{type:'response_item',payload:{type:'message',role:'assistant',content:[{type:'output_text',text:'reply'}]}}]);
  f.manifest('old',{codex_session_id:id,rollout:file,started_at:Date.now()-1000,status:'succeeded',secret:'NEVER EXPORT',prompt_file:path.join(f.dir,'not-read')});
  f.manifest('new',{codex_session_id:id,rollout:file,resumed:true,title:'latest',secret:'NEVER EXPORT'});
  const e=await f.send('tasks');assert.equal(e.status,'tasks');assert.equal(e.tasks.length,1);const t=e.tasks[0];
  assert.equal(t.id,'new');assert.equal(t.session_id,id);assert.equal(t.title,'latest');assert.equal(t.runs,2);assert.equal(t.resumed,true);assert.equal(t.worktree_name,'worktree');assert.equal(t.model,'gpt-test');assert.equal(t.effort,'high');assert.equal(t.tokens,123);assert.equal(t.last_reply,'reply');assert.equal(t.last_step,'执行：npm test');assert.equal(t.can_stop,true);assert.equal(JSON.stringify(e).includes('NEVER EXPORT'),false);assert.equal(JSON.stringify(e).includes(f.dir),false);
 }finally{f.close();}
});
test('exec-only sessions have a first user title, are read-only, and may be read by session ID',async()=>{
 const f=fixture();try{const id=uuid();f.rollout(id,[{type:'response_item',payload:{type:'message',role:'user',content:[{type:'input_text',text:'example '+ 'x'.repeat(200)}]}},{type:'response_item',payload:{type:'message',role:'assistant',content:[{type:'output_text',text:'done'}]}},{type:'event_msg',payload:{type:'task_complete'}}]);
  const e=await f.send('tasks');assert.equal(e.tasks.length,1);assert.equal(e.tasks[0].id,id);assert.equal(e.tasks[0].title.length,120);assert.equal(e.tasks[0].status,'succeeded');assert.equal(e.tasks[0].can_stop,false);
  const read=await f.send('agent_read',{mode:'task',target_id:id});assert.equal(read.status,'snapshot');assert.equal(read.snapshot.messages.at(-1).text,'done');assert.equal(read.snapshot.remote_ref,undefined);
  assert.equal((await f.send('agent_read',{mode:'task',target_id:uuid()})).error,'TASK_NOT_FOUND');assert.equal((await f.send('task_stop',{target_id:id})).error,'TASK_NOT_FOUND');
 }finally{f.close();}
});
test('disabled tasks and oversized lists use the specified errors and partial flag',async()=>{
 const f=fixture();try{for(let i=0;i<55;i++)f.manifest('task-'+i,{title:'"'.repeat(10000),started_at:Date.now()+i,status:'succeeded'});
  const e=await f.send('tasks');assert.equal(e.tasks.length,50);assert.equal(e.partial,true);assert.ok(Buffer.byteLength(JSON.stringify(e))<=196608);
  f.controller.config.externalTasksDir='';assert.equal((await f.send('tasks')).error,'TASKS_DISABLED');assert.equal((await f.send('task_stop',{target_id:'task-1'})).error,'TASKS_DISABLED');
 }finally{f.close();}
});
test('stop writes a marker and a live launcher timeout never terminates a process',async()=>{
 const f=fixture();try{f.manifest('running');const e=await f.send('task_stop',{target_id:'running'});assert.equal(e.error,'STOP_TIMEOUT');assert.equal(fs.existsSync(path.join(f.tasks,'running.stop')),true);assert.deepEqual(f.killed,[]);
  f.manifest('done',{status:'succeeded'});assert.equal((await f.send('task_stop',{target_id:'done'})).reply,'already_finished');
  assert.equal((await f.send('task_stop',{target_id:'missing'})).error,'TASK_NOT_FOUND');
 }finally{f.close();}
});
test('fallback stop refuses every incomplete process identity check',async()=>{
 const cases=[
  {name:'missing process',exists:async()=>false,inspect:async()=>[]},
  {name:'wrong start time',exists:async p=>p===20,inspect:async()=>[{pid:20,created_at:110001,command_line:'codex exec'}]},
  {name:'missing exec',exists:async p=>p===20,inspect:async()=>[{pid:20,created_at:100000,command_line:'codex serve'}]},
  {name:'wrong workspace',exists:async p=>p===20,inspect:async()=>[{pid:20,created_at:100000,command_line:'codex exec --cd C:\\unrelated'}]},
  {name:'unrelated tree',exists:async p=>p===20,inspect:async()=>[{pid:20,created_at:100000,command_line:'launcher'},{pid:21,parent_pid:999,created_at:100000,command_line:'codex exec'}]},
 ];
 for(const c of cases){const f=fixture({processAdapter:c});try{f.manifest('running');const e=await f.send('task_stop',{target_id:'running'});assert.equal(e.error,'STOP_UNVERIFIED',c.name);assert.deepEqual(f.killed,[],c.name);assert.equal(JSON.parse(fs.readFileSync(path.join(f.tasks,'running.json'))).status,'running');}finally{f.close();}}
});
test('verified fallback kills only the recorded tree and atomically records the stopped outcome',async()=>{
 let worktree;const f=fixture({processAdapter:{exists:async p=>p===20,inspect:async()=>[{pid:20,created_at:100000,command_line:'launcher'},{pid:21,parent_pid:20,created_at:100001,command_line:`codex exec --cd "${worktree}"`}]}});worktree=f.worktree;
 try{f.manifest('running');const e=await f.send('task_stop',{target_id:'running'});assert.equal(e.reply,'stopped');assert.deepEqual(f.killed,[20]);const m=JSON.parse(fs.readFileSync(path.join(f.tasks,'running.json')));assert.equal(m.status,'stopped');assert.equal(m.exit_code,-1);assert.ok(m.ended_at);assert.deepEqual(fs.readdirSync(f.tasks).filter(x=>x.endsWith('.tmp')),[]);}finally{f.close();}
});
test('a launcher can acknowledge the stop marker without a fallback kill',async()=>{
 const f=fixture({taskWaitMs:80});try{f.manifest('running');setTimeout(()=>f.manifest('running',{status:'stopped'}),10);
  const e=await f.send('task_stop',{target_id:'running'});assert.equal(e.reply,'stopped');assert.deepEqual(f.killed,[]);
 }finally{f.close();}
});
test('task snapshots refuse rollout paths outside the sessions root',async()=>{
 const f=fixture();try{const id=uuid(),outside=path.join(f.dir,'outside.jsonl');fs.writeFileSync(outside,JSON.stringify({type:'session_meta',payload:{id,source:'exec',originator:'codex_exec'}})+'\n');
  f.manifest('outside',{codex_session_id:id,rollout:outside});const e=await f.send('agent_read',{mode:'task',target_id:'outside'});assert.equal(e.error,'ROLLOUT_UNAVAILABLE');
 }finally{f.close();}
});
test('a directory occupying a stop marker is not treated as a valid stop signal',async()=>{
 const f=fixture({processAdapter:{exists:async p=>p===20}});try{f.manifest('running');fs.mkdirSync(path.join(f.tasks,'running.stop'));const e=await f.send('task_stop',{target_id:'running'});
  assert.equal(e.error,'STOP_UNVERIFIED');assert.deepEqual(f.killed,[]);
 }finally{f.close();}
});
test('incremental task summaries retain offsets for more than eight active rollouts',()=>{
 const {Rollouts}=require('../../app/src/main/assets/task-notifications/rollout-reader.cjs'),f=fixture();try{const reader=new Rollouts(f.home),files=[];
  for(let i=0;i<12;i++){const file=f.rollout(uuid(),[{type:'turn_context',payload:{model:'model-'+i}},{type:'event_msg',payload:{type:'token_count',info:{total_token_usage:{total_tokens:7}}}}]);files.push(file);assert.equal(reader.summary(file).model,'model-'+i);}
  const original=fs.readSync,reads=[];fs.readSync=function(fd,buffer,offset,length,position){reads.push(length);return original(fd,buffer,offset,length,position);};
  try{for(const file of files){fs.appendFileSync(file,JSON.stringify({type:'event_msg',payload:{type:'task_complete',last_agent_message:'done'}})+'\n');const result=reader.summary(file);assert.equal(result.status,'completed');assert.equal(result.tokens,7);assert.equal(result.last_reply,'done');}assert.equal(reads.length,12);assert.ok(reads.every(n=>n<200));}finally{fs.readSync=original;}
 }finally{f.close();}
});
test('long exec sessions use the first user message from the already-read metadata prefix',async()=>{
 const f=fixture();try{const id=uuid(),file=f.rollout(id,[{type:'response_item',payload:{type:'message',role:'user',content:[{type:'input_text',text:'first user request'}]}}]);
  fs.appendFileSync(file,JSON.stringify({type:'response_item',payload:{type:'function_call_output',output:'x'.repeat(4*1024*1024)}})+'\n');
  fs.appendFileSync(file,JSON.stringify({type:'response_item',payload:{type:'message',role:'user',content:[{type:'input_text',text:'later request'}]}})+'\n');
  const e=await f.send('tasks');assert.equal(e.tasks[0].title,'first user request');
 }finally{f.close();}
});
test('the process-tree query is valid PowerShell without executing it',t=>{
 if(process.platform!=='win32'){t.skip('Windows-specific process adapter');return;}
 const {processQuery}=require('../../app/src/main/assets/task-notifications/background-tasks.cjs'),{spawnSync}=require('node:child_process');
 const input=Buffer.from(processQuery(123),'utf8').toString('base64');
 const script=`$s=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${input}'));$t=$null;$e=$null;[void][Management.Automation.Language.Parser]::ParseInput($s,[ref]$t,[ref]$e);if($e.Count){$e|Out-String|Write-Output;exit 1}`;
 const result=spawnSync('powershell.exe',['-NoProfile','-NonInteractive','-EncodedCommand',Buffer.from(script,'utf16le').toString('base64')],{encoding:'utf8',windowsHide:true,timeout:10000});
 assert.equal(result.status,0,result.stdout||result.stderr);
});
test('the Windows process query walks only a mocked process tree',t=>{
 if(process.platform!=='win32'){t.skip('Windows-specific process adapter');return;}
 const {processQuery}=require('../../app/src/main/assets/task-notifications/background-tasks.cjs'),{spawnSync}=require('node:child_process');
 const input=Buffer.from(processQuery(123),'utf8').toString('base64');
 const script=`function Get-CimInstance { param($Class,$Filter,$ErrorAction) switch($Filter){ 'ProcessId=123' { [pscustomobject]@{ProcessId=123;ParentProcessId=1;CreationDate=[datetime]'2026-01-01T00:00:00Z';CommandLine='root'} } 'ParentProcessId=123' { [pscustomobject]@{ProcessId=456} } 'ProcessId=456' { [pscustomobject]@{ProcessId=456;ParentProcessId=123;CreationDate=[datetime]'2026-01-01T00:00:01Z';CommandLine='codex exec'} } } };$s=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${input}'));& ([scriptblock]::Create($s))`;
 const result=spawnSync('powershell.exe',['-NoProfile','-NonInteractive','-EncodedCommand',Buffer.from(script,'utf16le').toString('base64')],{encoding:'utf8',windowsHide:true,timeout:10000});assert.equal(result.status,0,result.stderr);
 const rows=JSON.parse(result.stdout);assert.deepEqual(rows.map(x=>x.pid),[123,456]);assert.equal(rows[1].parent_pid,123);assert.ok(rows[0].created_at>0);
});
