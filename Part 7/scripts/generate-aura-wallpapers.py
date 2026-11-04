"""Original silent cyberpunk loops, not third-party anime footage."""
from pathlib import Path
import subprocess
from concurrent.futures import ThreadPoolExecutor
root=Path(__file__).resolve().parents[1]/'app/src/main/res/raw'
root.mkdir(exist_ok=True)
items=[('aurora','071426','36ccbd'),('neon_drift','110c28','d245e8'),('ember','1d0a12','e77c36'),('skyline','060f21','356aad'),('frost_muse','061c2e','98d6ed'),('crimson_tide','17081a','b92b58'),('violet_gaze','120e32','9364e0'),('fallen_angel','121322','b1a3d7'),('inferno','210a05','ff6627'),('red_dusk','170d23','c45058'),('cobalt_dreamer','060d27','295dce'),('midnight_lace','07091a','625382'),('sakura_blush','230e27','d86f9e'),('shadow_call','080d18','245870'),('golden_hour','25140e','d5a052'),('frozen_tear','071722','6abecb')]
def render(entry):
 i,(name,bg,accent)=entry
 source=f'gradients=s=360x640:r=24:c0=0x{bg}:c1=0x{accent}:n=2:type={i%4}:speed=0.02:seed={i+7}:d=4'
 filters=['drawgrid=w=60:h=80:t=1:c=white@0.045']
 for b in range(9):
  h=45+((b*73+i*31)%150)
  filters.append(f'drawbox=x={b*43-12}:y={640-h}:w=35:h={h}:color=0x040712@0.85:t=fill')
  filters.append(f'drawbox=x={b*43+2}:y={650-h}:w=3:h={max(12,h-30)}:color=0x{accent}@0.65:t=fill')
 out=root/f'aura_{name}.mp4'
 subprocess.run(['ffmpeg','-nostdin','-y','-loglevel','error','-filter_threads','1','-f','lavfi','-i',source,'-vf',','.join(filters),'-t','4','-an','-c:v','libx264','-threads','1','-preset','fast','-crf','27','-pix_fmt','yuv420p','-movflags','+faststart',str(out)],check=True)
 return out.name,out.stat().st_size
with ThreadPoolExecutor(max_workers=2) as pool:
 for name,size in pool.map(render,enumerate(items)): print(name,size)
