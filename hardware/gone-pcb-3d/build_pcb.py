"""G-one reference-layout reconstruction. Run with Blender --background --python.
Geometry in millimetres. Visual assembly, NOT electrical/fabrication CAD.
"""
import bpy, bmesh, math, random, json, os
from pathlib import Path
from mathutils import Vector

OUT = Path(__file__).resolve().parent
random.seed(24)
bpy.ops.object.select_all(action='SELECT')
bpy.ops.object.delete(use_global=False)
for c in list(bpy.data.collections):
    if c.name != 'Collection': bpy.data.collections.remove(c)
scene = bpy.context.scene
scene.unit_settings.system = 'METRIC'
scene.unit_settings.scale_length = .001
scene.unit_settings.length_unit = 'MILLIMETERS'
root = bpy.data.objects.new('G1 | complete assembly | dimensions in mm', None)
scene.collection.objects.link(root)
COL = None
def collection(name):
    global COL
    COL = bpy.data.collections.new(name); scene.collection.children.link(COL)
    return COL
def assign(o, name, mat=None, parent=True):
    o.name=name
    for c in list(o.users_collection): c.objects.unlink(o)
    COL.objects.link(o)
    if mat: o.data.materials.append(mat)
    if parent: o.parent=root
    return o
def material(name, color, metal=0, rough=.4):
    m=bpy.data.materials.new(name); m.diffuse_color=(*color,1); m.use_nodes=True
    p=m.node_tree.nodes.get('Principled BSDF'); p.inputs['Base Color'].default_value=(*color,1)
    p.inputs['Metallic'].default_value=metal; p.inputs['Roughness'].default_value=rough
    return m
mask=material('Soldermask | deep green satin',(.014,.095,.051),.23,.32)
trace=material('Covered copper | subtle green relief',(.020,.125,.067),.25,.34)
fr4=material('FR4 | cut laminate edge',(.12,.16,.075),0,.65)
gold=material('ENIG | exposed gold',(.68,.43,.12),.82,.24)
tin=material('SAC solder | fillets',(.55,.58,.60),.88,.28)
steel=material('Brushed nickel | connector shields',(.5,.54,.57),.85,.3)
black=material('Molded epoxy',(.012,.016,.02),0,.46)
ceramic=material('MLCC | ceramic tan',(.36,.25,.135),0,.48)
white=material('Silkscreen | warm white',(.79,.85,.76),0,.55)
ivory=material('Connector | ivory nylon',(.81,.79,.65),0,.35)
ink=material('Laser etched lettering',(.07,.08,.09),.25,.48)
glass=material('Optical glass | smoked',(.017,.023,.028),.35,.12)
red=material('Red LED die | unpowered',(.36,.015,.012),.25,.23)
amber=material('IR LED die | unpowered',(.35,.18,.035),.55,.25)
green=material('Status LED phosphor',(.4,.52,.075),.05,.28)
# Microscopic roughness without image textures; every surface remains editable.
for m,scale,strength in [(mask,90,.08),(steel,160,.035),(black,120,.04)]:
    n=m.node_tree.nodes; l=m.node_tree.links
    noise=n.new('ShaderNodeTexNoise'); noise.inputs['Scale'].default_value=scale
    bump=n.new('ShaderNodeBump'); bump.inputs['Strength'].default_value=strength; bump.inputs['Distance'].default_value=.025
    l.new(noise.outputs['Fac'],bump.inputs['Height']); l.new(bump.outputs['Normal'],n.get('Principled BSDF').inputs['Normal'])
def box(name, loc, size, mat, bevel=.04):
    bpy.ops.mesh.primitive_cube_add(size=1,location=loc); o=assign(bpy.context.object,name,mat)
    o.dimensions=size; bpy.ops.object.transform_apply(location=False,rotation=False,scale=True)
    if bevel:
        b=o.modifiers.new('Manufactured edge radius','BEVEL'); b.width=bevel; b.segments=3
        o.modifiers.new('Weighted corner normals','WEIGHTED_NORMAL')
    return o
def cyl(name,x,y,z,r,depth,mat,vertices=32):
    bpy.ops.mesh.primitive_cylinder_add(vertices=vertices,radius=r,depth=depth,location=(x,y,z))
    o=assign(bpy.context.object,name,mat)
    for p in o.data.polygons: p.use_smooth=True
    return o
def line(name,pts,r,mat):
    cu=bpy.data.curves.new(name,'CURVE'); cu.dimensions='3D'; cu.resolution_u=1; cu.bevel_depth=r; cu.bevel_resolution=2
    s=cu.splines.new('POLY'); s.points.add(len(pts)-1)
    for p,co in zip(s.points,pts): p.co=(*co,1)
    o=bpy.data.objects.new(name,cu); COL.objects.link(o); o.parent=root; cu.materials.append(mat); return o
def label(body,x,y,z=.548,size=.7,mat=white,bottom=False):
    cu=bpy.data.curves.new('Text '+body,'FONT'); cu.body=body; cu.size=size; cu.align_x='CENTER'; cu.align_y='CENTER'; cu.extrude=.0015
    o=bpy.data.objects.new('Marking | '+body,cu); COL.objects.link(o); o.parent=root; o.location=(x,y,z)
    if bottom: o.rotation_euler[1]=math.pi
    cu.materials.append(mat); return o
def ring(name,x,y,z,outer,inner,depth,mat):
    n=40; verts=[]
    for zz,rr in [(z-depth/2,outer),(z+depth/2,outer),(z-depth/2,inner),(z+depth/2,inner)]:
        verts += [(x+rr*math.cos(i*2*math.pi/n),y+rr*math.sin(i*2*math.pi/n),zz) for i in range(n)]
    faces=[]
    for i in range(n):
        j=(i+1)%n
        faces.extend([(i,j,n+j,n+i),(2*n+j,2*n+i,3*n+i,3*n+j),(n+i,n+j,3*n+j,3*n+i),(j,i,2*n+i,2*n+j)])
    me=bpy.data.meshes.new(name); me.from_pydata(verts,[],faces); me.update()
    o=bpy.data.objects.new(name,me); COL.objects.link(o); o.parent=root; me.materials.append(mat); return o
def outline(name,x,y,w,h,z=.55):
    line(name,[(x-w/2,y-h/2,z),(x+w/2,y-h/2,z),(x+w/2,y+h/2,z),(x-w/2,y+h/2,z),(x-w/2,y-h/2,z)],.035,white)
def boolean_cut(target,cutter):
    bpy.context.view_layer.objects.active=target
    m=target.modifiers.new('Machined apertures','BOOLEAN'); m.operation='DIFFERENCE'; m.solver='EXACT'; m.object=cutter
    bpy.ops.object.modifier_apply(modifier=m.name); bpy.data.objects.remove(cutter,do_unlink=True)
def rounded(name,w,h,t,z,mat,r=3):
    pts=[]
    for cx,cy,a in [(w/2-r,h/2-r,0),(-w/2+r,h/2-r,90),(-w/2+r,-h/2+r,180),(w/2-r,-h/2+r,270)]:
        for j in range(13):
            ang=math.radians(a+j*90/12); pts.append((cx+r*math.cos(ang),cy+r*math.sin(ang)))
    n=len(pts); vs=[(x,y,z-t/2) for x,y in pts]+[(x,y,z+t/2) for x,y in pts]
    fs=[tuple(reversed(range(n))),tuple(range(n,2*n))]+[(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)]
    me=bpy.data.meshes.new(name); me.from_pydata(vs,[],fs); me.update()
    bm=bmesh.new(); bm.from_mesh(me); bmesh.ops.recalc_face_normals(bm,faces=list(bm.faces)); bm.to_mesh(me); bm.free()
    o=bpy.data.objects.new(name,me); COL.objects.link(o); o.parent=root; me.materials.append(mat); return o

collection('01 | PCB laminate, drilled holes and plating')
board=rounded('PCB | 44 x 64 x 1.00 mm',44,64,1,0,mask)
board['dimensions_mm']='44 x 64 x 1.0'; board['status']='Reference-layout reconstruction; no electrical netlist'
mounts=[(x,y) for x in [-19,19] for y in [26,18,-24]]
vias=[]
for x in [-18,-16,-14,14,16,18]:
    for y in [12,10,-2,-4]: vias.append((x,y))
for x in [-3,0,3,6]:
    for y in [-8,-23]: vias.append((x,y))
for x,y in [(-8,3),(-6,3),(-8,1),(-6,1),(7,12),(9,12),(11,12),(20,-9),(20,-12),(20,-15),(-20,-10),(-20,-13)]: vias.append((x,y))
cutters=[]
for x,y in mounts: cutters.append(cyl('drill',x,y,0,1.15,3,None))
for x,y in vias: cutters.append(cyl('via drill',x,y,0,.15,3,None,16))
# A U-shaped thermal moat: narrow retained neck at north end of DS18B20 island.
for x,y,w,h in [(13.2,7.5,.65,9),(18.8,7.5,.65,9),(16,3.0,6.2,.65)]: cutters.append(box('slot cutter',(x,y,0),(w,h,3),None,0))
for cutter in cutters:
    boolean_cut(board,cutter)
assert len(board.data.vertices)>100, 'PCB drilling unexpectedly removed substrate'
print('BOARD_GEOMETRY',len(board.data.vertices),len(board.data.polygons),flush=True)
for i,(x,y) in enumerate(mounts):
    for z in [-.506,.506]: ring(f'H{i+1} | annular land',x,y,z,1.55,1.15,.035,gold)
    ring(f'H{i+1} | barrel',x,y,0,1.15,1.1,1,tin)
for i,(x,y) in enumerate(vias):
    ring(f'V{i+1:03} | plated through via',x,y,0,.17,.145,1,tin)
    for z in [-.51,.51]: ring(f'V{i+1:03} | ENIG ring',x,y,z,.30,.15,.026,gold)

collection('02 | ESP32-S3-WROOM-1-N16R8 module')
module=box('U1 | module substrate 18 x 25.5 mm',(0,25.25,.97),(18,25.5,.85),black,.08)
module['part']='ESP32-S3-WROOM-1-N16R8'; module['flash']='16 MB'; module['PSRAM']='8 MB'; module['antenna']='Geometric approximation of reference, not RF artwork'
box('U1 | nickel RF shield',(0,21.3,2.02),(17.4,17.1,1.3),steel,.22)
for side in [-1,1]:
    for i in range(14):
        y=13.2+i*1.27
        box(f'U1 | castellated pad {side}_{i}',(side*8.95,y,1.0),(.52,.72,.75),gold,.06)
        box(f'U1 | solder toe {side}_{i}',(side*9.35,y,.64),(.62,.83,.24),tin,.10)
for i in range(12):box(f'U1 | south contact {i}',(-7+i*1.27,12.55,.83),(.7,.6,.45),gold,.05)
antenna=[(-7.8,30.4,1.43),(-7.8,37,1.43),(-4.8,37,1.43),(-4.8,33.8,1.43),(-1.8,33.8,1.43),(-1.8,37,1.43),(1.2,37,1.43),(1.2,33.8,1.43),(4.2,33.8,1.43),(4.2,37,1.43),(7.5,37,1.43),(7.5,30.4,1.43)]
line('U1 | visible antenna meander (illustrative)',antenna,.17,gold)
label('ESPRESSIF',0,26.5,2.69,1.1,ink)
label('ESP32-S3-WROOM-1',0,24.6,2.69,.74,ink)
label('N16R8',-3.5,22.6,2.69,1.05,ink)
label('16MB FLASH / 8MB PSRAM',0,18.1,2.69,.52,ink)
label('G-one  /  BLE',0,16.4,2.69,.65,ink)
for i in range(9):
    for j in range(9):
        if random.random()>.45:box('U1 | laser matrix cell',(3+i*.26,20+j*.26,2.69),(.22,.22,.008),ink,0)

def smd(ref,x,y,kind='C',bottom=False,angle=0):
    s=-1 if bottom else 1; start=set(bpy.data.objects)
    mat=ceramic if kind=='C' else black
    box(ref+' | body',(x,y,s*.87),(1.05,.72,.48),mat,.05)
    for dx in [-.67,.67]:
        box(ref+' | termination',(x+dx,y,s*.85),(.35,.75,.49),tin,.06)
        box(ref+' | solder fillet',(x+dx,y,s*.60),(.6,.91,.16),tin,.06)
    if angle:
        for o in set(bpy.data.objects)-start:
            dx=o.location.x-x; dy=o.location.y-y; a=math.radians(angle)
            o.location.x=x+dx*math.cos(a)-dy*math.sin(a); o.location.y=y+dx*math.sin(a)+dy*math.cos(a); o.rotation_euler.z=a
def ic(ref,part,x,y,w,h,pins,style='QFN',bottom=False):
    s=-1 if bottom else 1; height=.85
    body=box(ref+' | '+part,(x,y,s*(.6+height/2)),(w,h,height),black,.10)
    body['part']=part; body['package_model']='Nominal envelope; footprint must be verified'
    if style=='QFN':
        count=(pins+3)//4
        for side in range(4):
            for i in range(min(count,pins-side*count)):
                off=(i-(count-1)/2)*.5
                px,py=(x+off,y+(h/2)*(-1 if side==0 else 1)) if side<2 else (x+(w/2)*(-1 if side==2 else 1),y+off)
                size=(.27,.62,.18) if side<2 else (.62,.27,.18)
                box(f'{ref} | contact {side*count+i+1}',(px,py,s*.62),size,tin,.035)
    else:
        count=pins//2
        for side in [-1,1]:
            for i in range(count):
                yy=y+(i-(count-1)/2)*.65
                box(ref+' | lead heel',(x+side*(w/2+.15),yy,s*.95),(.4,.25,.22),tin,.045)
                box(ref+' | lead downstand',(x+side*(w/2+.4),yy,s*.78),(.22,.25,.43),tin,.045)
                box(ref+' | solder foot',(x+side*(w/2+.65),yy,s*.61),(.6,.33,.16),tin,.04)
    cyl(ref+' | pin 1 mark',x-w/2+.45,y+h/2-.45,s*1.457,.15,.012,ink)
    label(ref,x,y+h/2+1,s*.55,.65,bottom=bottom)
    label(part,x,y,s*1.46,min(.55,w/len(part)*1.55),white,bottom)
    return body
collection('03 | MPU6050 inertial sensor')
ic('U3','MPU6050',-12,8,4,4,24)
outline('U3 | courtyard',-12,8,6,6)
for i,(x,y) in enumerate([(-17,10),(-17,7),(-8,8),(-12,3.5)]):smd('C_IMU'+str(i),x,y,angle=90)
label('IMU',-12,12.3,size=.8)

collection('04 | microSD socket and removable card')
sd=box('J4 | microSD molded tray',(11,3.7,.95),(14.6,15.8,.8),black,.2)
box('J4 | stamped steel lid',(11,3.7,2.18),(14.6,15.8,.22),steel,.08)
for xx in [3.85,18.15]:box('J4 | folded side wall',(xx,3.7,1.54),(.22,15.6,1.5),steel,.05)
box('J4 | rear wall',(11,11.48,1.5),(14.5,.22,1.5),steel,.04)
for i in range(8):
    x=6.4+i*1.1
    box(f'J4 | spring contact {i+1}',(x,6,1.4),(.45,4.8,.12),gold,.045)
    box(f'J4 | SMT tail {i+1}',(x,11.9,.64),(.6,1.25,.18),tin,.04)
for x in [3.5,18.5]:
    for y in [-2.6,9.8]:box('J4 | anchor tab',(x,y,.75),(1.2,1.5,.35),tin,.08)
for x in [4.7,17.3]:
    for y in [-.5,3,7]:box('J4 | stamped aperture',(x,y,2.305),(.85,1.9,.01),black,.03)
box('J4 | card lip',(11,-4.45,1.35),(10.8,1,.65),black,.08)
label('microSD',11,4,2.31,1.05,ink); label('J4',11,13,size=.8)

collection('05 | EMG analog front end and electrode interface')
ic('U5','INA333',-11,-5,3,3,8,'TSSOP')
ic('U6','OPAMP',-13,-14,4.4,5,14,'TSSOP')
ic('U7','OPAMP',-5,-14,4.4,5,14,'TSSOP')
label('EMG / ANALOG',-10,-.5,size=.8)
for i,(x,y,k,a) in enumerate([(-17,-2,'R',0),(-13,-1.7,'C',0),(-7,-2,'R',90),(-17,-5,'C',90),(-6,-6,'R',90),(-17,-8,'R',0),(-12,-9,'C',0),(-7,-9,'R',0),(-17,-17.5,'C',0),(-10,-19,'R',0),(-5,-19,'C',0),(-2,-6,'C',90)]):smd(k+'_EMG'+str(i),x,y,k,angle=a)

def connector(ref,x,y,count=3,rot=0):
    initial=set(bpy.data.objects); w=count*1.25+1.5
    box(ref+' | floor',(x,y,.9),(w,3.7,.65),ivory,.14)
    for xx in [x-w/2+.35,x+w/2-.35]:box(ref+' | housing side',(xx,y,2),(.7,3.7,2.6),ivory,.13)
    box(ref+' | housing rear',(x,y+1.5,2),(w,.7,2.6),ivory,.13)
    box(ref+' | latch bridge',(x,y-.1,3.12),(w,.9,.36),ivory,.10)
    for i in range(count):
        xx=x+(i-(count-1)/2)*1.25
        box(ref+f' | contact {i+1}',(xx,y,1.7),(.38,2.3,.38),gold,.055)
        box(ref+f' | solder tail {i+1}',(xx,y+2,.63),(.55,1.3,.20),tin,.06)
    if rot:
        a=math.radians(rot)
        for o in set(bpy.data.objects)-initial:
            dx=o.location.x-x;dy=o.location.y-y
            o.location.x=x+dx*math.cos(a)-dy*math.sin(a);o.location.y=y+dx*math.sin(a)+dy*math.cos(a);o.rotation_euler.z+=a
connector('J3 | electrode E+ E- REF',-20,-13,3,-90)
label('J3',-19,-8.7,size=.7)

collection('06 | Charger, buck-boost and battery interface')
ic('U8','BQ24074',6,-13,3,3,16)
ic('U9','TPS63070',14,-13,3,2.5,15)
box('L1 | shielded ferrite inductor',(14,-21,2),(5,5,2.8),black,.35)
box('L1 | top ferrite cap',(14,-21,3.42),(4.7,4.7,.12),material('Ferrite',(.17,.18,.19),.2,.65),.20)
label('2R2',14,-21,3.49,1.4,ink)
for x in [11.7,16.3]:box('L1 | terminal',(x,-21,.85),(.7,4,.6),tin,.1)
ic('U10','1V8',3,-20,1.6,2.2,6,'TSSOP')
ic('U11','AFE LDO',-1,-7,1.6,2.2,6,'TSSOP')
for i,(x,y) in enumerate([(3,-10),(8,-10),(11,-10),(17,-10),(4,-16),(8,-16),(11,-16),(17,-16),(9,-20),(9,-23),(19,-20),(20,-17.5),(3,-23)]):smd('C_PWR'+str(i),x,y,angle=90 if i%2 else 0)
connector('J2 | battery connector',17,-28,3)
label('BAT / NTC',17,-24.6,size=.6)

collection('07 | USB-C, programming pads, switches and LEDs')
# Hollow USB-C shell with real open mouth, tongue and two rows of contacts.
shell=box('J1 | USB-C metal shell',(0,-30.3,2.15),(8.9,7.3,3.2),steel,.65)
bpy.context.view_layer.objects.active=shell
for m in list(shell.modifiers):
    if m.type=='BEVEL':bpy.ops.object.modifier_apply(modifier=m.name)
inner=box('USB mouth cutter',(0,-31.1,2.15),(8.15,7.8,2.52),None,.5)
bpy.context.view_layer.objects.active=inner
for m in list(inner.modifiers):
    if m.type=='BEVEL':bpy.ops.object.modifier_apply(modifier=m.name)
boolean_cut(shell,inner)
box('J1 | inner tongue',(0,-30.5,2.1),(6.8,4.8,.65),black,.2)
for i in range(12):
    for z in [1.75,2.45]:box(f'J1 | USB contact {i+1} z{z}',(-2.75+i*.5,-31.4,z),(.22,2.8,.06),gold,.025)
for x in [-4.5,4.5]:
    for y in [-32,-28]:box('J1 | solder shell lug',(x,y,.64),(1,1.6,.3),tin,.1)
label('USB-C',0,-25.2,size=.8)
for i,(x,txt) in enumerate([(-17,'RST'),(-10.5,'BOOT')]):
    box('SW'+str(i)+' | switch base',(x,-28,1.15),(4,4,1.1),black,.15)
    box('SW'+str(i)+' | metal frame',(x,-28,1.77),(3.7,3.7,.25),steel,.15)
    cyl('SW'+str(i)+' | actuator',x,-28,2.17,1.1,.7,black)
    for dx in [-2,2]:
        for dy in [-1.4,1.4]:box('SW'+str(i)+' | contact',(x+dx,-28+dy,.64),(.7,.6,.22),tin,.04)
    label(txt,x,-24.8,size=.75)
for x,txt in [(7,'PWR'),(10.5,'CHG')]:
    box(txt+' LED | base',(x,-28,.85),(1.5,2,.6),ivory,.12)
    box(txt+' LED | lens',(x,-28,1.24),(1.1,1.35,.25),green,.15)
    for y in [-29,-27]:box(txt+' LED | lead',(x,y,.64),(1.5,.6,.2),tin,.05)
    label(txt,x,-25.8,size=.6)
for i,txt in enumerate(['GND','IO0','3V3','TX','RX']):
    x=-17+i*3
    cyl('TP'+str(i+1)+' | '+txt,x,-21,.54,.63,.06,gold)
    label(txt,x,-22.55,size=.48)

collection('08 | Skin side MAX30102 optical sensor')
ppg=box('U2 | MAX30102 5.6 x 3.3 x 1.55',(0,1,-1.375),(5.6,3.3,1.55),black,.15)
ppg['part']='MAX30102EFD+'; ppg['compatibility']='Requested sensor; existing MAX30100 firmware requires a different driver'
box('U2 | optical cover glass',(0,1,-2.17),(5.3,3.03,.06),glass,.11)
for x,mat in [(-1.85,red),(-.75,amber)]:
    box('U2 | LED aperture',(x,1,-2.212),(.72,1.42,.035),mat,.09)
    box('U2 | LED emitter die',(x,1,-2.236),(.25,.42,.015),gold,.04)
box('U2 | photodiode window',(1.2,1,-2.215),(1.9,2.25,.04),glass,.2)
for i in range(7):
    for y in [-.7,2.7]:box('U2 | LGA solder land',(-2.1+i*.7,y,-.64),(.4,.6,.18),tin,.035)
for i,(x,y) in enumerate([(-5,1),(-5,-1.5),(5,1),(5,-1.5)]):smd('C_PPG'+str(i),x,y,bottom=True)
label('U2 / MAX30102',0,4.8,-.55,.7,bottom=True)
label('OPTICAL / SKIN',0,-3.8,-.55,.68,bottom=True)

collection('09 | DS18B20 thermal island')
ic('U4','DS18B20',16,8.1,3,3,8,'TSSOP',True)
smd('R_1WIRE',16,13,'R',True)
label('SKIN TEMP',16,15,-.55,.65,bottom=True)

collection('10 | Visible routing relief and silkscreen')
# These routes are illustrative surface detail, deliberately not called electrical nets.
routes=[ [(-9,13),(-10,12),(-14,12),(-15,11)], [(-5,12),(-5,9),(-8,6),(-8,4)],
 [(4,12),(4,9),(1,6),(1,-4),(3,-6)],[(7,12),(7,10),(6,9)],
 [(-12,5),(-12,4),(-9,1),(-9,-2)], [(-17,-4),(-18,-5),(-18,-7),(-15,-10)],
 [(-10,-7),(-10,-9),(-8,-11)], [(-3,-15),(-1,-15),(1,-13),(3,-13)],
 [(6,-15),(6,-18),(8,-20)],[(14,-15),(14,-17),(17,-20)],
 [(17,-25),(19,-25),(20,-24),(20,-18)], [(0,-26),(0,-22),(-1,-21)],
 [(-17,-20),(-17,-19),(-19,-17)],[(8,-6),(8,-8),(11,-8),(12,-9)] ]
for j,pts in enumerate(routes):
    for k in range(2 if j<5 else 1):line(f'Visual routing {j}.{k} | NOT a netlist',[(x+k*.4,y,.516) for x,y in pts],.065,trace)
for j,pts in enumerate([[(0,5),(0,9),(3,12),(3,16)],[(5,1),(9,1),(12,4)],[(16,13),(12,13),(10,15)], [(-5,1),(-9,1),(-12,-2),(-12,-9)]]):
    line('Bottom visual route '+str(j),[(x,y,-.516) for x,y in pts],.065,trace)
label('G1',0,-11,-.55,4,bottom=True)
label('G-ONE WEARABLE',0,-15,-.55,1.2,bottom=True)
label('REV A  /  64 x 44 mm',0,-17.6,-.55,.72,bottom=True)
label('3D ASSEMBLY CONCEPT',0,-20,-.55,.62,bottom=True)
label('G1',-3,7,size=1.8)
label('REV A',-3,5.2,size=.55)
for i,(x,y) in enumerate([(-20,0),(20,0),(-20,-24)]):
    cyl('Fiducial '+str(i),x,y,.54,.4,.03,gold)

collection('11 | Optional battery envelope (hidden by default)')
battery_col=COL
bat=box('BAT1 | provisional LiPo envelope 30 x 25 x 5',(0,-6,7),(30,25,5),steel,.8)
bat['status']='Envelope only; actual battery dimensions and part number unknown'
label('LiPo / PLACEHOLDER',0,-6,9.55,1.8,ink)
battery_col.hide_render=True; battery_col.hide_viewport=True

collection('12 | Studio, cameras and reference')
studio=COL
ground=box('Studio | neutral backdrop',(0,0,-5),(2000,2000,.2),material('Backdrop',(.15,.17,.18),0,.85),.0);ground.parent=None
def camera(name,pos,target,ortho):
    d=bpy.data.cameras.new(name);o=bpy.data.objects.new(name,d);studio.objects.link(o);o.location=pos
    o.rotation_euler=(Vector(target)-o.location).to_track_quat('-Z','Y').to_euler();d.type='ORTHO';d.ortho_scale=ortho;d.clip_end=2000
    return o
iso=camera('CAM | isometric',(85,-110,140),(0,3,0),94)
top=camera('CAM | top',(0,3,150),(0,3,0),84)
bottom=camera('CAM | bottom',(0,3,-150),(0,3,0),84)
detail=camera('CAM | USB and analog detail',(50,-75,68),(0,-15,1),54)
def area(name,pos,power,size,target=(0,0,0)):
    d=bpy.data.lights.new(name,'AREA');d.energy=power;d.shape='DISK';d.size=size
    o=bpy.data.objects.new(name,d);studio.objects.link(o);o.location=pos;o.rotation_euler=(Vector(target)-o.location).to_track_quat('-Z','Y').to_euler()
# World uses millimetre scene coordinates, thus studio light energy is scaled accordingly.
area('KEY | large softbox',(-45,-25,100),150000,85)
area('FILL | strip',(70,30,70),110000,70)
area('RIM | north',(-20,85,65),130000,60)
area('BOTTOM | softbox',(-30,-20,-85),150000,75)
area('BOTTOM | fill',(65,55,-65),90000,65)
scene.world.color=(.20,.20,.20)
scene.render.engine='CYCLES';scene.cycles.samples=48;scene.cycles.use_denoising=True
try:
    pref=bpy.context.preferences.addons['cycles'].preferences;pref.compute_device_type='OPTIX';pref.get_devices()
    for dev in pref.devices:dev.use=dev.type=='OPTIX'
    scene.cycles.device='GPU'
except Exception:scene.cycles.device='CPU'
scene.render.resolution_x=1600;scene.render.resolution_y=1800;scene.render.resolution_percentage=100
scene.render.image_settings.file_format='PNG';scene.render.film_transparent=False
scene.view_settings.view_transform='AgX'
scene.camera=iso
scene['Model status']='Editable reference-image reconstruction. Not production or electrically validated CAD.'
scene['Sensor change']='MAX30102 requested by user; repository firmware uses MAX30100.'
scene['Units']='One Blender unit = one millimetre'
notes='''G-ONE PCB / editable reconstruction
44 x 64 x 1 mm main board. Antenna extends beyond main board.
Reference image layout; package envelopes approximated where unidentified.
U1 ESP32-S3-WROOM-1-N16R8; U2 MAX30102; U3 MPU6050; U4 DS18B20.
EMG section: INA333 + opamp envelope models, NOT a validated EMG circuit.
Six mounting holes follow the top reference; source bottom view was inconsistent.
All traces are visual surface detail, NOT electrically routed nets.
Optional battery envelope is hidden, no exact battery part known.
No Gerbers, electrical DRC, assembly validation or patient-use suitability implied.
MAX30102 requires firmware migration from the repository MAX30100 library.
Camera views: top, bottom, isometric, detail. Native Cycles GPU rendering.
'''
bpy.data.texts.new('READ ME | model scope and controls').write(notes)
bpy.data.texts.new('build_pcb.py').write(Path(__file__).read_text(encoding='utf-8'))
# Pack the supplied reference as an image datablock, without obscuring the model.
ref=bpy.data.images.load(str(OUT/'layout-reference.png'));ref.use_fake_user=True;ref.pack()
for screen in bpy.data.screens:
    for ar in screen.areas:
        if ar.type=='VIEW_3D':
            ar.spaces.active.region_3d.view_distance=105
            ar.spaces.active.region_3d.view_location=(0,3,0)
            ar.spaces.active.region_3d.view_rotation=iso.rotation_euler.to_quaternion()
            ar.spaces.active.clip_end=3000
            ar.spaces.active.shading.type='MATERIAL'
bpy.ops.object.select_all(action='DESELECT');board.select_set(True);bpy.context.view_layer.objects.active=board
inventory={'object_count':len(bpy.data.objects),'mesh_count':sum(o.type=='MESH' for o in bpy.data.objects),'collections':[c.name for c in bpy.data.collections], 'board_mm':[44,64,1], 'scope':notes}
(OUT/'model-inventory.json').write_text(json.dumps(inventory,indent=2),encoding='utf-8')
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'G-one-PCB.blend'))
for cam,name in [(iso,'isometric'),(top,'top'),(bottom,'bottom'),(detail,'detail')]:
    scene.camera=cam;ground.hide_render=(cam==bottom)
    scene.render.filepath=str(OUT/(name+'.png'))
    bpy.ops.render.render(write_still=True)
scene.camera=iso;ground.hide_render=False
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'G-one-PCB.blend'))
print('G1_COMPLETE',json.dumps(inventory))
