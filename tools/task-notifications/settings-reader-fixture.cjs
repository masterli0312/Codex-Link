'use strict';
// Reads only the transient settings fixture created by settings-file.test.ps1.
const fs=require('node:fs');
const file=process.argv[2],until=Date.now()+4000;
let reads=0,invalid=0,errors={};
console.log('READY');
while(Date.now()<until){try{JSON.parse(fs.readFileSync(file,'utf8'));reads++;}catch(e){invalid++;const kind=e.code||e.name;errors[kind]=(errors[kind]||0)+1;}}
console.log(JSON.stringify({reads,invalid,errors}));process.exitCode=invalid?1:0;
