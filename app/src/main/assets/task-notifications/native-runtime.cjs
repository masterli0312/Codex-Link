'use strict';
// Desktop updates may remove the versioned native cache. Discover locally; never
// download an executable or change CODEX_HOME, authentication or Provider options.
const fs=require('node:fs'),path=require('node:path'),{execFileSync}=require('node:child_process');
function installedPackage(env){
 const clean={...env};for(const name of Object.keys(clean))if(name.toUpperCase()==='PSMODULEPATH')delete clean[name];
 const powershell=path.join(env.SystemRoot||env.WINDIR||'C:\\Windows','System32','WindowsPowerShell','v1.0','powershell.exe');
 const encoded=execFileSync(powershell,['-NoProfile','-NonInteractive','-Command',
  "$ErrorActionPreference='Stop'; $p=Get-AppxPackage -Name OpenAI.Codex | Sort-Object Version -Descending | Select-Object -First 1; [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]$p.InstallLocation))"],
  {env:clean,encoding:'utf8',windowsHide:true,timeout:5000,maxBuffer:8192,stdio:['ignore','pipe','ignore']}).trim();
 return Buffer.from(encoded,'base64').toString('utf8');
}
function resolveNativeExecutable(preferred,{env=process.env,platform=process.platform,queryPackage=installedPackage}={}){
 const valid=file=>{try{return typeof file==='string'&&path.isAbsolute(file)&&fs.statSync(file).isFile()&&fs.statSync(file).size>0;}catch{return false;}};
 if(valid(preferred))return preferred;
 const search=env.PATH||env.Path||env.path||'';
 for(const directory of search.split(platform==='win32'?';':':').filter(Boolean)){
  const file=path.join(directory,platform==='win32'?'codex.exe':'codex');if(valid(file))return file;
 }
 if(platform==='win32'){
  const local=env.LOCALAPPDATA||env.LocalAppData;
  if(local){
   const root=path.join(local,'OpenAI','Codex','bin');
   try{
    const dirs=fs.readdirSync(root,{withFileTypes:true}).filter(d=>d.isDirectory()).slice(0,128)
     .map(d=>({file:path.join(root,d.name,'codex.exe'),time:fs.statSync(path.join(root,d.name)).mtimeMs})).sort((a,b)=>b.time-a.time);
    for(const candidate of dirs)if(valid(candidate.file))return candidate.file;
   }catch{}
  }
  try{
   const install=queryPackage(env);
   if(typeof install==='string'&&path.isAbsolute(install)){
    const file=path.join(install,'app','resources','codex.exe');if(valid(file))return file;
   }
  }catch{} // Never relay package-manager diagnostics or local private settings.
 }
 throw Error('CODEX_MISSING');
}
if(require.main===module){
 if(!['--resolve','--resolve-base64'].includes(process.argv[2])){process.exitCode=2;}
 else try{const file=resolveNativeExecutable(process.argv[3]||'');process.stdout.write(process.argv[2]==='--resolve-base64'?Buffer.from(file,'utf8').toString('base64'):file);}
 catch{process.stderr.write('No installed native Codex executable was found. Update or install Codex Desktop, then retry.');process.exitCode=1;}
}
module.exports={resolveNativeExecutable};
