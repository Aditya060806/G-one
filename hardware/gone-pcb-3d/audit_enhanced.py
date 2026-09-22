import bpy,json,itertools
from mathutils.bvhtree import BVHTree
from mathutils.geometry import interpolate_bezier
from pathlib import Path
from mathutils import Vector
out=Path(__file__).resolve().parent
def bounds(o):
    pts=[o.matrix_world@Vector(v) for v in o.bound_box]
    return [min(v[i] for v in pts) for i in range(3)],[max(v[i] for v in pts) for i in range(3)]
def overlaps(a,b):
    al,ah=bounds(a);bl,bh=bounds(b)
    return [min(ah[i],bh[i])-max(al[i],bl[i]) for i in range(3)]
# Exterior package bodies only. Leads, solder, press fits and multi-piece housings
# intentionally touch/overlap and are not independent mechanical packages.
parts=[]
for o in bpy.data.objects:
    if o.type!='MESH':continue
    if o.get('part') or o.name.endswith(' | body') or o.name=='L1 | shielded ferrite inductor':parts.append(o)
clashes=[]
for a,b in itertools.combinations(parts,2):
    d=overlaps(a,b)
    if min(d)>.03:clashes.append({'a':a.name,'b':b.name,'penetration_mm':d})
missing=[o.name for o in bpy.data.objects if o.type=='MESH' and (not o.data.vertices or not o.data.materials)]
wire_checks=[]
deps=bpy.context.evaluated_depsgraph_get()
obstacles=[o for o in bpy.data.objects if o.type=='MESH' and (o in parts or o.name.startswith(('Carrier | one-piece','M2 | threaded','M2 | captive'))) and not o.name.startswith('BAT1')]
bvhs=[(o,BVHTree.FromObject(o,deps)) for o in obstacles]
for wire in [o for o in bpy.data.objects if o.type=='CURVE' and 'battery harness' in o.name]:
    bp=wire.data.splines[0].bezier_points;points=[]
    for a,b in zip(bp,bp[1:]):points.extend(interpolate_bezier(a.co,a.handle_right,b.handle_left,b.co,20))
    hits=set()
    # Endpoint segments intentionally enter battery tabs and connector housing.
    for p in points[10:-15]:
        world=wire.matrix_world@p
        for ob,bvh in bvhs:
            q=ob.matrix_world.inverted()@world
            hit=bvh.find_nearest(q)
            if hit[0] is not None and hit[3]<wire.data.bevel_depth*.95:hits.add(ob.name)
    wire_checks.append({'wire':wire.name,'sampled_points':len(points),'surface_clearance_hits':sorted(hits)})
report={'package_body_intersections':clashes,'empty_or_unmaterialed_meshes':missing,'checked_package_bodies':len(parts),'wire_sample_checks':wire_checks,'board_mm':list(bpy.data.objects['PCB | 44 x 64 x 1.00 mm'].dimensions),'object_count':len(bpy.data.objects),'scope':'Package AABB and sampled wire-to-package/carrier surface checks. Connector/tab insertion excluded. Does not certify all surfaces or electrical routing.'}
(out/'mechanical-audit.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report))
