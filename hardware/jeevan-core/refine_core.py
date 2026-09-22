import bpy,math
from pathlib import Path
OUT=Path(__file__).resolve().parent
bpy.ops.wm.open_mainfile(filepath=str(OUT/'Jeevan-Core.blend'))
S=bpy.context.scene;S.frame_set(1)
# Clear the side of the WROOM module with a provisional 2 mm inductor envelope.
o=bpy.data.objects['Power | shielded inductor'];o.data.transform(__import__('mathutils').Matrix.Translation((-10,4,-8.7)))
o.data.transform(__import__('mathutils').Matrix.Diagonal((2/2.7,2/2.7,1,1)))
o.data.transform(__import__('mathutils').Matrix.Translation((10.5,-4,8.7)))
bpy.data.objects['Mark | 2R2'].location.x=10.5
# Add conductive posts between the flex landing pads and skin contacts.
r=bpy.data.objects['09_Contacts'];col=r.users_collection[0]
for x,y in [(-7,13),(7,13),(-7,-3),(7,-3),(0,-15),(0,-9.5)]:
 z=1.78 if y!=-9.5 else 1.1;depth=.55 if y!=-9.5 else .6
 bpy.ops.mesh.primitive_cylinder_add(vertices=24,radius=.55,depth=depth,location=(x,y,z))
 o=bpy.context.object;o.name='Contact | conductive flex interconnect';o.parent=r
 for c in list(o.users_collection):c.objects.unlink(o)
 col.objects.link(o);o.data.materials.append(bpy.data.materials['ENIG | plated copper'])
# Save three scene cameras; geometry remains individually editable.
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'Jeevan-Core.blend'))
for name,frame,file in [('Camera | assembled',1,'core-assembled.png'),('Camera | exploded',121,'core-exploded.png'),('Camera | skin interface',1,'core-skin.png')]:
 S.camera=bpy.data.objects[name];S.frame_set(frame)
 if 'skin' in name:
  bpy.data.objects['Studio floor'].hide_render=True
  for o in bpy.data.objects['11_Liner'].children:o.hide_render=True
 S.render.filepath=str(OUT/file);bpy.ops.render.render(write_still=True)
 bpy.data.objects['Studio floor'].hide_render=False
 for o in bpy.data.objects['11_Liner'].children:o.hide_render=False
S.frame_set(1);S.camera=bpy.data.objects['Camera | assembled'];bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'Jeevan-Core.blend'))
print('REFINED_CORE_SAVED',flush=True)
