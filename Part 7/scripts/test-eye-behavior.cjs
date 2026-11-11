const assert=require('node:assert/strict');
const Eye=require('../app/src/main/assets/eye-behavior.js');
const eye=new Eye(()=>.5);
let blink=false,reopened=false;
for(let i=0;i<620;i++){const f=eye.tick(.01);assert(f.opening>=0&&f.opening<=1);if(f.opening<.1)blink=true;if(blink&&f.opening>.99)reopened=true;}
assert(blink&&reopened,'Natural blink must close and reopen eyelids');
assert(eye.nextBlink<=7&&eye.nextBlink>0);
eye.setState('listening');eye.audio(1);let f=eye.tick(.05);assert(f.level>0&&f.level<1);
for(let i=0;i<200;i++)f=eye.tick(.02);assert(f.level<.001,'Stale PCM must decay');
eye.setState('speaking');eye.audio(1);eye.tick(.05);eye.setState('interrupted');assert.equal(eye.level,0);
for(let i=0;i<20;i++)f=eye.tick(.02);assert.equal(f.state,'listening');
eye.setState('sleeping');for(let i=0;i<200;i++)f=eye.tick(.02);assert(f.opening<.001);
eye.setState('waking');for(let i=0;i<200;i++)f=eye.tick(.02);assert(f.opening>.99);assert.equal(f.state,'idle');
const t=eye.time;for(let i=0;i<100;i++)f=eye.tick(.02,true);assert.equal(eye.time,t);assert.equal(f.x,0);assert.equal(f.y,0);
eye.audio(NaN);assert.equal(eye.raw,0);assert.equal(eye.setState('invented_emotion'),false);
eye.configure({blink:false,movement:0});for(let i=0;i<1000;i++)assert(eye.tick(.02).opening>.99);
const double=new Eye(()=>0);let closures=0,closed=false;
for(let i=0;i<450;i++){const v=double.tick(.01).opening;if(v<.1&&!closed){closures++;closed=true;}if(v>.9)closed=false;}assert(closures>=2,'Occasional double blink must be supported');
console.log('PASS: blink closure/reopen/timing/double, sleep/wake, PCM attack/decay, interruption, reduced motion, configuration and invalid input');
