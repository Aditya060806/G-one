import bpy, json, struct, hashlib, shutil
from pathlib import Path
from mathutils import Matrix,Vector
OUT=Path(__file__).resolve().parent
source=OUT/'Jeevan-Core.blend';before=hashlib.sha256(source.read_bytes()).hexdigest()
bpy.ops.wm.open_mainfile(filepath=str(source));S=bpy.context.scene;S.frame_set(1)
layers=json.loads((OUT/'layers.json').read_text())
deps=bpy.context.evaluated_depsgraph_get();snapshots=[]
for layer in layers:
 root=bpy.data.objects[layer['id']]
 for o in root.children:
  if o.type not in {'MESH','CURVE','FONT'}:continue
  me=bpy.data.meshes.new_from_object(o.evaluated_get(deps),preserve_all_data_layers=True,depsgraph=deps)
  if not me.polygons:continue
  me.transform(Matrix.Scale(.001,4)@o.matrix_world);me.update()
  snapshots.append((o.name,me,layer['id']))
for o in list(bpy.data.objects):bpy.data.objects.remove(o,do_unlink=True)
for c in list(bpy.data.collections):bpy.data.collections.remove(c)
S.unit_settings.scale_length=1
roots={}
for layer in layers:
 r=bpy.data.objects.new(layer['id'],None);S.collection.objects.link(r)
 for k,v in layer.items():r[k]=v
 roots[layer['id']]=r
for name,mesh,root in snapshots:
 o=bpy.data.objects.new(name,mesh);S.collection.objects.link(o);o.parent=roots[root]
# Merge by mechanical layer, retaining material slots and named animation parents.
for key,r in roots.items():
 bpy.ops.object.select_all(action='DESELECT')
 members=list(r.children)
 for o in members:o.select_set(True)
 bpy.context.view_layer.objects.active=members[0];bpy.ops.object.join()
 bpy.context.object.name=key+'_Geometry';bpy.context.object.data.name=key+'_Mesh'
 r.location.z=0;r.keyframe_insert(data_path='location',frame=1)
 r.location.z=r['explodeDistanceM'];r.keyframe_insert(data_path='location',frame=121)
S.frame_set(1)
for o in S.objects:o.select_set(True)
target=OUT/'Jeevan-Core.glb'
options=dict(filepath=str(target),export_format='GLB',use_selection=True,export_yup=True,export_materials='EXPORT',export_extras=True,export_animations=True,export_frame_range=True,export_cameras=False,export_lights=False)
rna=bpy.ops.export_scene.gltf.get_rna_type()
if 'export_animation_mode' in rna.properties:
 values={x.identifier for x in rna.properties['export_animation_mode'].enum_items}
 if 'SCENE' in values:options['export_animation_mode']='SCENE'
bpy.ops.export_scene.gltf(**options)
blob=target.read_bytes();magic,version,size=struct.unpack_from('<4sII',blob);assert magic==b'glTF' and version==2 and size==len(blob)
n=struct.unpack_from('<I',blob,12)[0];doc=json.loads(blob[20:20+n])
# Standardize the exporter-specific per-object clips into one portable clip.
samplers=[];channels=[]
for animation in doc.get('animations',[]):
 offset=len(samplers);samplers.extend(animation['samplers'])
 for ch in animation['channels']:
  ch['sampler']+=offset;channels.append(ch)
doc['animations']=[{'name':'Explode','samplers':samplers,'channels':channels}]
json_bytes=json.dumps(doc,separators=(',',':')).encode();json_bytes+=b' '*((-len(json_bytes))%4)
tail=blob[20+n:]
blob=struct.pack('<4sII',b'glTF',2,20+len(json_bytes)+len(tail))+struct.pack('<I4s',len(json_bytes),b'JSON')+json_bytes+tail
target.write_bytes(blob)
assert all(any(n.get('name')==l['id'] for n in doc['nodes']) for l in layers)
assert not doc.get('cameras') and not any('uri' in b for b in doc['buffers'])
assert doc.get('animations'), 'Missing explode animation'
report={'bytes':len(blob),'meshes':len(doc['meshes']),'triangles':sum(doc['accessors'][p['indices']]['count']//3 for m in doc['meshes'] for p in m['primitives']),
 'layers':len(layers),'animations':len(doc['animations']),'units':'metres','up_axis':'Y','self_contained':True,'source_sha256':before}
bpy.ops.wm.read_factory_settings(use_empty=True);bpy.ops.import_scene.gltf(filepath=str(target))
assert all(bpy.data.objects.get(l['id']) for l in layers)
meshes=[o for o in bpy.context.scene.objects if o.type=='MESH'];assert len(meshes)==11
pts=[o.matrix_world@Vector(p) for o in meshes for p in o.bound_box]
report['assembled_bounds_m']=[[min(p[i] for p in pts) for i in range(3)],[max(p[i] for p in pts) for i in range(3)]]
assert hashlib.sha256(source.read_bytes()).hexdigest()==before
report['source_unchanged']=True;report['roundtrip_layers_verified']=True
(OUT/'export-report.json').write_text(json.dumps(report,indent=2))
shutil.copy2(target,OUT/'web/public/Jeevan-Core.glb')
shutil.copy2(OUT/'layers.json',OUT/'web/src/layers.json')
print('CORE_EXPORT_VERIFIED',json.dumps(report),flush=True)
