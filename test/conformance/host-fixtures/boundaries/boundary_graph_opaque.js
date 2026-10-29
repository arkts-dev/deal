let saved;module.exports={corrupt:r=>{saved=r;r.set("bad",new Date());},repair:()=>{saved.delete("bad");}};
