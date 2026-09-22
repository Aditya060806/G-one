import bpy,json,struct
from pathlib import Path
out=Path(__file__).resolve().parent
scene=bpy.context.scene
assert scene.get('enhanced_revision') and scene.get('presentation_contacts_fixed')
for c in list(bpy.data.collections):
    if not c.objects and not c.children:bpy.data.collections.remove(c)
for stem in ['enhance_pcb.py','finish_enhanced.py','audit_enhanced.py']:
    t=bpy.data.texts.get(stem) or bpy.data.texts.new(stem)
    t.clear();t.write((out/stem).read_text(encoding='utf-8'));t.use_fake_user=True
    for other in list(bpy.data.texts):
        if other.name.startswith(stem+'.'):bpy.data.texts.remove(other)
for c in ['14 | Removable battery carrier and fixings','15 | Li-ion pouch cell, tabs and insulation','16 | Battery harness and mated connector']:
    assert not bpy.data.collections[c].hide_render
assert bpy.data.images['layout-reference.png'].packed_file
audit=json.loads((out/'mechanical-audit.json').read_text())
assert not audit['package_body_intersections']
assert not audit['empty_or_unmaterialed_meshes']
assert not any(w['surface_clearance_hits'] for w in audit['wire_sample_checks'])
renders=[]
for name in ['assembled','isometric','top','bottom','side','sensors','esp32-power','detail']:
    f=out/(name+'.png');data=f.read_bytes()[:24]
    assert data[:8]==b'\x89PNG\r\n\x1a\n'
    dims=struct.unpack('>II',data[16:24]);assert dims==(1800,1800)
    renders.append({'file':f.name,'pixels':dims})
summary=json.loads((out/'enhancement-validation.json').read_text())
summary.update({'final_object_count':len(bpy.data.objects),'final_mesh_count':sum(o.type=='MESH' for o in bpy.data.objects),'camera_count':sum(o.type=='CAMERA' for o in bpy.data.objects),'renders_verified':renders,'body_and_sampled_wire_clearance':'PASS','original_backup':'G-one-PCB-before-enhancement.blend'})
(out/'enhancement-validation.json').write_text(json.dumps(summary,indent=2))
bpy.ops.wm.save_as_mainfile(filepath=str(out/'G-one-PCB.blend'))
print('REV_B_FINAL_CHECKS_PASS',json.dumps(summary))
