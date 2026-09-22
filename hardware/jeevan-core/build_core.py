"""Jeevan / G-one Core concept assembly. Millimetres, provisional mechanical fit."""
import bpy, math, json, random
from pathlib import Path
from mathutils import Vector
OUT=Path(__file__).resolve().parent
random.seed(19)
bpy.ops.wm.read_factory_settings(use_empty=True)
S=bpy.context.scene; S.name='Jeevan Core | assembled and exploded'
S.unit_settings.system='METRIC'; S.unit_settings.scale_length=.001
S.unit_settings.length_unit='MILLIMETERS'
ROOT=None;COL=None
layers=[
('01_Cover','Protective enclosure','Pearl polymer shell, recessed identity panel and USB-C service opening.'),
('02_LightGuide','Status light guide','Translucent guide carries the PCB status LED to the front indicator.'),
('03_Gasket','Perimeter gasket','Elastomer seal between the reusable cover and structural frame; sealing performance untested.'),
('04_MainPCB','Main electronics','ESP32-S3 N16R8, ECG and EMG front ends, MPU6050, BME280, charging, conversion and local microSD storage.'),
('05_Battery','Rechargeable cell','Protected pouch-cell envelope and keyed connection. Capacity awaits cell selection.'),
('06_Frame','Inner support frame','Battery pocket, board supports, screw bosses and routed flex passage.'),
('07_SensorFlex','Flexible sensor interface','Polyimide substrate, copper routes, optical sensing and temperature interface. Contact placement is provisional.'),
('08_Spacer','Skin-side spacer','Compliant silicone carrier with optical and electrode apertures.'),
('09_Contacts','Electrodes and optical interface','Two ECG contacts, two EMG contacts, a BioZ contact and a temperature contact around the PPG window.'),
('10_Adhesive','Replaceable adhesive','Die-cut attachment layer with sensing apertures. Material qualification is pending.'),
('11_Liner','Peel-away release liner','Protects the adhesive before application. Remove before wear.')]
roots=[]
def material(name,c,rough=.45,metal=0):
 m=bpy.data.materials.new(name);m.diffuse_color=(*c,1);m.use_nodes=True
 p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Base Color'].default_value=(*c,1);p.inputs['Roughness'].default_value=rough;p.inputs['Metallic'].default_value=metal
 return m
pearl=material('Pearl | fine satin polymer',(.78,.8,.79),.32)
dark=material('Graphite | engineering polymer',(.024,.031,.034),.46)
seal=material('Gasket | charcoal elastomer',(.012,.018,.02),.85)
pcb=material('PCB | deep green solder mask',(.014,.075,.053),.38)
fr4=material('FR4 | laminate edge',(.16,.19,.10),.7)
gold=material('ENIG | plated copper',(.65,.4,.105),.25,.82)
tin=material('Solder | tin silver',(.61,.66,.69),.26,.82)
steel=material('RF shield | brushed nickel',(.48,.52,.55),.35,.85)
icmat=material('IC | epoxy',(.011,.014,.018),.6)
ink=material('Laser markings | graphite',(.06,.083,.09),.6)
silk=material('Silkscreen | ivory',(.8,.85,.75),.66)
kapton=material('Flex | amber polyimide',(.57,.23,.025),.39)
foil=material('Battery | aluminium pouch',(.56,.6,.64),.3,.78)
tape=material('Battery | polyimide edge tape',(.85,.45,.02),.38)
silicone=material('Skin carrier | silicone',(.64,.67,.63),.86)
contact=material('Electrodes | silver finish',(.51,.56,.56),.51,.75)
adhesive=material('Adhesive | microporous carrier',(.72,.70,.63),.95)
liner=material('Liner | release film',(.84,.85,.81),.48)
teal=material('Light guide | cool teal',(.045,.66,.61),.22)
p=teal.node_tree.nodes.get('Principled BSDF');p.inputs['Emission Color'].default_value=(.02,.7,.6,1);p.inputs['Emission Strength'].default_value=1.5
red=material('Power lead | red insulation',(.38,.012,.015),.55)
def group(i):
 global ROOT,COL
 key,title,desc=layers[i];COL=bpy.data.collections.new(key+' | '+title);S.collection.children.link(COL)
 ROOT=bpy.data.objects.new(key,None);COL.objects.link(ROOT);ROOT['layerIndex']=i;ROOT['title']=title;ROOT['description']=desc;ROOT['explodeDistanceM']=(10-i)*.007
 roots.append(ROOT)
def assign(o,name,mat):
 o.name=name
 for c in list(o.users_collection):c.objects.unlink(o)
 COL.objects.link(o)
 if ROOT:o.parent=ROOT
 if mat:o.data.materials.append(mat)
 return o
def outline(w,h,r,n=12):
 pts=[]
 for cx,cy,a in [(w/2-r,h/2-r,0),(-w/2+r,h/2-r,90),(-w/2+r,-h/2+r,180),(w/2-r,-h/2+r,270)]:
  for j in range(n):
   t=math.radians(a+j*90/(n-1));pts.append((cx+r*math.cos(t),cy+r*math.sin(t)))
 return pts
def plate(name,w,h,r,z,t,mat,xy=(0,0),bevel=.08):
 pts=outline(w,h,r);N=len(pts);v=[(x+xy[0],y+xy[1],zz) for zz in [z-t/2,z+t/2] for x,y in pts]
 f=[tuple(reversed(range(N))),tuple(range(N,2*N))]+[(i,(i+1)%N,(i+1)%N+N,i+N) for i in range(N)]
 me=bpy.data.meshes.new(name);me.from_pydata(v,[],f);me.update();o=assign(bpy.data.objects.new(name,me),name,mat)
 if bevel:
  b=o.modifiers.new('Manufactured edge radius','BEVEL');b.width=bevel;b.segments=3
  o.modifiers.new('Surface normals','WEIGHTED_NORMAL')
 return o
def ring(name,w,h,r,wall,z,t,mat):
 a=outline(w,h,r);b=outline(w-2*wall,h-2*wall,max(.2,r-wall));N=len(a)
 v=[(x,y,zz) for zz in [z-t/2,z+t/2] for loop in [a,b] for x,y in loop];f=[]
 for i in range(N):
  j=(i+1)%N;f.extend([(i,j,j+2*N,i+2*N),(i+N,i+3*N,j+3*N,j+N),(i+2*N,j+2*N,j+3*N,i+3*N),(i,i+N,j+N,j)])
 me=bpy.data.meshes.new(name);me.from_pydata(v,[],f);me.update();o=assign(bpy.data.objects.new(name,me),name,mat)
 bevel=o.modifiers.new('Soft edges','BEVEL');bevel.width=.08;bevel.segments=2;o.modifiers.new('Surface normals','WEIGHTED_NORMAL');return o
def box(name,xyz,size,mat,bev=.1):return plate(name,size[0],size[1],min(bev,size[0]/3,size[1]/3),xyz[2],size[2],mat,xyz[:2],min(bev,.08))
def cyl(name,xyz,r,d,mat):
 bpy.ops.mesh.primitive_cylinder_add(vertices=32,radius=r,depth=d,location=xyz);o=assign(bpy.context.object,name,mat)
 b=o.modifiers.new('Machined edge','BEVEL');b.width=min(.08,d*.1);b.segments=2
 o.modifiers.new('Surface normals','WEIGHTED_NORMAL');return o
def line(name,pts,r,mat):
 c=bpy.data.curves.new(name,'CURVE');c.dimensions='3D';c.bevel_depth=r;c.bevel_resolution=2;c.resolution_u=10
 s=c.splines.new('POLY');s.points.add(len(pts)-1)
 for p,co in zip(s.points,pts):p.co=(*co,1)
 return assign(bpy.data.objects.new(name,c),name,mat)
def text(body,xyz,size,mat=ink):
 c=bpy.data.curves.new(body,'FONT');c.body=body;c.size=size;c.align_x='CENTER';c.align_y='CENTER';c.extrude=.006
 o=assign(bpy.data.objects.new('Mark | '+body,c),'Mark | '+body,mat);o.location=xyz;return o
def cut(o,xyz,size):
 cutter=box('TEMP cut',xyz,size,None,min(2,min(size[:2])*.25));bpy.context.view_layer.objects.active=cutter
 for m in list(cutter.modifiers):bpy.ops.object.modifier_apply(modifier=m.name)
 bpy.context.view_layer.objects.active=o;mod=o.modifiers.new('Functional aperture','BOOLEAN');mod.object=cutter
 bpy.ops.object.modifier_apply(modifier=mod.name);bpy.data.objects.remove(cutter,do_unlink=True)
def pads(x,y,z,n,pitch=.5,axis='x'):
 for i in range(n):
  a=(i-(n-1)/2)*pitch
  box('Soldered terminal',(x+a if axis=='x' else x,y if axis=='x' else y+a,z),(.3,.65,.1) if axis=='x' else (.65,.3,.1),tin,.04)
def chip(name,x,y,w,h,z=8.25,legs=6):
 o=box(name,(x,y,z),(w,h,.7),icmat,.14)
 pads(x,y-h/2-.17,z-.22,legs,w/(legs+1));pads(x,y+h/2+.17,z-.22,legs,w/(legs+1))
 text(name.split(' | ')[0],(x,y,z+.36),min(.58,w/6),silk);cyl('Pin 1',(x-w/2+.4,y+h/2-.4,z+.36),.11,.02,silk)
 return o
def passive(x,y,z=8.17):
 box('MLCC ceramic',(x,y,z),(.7,1.1,.45),silicone,.08)
 for dy in [-.47,.47]:box('MLCC solder cap',(x,y+dy,z),(.75,.2,.46),tin,.04)

group(0)
cover=plate('Outer cover | 30 x 50 envelope',30,50,11,12.5,1,pearl,bevel=.32)
side=ring('Cover | hollow sidewall',30,50,11,.9,9.6,4.8,pearl)
cut(side,(0,-24.4,8.8),(9.1,3,3.25))
badge=plate('Identity | recessed inset',16,24.4,7,13.03,.12,pearl,xy=(0,2),bevel=.06)
text('G',(0,2,13.105),8)
text('G-one',(0,-15.5,13.04),2.5)
text('J E E V A N  /  C O R E',(0,-19,13.04),.68)
cut(cover,(0,-10.9,12.7),(4.9,.95,3))
for x in [-11,11]:
 for y in [-17,17]:
  cyl('Shell | receiving boss',(x,y,11.35),.85,1.25,pearl)

group(1)
plate('Status | light window',4.5,.65,.30,12.87,.32,teal,xy=(0,-10.9),bevel=.06)
box('Lightpipe | riser',(-5,-19,10),(1.0,.6,3.5),teal,.1)
line('Lightpipe | routed bridge',[(-5,-19,11.75),(-5,-14,11.75),(0,-10.9,11.75),(0,-10.9,12.87)],.22,teal)

group(2)
ring('Seal | continuous perimeter',28.1,48.1,10,.65,7.08,.3,seal)

group(3)
# Mainboard enlarged from contradictory concept sheet to preserve WROOM package size.
board=plate('PCB | provisional 24 x 40 x 1',24,40,3.5,7.5,1,pcb)
for z in [7.07,7.24,7.41,7.58,7.75,7.92]:
 ring('PCB | copper layer edge',23.95,39.95,3.5,.028,z,.012,gold)
for x in [-10.5,10.5]:
 for y in [-17.6,17.6]:
  cut(board,(x,y,7.5),(1.25,1.25,3));cyl('PCB | ENIG mounting annulus',(x,y,8.02),.85,.04,gold)
  cyl('PCB | screw recess',(x,y,8.06),.51,.045,dark)
module=box('ESP32-S3-WROOM-1 | 18 x 25.5',(0,6.65,8.42),(18,25.5,.8),dark,.12)
box('ESP32 | RF shield',(0,3.1,9.95),(17.1,17.5,2.3),steel,.22)
text('ESPRESSIF',(0,6,11.12),1.2)
text('ESP32-S3',(0,3.8,11.12),1.2)
text('N16R8',(0,1.8,11.12),1)
text('Wi-Fi  /  BLE',(0,-.4,11.12),.62)
for x in [-9,9]:
 for j in range(14):box('WROOM | castellated pad',(x,-4.8+j*1.27,8.38),(.58,.65,.7),gold,.04)
points=[(-7.7,13,8.84),(-7.7,18.35,8.84),(-5.4,18.35,8.84),(-5.4,15.65,8.84),(-3.0,15.65,8.84),(-3,18.35,8.84),(-.6,18.35,8.84),(-.6,15.65,8.84),(1.8,15.65,8.84),(1.8,18.35,8.84),(4.1,18.35,8.84),(4.1,15.65,8.84),(6.8,15.65,8.84),(6.8,18.35,8.84)]
line('RF | visible antenna pattern',points,.15,gold)
chip('ADS1292R | ECG AFE',-7,-10,4,4,legs=7)
chip('INA333 | EMG AFE',-7,-15.5,2.5,2.5,legs=4)
chip('MPU6050 | IMU',-2.4,-17.2,3,3,legs=5)
# Storage socket on lower right, sized to accept a microSD card.
socket=box('microSD | stamped shell',(5,-12,8.8),(12,13.8,1.5),steel,.3)
cut(socket,(5,-18.9,8.7),(10.8,1.5,.8))
for x in [.8,9.2]:
 for y in [-15,-10]:cut(socket,(x,y,9.55),(.65,1.6,.4))
box('microSD | card',(5,-13,8.5),(10.8,12,.65),dark,.1)
text('microSD',(5,-12,9.57),.95)
chip('BQ24074',-10.5,1,2,2,legs=3)
chip('TPS63070',10.5,0,2,2,legs=3)
box('Power | shielded inductor',(10,-4,8.7),(2.7,2.7,1.4),dark,.2);text('2R2',(10,-4,9.42),.55,silk)
for x in [-10.5,10.5]:
 for y in [5,8,11]:passive(x,y)
for x in [-10,-4,0]:passive(x,-7)
usb=box('USB-C | receptacle',(0,-21.45,8.65),(8.4,5.8,2.6),steel,.55)
cut(usb,(0,-24.3,8.65),(7.5,5.4,1.85));box('USB-C | contact tongue',(0,-22.5,8.65),(6.5,3.7,.45),dark,.15)
for x in range(12):box('USB-C | contact',(x*.45-2.48,-23.25,8.9),(.22,1.25,.05),gold,.025)
# Underside analog and board-to-flex connector, outside battery envelope.
chip('AD5940 | BioZ option',7,-18.0,4,3,6.4,legs=5)
chip('BME280 | environment',-7,-18,2,2,6.4,legs=3)
box('FPC | 24 pin socket',(0,-6.2,6.65),(13.8,2.4,.65),silk,.1)
pads(0,-7.3,6.6,24,.5)
box('FPC | locking bar',(0,-5.3,6.32),(13,.5,.25),dark,.08)
box('Battery | board socket',(-9,9.6,6.4),(3.8,3.2,1.2),silk,.1)
for x in [-9.7,-8.3]:box('Battery socket | contact',(x,9.6,6.2),(.3,2,.3),gold,.03)
box('RGB LED',(-5,-19,8.28),(1.5,1.5,.5),teal,.08)
for i in range(30):
 x=random.choice([-11.1,11.1]);y=random.uniform(-14,12)
 cyl('Via | plated barrel',(x,y,8.04),.12,.025,gold)
for x in [-10.5,10.5]:line('Routing | edge bus',[(x,-14,8.02),(x,-6,8.02),(x*.88,-4,8.02),(x*.88,11,8.02)],.045,gold)
text('G-ONE CORE  /  FIT STUDY',(0,-19.4,8.04),.5,silk)

group(4)
plate('Cell | sealed pouch',20,22,1,4.65,2.9,foil,xy=(0,4),bevel=.25)
ring('Cell | perimeter tape',20.3,22.3,1.1,.38,4.65,2.95,tape).location.y=4
text('Li-ion  3.7 V',(0,5,6.12),1.4)
text('CELL ENVELOPE',(0,2.2,6.12),.75)
for x,mat in [(-.8,red),(.8,dark)]:line('Cell | terminated lead',[(x,15.2,5.4),(x,16.2,5.4),(-6,16.2,5.4),(-8.5+x,10,5.9)],.20,mat)
box('Cell | keyed plug',(-9,10,5.85),(3.5,2.4,.8),silk,.1)

group(5)
frame=ring('Frame | cavity and outer wall',28,48,10,1.4,4.2,5.2,pearl)
plate('Frame | battery support',22,24,1.5,3.05,.3,dark,xy=(0,4))
for x in [-10.5,10.5]:
 for y in [-17.6,17.6]:
  cyl('Frame | PCB standoff',(x,y,5.1),1.25,3.8,dark)
  cyl('Frame | screw insert',(x,y,7),.53,.1,gold)
cut(frame,(0,-23,8.3),(9.3,4,4))
for x in [-12.5,12.5]:box('Frame | snap hook',(x,0,6.9),(.65,2,.4),dark,.08)

group(6)
flex=plate('Flex | polyimide substrate',25.5,44,9,2.1,.2,kapton)
contacts=[(-7,13,6,8),(7,13,6,8),(-7,-3,6,7),(7,-3,6,7),(0,-15,9,6)]
for x,y,w,h in contacts:
 plate('Flex | contact landing',w-.5,h-.5,2,2.23,.05,gold,xy=(x,y))
 for dx in [-.4,0,.4]:line('Flex | routed electrode trace',[(x+dx,y,2.22),(x+dx,y/2,2.22),(dx,0,2.22),(dx,-8,2.22)],.055,gold)
# Optical package lives only on the skin flex, not duplicated on the main PCB.
opt=plate('MAX30102 | optical package',5.6,3.3,.5,1.43,1.1,dark)
for x,col in [(-1.6,gold),(0,teal),(1.6,ink)]:box('PPG | emitter or receiver',(x,0,.86),(.85,1.6,.05),col,.1)
box('DS18B20 | temperature package',(0,-9.4,1.7),(3.9,4.9,.75),icmat,.12)
for x in [-1.2,0,1.2]:box('Temperature | terminal',(x,-12,1.65),(.35,1,.3),tin,.04)
# Folded flex ribbon terminates in underside socket. Editable mesh strips.
for i in range(24):
 x=(i-11.5)*.5
 line('FPC | conductor',[(x,-8,2.23),(x,-9,2.5),(x,-9,5.8),(x,-7,6.35),(x,-6.2,6.35)],.045,gold)
for a,b in [((0,-8.5,2.35),(12.5,1,.2)),((0,-9,4.15),(12.5,.2,3.4)),((0,-8,6.05),(12.5,2,.2))]:box('FPC | folded polyimide',a,b,kapton,.04)

group(7)
spacer=plate('Spacer | die-cut silicone',27,46,9.5,1.35,.9,silicone)
for x,y,w,h in contacts:cut(spacer,(x,y,1.3),(w+.4,h+.4,3))
cut(spacer,(0,0,1.3),(6.5,5.3,3));cut(spacer,(0,-9.5,1.3),(3,3,3))

group(8)
for i,(x,y,w,h) in enumerate(contacts):
 plate(['ECG left','ECG right','EMG left','EMG right','BioZ shared contact'][i],w,h,2.5,.95,1.15,contact,xy=(x,y),bevel=.15)
 for j in range(8):
  for k in range(5):cyl('Electrode | fine contact texture',(x+(k-2)*.65,y+(j-3.5)*.65,.369),.08,.015,steel)
rim=ring('Optical | ambient light baffle',7,6,2,.7,.75,.7,seal)
cyl('Temperature | conductive contact',(0,-9.5,.55),1.25,.6,gold)

group(9)
ad=plate('Adhesive | replaceable patch',30.5,50.5,11,.2,.3,adhesive)
for x,y,w,h in contacts:cut(ad,(x,y,.2),(w+.5,h+.5,2))
cut(ad,(0,0,.2),(7.2,6.2,2));cut(ad,(0,-9.5,.2),(3,3,2))

group(10)
plate('Release liner | peel before wear',30.7,50.7,11,-.06,.12,liner)
plate('Release liner | peel tab',8,6,2,-.06,.12,liner,xy=(0,-26))
for y in [-17,-7,3,13]:text('PEEL  /  REMOVE',(0,y,-.13),1.1,ink).rotation_euler.x=math.pi

# Animation is part of the native scene and exported as a scrub-able GLB clip.
for i,r in enumerate(roots):
 r.location.z=0;r.keyframe_insert(data_path='location',frame=1)
 r.location.z=(10-i)*7;r.keyframe_insert(data_path='location',frame=121)
S.frame_start=1;S.frame_end=121;S.render.fps=30;S.frame_set(1)
ROOT=None;COL=bpy.data.collections.new('Studio');S.collection.children.link(COL)
floor=plate('Studio floor',500,500,10,-1.6,1,dark)
def camera(name,pos,target,scale):
 d=bpy.data.cameras.new(name);o=bpy.data.objects.new(name,d);COL.objects.link(o);o.location=pos;o.rotation_euler=(Vector(target)-o.location).to_track_quat('-Z','Y').to_euler();d.type='ORTHO';d.ortho_scale=scale;d.clip_end=2000;return o
hero=camera('Camera | assembled',(65,-90,110),(0,0,5),74)
exploded=camera('Camera | exploded',(110,-145,115),(0,0,38),116)
back=camera('Camera | skin interface',(48,-75,-110),(0,0,1),68)
for name,pos,power,size in [('Key',(-45,-30,120),170000,75),('Fill',(70,40,100),130000,65),('Rim',(-20,65,60),110000,55),('Skin fill',(20,-60,-90),65000,65)]:
 d=bpy.data.lights.new(name,'AREA');d.energy=power;d.shape='DISK';d.size=size;o=bpy.data.objects.new(name,d);COL.objects.link(o);o.location=pos;o.rotation_euler=(Vector((0,0,20))-o.location).to_track_quat('-Z','Y').to_euler()
S.world=bpy.data.worlds.new('Studio atmosphere');S.world.use_nodes=True;S.world.node_tree.nodes['Background'].inputs[0].default_value=(.16,.19,.21,1);S.world.node_tree.nodes['Background'].inputs[1].default_value=.5
S.render.engine='CYCLES';S.cycles.samples=48;S.cycles.use_denoising=True;S.cycles.device='GPU'
pref=bpy.context.preferences.addons['cycles'].preferences;pref.compute_device_type='OPTIX';pref.get_devices()
for d in pref.devices:d.use=d.type=='OPTIX'
S.render.resolution_x=1500;S.render.resolution_y=1500;S.render.resolution_percentage=100;S.view_settings.view_transform='AgX';S.camera=hero
for name in ['ChatGPT Image Sep 19, 2026, 05_17_11 PM.png','ChatGPT Image Sep 19, 2026, 05_15_27 PM.png','ChatGPT Image Sep 19, 2026, 05_13_07 PM.png']:
 photo=Path('C:/Users/Aditya Pandey/Downloads')/name
 if photo.exists():
  im=bpy.data.images.load(str(photo));im.pack();im.use_fake_user=True
 else:
  # Retain the already-packed reference if the Downloads original has moved.
  with bpy.data.libraries.load(str(OUT/'Jeevan-Core.blend')) as (src,dst):
   dst.images=[n for n in src.images if n==name]
notes=bpy.data.texts.new('READ ME | model accuracy');notes.write('Jeevan / G-one Core. Photo-concept reconstruction, NOT manufacturing CAD. Provisional 30 x 50 x 13 mm envelope; 24 x 40 x 1 mm PCB. WROOM package 18 x 25.5 mm. Six visual copper layers; no routed electrical netlist. Electrode placement, BioZ circuit, battery capacity, sealing and skin materials require engineering validation. No certified runtime/IP rating asserted. Frame 1 assembled, frame 121 exploded. All eleven root layers have stable GLB names.')
(OUT/'layers.json').write_text(json.dumps([{'id':k,'title':t,'description':d,'index':i,'explodeDistanceM':(10-i)*.007} for i,(k,t,d) in enumerate(layers)],indent=2))
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'Jeevan-Core.blend'))
print('CORE_SAVED',flush=True)
for cam,frame,file in [(hero,1,'core-assembled.png'),(exploded,121,'core-exploded.png'),(back,1,'core-skin.png')]:
 S.camera=cam;S.frame_set(frame)
 if cam==back:
  floor.hide_render=True;roots[10].hide_render=True
  for o in roots[10].children:o.hide_render=True
 S.render.filepath=str(OUT/file);bpy.ops.render.render(write_still=True)
 floor.hide_render=False
 for o in roots[10].children:o.hide_render=False
 roots[10].hide_render=False
S.frame_set(1);S.camera=hero
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'Jeevan-Core.blend'))
print('CORE_RENDERED',flush=True)
