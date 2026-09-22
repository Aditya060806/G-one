import bpy,json,math
from pathlib import Path
from mathutils import Vector
from mathutils.bvhtree import BVHTree
from mathutils.geometry import interpolate_bezier
OUT=Path(__file__).resolve().parent
scene=bpy.data.scenes['01 | Worn Phase-1 prototype']
bpy.context.window.scene=scene
deps=bpy.context.evaluated_depsgraph_get()
body=bpy.data.objects['Body | continuous closed-fist sculpt']
tree=BVHTree.FromObject(body,deps)
inside=[]
for o in scene.objects:
    if o.type!='CURVE' or not o.name.startswith('Jumper |'):continue
    for sp in o.data.splines:
        ps=sp.bezier_points
        for a,b in zip(ps,ps[1:]):
            for p in interpolate_bezier(a.co,a.handle_right,b.handle_left,b.co,16):
                p=body.matrix_world.inverted() @ (o.matrix_world @ p)
                q,n,index,d=tree.find_nearest(p)
                if q is not None and (p-q).dot(n)<-.5:
                    inside.append({'wire':o.name,'penetration_mm':round(d,3),'point':list(p),'segment_start':list(a.co),'segment_end':list(b.co)});break
            else:continue
            break
bad=[o.name for o in scene.objects if o.type=='MESH' and (not len(o.data.polygons) or not len(o.data.materials))]
report={'worn_scene_objects':len(scene.objects),'empty_or_unmaterialed_meshes':bad,'wire_samples_inside_body':inside,
 'packed_references':sum(bool(i.packed_file) for i in bpy.data.images),
 'scope':'Body penetration check samples main jumper centerlines only; not a full solid interference or electrical validation.'}
(OUT/'geometry-audit.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report),flush=True)
