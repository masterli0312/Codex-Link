'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),os=require('node:os');
const {resolveNativeExecutable}=require('../../app/src/main/assets/task-notifications/native-runtime.cjs');
function fixture(){
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'CodexUsageNative-'));
 const file=name=>{const p=path.join(root,name);fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,'fixture');return p;};
 const options={env:{LOCALAPPDATA:root,PATH:''},platform:'win32',queryPackage:()=>''};
 return {root,file,options,close(){assert.equal(path.dirname(root),path.resolve(os.tmpdir()));assert.ok(path.basename(root).startsWith('CodexUsageNative-'));fs.rmSync(root,{recursive:true,force:true});}};
}
test('a valid explicitly selected native executable is preserved without querying the desktop',()=>{
 const f=fixture();try{const own=f.file('custom/codex.exe');assert.equal(resolveNativeExecutable(own,{...f.options,queryPackage(){throw Error('UNEXPECTED_DISCOVERY');}}),own);}finally{f.close();}
});
test('removed desktop cache paths recover to the actual installed package and recover again after an update',()=>{
 const f=fixture();try{
  const old=f.file('package-one/app/resources/codex.exe');let installed=path.join(f.root,'package-one');
  const opts={...f.options,queryPackage:()=>installed};
  assert.equal(resolveNativeExecutable(path.join(f.root,'removed/codex.exe'),opts),old);
  fs.unlinkSync(old);const current=f.file('package-two/app/resources/codex.exe');installed=path.join(f.root,'package-two');
  assert.equal(resolveNativeExecutable(old,opts),current);
 }finally{f.close();}
});
test('an existing native CLI on PATH is used without interpreting shell arguments',()=>{
 const f=fixture();try{const cli=f.file('native CLI/codex.exe');const opts={...f.options,env:{...f.options.env,PATH:path.dirname(cli)}};
  assert.equal(resolveNativeExecutable('',opts),cli);assert.throws(()=>resolveNativeExecutable('codex.exe --arbitrary',f.options),/CODEX_MISSING/);
 }finally{f.close();}
});
test('desktop cache discovery selects an existing recent native file and ignores missing or zero-byte files',()=>{
 const f=fixture();try{
  const old=f.file('OpenAI/Codex/bin/old/codex.exe'),current=f.file('OpenAI/Codex/bin/current/codex.exe');
  f.file('OpenAI/Codex/bin/old/codex-code-mode-host.exe');f.file('OpenAI/Codex/bin/current/codex-code-mode-host.exe');
  fs.utimesSync(path.dirname(old),new Date(1000),new Date(1000));fs.utimesSync(path.dirname(current),new Date(2000),new Date(2000));
  f.file('OpenAI/Codex/bin/newest/other.txt');assert.equal(resolveNativeExecutable('',f.options),current);
  fs.truncateSync(current,0);assert.equal(resolveNativeExecutable('',f.options),old);
 }finally{f.close();}
});
test('a pinned desktop cache folder pruned by an update moves to the runtime of the installed desktop',()=>{
 const f=fixture();try{
  const pinned=f.file('OpenAI/Codex/bin/pruned/codex.exe'); // the update removed its sidecars
  const runtime=f.file('OpenAI/Codex/bin/updated/codex.exe');fs.writeFileSync(runtime,'updated runtime');f.file('OpenAI/Codex/bin/updated/codex-code-mode-host.exe');
  const install=f.file('package/app/resources/codex.exe');fs.writeFileSync(install,'updated runtime');f.file('package/app/resources/codex-code-mode-host.exe');
  fs.utimesSync(path.dirname(pinned),new Date(3000),new Date(3000));fs.utimesSync(path.dirname(runtime),new Date(1000),new Date(1000));
  assert.equal(resolveNativeExecutable(pinned,{...f.options,queryPackage:()=>path.join(f.root,'package')}),runtime);
  fs.rmSync(path.dirname(runtime),{recursive:true});
  assert.equal(resolveNativeExecutable(pinned,{...f.options,queryPackage:()=>path.join(f.root,'package')}),install);
 }finally{f.close();}
});
test('a complete but outdated pinned desktop runtime follows the installed desktop version',()=>{
 const f=fixture();try{
  const pinned=f.file('OpenAI/Codex/bin/old/codex.exe');f.file('OpenAI/Codex/bin/old/codex-code-mode-host.exe');
  const runtime=f.file('OpenAI/Codex/bin/new/codex.exe');fs.writeFileSync(runtime,'newer runtime');f.file('OpenAI/Codex/bin/new/codex-code-mode-host.exe');
  const install=f.file('package/app/resources/codex.exe');fs.writeFileSync(install,'newer runtime');
  assert.equal(resolveNativeExecutable(pinned,{...f.options,queryPackage:()=>path.join(f.root,'package')}),runtime);
  // Without a resolvable desktop package the complete pinned runtime is still usable.
  assert.equal(resolveNativeExecutable(pinned,{...f.options,queryPackage:()=>''}),pinned);
 }finally{f.close();}
});
test('desktop cache discovery skips folders whose sidecars were pruned',()=>{
 const f=fixture();try{
  const complete=f.file('OpenAI/Codex/bin/complete/codex.exe');f.file('OpenAI/Codex/bin/complete/codex-code-mode-host.exe');
  const pruned=f.file('OpenAI/Codex/bin/pruned/codex.exe');
  fs.utimesSync(path.dirname(complete),new Date(1000),new Date(1000));fs.utimesSync(path.dirname(pruned),new Date(2000),new Date(2000));
  assert.equal(resolveNativeExecutable('',f.options),complete);
 }finally{f.close();}
});
test('failed or relative desktop discovery has a closed error without exposing its private diagnostics',()=>{
 const f=fixture();try{
  for(const queryPackage of [()=>{throw Error('PRIVATE_ACCOUNT_DETAIL');},()=>'.',()=>null]){
   assert.throws(()=>resolveNativeExecutable('',{...f.options,queryPackage}),error=>error.message==='CODEX_MISSING');
  }
 }finally{f.close();}
});
test('PowerShell discovery output retains Unicode paths regardless of the console code page',()=>{
 const f=fixture();try{
  const executable=f.file('\u4e2d\u6587 path/codex.exe');
  const child=require('node:child_process').spawnSync(process.execPath,[path.resolve(__dirname,'../../app/src/main/assets/task-notifications/native-runtime.cjs'),'--resolve-base64',executable],{encoding:'utf8',windowsHide:true});
  assert.equal(child.status,0);assert.match(child.stdout,/^[A-Za-z0-9+/]+=*$/);assert.equal(Buffer.from(child.stdout,'base64').toString('utf8'),executable);
 }finally{f.close();}
});
