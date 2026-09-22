import bpy,math,random,json
from pathlib import Path
from mathutils import Vector
OUT=Path(__file__).resolve().parent;S=bpy.context.scene
random.seed(63)
body=bpy.data.objects['Body | continuous closed-fist sculpt']
# Relax the initial volume construction into a less bulbous fist surface.
vg=body.vertex_groups.new(name='Hand sculpt smoothing mask')
for v in body.data.vertices:
    if v.co.x < -70:
        vg.add([v.index],min(1,(-v.co.x-70)/30),'REPLACE')
    if v.co.x < -106 and v.co.z > 61:
        v.co.z=61+(v.co.z-61)*.48
sm=body.modifiers.new('Hand | soften knuckle transitions','SMOOTH');sm.factor=1.0;sm.iterations=24;sm.vertex_group=vg.name
bpy.context.view_layer.objects.active=body
bpy.ops.object.modifier_move_up(modifier=sm.name)
m=bpy.data.materials['Skin | warm brown with fine pores'];p=m.node_tree.nodes.get('Principled BSDF')
p.inputs['Roughness'].default_value=.6;p.inputs['Subsurface Weight'].default_value=.045
# Introduce low-amplitude skin color variation.
n=m.node_tree.nodes;l=m.node_tree.links
tex=n.new('ShaderNodeTexNoise');tex.inputs['Scale'].default_value=.2
r=n.new('ShaderNodeValToRGB');r.color_ramp.elements[0].position=.15;r.color_ramp.elements[0].color=(.27,.12,.063,1)
r.color_ramp.elements[1].position=.85;r.color_ramp.elements[1].color=(.37,.19,.115,1)
l.new(tex.outputs['Fac'],r.inputs[0]);l.new(r.outputs[0],p.inputs['Base Color'])
# Keep the cables free-form, not a parallel ribbon.
wires=sorted([o for o in bpy.data.objects if o.type=='CURVE' and o.get('endpoint_from')],key=lambda o:o.name)
for i,o in enumerate(wires):
    sp=o.data.splines[0];q=sp.bezier_points
    q[4].co.x += random.uniform(-14,17);q[4].co.y+=random.uniform(-9,9);q[4].co.z+=random.uniform(-10,14)
    q[5].co.x += random.uniform(-13,20);q[5].co.y+=random.uniform(-14,18);q[5].co.z+=random.uniform(-16,10)
    for v in q:v.handle_left_type='AUTO';v.handle_right_type='AUTO'
resw=sorted([o for o in bpy.data.objects if o.name.startswith('Resistor | insulated connection')],key=lambda o:o.name)
for i,o in enumerate(resw):o.data.splines[0].bezier_points[-1].co=wires[i].data.splines[0].bezier_points[4].co
# Remove free-standing skin crease curves that no longer coincide with the refined sculpt.
for o in list(bpy.data.objects):
    if o.name.startswith('Skin | knuckle transverse'):bpy.data.objects.remove(o,do_unlink=True)
S['final_notes']='Closed fist is sculpted approximation; exact hand anatomy not present in reference photographs.'

# Separate opened strap scene: same sensor mesh data, not a redesigned architecture.
flat=bpy.data.scenes.new('02 | Opened strap - sensor inspection')
flat.unit_settings.system='METRIC';flat.unit_settings.scale_length=.001;flat.unit_settings.length_unit='MILLIMETERS'
flat.world=S.world
fc=bpy.data.collections.new('OPEN | Flat textile cuff and original modules');flat.collection.children.link(fc)
def cube(name,loc,size,material):
    verts=[(a*size[0]/2,b*size[1]/2,c*size[2]/2) for a,b,c in [(-1,-1,-1),(-1,-1,1),(-1,1,-1),(-1,1,1),(1,-1,-1),(1,-1,1),(1,1,-1),(1,1,1)]]
    faces=[(0,4,6,2),(1,3,7,5),(0,1,5,4),(2,6,7,3),(0,2,3,1),(4,5,7,6)]
    me=bpy.data.meshes.new(name);me.from_pydata(verts,[],faces);me.update();o=bpy.data.objects.new(name,me);fc.objects.link(o);o.location=loc;me.materials.append(material)
    b=o.modifiers.new('Soft textile edge','BEVEL');b.width=.65;b.segments=3;return o
cube('OPEN | black fabric strap',(0,0,20),(76,265,1.65),bpy.data.materials['Strap | black woven nylon'])
cube('OPEN | Velcro fastening end',(0,101,21.2),(65,53,1.0),bpy.data.materials['Velcro | matte loop pile'])
roots={}
for typ,y in [('optical',-67),('bioamp',0),('imu',65)]:
    old=bpy.data.objects['Sensor | '+typ];new=old.copy();fc.objects.link(new);new.name='OPEN | '+old.name
    new.location=(0,y,22);new.rotation_euler=(0,0,math.pi/2 if typ=='bioamp' else 0);roots[typ]=new
    for child in old.children:
        c=child.copy();fc.objects.link(c);c.parent=new;c.name='OPEN | '+child.name
old=bpy.data.objects['ESP32 | mounted development board'];new=old.copy();fc.objects.link(new);new.name='OPEN | ESP32 outside face'
new.location=(85,0,24);new.rotation_euler=(0,0,math.pi/2)
for child in old.children:
    c=child.copy();fc.objects.link(c);c.parent=new;c.name='OPEN | '+child.name
def curve(name,pts,r,mat):
    cu=bpy.data.curves.new(name,'CURVE');cu.dimensions='3D';cu.bevel_depth=r;cu.bevel_resolution=3;cu.resolution_u=20
    s=cu.splines.new('BEZIER');s.bezier_points.add(len(pts)-1)
    for p,co in zip(s.bezier_points,pts):p.co=co;p.handle_left_type='AUTO';p.handle_right_type='AUTO'
    o=bpy.data.objects.new(name,cu);fc.objects.link(o);cu.materials.append(mat);return o
for i,o in enumerate(wires):
    typ=o['endpoint_from'].split(' | ')[-1];y={'optical':-67,'bioamp':0,'imu':65}[typ]
    pts=[(9,y-6+i%4*2,22),(42,y-4,23),(55+random.uniform(-6,10),y*.65,36+random.uniform(0,25)),(80,y*.25,41),(85+(-12.5 if i%2 else 12.5),-26+i*4,32)]
    curve('OPEN | jumper '+str(i),pts,.63,o.data.materials[0])
for x in [-36,36]:
    for j in range(90):curve('OPEN | edge stitch',[(x,-130+j*2.9,21),(x,-128.5+j*2.9,21)],.09,bpy.data.materials['Seam | charcoal nylon'])
# Duplicate studio into the independent scene without linking body or worn assembly.
for o in bpy.data.collections['10 | Studio and presentation cameras'].objects:
    if o.type=='LIGHT' or o.name.startswith('Studio |'):
        c=o.copy();flat.collection.objects.link(c)
camdata=bpy.data.cameras.new('OPEN | camera');cam=bpy.data.objects.new('OPEN | camera',camdata);flat.collection.objects.link(cam)
cam.location=(230,-320,570);cam.rotation_euler=(Vector((25,0,25))-cam.location).to_track_quat('-Z','Y').to_euler();camdata.type='ORTHO';camdata.ortho_scale=310;camdata.clip_end=10000;flat.camera=cam
for sc in [S,flat]:
    sc.render.engine='CYCLES';sc.cycles.device='GPU';sc.cycles.samples=64;sc.cycles.use_denoising=True
    sc.render.resolution_x=1800;sc.render.resolution_y=1400;sc.render.resolution_percentage=100
    sc.view_settings.view_transform='AgX'
pref=bpy.context.preferences.addons['cycles'].preferences;pref.compute_device_type='OPTIX';pref.get_devices()
for d in pref.devices:d.use=d.type=='OPTIX'
S.camera=bpy.data.objects['CAM | wrist and closed fist']
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'G-one-Phase-1-Strap.blend'))
print('FINAL_GEOMETRY_SAVED',flush=True)
S.render.filepath=str(OUT/'phase1-worn.png');bpy.ops.render.render(write_still=True,scene=S.name)
S.camera=bpy.data.objects['CAM | controller and prototype wiring'];S.render.filepath=str(OUT/'phase1-hardware.png');bpy.ops.render.render(write_still=True,scene=S.name)
S.camera=bpy.data.objects['CAM | inner sensor arrangement'];S.render.filepath=str(OUT/'phase1-inside.png')
bpy.data.collections['01 | Anatomy - closed fist and forearm'].hide_render=True
bpy.data.objects['Studio | warm neutral surface'].hide_render=True
bpy.ops.render.render(write_still=True,scene=S.name)
bpy.data.collections['01 | Anatomy - closed fist and forearm'].hide_render=False
bpy.data.objects['Studio | warm neutral surface'].hide_render=False
flat.render.filepath=str(OUT/'phase1-opened.png');bpy.ops.render.render(write_still=True,scene=flat.name)
S.camera=bpy.data.objects['CAM | wrist and closed fist']
report={'scenes':[s.name for s in bpy.data.scenes],'objects':len(bpy.data.objects),
 'packed_photos':sum(bool(i.packed_file) for i in bpy.data.images),
 'hardware_modules':['ESP32-S3 development board','Muscle BioAmp Patchy','MAX30100/30102 breakout','MPU6050 GY-521','temperature probe'],
 'resistors':4,'scale':'Approximate millimetres; nominal 2.54 mm header pitch',
 'limitations':['Closed-fist anatomy approximated','Hidden routing and pin assignments not electrically validated','Exact dimensions and resistor values not measurable from photos']}
(OUT/'model-report.json').write_text(json.dumps(report,indent=2))
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'G-one-Phase-1-Strap.blend'))
print('PHASE1_RENDER_COMPLETE',flush=True)
