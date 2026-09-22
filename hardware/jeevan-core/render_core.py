import bpy
from pathlib import Path
OUT=Path(__file__).resolve().parent
bpy.ops.wm.open_mainfile(filepath=str(OUT/'Jeevan-Core.blend'))
S=bpy.context.scene
pref=bpy.context.preferences.addons['cycles'].preferences
pref.compute_device_type='OPTIX';pref.get_devices()
for d in pref.devices:d.use=d.type=='OPTIX'
S.cycles.device='GPU'
for name,frame,file in [('Camera | assembled',1,'core-assembled.png'),('Camera | exploded',121,'core-exploded.png'),('Camera | skin interface',1,'core-skin.png')]:
 S.camera=bpy.data.objects[name];S.frame_set(frame)
 if 'skin' in name:
  bpy.data.objects['Studio floor'].hide_render=True
  for o in bpy.data.objects['11_Liner'].children:o.hide_render=True
 S.render.filepath=str(OUT/file);bpy.ops.render.render(write_still=True)
 bpy.data.objects['Studio floor'].hide_render=False
 for o in bpy.data.objects['11_Liner'].children:o.hide_render=False
S.frame_set(1);S.camera=bpy.data.objects['Camera | assembled'];bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'Jeevan-Core.blend'))
print('REFINED_CORE_SAVED',flush=True)

