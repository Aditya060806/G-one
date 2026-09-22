import bpy,json
from pathlib import Path
OUT=Path(__file__).resolve().parent
scene=bpy.context.scene
assert scene.get('enhanced_revision')
if not scene.get('enhancement_clearance_fixed'):
    for o in bpy.data.objects:
        if o.name.startswith('C_EMG11 |') or o.name.startswith('LAND | C_EMG11 |'):
            o.location.x-=2;o.location.y+=1
    # Fuse the four overlapping frame members into one molded part.
    frames=[o for o in bpy.data.objects if o.name.startswith(('Carrier | longitudinal side rail','Carrier | cross member'))]
    bpy.ops.object.select_all(action='DESELECT')
    for o in frames:
        bpy.context.view_layer.objects.active=o;o.select_set(True)
        bpy.ops.object.convert(target='MESH');o.select_set(False)
    base=frames[0]
    for o in frames[1:]:
        bpy.context.view_layer.objects.active=base
        mod=base.modifiers.new('Fuse molded frame','BOOLEAN');mod.operation='UNION';mod.solver='EXACT';mod.object=o
        bpy.ops.object.modifier_apply(modifier=mod.name);bpy.data.objects.remove(o,do_unlink=True)
    base.name='Carrier | one-piece molded frame'
    assert len(base.data.polygons)>20
    scene['enhancement_clearance_fixed']=True
if not scene.get('presentation_contacts_fixed'):
    # Clear the protection IC's formed lead envelope, not just its plastic body.
    for o in bpy.data.objects:
        if o.name.startswith('C_PWR1 |'):o.location.x+=10;o.location.y+=2.9
    for o in bpy.data.objects:
        if o.name.startswith('LAND | '):
            src=bpy.data.objects.get(o.name[len('LAND | '):])
            if src:o.location.x=src.location.x;o.location.y=src.location.y
        if o.type=='FONT' and 'REV A' in o.data.body:o.data.body=o.data.body.replace('REV A','REV B')
    # Small presentation supports contact the underside; they belong to the studio,
    # not the hardware BOM. The optical package remains clear of the tabletop.
    studio=bpy.data.collections['12 | Studio, cameras and reference']
    mat=bpy.data.materials.get('Battery cushions | silicone')
    for x in [-19,19]:
        for y in [18,-24]:
            bpy.ops.mesh.primitive_cylinder_add(vertices=48,radius=1.75,depth=1.8,location=(x,y,-1.4))
            o=bpy.context.object;o.name='Studio | removable presentation support'
            for c in list(o.users_collection):c.objects.unlink(o)
            studio.objects.link(o);o.data.materials.append(mat)
    scene['presentation_contacts_fixed']=True
ground=bpy.data.objects['Studio | neutral backdrop']
ground.location.z=-2.4;ground.dimensions.x=10000;ground.dimensions.y=10000
for o in bpy.data.objects:
    if o.type=='CAMERA':o.data.clip_end=20000
pref=bpy.context.preferences.addons['cycles'].preferences
pref.compute_device_type='OPTIX';pref.get_devices()
for d in pref.devices:d.use=d.type=='OPTIX'
scene.cycles.device='GPU';scene.cycles.samples=64
scene.cycles.use_denoising=True
scene.render.resolution_x=1800;scene.render.resolution_y=1800
print('RENDER_DEVICES',[(d.name,d.type,d.use) for d in pref.devices],flush=True)
carrier=bpy.data.collections['14 | Removable battery carrier and fixings']
battery=bpy.data.collections['15 | Li-ion pouch cell, tabs and insulation']
wires=bpy.data.collections['16 | Battery harness and mated connector']
ground=bpy.data.objects['Studio | neutral backdrop']
views=[('CAM | isometric','assembled',True,False),('CAM | isometric','isometric',False,False),('CAM | top','top',False,False),('CAM | bottom','bottom',False,True),('CAM | assembled side profile','side',True,False),('CAM | skin sensors macro','sensors',False,True),('CAM | ESP32 and power detail','esp32-power',False,False),('CAM | USB and analog detail','detail',False,False)]
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'G-one-PCB.blend'))
for cam,name,assembled,under in views:
    for c in [carrier,battery,wires]:c.hide_render=not assembled
    scene.camera=bpy.data.objects[cam];ground.hide_render=under
    for o in bpy.data.objects:
        if o.name.startswith('Studio | removable presentation support'):o.hide_render=under
    scene.render.filepath=str(OUT/(name+'.png'));bpy.ops.render.render(write_still=True)
for c in [carrier,battery,wires]:c.hide_render=False
scene.camera=bpy.data.objects['CAM | isometric'];ground.hide_render=False
for o in bpy.data.objects:
    if o.name.startswith('Studio | removable presentation support'):o.hide_render=False;o.hide_set(True)
for o in bpy.data.collections['12 | Studio, cameras and reference'].objects:
    if o.type=='LIGHT' or o==ground:o.hide_set(True)
for sc in bpy.data.screens:
    for ar in sc.areas:
        if ar.type=='VIEW_3D':
            ar.spaces.active.region_3d.view_distance=110;ar.spaces.active.region_3d.view_location=(0,1,3);ar.spaces.active.region_3d.view_rotation=scene.camera.rotation_euler.to_quaternion()
bpy.data.texts.new('finish_enhanced.py').write(Path(__file__).read_text())
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'G-one-PCB.blend'))
exec(compile((OUT/'audit_enhanced.py').read_text(),str(OUT/'audit_enhanced.py'),'exec'))
print('FINAL_RENDERS_COMPLETE',flush=True)
