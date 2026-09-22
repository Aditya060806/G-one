import bpy, runpy
from pathlib import Path
OUT=Path(__file__).resolve().parent
S=bpy.data.scenes['01 | Worn Phase-1 prototype']
bpy.context.window.scene=S
for o in S.objects:
    if o.type=='CURVE' and o.name.startswith('Jumper |'):
        ps=o.data.splines[0].bezier_points
        ps[1].co.y=ps[0].co.y
        ps[1].co.z=ps[0].co.z
        if 'bioamp' in o.get('endpoint_from',''):
            ps[1].co.z=17
            ps[2].co.y=-43
            ps[2].co.z=17
# Preserve the complete harness silhouette above the presentation surface.
from mathutils.geometry import interpolate_bezier
lowest=100
for o in S.objects:
    if o.type=='CURVE' and o.name.startswith('Jumper |'):
        ps=o.data.splines[0].bezier_points
        for a,b in zip(ps,ps[1:]):
            lowest=min(lowest,min(p.z for p in interpolate_bezier(a.co,a.handle_right,b.handle_left,b.co,40))-.63)
bpy.data.objects['Studio | warm neutral surface'].location.z=lowest-1.25
# Use millimetre coordinates for velcro grain rather than generated bounding-box coordinates.
m=bpy.data.materials['Velcro | matte loop pile']
n=m.node_tree.nodes
c=n.new('ShaderNodeTexCoord')
for t in n:
    if t.type=='TEX_NOISE':
        m.node_tree.links.new(c.outputs['Object'],t.inputs['Vector'])
        t.inputs['Scale'].default_value=3
bpy.context.view_layer.update()
runpy.run_path(str(OUT/'audit_phase1.py'))
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'G-one-Phase-1-Strap.blend'))
print('CORRECTIONS_SAVED',flush=True)
