"""Refine the existing Phase-1 scene; keep source components editable."""
import bpy, math, json
from pathlib import Path
from mathutils import Vector
OUT=Path(__file__).resolve().parent
S=bpy.context.scene
# Correct skin-facing sensor normals and keep pads clear of the forearm surface.
changes={'optical':((0,-33,43),(-1.36,0,0)),
         'imu':((0,33,43),(1.36,0,0)),
         'bioamp':((0,0,23),(0,0,0))}
old={}
for name,(loc,rot) in changes.items():
    o=bpy.data.objects['Sensor | '+name];old[name]=o.matrix_world.copy()
    o.location=loc;o.rotation_euler=rot
bpy.context.view_layer.update()
for o in bpy.data.objects:
    if o.name.startswith('Jumper |') and o.type=='CURVE' and o.get('endpoint_from'):
        name=o['endpoint_from'].split(' | ')[-1]
        if name in changes:
            first=o.data.splines[0].bezier_points[0]
            first.co=bpy.data.objects['Sensor | '+name].matrix_world @ (old[name].inverted() @ first.co)
# The narrow foam rails support the board without intersecting its shield or regulator.
o=bpy.data.objects['Mount | white foam tape'];o.location=(0,-10,-2.3);o.dimensions=(54,2,3)
rail=o.copy();rail.data=o.data.copy();o.users_collection[0].objects.link(rail);rail.name='Mount | second foam support rail';rail.location.y=10
# Solder blobs are moved to the newly corrected wire endpoints.
blobs=sorted([o for o in bpy.data.objects if o.name.startswith('Jumper | solder joint')],key=lambda o:o.name)
wires=sorted([o for o in bpy.data.objects if o.type=='CURVE' and o.get('endpoint_from')],key=lambda o:o.name)
for blob,w in zip(blobs,wires):blob.location=w.data.splines[0].bezier_points[0].co
S['refinement']='Inner modules face skin, support foam clears the electronics, wires follow moved terminal positions.'
S.render.resolution_percentage=65;S.cycles.samples=32
S.render.filepath=str(OUT/'phase1-refined-preview.png')
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'G-one-Phase-1-Strap.blend'))
bpy.ops.render.render(write_still=True)
