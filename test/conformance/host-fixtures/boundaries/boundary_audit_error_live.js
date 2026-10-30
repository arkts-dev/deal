let saved;module.exports={edit:e=>{saved=e;e.message="changed";},read:e=>e===saved?e.message:"LOST_IDENTITY"};
