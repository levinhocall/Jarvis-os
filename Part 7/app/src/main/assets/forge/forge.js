import * as THREE from 'three';
import { EffectComposer } from './postprocessing/EffectComposer.js';
import { RenderPass } from './postprocessing/RenderPass.js';
import { UnrealBloomPass } from './postprocessing/UnrealBloomPass.js';
import { ShaderPass } from './postprocessing/ShaderPass.js';
import { OutputPass } from './postprocessing/OutputPass.js';

(function () {
  let renderer = null;
  let scene = null;
  let camera = null;
  let composer = null;
  let isDisposed = false;
  let isRunning = false;
  let animId = null;

  let clock = new THREE.Clock();
  let progress = 0.0;
  let currentStage = 1;
  let isRevealing = false;
  let isFailed = false;
  let reduceMotion = false;
  let isDirty = true;

  // Scene elements
  let coreGroup = null;
  let coreSphere = null;
  let coreWire = null;
  let coreRings = [];
  let coreLight = null;
  let particles = null;
  let particlePositions = null;
  let particleInitialCoords = null;
  let floorMesh = null;
  let stageNodes = [];
  let slabs = [];
  let logCards = [];
  let shockwaves = [];
  let dataBeams = [];

  // Theme colors
  let theme = {
    bgPrimary: '#070709',
    bgSecondary: '#121218',
    accentPurple: '#8B5CF6',
    accentGold: '#F59E0B',
    accentCyan: '#06B6D4',
    textPrimary: '#F8FAFC',
    textSecondary: '#94A3B8'
  };

  // Camera animation
  let cameraTarget = new THREE.Vector3(0, 0, 0);
  let flyInStart = 0;
  let cameraKick = 0;
  let touchStartX = 0, touchStartY = 0;
  let camRotX = 0, camRotY = 0;
  let targetCamRotX = 0, targetCamRotY = 0;

  // Vignette + Chromatic Aberration Shader
  const PostShader = {
    uniforms: {
      tDiffuse: { value: null },
      uOffset: { value: 0.003 },
      uDarkness: { value: 1.15 }
    },
    vertexShader: `
      varying vec2 vUv;
      void main() {
        vUv = uv;
        gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
      }
    `,
    fragmentShader: `
      uniform sampler2D tDiffuse;
      uniform float uOffset;
      uniform float uDarkness;
      varying vec2 vUv;
      void main() {
        vec2 uv = vUv;
        // Chromatic aberration
        float r = texture2D(tDiffuse, uv + vec2(uOffset, 0.0)).r;
        float g = texture2D(tDiffuse, uv).g;
        float b = texture2D(tDiffuse, uv - vec2(uOffset, 0.0)).b;
        vec4 col = vec4(r, g, b, 1.0);
        // Vignette
        vec2 dist = (uv - 0.5) * 1.25;
        col.rgb *= clamp(1.0 - dot(dist, dist) * uDarkness, 0.0, 1.0);
        gl_FragColor = col;
      }
    `
  };

  function checkWebGL() {
    try {
      const canvas = document.createElement('canvas');
      return !!(window.WebGLRenderingContext && (canvas.getContext('webgl') || canvas.getContext('experimental-webgl')));
    } catch (e) {
      return false;
    }
  }

  function init(themeJson) {
    if (isDisposed) return;
    if (!checkWebGL()) {
      if (window.ForgeBridge && window.ForgeBridge.onWebGLFailed) {
        window.ForgeBridge.onWebGLFailed();
      }
      return;
    }

    try {
      if (typeof themeJson === 'string') {
        try { Object.assign(theme, JSON.parse(themeJson)); } catch (_) {}
      } else if (typeof themeJson === 'object' && themeJson !== null) {
        Object.assign(theme, themeJson);
      }
      if (theme.reduceMotion !== undefined) {
        reduceMotion = !!theme.reduceMotion;
      }

      const container = document.getElementById('canvas-container') || document.body;
      const width = window.innerWidth || 360;
      const height = window.innerHeight || 640;

      // Renderer setup
      renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false, powerPreference: 'high-performance' });
      const maxPr = (theme.isLowRam ? 1.5 : 2.0);
      renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, maxPr));
      renderer.setSize(width, height);
      renderer.toneMapping = THREE.ACESFilmicToneMapping;
      renderer.toneMappingExposure = 1.1;
      container.innerHTML = '';
      container.appendChild(renderer.domElement);

      // Scene
      scene = new THREE.Scene();
      scene.background = new THREE.Color(theme.bgPrimary);
      scene.fog = new THREE.FogExp2(theme.bgPrimary, 0.025);

      // Camera
      camera = new THREE.PerspectiveCamera(50, width / height, 0.1, 1000);
      camera.position.set(0, 4, 55); // Starts far for 2s fly-in
      camera.lookAt(cameraTarget);
      flyInStart = performance.now();

      // Lighting
      const ambLight = new THREE.AmbientLight(theme.bgSecondary, 1.2);
      scene.add(ambLight);

      coreLight = new THREE.PointLight(theme.accentPurple, 3.5, 35);
      coreLight.position.set(0, 0, 0);
      scene.add(coreLight);

      // 1. Build Cosmic Backdrop & Stars
      buildBackdrop();

      // 2. Build Floor Grid
      buildFloorGrid();

      // 3. Build Reactor Core
      buildReactorCore();

      // 4. Build 1800 Glyph Particles
      buildGlyphParticles();

      // 5. Build Stage Nodes Ring
      buildStageNodes();

      // 6. Setup Composer with Bloom and PostShader
      setupPostProcessing(width, height);

      // Event listeners
      setupEvents();

      isRunning = true;
      clock.start();
      animate();

      if (window.ForgeBridge && window.ForgeBridge.onReady) {
        window.ForgeBridge.onReady();
      }
    } catch (err) {
      console.error("WebGL Forge init failed:", err);
      if (window.ForgeBridge && window.ForgeBridge.onWebGLFailed) {
        window.ForgeBridge.onWebGLFailed();
      }
    }
  }

  function setupPostProcessing(w, h) {
    try {
      composer = new EffectComposer(renderer);
      const renderPass = new RenderPass(scene, camera);
      composer.addPass(renderPass);

      // Bloom pass at half resolution for performance
      const bloomPass = new UnrealBloomPass(
        new THREE.Vector2(Math.floor(w / 2), Math.floor(h / 2)),
        1.1, // strength
        0.4, // radius
        0.82 // threshold
      );
      composer.addPass(bloomPass);

      const shaderPass = new ShaderPass(PostShader);
      composer.addPass(shaderPass);

      const outputPass = new OutputPass();
      composer.addPass(outputPass);
    } catch (e) {
      console.warn("Composer setup fallback to direct render", e);
      composer = null;
    }
  }

  function buildBackdrop() {
    // Star field
    const starCount = 600;
    const starGeo = new THREE.BufferGeometry();
    const starPos = new Float32Array(starCount * 3);
    for (let i = 0; i < starCount; i++) {
      const r = 80 + Math.random() * 40;
      const theta = Math.random() * Math.PI * 2;
      const phi = Math.acos(2 * Math.random() - 1);
      starPos[i * 3] = r * Math.sin(phi) * Math.cos(theta);
      starPos[i * 3 + 1] = r * Math.sin(phi) * Math.sin(theta);
      starPos[i * 3 + 2] = r * Math.cos(phi);
    }
    starGeo.setAttribute('position', new THREE.BufferAttribute(starPos, 3));
    const starMat = new THREE.PointsMaterial({
      color: 0xffffff,
      size: 0.8,
      transparent: true,
      opacity: 0.65
    });
    scene.add(new THREE.Points(starGeo, starMat));
  }

  function buildFloorGrid() {
    const gridHelper = new THREE.GridHelper(60, 40, new THREE.Color(theme.accentPurple), new THREE.Color(theme.bgSecondary));
    gridHelper.position.y = -6;
    gridHelper.material.transparent = true;
    gridHelper.material.opacity = 0.35;
    floorMesh = gridHelper;
    scene.add(floorMesh);
  }

  function buildReactorCore() {
    coreGroup = new THREE.Group();
    coreGroup.position.set(0, 0, 0);

    // Inner fresnel-like sphere
    const sphereGeo = new THREE.SphereGeometry(1.6, 24, 24);
    const sphereMat = new THREE.MeshBasicMaterial({
      color: new THREE.Color(theme.accentPurple),
      wireframe: false,
      transparent: true,
      opacity: 0.85
    });
    coreSphere = new THREE.Mesh(sphereGeo, sphereMat);
    coreGroup.add(coreSphere);

    // Wireframe icosahedron
    const icoGeo = new THREE.IcosahedronGeometry(2.4, 1);
    const icoMat = new THREE.MeshBasicMaterial({
      color: new THREE.Color(theme.accentCyan),
      wireframe: true,
      transparent: true,
      opacity: 0.55
    });
    coreWire = new THREE.Mesh(icoGeo, icoMat);
    coreGroup.add(coreWire);

    // 3 Gyroscopic torus rings
    coreRings = [];
    const ringRadii = [3.2, 3.8, 4.4];
    const ringColors = [theme.accentCyan, theme.accentPurple, theme.accentGold];
    for (let i = 0; i < 3; i++) {
      const torusGeo = new THREE.TorusGeometry(ringRadii[i], 0.04, 8, 48);
      const torusMat = new THREE.MeshBasicMaterial({
        color: new THREE.Color(ringColors[i]),
        transparent: true,
        opacity: 0.75
      });
      const ring = new THREE.Mesh(torusGeo, torusMat);
      coreRings.push(ring);
      coreGroup.add(ring);
    }

    scene.add(coreGroup);
  }

  function buildGlyphParticles() {
    const count = 1800;
    const geo = new THREE.BufferGeometry();
    const pos = new Float32Array(count * 3);
    particleInitialCoords = new Float32Array(count * 3);

    for (let i = 0; i < count; i++) {
      const radius = 6 + Math.random() * 22;
      const angle = Math.random() * Math.PI * 2;
      const height = (Math.random() - 0.5) * 16;
      pos[i * 3] = radius * Math.cos(angle);
      pos[i * 3 + 1] = height;
      pos[i * 3 + 2] = radius * Math.sin(angle);

      particleInitialCoords[i * 3] = radius;
      particleInitialCoords[i * 3 + 1] = angle;
      particleInitialCoords[i * 3 + 2] = height;
    }

    geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    particlePositions = pos;

    // Canvas particle texture with glyphs
    const canvas = document.createElement('canvas');
    canvas.width = 64;
    canvas.height = 64;
    const ctx = canvas.getContext('2d');
    ctx.fillStyle = '#ffffff';
    ctx.font = 'bold 36px monospace';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('{ }', 32, 32);

    const texture = new THREE.CanvasTexture(canvas);
    const mat = new THREE.PointsMaterial({
      size: 0.9,
      map: texture,
      transparent: true,
      opacity: 0.8,
      color: new THREE.Color(theme.accentCyan),
      blending: THREE.AdditiveBlending
    });

    particles = new THREE.Points(geo, mat);
    scene.add(particles);
  }

  function buildStageNodes() {
    stageNodes = [];
    const count = 6;
    const radius = 8.5;
    for (let i = 0; i < count; i++) {
      const theta = (i / count) * Math.PI * 2 - Math.PI / 2;
      const x = radius * Math.cos(theta);
      const z = radius * Math.sin(theta);
      const y = -1.5;

      const nodeGeo = new THREE.SphereGeometry(0.35, 12, 12);
      const nodeMat = new THREE.MeshBasicMaterial({
        color: (i === 0 ? new THREE.Color(theme.accentPurple) : new THREE.Color(theme.bgSecondary)),
        transparent: true,
        opacity: (i === 0 ? 0.95 : 0.4)
      });
      const mesh = new THREE.Mesh(nodeGeo, nodeMat);
      mesh.position.set(x, y, z);
      scene.add(mesh);
      stageNodes.push(mesh);
    }
  }

  function setStage(idx) {
    if (idx < 1 || idx > 6) return;
    currentStage = idx;
    cameraKick = 1.0;
    isDirty = true;

    // Update stage node colors
    stageNodes.forEach((node, i) => {
      if (i < idx) {
        node.material.color.set(theme.accentCyan);
        node.material.opacity = 0.95;
        node.scale.set(1.2, 1.2, 1.2);
      } else {
        node.material.color.set(theme.bgSecondary);
        node.material.opacity = 0.4;
        node.scale.set(1.0, 1.0, 1.0);
      }
    });

    // Create a shockwave ripple
    createShockwave();
    // Fire a data beam
    createDataBeam();
  }

  function setProgress(p) {
    progress = Math.max(0, Math.min(1, p));
    isDirty = true;
    if (coreLight) {
      coreLight.intensity = 3.5 + progress * 4.0;
    }
  }

  function addSection(name) {
    if (slabs.length >= 10 || !name) return;
    isDirty = true;

    const canvas = document.createElement('canvas');
    canvas.width = 256;
    canvas.height = 64;
    const ctx = canvas.getContext('2d');
    ctx.fillStyle = 'rgba(20, 20, 30, 0.85)';
    ctx.fillRect(0, 0, 256, 64);
    ctx.strokeStyle = theme.accentPurple;
    ctx.lineWidth = 4;
    ctx.strokeRect(2, 2, 252, 60);

    ctx.fillStyle = theme.textPrimary;
    ctx.font = 'bold 22px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(name.slice(0, 20), 128, 32);

    const texture = new THREE.CanvasTexture(canvas);
    const geo = new THREE.BoxGeometry(4.2, 1.1, 0.2);
    const mat = new THREE.MeshBasicMaterial({
      map: texture,
      transparent: true,
      opacity: 0.9
    });
    const slabMesh = new THREE.Mesh(geo, mat);

    const angle = (slabs.length / 10) * Math.PI * 1.5 - Math.PI * 0.75;
    const r = 11.5;
    slabMesh.position.set(r * Math.sin(angle), 1.5 + (slabs.length % 3) * 1.8 - 1.8, r * Math.cos(angle) - 4);
    slabMesh.lookAt(0, 0, 0);

    scene.add(slabMesh);
    slabs.push(slabMesh);
  }

  function log(line) {
    if (!line) return;
    isDirty = true;
    if (logCards.length >= 4) {
      const old = logCards.shift();
      scene.remove(old);
      if (old.geometry) old.geometry.dispose();
      if (old.material) old.material.dispose();
    }

    const canvas = document.createElement('canvas');
    canvas.width = 384;
    canvas.height = 48;
    const ctx = canvas.getContext('2d');
    ctx.fillStyle = 'rgba(10, 10, 16, 0.75)';
    ctx.fillRect(0, 0, 384, 48);
    ctx.fillStyle = theme.accentGold;
    ctx.font = '16px monospace';
    ctx.textAlign = 'left';
    ctx.textBaseline = 'middle';
    ctx.fillText('> ' + line.slice(0, 38), 12, 24);

    const texture = new THREE.CanvasTexture(canvas);
    const geo = new THREE.PlaneGeometry(5.0, 0.65);
    const mat = new THREE.MeshBasicMaterial({
      map: texture,
      transparent: true,
      opacity: 0.85,
      side: THREE.DoubleSide
    });
    const plane = new THREE.Mesh(geo, mat);
    plane.position.set(7.5, -2.5 - logCards.length * 0.9, 8);
    plane.lookAt(0, 0, 12);

    scene.add(plane);
    logCards.push(plane);
  }

  function createShockwave() {
    const geo = new THREE.RingGeometry(0.5, 0.8, 32);
    const mat = new THREE.MeshBasicMaterial({
      color: new THREE.Color(theme.accentCyan),
      side: THREE.DoubleSide,
      transparent: true,
      opacity: 0.85
    });
    const mesh = new THREE.Mesh(geo, mat);
    mesh.rotation.x = Math.PI / 2;
    mesh.position.y = -5.8;
    scene.add(mesh);
    shockwaves.push({ mesh, scale: 1.0, life: 1.0 });
  }

  function createDataBeam() {
    const geo = new THREE.CylinderGeometry(0.08, 0.08, 16, 8);
    const mat = new THREE.MeshBasicMaterial({
      color: new THREE.Color(theme.accentPurple),
      transparent: true,
      opacity: 0.75
    });
    const beam = new THREE.Mesh(geo, mat);
    beam.position.set((Math.random() - 0.5) * 8, 2, (Math.random() - 0.5) * 8);
    beam.rotation.z = Math.PI / 2;
    scene.add(beam);
    dataBeams.push({ beam, life: 1.0 });
  }

  function fail() {
    isFailed = true;
    isDirty = true;
    if (coreLight) coreLight.color.set('#64748B');
    if (coreSphere) coreSphere.material.color.set('#334155');
    slabs.forEach(s => {
      s.position.y -= 2.0;
      s.rotation.z += 0.3;
    });
  }

  function reveal() {
    if (isRevealing || isDisposed) return;
    isRevealing = true;
    isDirty = true;
    const revealStart = performance.now();

    // Flash light
    if (coreLight) {
      coreLight.intensity = 15;
      coreLight.color.set('#FFFFFF');
    }

    const interval = setInterval(() => {
      const elapsed = (performance.now() - revealStart) / 1000;
      if (elapsed >= 1.5) {
        clearInterval(interval);
        if (window.ForgeBridge && window.ForgeBridge.onRevealFinished) {
          window.ForgeBridge.onRevealFinished();
        }
      }
    }, 100);
  }

  function setupEvents() {
    window.addEventListener('resize', onResize);
    window.addEventListener('touchstart', onTouchStart, { passive: true });
    window.addEventListener('touchmove', onTouchMove, { passive: true });
    window.addEventListener('deviceorientation', onOrientation, { passive: true });
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) {
        clock.stop();
      } else {
        clock.start();
        isDirty = true;
      }
    });
  }

  function onResize() {
    if (!renderer || !camera) return;
    const w = window.innerWidth;
    const h = window.innerHeight;
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
    renderer.setSize(w, h);
    if (composer) composer.setSize(w, h);
    isDirty = true;
  }

  function onTouchStart(e) {
    if (e.touches && e.touches[0]) {
      touchStartX = e.touches[0].clientX;
      touchStartY = e.touches[0].clientY;
    }
  }

  function onTouchMove(e) {
    if (e.touches && e.touches[0]) {
      const dx = (e.touches[0].clientX - touchStartX) * 0.002;
      const dy = (e.touches[0].clientY - touchStartY) * 0.002;
      targetCamRotY -= dx;
      targetCamRotX = Math.max(-0.4, Math.min(0.4, targetCamRotX - dy));
      touchStartX = e.touches[0].clientX;
      touchStartY = e.touches[0].clientY;
      isDirty = true;
    }
  }

  function onOrientation(e) {
    if (e.gamma !== null && e.beta !== null) {
      targetCamRotY = (e.gamma / 90) * 0.35;
      targetCamRotX = ((e.beta - 45) / 90) * 0.25;
      isDirty = true;
    }
  }

  function animate() {
    if (isDisposed) return;
    animId = requestAnimationFrame(animate);

    if (reduceMotion && !isDirty && !isRevealing) {
      return;
    }

    const delta = Math.min(clock.getDelta(), 0.1);
    const elapsed = clock.getElapsedTime();

    // 1. Camera Fly-in (first 2 seconds)
    const flyElapsed = (performance.now() - flyInStart) / 1000;
    if (flyElapsed < 2.0) {
      const t = flyElapsed / 2.0;
      const ease = 1 - Math.pow(1 - t, 3);
      camera.position.z = 55 - ease * (55 - 22);
      camera.position.y = 4 - ease * 1.5;
    } else {
      camera.position.z = 22;
      camera.position.y = 2.5;
    }

    // Camera kick on stage changes
    if (cameraKick > 0) {
      camera.position.y += Math.sin(cameraKick * Math.PI) * 0.45;
      cameraKick -= delta * 3.0;
    }

    // Touch & parallax interpolation
    camRotX += (targetCamRotX - camRotX) * 0.05;
    camRotY += (targetCamRotY - camRotY) * 0.05;
    camera.position.x = 22 * Math.sin(camRotY);
    camera.position.z = 22 * Math.cos(camRotY);
    camera.position.y = 2.5 + camRotX * 6;
    camera.lookAt(cameraTarget);

    // 2. Core rotation & pulsation
    if (coreGroup && !isFailed) {
      const speed = 0.5 + progress * 1.5;
      coreSphere.rotation.y += delta * speed * 0.8;
      coreWire.rotation.x -= delta * speed * 0.6;
      coreWire.rotation.y += delta * speed * 0.7;

      if (coreRings[0]) coreRings[0].rotation.x += delta * speed * 1.1;
      if (coreRings[1]) coreRings[1].rotation.y += delta * speed * 0.9;
      if (coreRings[2]) coreRings[2].rotation.z += delta * speed * 1.3;
    }

    // 3. Glyphs spiralling inward
    if (particles && particlePositions && particleInitialCoords && !isFailed) {
      const count = 1800;
      const speed = 1.0 + progress * 2.0;
      for (let i = 0; i < count; i++) {
        let r = particlePositions[i * 3] - delta * speed * 1.2;
        let th = particleInitialCoords[i * 3 + 1] + elapsed * (0.3 + (i % 5) * 0.1);
        if (r < 1.5) {
          r = 6 + Math.random() * 20;
        }
        particlePositions[i * 3] = r * Math.cos(th);
        particlePositions[i * 3 + 2] = r * Math.sin(th);
      }
      particles.geometry.attributes.position.needsUpdate = true;
    }

    // 4. Update shockwaves
    for (let i = shockwaves.length - 1; i >= 0; i--) {
      const sw = shockwaves[i];
      sw.scale += delta * 12;
      sw.life -= delta * 1.8;
      sw.mesh.scale.set(sw.scale, sw.scale, 1);
      sw.mesh.material.opacity = Math.max(0, sw.life * 0.85);
      if (sw.life <= 0) {
        scene.remove(sw.mesh);
        sw.mesh.geometry.dispose();
        sw.mesh.material.dispose();
        shockwaves.splice(i, 1);
      }
    }

    // 5. Update data beams
    for (let i = dataBeams.length - 1; i >= 0; i--) {
      const db = dataBeams[i];
      db.life -= delta * 2.0;
      db.beam.material.opacity = Math.max(0, db.life * 0.75);
      if (db.life <= 0) {
        scene.remove(db.beam);
        db.beam.geometry.dispose();
        db.beam.material.dispose();
        dataBeams.splice(i, 1);
      }
    }

    // Render pass
    if (composer) {
      composer.render();
    } else if (renderer && scene && camera) {
      renderer.render(scene, camera);
    }

    isDirty = false;
  }

  function dispose() {
    isDisposed = true;
    isRunning = false;
    if (animId) cancelAnimationFrame(animId);

    window.removeEventListener('resize', onResize);
    window.removeEventListener('touchstart', onTouchStart);
    window.removeEventListener('touchmove', onTouchMove);
    window.removeEventListener('deviceorientation', onOrientation);

    if (scene) {
      scene.traverse(obj => {
        if (obj.geometry) obj.geometry.dispose();
        if (obj.material) {
          if (Array.isArray(obj.material)) obj.material.forEach(m => m.dispose());
          else obj.material.dispose();
        }
      });
    }
    if (renderer) {
      renderer.dispose();
      renderer.forceContextLoss();
      if (renderer.domElement && renderer.domElement.parentNode) {
        renderer.domElement.parentNode.removeChild(renderer.domElement);
      }
      renderer = null;
    }
    composer = null;
  }

  // Expose API to window.Forge
  window.Forge = {
    init: init,
    setStage: setStage,
    setProgress: setProgress,
    addSection: addSection,
    log: log,
    fail: fail,
    reveal: reveal,
    dispose: dispose
  };
})();
