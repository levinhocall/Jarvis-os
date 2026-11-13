/* Original JARVIS fluid plasma. Uses the repository's packaged Three.js only. */
(function (root) {
  'use strict';
  const STATES = ['IDLE','LISTENING','USER_SPEAKING','PROCESSING','AI_SPEAKING','INTERRUPTED','ERROR','DISCONNECTED'];
  const ENERGY = {IDLE:.35,LISTENING:.53,USER_SPEAKING:.73,PROCESSING:.63,AI_SPEAKING:.82,INTERRUPTED:.5,ERROR:.22,DISCONNECTED:.2};
  const clamp = x => Number.isFinite(x) ? Math.max(0, Math.min(1, x)) : 0;
  class PlasmaMotion {
    constructor() {
      this.state = 'IDLE'; this.session = -1; this.generation = -1;
      this.mic = 0; this.output = 0; this.targetMic = 0; this.targetOutput = 0;
      this.received = -Infinity; this.energy = .35; this.process = 0; this.error = 0; this.disconnected = 0;
      this.time = 0; this.reduced = false;
    }
    accept(p, now) {
      if (!p || !STATES.includes(p.state) || !Number.isFinite(p.sessionId) || !Number.isFinite(p.generation)) return false;
      if (p.sessionId < this.session || (p.sessionId === this.session && p.generation < this.generation)) return false;
      if (p.sessionId !== this.session || p.generation !== this.generation || p.state === 'INTERRUPTED') {
        this.output = 0; this.mic = 0; this.targetOutput = 0;
      }
      this.session = p.sessionId; this.generation = p.generation; this.state = p.state;
      this.targetMic = ['LISTENING','USER_SPEAKING'].includes(p.state) ? clamp(p.mic) : 0;
      this.targetOutput = p.state === 'AI_SPEAKING' ? clamp(p.output) : 0;
      this.received = now;
      return true;
    }
    tick(dt, now) {
      dt = Math.min(.08, Math.max(0, dt));
      const blend = 1 - Math.exp(-dt / .15), stale = now - this.received > 300;
      this.mic += ((stale ? 0 : this.targetMic) - this.mic) * (1 - Math.exp(-dt/.035));
      this.output += ((stale ? 0 : this.targetOutput) - this.output) * (1 - Math.exp(-dt/.035));
      const target = ENERGY[this.state];
      this.energy += (target - this.energy) * blend;
      this.process += ((this.state === 'PROCESSING' ? 1 : 0) - this.process) * blend;
      this.error += ((this.state === 'ERROR' ? 1 : 0) - this.error) * blend;
      this.disconnected += ((this.state === 'DISCONNECTED' ? 1 : 0) - this.disconnected) * blend;
      if (!this.reduced) this.time += dt;
      else { this.energy=target;this.process=this.state==='PROCESSING'?1:0;this.error=this.state==='ERROR'?1:0;this.disconnected=this.state==='DISCONNECTED'?1:0; }
    }
  }
  const vertex = 'varying vec2 vUv; void main(){vUv=uv;gl_Position=vec4(position.xy,0.0,1.0);}';
  // Bounded analytic volume: no ray march, textures, particle buffers or hidden geometry.
  const fragment = `
    precision highp float;
    varying vec2 vUv;
    uniform vec2 resolution;
    uniform float time, energy, audio, processing, concern, offline, quality, intensity, glow, orbSize;
    uniform vec3 cyan, blue, ice, whiteLight;
    float hash(vec3 p){p=fract(p*.3183099+vec3(.13,.71,.27));p*=17.;return fract(p.x*p.y*p.z*(p.x+p.y+p.z));}
    float noise(vec3 p){vec3 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);
      return mix(mix(mix(hash(i),hash(i+vec3(1,0,0)),f.x),mix(hash(i+vec3(0,1,0)),hash(i+vec3(1,1,0)),f.x),f.y),
      mix(mix(hash(i+vec3(0,0,1)),hash(i+vec3(1,0,1)),f.x),mix(hash(i+vec3(0,1,1)),hash(i+vec3(1,1,1)),f.x),f.y),f.z);}
    float fbm(vec3 p){float sum=0.,a=.5;for(int i=0;i<4;i++){if(i>=2 && quality<.5)break;sum+=a*noise(p);p=p*2.03+vec3(3.1,1.7,2.8);a*=.5;}return sum;}
    void main(){
      vec2 p=(vUv-.5)*2.*resolution/min(resolution.x,resolution.y);
      float t=time*.15, angle=atan(p.y,p.x);
      // Incommensurate low-frequency flows avoid a short loop and rigid radial symmetry.
      float breathing=.012*sin(time*1.41)+.006*sin(time*.71);
      float radius=(.64+breathing+audio*.035)*orbSize;
      float fold=.036*sin(angle*3.+t*1.13)+.022*sin(angle*5.-t*.67)+.01*sin(angle*7.+t*.39);
      float d=length(p)/(radius+fold*(.7+audio));
      float edge=1.-smoothstep(.94,1.045,d);
      float z=sqrt(max(0.,1.-min(d*d,1.)));
      vec3 n=normalize(vec3(p/radius,z+.04));
      vec3 q=n*1.9+vec3(t*.27,-t*.23,t*.17);
      float warp=fbm(q+vec3(0.,t*.31,0.));
      float fluid=fbm(q*1.3+vec3(warp*2.4,-warp*1.7,warp)+vec3(0.,-processing*t*.4,0.));
      float ribbons=.5+.5*sin(fluid*16.+warp*5.-t*(1.+processing));
      ribbons=pow(ribbons,3.);
      float backLayer=fbm(q*1.1+vec3(2.4,-1.2,t*.2));
      float fresnel=pow(1.-z,3.);
      float diffuse=.25+.75*max(0.,dot(n,normalize(vec3(-.5,.7,1.2))));
      float nucleus=exp(-dot(p-vec2(.025*sin(t),.025*cos(t*.73)),p-vec2(.025*sin(t),.025*cos(t*.73)))*20.);
      vec3 c=mix(blue,cyan,clamp(fluid*.9+ribbons*.35,0.,1.));
      c*=.22+diffuse*.35+backLayer*.45;
      c+=mix(cyan,ice,ribbons)*ribbons*(.3+z*.6);
      c+=whiteLight*nucleus*(.2+audio*.25)*( .65+fluid*.6);
      c+=ice*pow(max(0.,dot(reflect(-normalize(vec3(-.45,.65,1.)),n),vec3(0,0,1))),30.)*.35;
      c+=cyan*fresnel*.28;
      // Mix state hues in linear light; restrained error dimming, never a red strobe.
      c=mix(c,vec3(c.b*.7,c.g*.38,c.b*1.3),processing*.6);
      c=mix(c,c*vec3(1.45,.6,.35)+vec3(.12,.018,.005),concern*.7);
      c=mix(c,c*.35,offline*.7);
      c*=intensity*(.65+energy*.55);
      float halo=exp(-max(d-1.,0.)*10.)*.14*glow*(.5+energy)*smoothstep(.6,1.1,d);
      float alpha=clamp(edge+halo,0.,1.);
      c=mix(cyan*.35*glow,c,edge);
      c=vec3(1.)-exp(-c*1.25);
      gl_FragColor=vec4(pow(max(c,vec3(0.)),vec3(1./2.2)),alpha);
    }`;
  function create(canvas) {
    const THREE = root.THREE;
    const motion = new PlasmaMotion();
    let renderer, disposed = false, failed = false, visible = true, reduced = false;
    let frame = 0, last = 0, budget = 1000/60, slow = 0, frames = 0, degraded = false;
    let speed = 1;
    const fail = () => {
      if (disposed || failed) return;
      failed = true; cancelAnimationFrame(frame);
      if (root.OrbRendererBridge) root.OrbRendererBridge.onFailure();
    };
    try { renderer = new THREE.WebGLRenderer({canvas, alpha:true, antialias:false, powerPreference:'low-power'}); }
    catch (_) { fail(); return {inspect:()=>({failed:true}),dispose:()=>{disposed=true;}}; }
    const linear = hex => new THREE.Color(hex).convertSRGBToLinear();
    const uniforms = {
      resolution:{value:new THREE.Vector2(1,1)}, time:{value:0}, energy:{value:.35}, audio:{value:0},
      processing:{value:0}, concern:{value:0}, offline:{value:0}, quality:{value:1},
      intensity:{value:1}, glow:{value:1}, orbSize:{value:1},
      cyan:{value:linear('#00E5FF')}, blue:{value:linear('#008CFF')}, ice:{value:linear('#A5F3FF')}, whiteLight:{value:linear('#E8FCFF')}
    };
    const scene = new THREE.Scene(), camera = new THREE.Camera();
    const geometry = new THREE.PlaneGeometry(2,2);
    const material = new THREE.ShaderMaterial({uniforms,vertexShader:vertex,fragmentShader:fragment,transparent:true,depthTest:false,depthWrite:false});
    scene.add(new THREE.Mesh(geometry,material));
    function resize() {
      if (disposed || failed) return;
      const w=Math.max(1,canvas.clientWidth), h=Math.max(1,canvas.clientHeight);
      renderer.setSize(w,h,false);uniforms.resolution.value.set(w,h);dirty();
    }
    function draw(now) {
      if(disposed || failed || !visible || document.hidden)return;
      const elapsed=last ? now-last : budget;
      if(!reduced && elapsed < budget-.8){schedule();return;}
      // Sustained late frame delivery selects a cheaper shader and 30 Hz, never a FPS claim.
      if(!reduced && budget<20){slow=elapsed>24?slow+1:Math.max(0,slow-1);if(slow>=30)lowerQuality();}
      last=now;motion.tick(Math.min(elapsed/1000,.08),now);
      uniforms.time.value=motion.time*speed;
      uniforms.energy.value=motion.energy;uniforms.processing.value=motion.process;
      uniforms.concern.value=motion.error;uniforms.offline.value=motion.disconnected;
      uniforms.audio.value=reduced?0:Math.max(motion.mic*(motion.state==='USER_SPEAKING'?.8:.18),motion.output);
      try { renderer.render(scene,camera);frames++; } catch(_){fail();}
      if(!reduced)schedule();
    }
    function schedule(){if(!frame && !disposed && !failed && visible && !document.hidden)frame=requestAnimationFrame(n=>{frame=0;draw(n);});}
    function dirty(){schedule();}
    function lowerQuality(){degraded=true;uniforms.quality.value=0;budget=1000/30;renderer.setPixelRatio(Math.min(devicePixelRatio||1,1));}
    function setVisible(value){visible=!!value;cancelAnimationFrame(frame);frame=0;last=0;if(visible)dirty();}
    function visibility(){setVisible(visible);}
    function lost(event){event.preventDefault();fail();}
    renderer.setPixelRatio(Math.min(devicePixelRatio||1,1.5));renderer.setClearColor(0,0);
    canvas.addEventListener('webglcontextlost',lost);
    window.addEventListener('resize',resize);document.addEventListener('visibilitychange',visibility);
    const observer=root.ResizeObserver?new ResizeObserver(resize):null;if(observer)observer.observe(canvas);
    const api = {
      setPresentation(p){
        const previousState=motion.state, previousSession=motion.session, previousGeneration=motion.generation;
        if(motion.accept(p,performance.now())){
          // Reduced motion draws state changes only, not a static image for every PCM packet.
          if(reduced){
            if(previousState===motion.state && previousSession===motion.session && previousGeneration===motion.generation)return;
            motion.tick(.08,performance.now());
          }
          dirty();
        }
      },
      setVisible,
      setReducedMotion(value){reduced=!!value;motion.reduced=reduced;cancelAnimationFrame(frame);frame=0;last=0;dirty();},
      configure(o){for(const [key,name,min,max] of [['intensity','intensity',.3,1.5],['glow','glow',0,1.5],['size','orbSize',.7,1.15]])if(Number.isFinite(o[key]))uniforms[name].value=Math.max(min,Math.min(max,o[key]));if(Number.isFinite(o.speed))speed=Math.max(.4,Math.min(1.6,o.speed));if(o.quality==='saver')lowerQuality();resize();},
      // Legacy host calls cannot replace the authoritative presentation in this mode.
      setState(){},setExternalAudio(){},setTheme(){},setBackground(){},
      inspect:()=>({mode:'fluid_plasma',state:motion.state,mic:motion.mic,output:motion.output,frames,visible,reduced,failed,disposed,degraded,programs:renderer.info.programs.length,geometries:renderer.info.memory.geometries,targetHz:Math.round(1000/budget)}),
      dispose(){if(disposed)return;disposed=true;cancelAnimationFrame(frame);if(observer)observer.disconnect();window.removeEventListener('resize',resize);document.removeEventListener('visibilitychange',visibility);canvas.removeEventListener('webglcontextlost',lost);geometry.dispose();material.dispose();renderer.dispose();renderer.forceContextLoss();}
    };
    resize();
    try { renderer.compile(scene,camera); renderer.render(scene,camera);
      if(renderer.info.programs.some(p=>p.diagnostics && !p.diagnostics.runnable)) fail();
    } catch(_){fail();}
    return api;
  }
  if(typeof module !== 'undefined')module.exports={PlasmaMotion,STATES};
  root.createJarvisPlasma=create;
})(typeof window !== 'undefined'?window:globalThis);
