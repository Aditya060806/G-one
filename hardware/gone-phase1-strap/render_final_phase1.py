import bpy
from pathlib import Path
OUT=Path(__file__).resolve().parent
S=bpy.data.scenes['01 | Worn Phase-1 prototype']
bpy.context.window.scene=S
pref=bpy.context.preferences.addons['cycles'].preferences
pref.compute_device_type='OPTIX';pref.get_devices()
for d in pref.devices:d.use=d.type=='OPTIX'
for camera,filename in [('CAM | wrist and closed fist','phase1-worn.png'),('CAM | controller and prototype wiring','phase1-hardware.png')]:
    S.camera=bpy.data.objects[camera];S.render.filepath=str(OUT/filename)
    bpy.ops.render.render(write_still=True,scene=S.name)
# Reveal inward-facing sensor surfaces through the open wrist aperture.
from mathutils import Vector
cam=bpy.data.objects['CAM | inner sensor arrangement']
cam.location=(-190,-25,68)
cam.rotation_euler=(Vector((0,0,40))-cam.location).to_track_quat('-Z','Y').to_euler()
cam.data.ortho_scale=115
S.camera=cam
hidden=[bpy.data.collections['01 | Anatomy - closed fist and forearm'],bpy.data.collections['02 | Woven strap - stitching and fastening'],bpy.data.objects['Studio | warm neutral surface']]
for o in hidden:o.hide_render=True
S.render.filepath=str(OUT/'phase1-inside.png');bpy.ops.render.render(write_still=True,scene=S.name)
for o in hidden:o.hide_render=False
flat=bpy.data.scenes['02 | Opened strap - sensor inspection']
flat.render.filepath=str(OUT/'phase1-opened.png');bpy.ops.render.render(write_still=True,scene=flat.name)
S.camera=bpy.data.objects['CAM | wrist and closed fist']
S.render.filepath=str(OUT/'phase1-worn.png')
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'G-one-Phase-1-Strap.blend'))
print('FINAL_RENDERS_SAVED',flush=True)
