/* Original JARVIS neural wave shader: bounded analytic ribbons and GPU particle shell.
 * Shared host owns timing, input epochs, disposal and visibility. No audio capture. */
(function(root){
'use strict';
root.JarvisNeuralWave={fragment:`
precision highp float;
varying vec2 vUv;
uniform vec2 resolution;
uniform float time, energy, audio, processing, concern, offline, quality, intensity, glow, orbSize, executing;
uniform vec3 cyan, blue, ice, whiteLight;
float line(float d,float width){return exp(-abs(d)/width);}
void main(){
 vec2 p=(vUv-.5)*2.*resolution/min(resolution.x,resolution.y);p.y-=.07;
 float t=time*.23,r=.65*orbSize*(1.+.012*sin(time*1.3)+audio*.045);
 float a=atan(p.y,p.x),d=length(p),edge=d-r;
 vec3 violet=vec3(.42,.06,1.);vec3 tint=mix(cyan,violet,processing*.7);
 tint=mix(tint,vec3(1.,.38,.08),max(executing*.3,concern*.7));
 vec3 c=tint*exp(-abs(edge)*13.)*.14*glow;
 // Woven translucent spherical bands: many thin curves, independently tilted.
 for(int k=0;k<5;k++){
  if(quality<.5 && k>=3)break;
  float f=float(k),rot=f*.73+t*(.17+f*.07)*(1.+processing*1.4);
  vec2 q=mat2(cos(rot),-sin(rot),sin(rot),cos(rot))*p;
  float wave=.045*sin(a*3.+t+f)+.026*sin(a*7.-t*.61+f);
  float ellipse=length(vec2(q.x,q.y/(.44+.09*f)))-r-wave*(1.+audio*.65);
  vec3 hue=mix(tint,violet,.5+.5*sin(a+f*1.9));
  c+=hue*(line(ellipse,.004)*1.5+line(ellipse,.028)*.36*glow);
  for(int j=1;j<8;j++){
   if(quality<.5 && j>=4)break;
   float strand=ellipse+float(j)*.014*sin(a*2.+f+t*.4);
   c+=hue*line(strand,.0023)*.24;
  }
 }
 // Central measured-energy glyph. No random waveform or invented frequency bands.
 float span=.31*orbSize,x=p.x/span;
 float bar=abs(fract((p.x+span)*65.)-.5);
 float h=.003+audio*.16*exp(-x*x*3.)*(.35+.65*abs(cos(x*10.)));
 float waveCore=(1.-smoothstep(.18,.45,bar))*(1.-smoothstep(h,h+.008,abs(p.y)))*(1.-smoothstep(.85,1.,abs(x)));
 c+=mix(tint,ice,.35)*waveCore*1.2;
 c+=tint*exp(-abs(p.y)*85.-p.x*p.x*18.)*(.018+audio*.12)*glow;
 // Faint projector ellipse and reflected light; never a solid platform disc.
 vec2 floorP=vec2(p.x,(p.y+.88*orbSize)*6.);
 float floorD=length(floorP);
 c+=tint*(line(floorD-.59*orbSize,.012)*.5+line(floorD-.76*orbSize,.006)*.07)*glow;
 c+=ice*exp(-dot(floorP,floorP)*26.)*.16*glow;
 c*=intensity*(.55+energy*.6)*(1.-offline*.55);
 float alpha=clamp(max(c.r,max(c.g,c.b))*2.,0.,1.);
 c=vec3(1.)-exp(-c*1.4);
 gl_FragColor=vec4(pow(max(c,vec3(0.)),vec3(1./2.2)),alpha);
}`,particleVertex:`
precision highp float;
uniform vec2 resolution;
uniform float time,audio,orbSize,processing,energy;
attribute float seed;
varying float brightness;
varying float hue;
void main(){
 float t=time*.11*(1.+processing);vec3 p=position;
 float a=t+seed*.0002; p.xz=mat2(cos(a),-sin(a),sin(a),cos(a))*p.xz;
 float f=1.+.035*sin(p.y*9.+t*2.+seed)+audio*.05*sin(seed*3.);
 p*=.65*orbSize*f;float depth=1./(1.-p.z*.26);
 vec2 screen=p.xy*depth;screen.y+=.07;
 screen*=min(resolution.x,resolution.y)/resolution;
 gl_Position=vec4(screen,0.,1.);
 gl_PointSize=(1.8+4.*pow(fract(seed*.73),8.))*depth;
 brightness=(.25+.95*pow(max(0.,1.-abs(position.z)),2.))*(.65+energy*.5);
 hue=.5+.5*sin(seed*.027+time*.08);
}`,particleFragment:`
precision mediump float;
uniform vec3 cyan;
uniform float glow,intensity,concern,offline;
varying float brightness;varying float hue;
void main(){float d=length(gl_PointCoord-.5)*2.;if(d>1.)discard;
 vec3 c=mix(cyan,vec3(.42,.06,1.),hue*.85);c=mix(c,vec3(1.,.3,.08),concern*.7);
 gl_FragColor=vec4(pow(c,vec3(1./2.2)),exp(-d*d*4.)*brightness*intensity*(.6+glow*.4)*(1.-offline*.6));
}`};
})(window);
