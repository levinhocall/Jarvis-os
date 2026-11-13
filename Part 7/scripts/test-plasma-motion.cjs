const assert=require('node:assert/strict');
const {PlasmaMotion,STATES}=require('../app/src/main/assets/plasma-orb.js');
let checks=0;
for(const state of STATES){const m=new PlasmaMotion();assert(m.accept({state,sessionId:1,generation:1,mic:.5,output:.6},0));for(let n=0;n<20;n++)m.tick(.016,n*16);assert.equal(m.state,state);assert(Number.isFinite(m.energy));checks++;}
const m=new PlasmaMotion();m.accept({state:'AI_SPEAKING',sessionId:2,generation:2,output:1},0);m.tick(.08,10);assert(m.output>0);
m.accept({state:'LISTENING',sessionId:2,generation:3,mic:.2},20);assert.equal(m.output,0);
assert.equal(m.accept({state:'AI_SPEAKING',sessionId:2,generation:2,output:1},21),false);
assert.equal(m.accept({state:'AI_SPEAKING',sessionId:1,generation:8,output:1},22),false);
for(let n=0;n<30;n++)m.tick(.016,500+n*16);assert(m.mic<.001);checks+=4;
m.reduced=true;const time=m.time;m.tick(.08,1100);assert.equal(m.time,time);checks++;
m.accept({state:'PROCESSING',sessionId:2,generation:3,mic:1,output:1},1200);assert.equal(m.targetMic,0);assert.equal(m.targetOutput,0);checks++;
console.log(`PASS ${checks} plasma motion checks`);
