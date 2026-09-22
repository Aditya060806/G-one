import fs from 'node:fs/promises';
import {validateBytes} from 'gltf-validator';
const file=new URL('../Jeevan-Core.glb',import.meta.url);
const result=await validateBytes(new Uint8Array(await fs.readFile(file)),{uri:'Jeevan-Core.glb',maxIssues:100});
await fs.writeFile(new URL('../gltf-validation.json',import.meta.url),JSON.stringify(result,null,2));
console.log(JSON.stringify({errors:result.issues.numErrors,warnings:result.issues.numWarnings,infos:result.issues.numInfos,messages:result.issues.messages.slice(0,8)},null,2));
if(result.issues.numErrors)process.exitCode=1;
