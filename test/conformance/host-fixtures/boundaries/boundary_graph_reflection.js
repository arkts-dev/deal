module.exports={f:a=>{
  const rejects=[
    ()=>Object.defineProperty(a,"length",{value:0,enumerable:true}),
    ()=>Object.defineProperty(a,"length",{value:0,configurable:true}),
    ()=>Object.defineProperty(a,"length",{value:0,writable:false}),
    ()=>Object.defineProperty(a,"length",{get:()=>0}),
    ()=>Object.defineProperty(a,"0",{value:9,writable:false}),
    ()=>Object.defineProperty(a,"0",{value:9,enumerable:false}),
    ()=>Object.defineProperty(a,"0",{value:9,configurable:false}),
    ()=>Object.preventExtensions(a)
  ];
  for(const reject of rejects){
    let caught=false;
    try{reject();}catch(e){if(!(e instanceof TypeError))throw e;caught=true;}
    if(!caught)throw Error("operation unexpectedly accepted");
    if(a.length!==2||a[0]!==1||a[1]!==2)throw Error("rejected operation mutated storage");
  }
  if(Reflect.deleteProperty(a,"0")||Reflect.set(a,"length",3)||Reflect.set(a,"4",9))throw Error("invalid write accepted");
  if(a.length!==2||a[0]!==1||a[1]!==2)throw Error("failed write mutated storage");
  Object.defineProperty(a,"0",{value:9,writable:true,enumerable:true,configurable:true});
  Object.defineProperty(a,"length",{value:1,writable:true,enumerable:false,configurable:false});
}};
