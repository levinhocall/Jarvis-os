// Host verification only: desktop Chromium/SwiftShader is not an Android GPU benchmark.
const {chromium}=require('playwright');
const http=require('node:http'), fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const assets=path.resolve(__dirname,'../app/src/main/assets');
const server=http.createServer((req,res)=>{const name=decodeURIComponent(req.url.split('?')[0]).replace(/^\//,'');const file=path.resolve(assets,name);if(!file.startsWith(assets+path.sep)||!fs.existsSync(file)){res.writeHead(404).end();return;}res.setHeader('Content-Type',file.endsWith('.js')?'text/javascript':'text/html');res.end(fs.readFileSync(file));});
(async()=>{await new Promise(r=>server.listen(0,'127.0.0.1',r));let browser;
try{browser=await chromium.launch({executablePath:'/usr/bin/chromium',headless:true,args:['--no-sandbox','--use-angle=swiftshader','--enable-unsafe-swiftshader']});const page=await browser.newPage({viewport:{width:460,height:600}});const errors=[];page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error'&&m.text().includes('THREE'))errors.push(m.text());});await page.addInitScript(()=>{window.failureSignals=0;window.OrbRendererBridge={onFailure(){window.failureSignals++;}};});
const base=`http://127.0.0.1:${server.address().port}`;
await page.goto(base+'/orb.html?mode=fluid_plasma');await page.waitForFunction(()=>window.JarvisOrb?.inspect().frames>3);
assert.equal(await page.evaluate(()=>JarvisOrb.inspect().programs),1);
const sibling=await browser.newPage();await sibling.goto(base+'/orb.html?mode=fluid_plasma');await sibling.waitForFunction(()=>window.JarvisOrb?.inspect().frames>3);
await page.evaluate(()=>JarvisOrb.setVisible(false));const siblingBefore=await sibling.evaluate(()=>JarvisOrb.inspect().frames);await sibling.waitForFunction(n=>JarvisOrb.inspect().frames>n,siblingBefore);await page.evaluate(()=>JarvisOrb.setVisible(true));await sibling.close();await page.bringToFront();await page.waitForFunction(()=>!document.hidden);
for(const state of ['IDLE','LISTENING','USER_SPEAKING','PROCESSING','AI_SPEAKING','INTERRUPTED','ERROR','DISCONNECTED']){await page.evaluate(state=>JarvisOrb.setPresentation({state,sessionId:1,generation:1,mic:.6,output:.7}),state);assert.equal(await page.evaluate(()=>JarvisOrb.inspect().state),state);}
await page.evaluate(()=>JarvisOrb.setPresentation({state:'AI_SPEAKING',sessionId:1,generation:2,output:.7}));await page.waitForFunction(()=>JarvisOrb.inspect().output>.1).catch(async error=>{console.error('Playback probe',await page.evaluate(()=>({hidden:document.hidden,...JarvisOrb.inspect()})));throw error;});
await page.evaluate(()=>JarvisOrb.setPresentation({state:'LISTENING',sessionId:1,generation:3,mic:.2}));assert.equal(await page.evaluate(()=>JarvisOrb.inspect().output),0);
await page.evaluate(()=>{JarvisOrb.configure({quality:'saver'});JarvisOrb.setVisible(false);});const count=await page.evaluate(()=>JarvisOrb.inspect().frames);await page.waitForTimeout(150);assert.equal(await page.evaluate(()=>JarvisOrb.inspect().frames),count);
await page.evaluate(()=>JarvisOrb.setVisible(true));await page.waitForFunction(n=>JarvisOrb.inspect().frames>n,count);
await page.evaluate(()=>JarvisOrb.setReducedMotion(true));await page.waitForTimeout(80);const staticFrames=await page.evaluate(()=>JarvisOrb.inspect().frames);await page.waitForTimeout(150);assert.equal(await page.evaluate(()=>JarvisOrb.inspect().frames),staticFrames);
await page.evaluate(async()=>{for(let i=0;i<10;i++){JarvisOrb.setPresentation({state:'LISTENING',sessionId:1,generation:3,mic:i/10,output:0});await new Promise(r=>setTimeout(r,30));}});
assert.equal(await page.evaluate(()=>JarvisOrb.inspect().frames),staticFrames);
await page.evaluate(()=>{JarvisOrb.setReducedMotion(false);JarvisOrb.setPresentation({state:'LISTENING',sessionId:1,generation:3,mic:.3});});await page.waitForTimeout(300);
if(process.env.PLASMA_SCREENSHOT){await page.evaluate(()=>document.documentElement.style.setProperty('background','#030711','important'));await page.screenshot({path:process.env.PLASMA_SCREENSHOT});}
await page.evaluate(()=>document.querySelector('canvas').dispatchEvent(new Event('webglcontextlost',{cancelable:true})));assert.equal(await page.evaluate(()=>failureSignals),1);assert.equal(await page.evaluate(()=>JarvisOrb.inspect().failed),true);
await page.evaluate(()=>{JarvisOrb.dispose();JarvisOrb.dispose();});assert.equal(await page.evaluate(()=>JarvisOrb.inspect().disposed),true);
assert.equal(await page.evaluate(()=>JarvisOrb.inspect().geometries),0);
assert.equal(await page.evaluate(()=>JarvisOrb.inspect().programs),0);
assert.deepEqual(errors,[]);
await page.goto(base+'/orb.html');await page.waitForFunction(()=>window.JarvisOrb?.inspect().opening!==undefined);assert.equal(await page.evaluate(()=>JarvisOrb.inspect().disposed),false);
// Force constructor failure before loading the plasma scene, exercising native-fallback signal.
await page.route('**/plasma-orb.js',async route=>{const body=fs.readFileSync(path.join(assets,'plasma-orb.js'),'utf8');await route.fulfill({contentType:'text/javascript',body:'THREE.WebGLRenderer=function(){throw Error("test unavailable")};\n'+body});});
await page.goto(base+'/orb.html?mode=fluid_plasma');assert.equal(await page.evaluate(()=>failureSignals),1);
console.log('PASS WebGL shader compile/render, 8 states, interruption, hidden/reduced-motion stop, context loss, disposal, initialization failure, legacy renderer');
}finally{if(browser)await browser.close();server.close();}})().catch(e=>{console.error(e);process.exitCode=1;});
