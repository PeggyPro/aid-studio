import {createHash} from 'node:crypto';

// Property order must not make an equivalent archived result a different receipt.
export function archivedResultDigest(result){
  function canonical(value){
    if(Array.isArray(value))return value.map(canonical);
    if(value&&typeof value==='object')return Object.fromEntries(Object.keys(value).sort().filter(key=>value[key]!==undefined).map(key=>[key,canonical(value[key])]));
    return value;
  }
  return createHash('sha256').update(JSON.stringify(canonical(result))).digest('hex');
}
