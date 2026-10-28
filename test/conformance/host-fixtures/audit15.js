const rt = require('../deal/runtime');
const identity = '@$external/host.audit15/C';
let retained, growing, callback, unknown;
module.exports = {
  C: {$kind:'class', $classname:identity},
  badClass:kind=>kind===0?{$kind:'class',$classname:identity,x:null}:kind===1?{$kind:'class',$classname:identity,x:'\uD800'}:kind===2?{$kind:'class',$classname:identity}:{$kind:'class',$classname:identity,x:1},
  nullish:()=>undefined,
  echoArray:value=>value,
  replace:value=>{value[0]=2;},
  numbers:value=>value, booleans:value=>value, strings:value=>value,
  keep:value=>{retained=value;}, seesUndefined:()=>retained[0]===undefined,
  fresh:()=>{growing=[1];return growing;}, grow:()=>{growing.push(2);},
  conflict:()=>rt.tagArray([1], 'number'),
  callbacks:(a,b)=>{const same=a===b && (callback===undefined || callback===a);callback=a;return same;},
  echoFunction:value=>value,
  nativeFailure:()=>{throw new Error('native');},
  nativeCallable:()=>rt.function('()->void',()=>{throw new Error('callable');}),
  callbackFailure:value=>value.$f(),
  asyncFailure:()=>Promise.reject(new Error('native async')),
  asyncCallback:async value=>value.$f(),
  echoError:value=>{throw value;},
  keepUnknown:value=>{unknown=value;},
  corrupt:kind=>{unknown[0]=kind===0?'\uD800':()=>0;},
  opaqueArray:()=>[new Date()]
};
