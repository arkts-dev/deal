let saved;module.exports={corrupt:r=>{saved=r;r.set("bad",{$kind:"function",$sig:"()->number",$f:null});},repair:()=>{saved.delete("bad");}};
