"""Run against the saved .blend; checks assembly presence, scale and saved renders."""
import bpy, json
from pathlib import Path
out=Path(__file__).resolve().parent
board=bpy.data.objects['PCB | 44 x 64 x 1.00 mm']
assert len(board.data.vertices)>100
assert all(abs(a-b)<.01 for a,b in zip(board.dimensions,(44,64,1)))
required=['ESP32-S3-WROOM-1-N16R8','MAX30102EFD+','MPU6050','DS18B20','INA333','BQ24074','TPS63070']
present={o.get('part') for o in bpy.data.objects if o.get('part')}
assert all(p in present for p in required),(required,present)
empty=[o.name for o in bpy.data.objects if o.type=='MESH' and not o.data.vertices]
assert not empty,empty
missing_mats=[o.name for o in bpy.data.objects if o.type=='MESH' and not o.data.materials]
assert not missing_mats,missing_mats
assert len([o for o in bpy.data.objects if o.type=='CAMERA'])==4
ref=bpy.data.images.get('layout-reference.png')
if ref is None:ref=bpy.data.images.load(str(out/'layout-reference.png'))
ref.use_fake_user=True
ref.pack()
assert ref.packed_file
source=bpy.data.texts.get('build_pcb.py') or bpy.data.texts.new('build_pcb.py')
source.clear();source.write((out/'build_pcb.py').read_text(encoding='utf-8'));source.use_fake_user=True
for name in ['isometric','top','bottom','detail']:
    assert (out/(name+'.png')).stat().st_size>10000
# Keep the editable assembly unobstructed when first opened. These are viewport-only
# hides; the complete studio remains enabled for F12 rendering.
for o in bpy.data.objects:
    if o.type=='LIGHT' or o.name=='Studio | neutral backdrop':o.hide_set(True)
bpy.ops.wm.save_as_mainfile(filepath=str(out/'G-one-PCB.blend'))
report={'result':'PASS','board_mm':list(board.dimensions),'board_vertices':len(board.data.vertices),'objects':len(bpy.data.objects),'meshes':sum(o.type=='MESH' for o in bpy.data.objects),'required_parts':required,'empty_meshes':empty,'meshes_without_materials':missing_mats,'packed_reference':True,'renders':['isometric.png','top.png','bottom.png','detail.png'],'validation_scope':'Presence, dimensions, geometry and file checks only; no electrical or fabrication validation.'}
(out/'validation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report))
