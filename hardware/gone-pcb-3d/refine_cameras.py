import bpy,math
from mathutils import Vector
from pathlib import Path
out=Path(__file__).resolve().parent
s=bpy.context.scene
side=bpy.data.objects['CAM | assembled side profile']
sensor=bpy.data.objects['CAM | skin sensors macro']
if not s.get('camera_presentation_fixed'):
    # Move the orthographic camera backward along its existing viewing ray.
    # This preserves composition while placing its entire image plane above the floor.
    target=Vector((0,-2,3));side.location=target+(side.location-target)*4
    sensor.rotation_euler.rotate_axis('Z',math.pi)
    s['camera_presentation_fixed']=True
pref=bpy.context.preferences.addons['cycles'].preferences;pref.compute_device_type='OPTIX';pref.get_devices()
for d in pref.devices:d.use=d.type=='OPTIX'
s.cycles.device='GPU'
ground=bpy.data.objects['Studio | neutral backdrop']
cols=[bpy.data.collections[n] for n in ['14 | Removable battery carrier and fixings','15 | Li-ion pouch cell, tabs and insulation','16 | Battery harness and mated connector']]
for cam,name,under in [(side,'side',False),(sensor,'sensors',True)]:
    s.camera=cam;ground.hide_render=under
    for c in cols:c.hide_render=under
    for o in bpy.data.objects:
        if o.name.startswith('Studio | removable presentation support'):o.hide_render=under
    s.render.filepath=str(out/(name+'.png'));bpy.ops.render.render(write_still=True)
for c in cols:c.hide_render=False
ground.hide_render=False
for o in bpy.data.objects:
    if o.name.startswith('Studio | removable presentation support'):o.hide_render=False
s.camera=bpy.data.objects['CAM | isometric']
bpy.ops.wm.save_as_mainfile(filepath=str(out/'G-one-PCB.blend'))
print('CAMERA_REFINEMENT_COMPLETE')
