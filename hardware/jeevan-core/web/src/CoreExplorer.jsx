'use client';
import React, {useEffect, useRef, useState} from 'react';
import * as THREE from 'three';
import {GLTFLoader} from 'three/addons/loaders/GLTFLoader.js';
import {OrbitControls} from 'three/addons/controls/OrbitControls.js';
import {RoomEnvironment} from 'three/addons/environments/RoomEnvironment.js';
import layers from './layers.json';

/** Portable React / Next.js client component. Import style.css once in your app. */
export default function CoreExplorer({modelUrl='/Jeevan-Core.glb'}) {
 const section=useRef(null), mount=useRef(null), progress=useRef(0), slider=useRef(null), viewer=useRef(null);
 const [status,setStatus]=useState('Loading the assembly…'), [active,setActive]=useState(-1), [mode,setMode]=useState('scroll');
 const modeRef=useRef('scroll'), activeRef=useRef(-1);
 const chooseMode=(value)=>{modeRef.current=value;setMode(value);};
 const jump=(index)=>{
  const p=index<0?0:index===10?1:(index+.5)/11;
  progress.current=p;chooseMode('manual');
  if(slider.current)slider.current.value=String(p*100);
 };
 useEffect(()=>{
  const host=mount.current;let disposed=false, raf=0, root=null, environment=null;
  let renderer;
  try {renderer=new THREE.WebGLRenderer({antialias:true,alpha:true,powerPreference:'high-performance'});}
  catch {setStatus('3D is unavailable on this browser. You can still read every layer below.');return;}
  renderer.setPixelRatio(Math.min(window.devicePixelRatio,1.75));
  renderer.outputColorSpace=THREE.SRGBColorSpace;renderer.toneMapping=THREE.ACESFilmicToneMapping;renderer.toneMappingExposure=.95;
  renderer.domElement.setAttribute('aria-label','Interactive G-one Core assembly. Drag to rotate. Use the layer slider to separate components.');
  renderer.domElement.setAttribute('role','img');host.appendChild(renderer.domElement);
  const scene=new THREE.Scene(),camera=new THREE.PerspectiveCamera(34,1,.001,10);
  camera.position.set(.075,.10,.10);
  const controls=new OrbitControls(camera,renderer.domElement);controls.enableDamping=true;controls.enablePan=false;controls.enableZoom=false;
  controls.target.set(0,.014,0);
  // Vertical page scrolling remains available on touch screens.
  renderer.domElement.style.touchAction='pan-y';controls.touches.ONE=THREE.TOUCH.ROTATE;controls.touches.TWO=THREE.TOUCH.DOLLY_ROTATE;
  const pmrem=new THREE.PMREMGenerator(renderer),room=new RoomEnvironment();
  environment=pmrem.fromScene(room,.04);scene.environment=environment.texture;scene.environmentIntensity=.65;room.dispose();pmrem.dispose();
  scene.add(new THREE.HemisphereLight(0xd3edeb,0x142023,.45));
  const light=new THREE.DirectionalLight(0xffffff,1);light.position.set(.05,.15,.10);scene.add(light);
  const modelLayers=[];
  const disposeRoot=(r)=>{r.traverse(o=>{if(o.isMesh){o.geometry.dispose();for(const m of [].concat(o.material)){for(const v of Object.values(m))if(v?.isTexture)v.dispose();m.dispose();}}});};
  new GLTFLoader().load(modelUrl,gltf=>{
   if(disposed){disposeRoot(gltf.scene);return;}
   root=gltf.scene;scene.add(root);
   for(const layer of layers){
    const object=root.getObjectByName(layer.id);
    if(!object){setStatus(`Model is missing layer: ${layer.title}`);return;}
    modelLayers.push({object,base:object.position.clone(),...layer});
   }
   setStatus('Ready');host.dataset.loaded='true';host.dataset.layers=String(modelLayers.length);
  },undefined,()=>{if(!disposed)setStatus('The model could not load. Check the GLB URL and try refreshing.');});
  const resize=()=>{const {width,height}=host.getBoundingClientRect();renderer.setSize(width,height);camera.aspect=width/Math.max(height,1);camera.updateProjectionMatrix();};
  const observer=new ResizeObserver(resize);observer.observe(host);resize();
  const reduced=window.matchMedia('(prefers-reduced-motion: reduce)');
  if(reduced.matches){modeRef.current='manual';setMode('manual');}
  const scroll=()=>{
   if(modeRef.current!=='scroll')return;
   const rect=section.current.getBoundingClientRect(),range=section.current.offsetHeight-window.innerHeight;
   progress.current=THREE.MathUtils.clamp(-rect.top/Math.max(1,range),0,1);
   if(slider.current)slider.current.value=String(progress.current*100);
  };
  window.addEventListener('scroll',scroll,{passive:true});scroll();
  let current=0;
  const draw=()=>{
   raf=requestAnimationFrame(draw);current=reduced.matches?progress.current:THREE.MathUtils.lerp(current,progress.current,.10);
   const index=current<.018?-1:Math.min(10,Math.floor(current*11));
   if(index!==activeRef.current){activeRef.current=index;setActive(index);}
   for(const layer of modelLayers){
    layer.object.position.copy(layer.base);
    layer.object.position.y+=layer.explodeDistanceM*current;
   }
   host.dataset.progress=current.toFixed(3);
   const targetY=.006+current*.036,dist=.11+current*.075;
   camera.position.y+=targetY-controls.target.y;
   controls.target.y=targetY;
   const direction=camera.position.clone().sub(controls.target).normalize();
   camera.position.copy(controls.target).addScaledVector(direction,dist);
   controls.update();renderer.render(scene,camera);
  };
  draw();viewer.current={camera,controls};
  return()=>{disposed=true;cancelAnimationFrame(raf);window.removeEventListener('scroll',scroll);observer.disconnect();controls.dispose();if(root)disposeRoot(root);environment?.dispose();renderer.dispose();renderer.domElement.remove();viewer.current=null;};
 },[modelUrl]);
 const layer=active<0?null:layers[active];
 return <main className="core-explorer">
  <header className="core-header"><a href="#core" className="brand"><span className="brand-mark">G</span><span>Jeevan<span className="brand-sub">G-one Core</span></span></a><span className="edition">Architecture study / 04</span><a className="download" href={modelUrl} download>Download model ↗</a></header>
  <section className="scroll-section" id="core" ref={section}>
   <div className="core-stage">
    <div className="story"><p className="eyebrow">ONE PATCH. MULTIPLE PERSPECTIVES.</p><h1>Closer to you.<br/><span>Layer by layer.</span></h1><p className="intro">Explore the physical architecture of G-one Core — from its protective shell to the interface that meets your skin.</p><div className="chapter" aria-live="polite"><span className="chapter-number">{active<0?'00':String(active+1).padStart(2,'0')} / 11</span><h2>{layer?.title||'One coherent assembly'}</h2><p>{layer?.description||'A reusable electronics core above a replaceable skin-contact interface. Scroll to reveal what is inside.'}</p></div><p className="fit-note">Concept geometry · dimensions and component fit remain provisional</p></div>
    <div className="visual"><div className="canvas-mount" ref={mount}/>{status!=='Ready'&&<div className="load-status" role="status">{status}</div>}<div className="model-caption"><span>G-ONE CORE</span><span>Drag to rotate · Scroll to explore</span></div></div>
    <div className="controls"><div className="mode-buttons"><button className={mode==='scroll'?'selected':''} onClick={()=>chooseMode('scroll')}>Follow scroll</button><button onClick={()=>jump(-1)}>Assembled</button><button onClick={()=>jump(10)}>Exploded</button></div><label className="range-label">Layer separation<input ref={slider} type="range" min="0" max="100" defaultValue="0" onChange={e=>{chooseMode('manual');progress.current=Number(e.target.value)/100;}}/></label><span className="control-hint">{mode==='scroll'?'Scroll the page to open the device':'Manual control · select Follow scroll to resume'}</span></div>
   </div>
  </section>
  <section className="layer-index"><div><p className="eyebrow">THE COMPLETE STACK</p><h2>Eleven parts.<br/>One connected system.</h2><p>Choose a layer to inspect its place in the assembly.</p></div><ol>{layers.map((l,i)=><li key={l.id}><button onClick={()=>{jump(i);section.current.scrollIntoView({behavior:'instant'});}}><span>{String(i+1).padStart(2,'0')}</span><strong>{l.title}</strong><span>↗</span></button><p>{l.description}</p></li>)}</ol></section>
  <footer><span>Jeevan / G-one Core</span><span>Sense · Log · Move · Belong</span><p>Engineering visualization. This model does not establish clinical performance, battery runtime, waterproofing, or manufacturing readiness.</p></footer>
 </main>;
}
