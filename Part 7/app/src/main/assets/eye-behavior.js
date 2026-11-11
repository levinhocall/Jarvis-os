(function(root){
 'use strict';
 const clamp=(x,a,b)=>Math.max(a,Math.min(b,Number.isFinite(x)?x:a));
 const smooth=x=>{x=clamp(x,0,1);return x*x*(3-2*x);};
 const states=['sleeping','waking','idle','listening','thinking','speaking','executing','success','error','interrupted'];
 class EyeBehavior {
  constructor(random=Math.random){this.random=random;this.state='idle';this.age=0;this.time=0;this.level=0;this.raw=0;this.audioAge=10;this.blinkAge=-1;this.double=false;this.nextBlink=3+random()*4;this.nextGaze=1;this.x=0;this.y=0;this.tx=0;this.ty=0;this.open=1;this.settings={blink:true,movement:1,speed:1};}
  setState(s){
   const aliases={standby:'sleeping',connected:'listening',connecting:'waking',recovering:'thinking',failed:'error',waiting_for_approval:'idle'};
   s=String(s).toLowerCase();s=aliases[s]||s;
   if(!states.includes(s))return false;
   if(this.state===s)return true;
   const previous=this.state;this.state=s;this.age=0;
   if(s==='interrupted'||previous==='speaking'){this.raw=0;this.level=0;this.audioAge=10;}
   if(s==='sleeping')this.blinkAge=-1;
   return true;
  }
  audio(x){this.raw=clamp(x,0,1);this.audioAge=0;}
  configure(s){this.settings={blink:s.blink!==false,movement:clamp(s.movement??1,0,1),speed:clamp(s.speed??1,.4,1.6)};}
  tick(delta,reduced=false){
   const dt=clamp(delta,0,.1);this.age+=dt;this.audioAge+=dt;
   const target=this.audioAge<.22&&['listening','speaking'].includes(this.state)?this.raw:0;
   this.level+=(target-this.level)*(1-Math.exp(-dt*(target>this.level?22:7)));
   if(this.state==='interrupted'&&this.age>.16)this.setState('listening');
   if(this.state==='waking'&&this.age>1.1)this.setState('idle');
   if(['success','error'].includes(this.state)&&this.age>1.6)this.setState('idle');
   if(!reduced)this.time+=dt*this.settings.speed;
   if(!reduced&&this.settings.blink&&this.state!=='sleeping'){
    this.nextBlink-=dt;
    if(this.blinkAge<0&&this.nextBlink<=0){this.blinkAge=0;this.double=this.random()<.13;}
    if(this.blinkAge>=0){this.blinkAge+=dt;if(this.blinkAge>.32){this.blinkAge=-1;this.nextBlink=this.double?.18:(this.state==='listening'?4:3)+this.random()*4;this.double=false;}}
   }else this.blinkAge=-1;
   let blink=0;
   if(this.blinkAge>=0)blink=this.blinkAge<.11?smooth(this.blinkAge/.11):1-smooth((this.blinkAge-.11)/.21);
   const opening=this.state==='sleeping'?0:this.state==='waking'?smooth(this.age/1.05):1;
   this.open+=(opening-this.open)*(1-Math.exp(-dt*5));
   if(reduced)this.open=opening;
   if(!reduced){this.nextGaze-=dt;if(this.nextGaze<=0){this.tx=(this.random()-.5)*.11*this.settings.movement;this.ty=(this.random()-.5)*.065*this.settings.movement;this.nextGaze=1.2+this.random()*2.8;}}
   const k=1-Math.exp(-dt*4);this.x+=(this.tx-this.x)*k;this.y+=(this.ty-this.y)*k;
   const frame=this.frame||(this.frame={});
   frame.opening=this.open*(1-blink);frame.x=reduced?0:this.x;frame.y=reduced?0:this.y;frame.level=this.level;
   frame.pupil=.165+(reduced?0:Math.sin(this.time*.6)*.008)+this.level*.022;frame.time=this.time;frame.state=this.state;
   return frame;
  }
 }
 root.EyeBehavior=EyeBehavior;
 if(typeof module!=='undefined')module.exports=EyeBehavior;
})(typeof window!=='undefined'?window:globalThis);
