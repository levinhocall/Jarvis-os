package com.jarvis.assistant.ui.orb

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi

/** Analytic normals and bounded four-octave noise; no textures or network assets. */
@RequiresApi(33)
internal class OrbShader {
    val shader = RuntimeShader(SOURCE)
    fun update(w: Float,h: Float,m: OrbMotion,core: FloatArray,rim: FloatArray,accent: FloatArray,reduced: Boolean) {
        shader.setFloatUniform("resolution",w,h)
        shader.setFloatUniform("time",m.time)
        shader.setFloatUniform("audio",m.rms,m.bass,m.mid,m.treble)
        shader.setFloatUniform("mixA",m.mix[0],m.mix[1],m.mix[2],m.mix[3])
        shader.setFloatUniform("mixB",m.mix[4],m.mix[5],m.mix[6],m.mix[7])
        shader.setFloatUniform("coreColor",core[0],core[1],core[2])
        shader.setFloatUniform("rimColor",rim[0],rim[1],rim[2])
        shader.setFloatUniform("accentColor",accent[0],accent[1],accent[2])
        shader.setFloatUniform("glitch",m.glitch(reduced));shader.setFloatUniform("energy",m.energy)
    }
    companion object {
        const val SOURCE = """
uniform float2 resolution;
uniform float time, glitch, energy;
uniform float4 audio, mixA, mixB;
uniform float3 coreColor, rimColor, accentColor;
float hash(float3 p) { return fract(sin(dot(p,float3(127.1,311.7,74.7)))*43758.5453); }
float noise(float3 p) {
 float3 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);
 return mix(mix(mix(hash(i),hash(i+float3(1,0,0)),f.x),
 mix(hash(i+float3(0,1,0)),hash(i+float3(1,1,0)),f.x),f.y),
 mix(mix(hash(i+float3(0,0,1)),hash(i+float3(1,0,1)),f.x),
 mix(hash(i+float3(0,1,1)),hash(i+float3(1,1,1)),f.x),f.y),f.z);
}
float fbm(float3 p) {
 float value=0.,weight=.5;
 for(int i=0;i<4;i++){value+=weight*noise(p);p=p*2.03+3.1;weight*=.5;}
 return value;
}
half4 main(float2 xy) {
 float2 p=(xy-resolution*.5)/min(resolution.x,resolution.y)*2.;
 p.x+=sin(time*18.)*glitch*.012;
 float r=length(p),radius=.65+audio.y*(.025+mixB.x*.03)+mixA.z*audio.x*.012*sin(atan(p.y,p.x)*9.+time*3.);
 // Keep packed state uniforms live; muted/offline remain restrained and dim.
 float quiet=(1.-mixB.z*.4-mixB.w*.15)*(1.-mixB.w*(.03+.03*sin(time)));
 float halo=exp(-r*r*3.5)*.14*energy*quiet;
 float3 col=rimColor*halo;
 float alpha=halo;
 if(r<radius){
  float2 q=p/radius;float z=sqrt(max(0.,1.-dot(q,q)));
  float3 n=normalize(float3(q,z));
  float speed=.09+mixA.w*.35;
  float3 sampleP=n*3.+float3(0,time*speed,time*speed*.7);
  sampleP+=n*audio.x*.08*sin(time*2.);
  // Domain warp adds fluid depth instead of sinusoidal contour lines.
  float warp=fbm(sampleP+fbm(sampleP+2.));
  float field=fbm(sampleP+warp*2.);
  float fresnel=pow(1.-z,3.);
  float filaments=pow(max(0.,1.-abs(field-.52)*9.),3.);
  float nucleus=exp(-r*r/(.014+audio.y*.018));
  float lit=max(0.,dot(n,normalize(float3(-.5,-.6,1.))));
  float3 tint=mix(rimColor,accentColor,mixA.w*.7);
  col+=tint*(.07+field*.19+filaments*.28)*(lit*.6+.4)*energy;
  col+=rimColor*fresnel*(.45+audio.x*.4)+coreColor*nucleus*(.8+audio.y)*energy*quiet*1.8;
  // Edge chromatic split is small normally, raised briefly by ERROR.
  float split=(1.+glitch*3.)*2./min(resolution.x,resolution.y);
  float redEdge=pow(1.-sqrt(max(0.,1.-pow(length(p+float2(split,0))/radius,2.))),3.);
  float blueEdge=pow(1.-sqrt(max(0.,1.-pow(length(p-float2(split,0))/radius,2.))),3.);
  col.r+=redEdge*(.025+glitch*.15);col.b+=blueEdge*.025;
  alpha=max(.70,clamp(length(col),0.,1.));
 }
 // Shader inputs are linear RGB; encode for ordinary sRGB Canvas output.
 col=clamp(col,0.,1.);
 col=mix(col*12.92,1.055*pow(max(col,float3(.00001)),float3(1./2.4))-.055,step(float3(.0031308),col));
 return half4(half3(col*alpha),half(alpha));
}
"""
    }
}
