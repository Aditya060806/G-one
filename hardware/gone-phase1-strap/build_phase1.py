"""Photo-derived Phase-1 strap. All dimensions are approximate millimetres.
Run using Blender --background --python build_phase1.py. No earlier model is opened.
"""
import bpy, math, random, json
from pathlib import Path
from mathutils import Vector, Matrix
random.seed(41)
OUT=Path(__file__).resolve().parent
bpy.ops.wm.read_factory_settings(use_empty=True)
S=bpy.context.scene; S.name='01 | Worn Phase-1 prototype'
S.unit_settings.system='METRIC';S.unit_settings.scale_length=.001
S.unit_settings.length_unit='MILLIMETERS'
C=None; P=None
def col(name):
    global C,P
    C=bpy.data.collections.new(name);S.collection.children.link(C);P=None
    return C
def mat(name,rgb,rough=.5,metal=0):
    m=bpy.data.materials.new(name);m.diffuse_color=(*rgb,1);m.use_nodes=True
    p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Base Color'].default_value=(*rgb,1)
    p.inputs['Roughness'].default_value=rough;p.inputs['Metallic'].default_value=metal
    return m
def noise(m,scale,distance,strength):
    n=m.node_tree.nodes;l=m.node_tree.links
    tex=n.new('ShaderNodeTexNoise');tex.inputs['Scale'].default_value=scale
    bump=n.new('ShaderNodeBump');bump.inputs['Distance'].default_value=distance;bump.inputs['Strength'].default_value=strength
    l.new(tex.outputs['Fac'],bump.inputs['Height']);l.new(bump.outputs['Normal'],n.get('Principled BSDF').inputs['Normal'])
skin=mat('Skin | warm brown with fine pores',(.36,.175,.095),.48)
p=skin.node_tree.nodes.get('Principled BSDF');p.inputs['Subsurface Weight'].default_value=.09
p.inputs['Subsurface Radius'].default_value=(1,.45,.22);noise(skin,2.5,.10,.22)
crease=mat('Skin | shallow crease shadow',(.21,.085,.047),.67)
nail=mat('Natural nail | satin keratin',(.43,.25,.17),.36)
fabric=mat('Strap | black woven nylon',(.011,.014,.018),.85)
thread=mat('Seam | charcoal nylon',(.028,.030,.033),.86)
velcro=mat('Velcro | matte loop pile',(.006,.008,.01),.98);noise(velcro,8,.28,.75)
black=mat('Header housings | black thermoplastic',(.008,.010,.014),.4)
tape=mat('Electrical insulation tape',(.009,.012,.015),.31);noise(tape,1,.06,.15)
pcb=mat('ESP32 | black soldermask',(.008,.019,.019),.35)
blue=mat('GY-521 | blue soldermask',(.012,.12,.25),.36)
green=mat('Pulse module | green soldermask',(.035,.22,.11),.38)
red=mat('BioAmp Patchy | red soldermask',(.5,.009,.025),.35)
yellow=mat('Pin strips | yellow insulator',(.87,.63,.008),.4)
silver=mat('Tin and nickel | soldered metal',(.57,.61,.64),.27,.88)
gold=mat('Pin contacts | plated brass',(.65,.40,.10),.24,.8)
white=mat('Board silkscreen | off white',(.8,.82,.74),.6)
foam=mat('Mounting tape | foam backing',(.68,.71,.65),.92)
tan=mat('MLCC ceramic',(.42,.28,.12),.48)
resmat=mat('Axial resistor | seafoam lacquer',(.13,.49,.35),.42)
wirem=[mat('Wire | '+n,c,.38) for n,c in [('red',(.6,.008,.025)),('white',(.72,.74,.69)),('purple',(.20,.025,.43)),('orange',(.86,.32,.018)),('green',(.008,.36,.19)),('blue',(.013,.075,.35)),('grey',(.23,.25,.25)),('yellow',(.85,.67,.01)),('brown',(.21,.12,.08))]]
# Crossed wave bump in physical object coordinates, rather than a painted grid.
n=fabric.node_tree.nodes;l=fabric.node_tree.links
coord=n.new('ShaderNodeTexCoord');wave=[]
for axis in ['X','Y']:
    w=n.new('ShaderNodeTexWave');w.bands_direction=axis;w.inputs['Scale'].default_value=3.5
    l.new(coord.outputs['Object'],w.inputs['Vector']);wave.append(w)
mix=n.new('ShaderNodeMath');mix.operation='MULTIPLY'
l.new(wave[0].outputs['Color'],mix.inputs[0]);l.new(wave[1].outputs['Color'],mix.inputs[1])
b=n.new('ShaderNodeBump');b.inputs['Distance'].default_value=.13;b.inputs['Strength'].default_value=.6
l.new(mix.outputs[0],b.inputs['Height']);l.new(b.outputs[0],n.get('Principled BSDF').inputs['Normal'])
def assign(o,name,m=None):
    o.name=name
    for c in list(o.users_collection):c.objects.unlink(o)
    C.objects.link(o)
    if m:o.data.materials.append(m)
    if P:o.parent=P
    return o
def box(name,loc,size,m,bev=.15):
    bpy.ops.mesh.primitive_cube_add(size=1,location=loc);o=assign(bpy.context.object,name,m)
    o.dimensions=size;bpy.ops.object.transform_apply(location=False,rotation=False,scale=True)
    if bev:
        mod=o.modifiers.new('Soft manufactured edges','BEVEL');mod.width=bev;mod.segments=3
        o.modifiers.new('Weighted surface normals','WEIGHTED_NORMAL')
    return o
def ell(name,loc,size,m):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=32,ring_count=20,location=loc)
    o=assign(bpy.context.object,name,m);o.scale=size;bpy.ops.object.transform_apply(location=False,rotation=False,scale=True)
    for f in o.data.polygons:f.use_smooth=True
    return o
def cyl(name,loc,r,depth,m):
    bpy.ops.mesh.primitive_cylinder_add(vertices=40,radius=r,depth=depth,location=loc)
    o=assign(bpy.context.object,name,m)
    for f in o.data.polygons:f.use_smooth=True
    mod=o.modifiers.new('Edge radius','BEVEL');mod.width=min(.12,depth*.15);mod.segments=2
    return o
def wire(name,pts,r,m,poly=False):
    cu=bpy.data.curves.new(name,'CURVE');cu.dimensions='3D';cu.resolution_u=16;cu.bevel_depth=r;cu.bevel_resolution=3
    if poly:
        sp=cu.splines.new('POLY');sp.points.add(len(pts)-1)
        for a,v in zip(sp.points,pts):a.co=(*v,1)
    else:
        sp=cu.splines.new('BEZIER');sp.bezier_points.add(len(pts)-1)
        for a,v in zip(sp.bezier_points,pts):a.co=v;a.handle_left_type='AUTO';a.handle_right_type='AUTO'
    o=bpy.data.objects.new(name,cu);C.objects.link(o);cu.materials.append(m)
    if P:o.parent=P
    return o
def txt(body,loc,size=1,m=white,rot=0):
    cu=bpy.data.curves.new(body,'FONT');cu.body=body;cu.size=size;cu.align_x='CENTER';cu.extrude=.007
    o=bpy.data.objects.new('Print | '+body,cu);C.objects.link(o);cu.materials.append(m);o.location=loc;o.rotation_euler.z=rot
    if P:o.parent=P
    return o
def ring(name,loc,outer,inner,depth,m):
    verts=[];N=48
    for z,r in [(-depth/2,outer),(depth/2,outer),(-depth/2,inner),(depth/2,inner)]:
        verts += [(loc[0]+r*math.cos(i*2*math.pi/N),loc[1]+r*math.sin(i*2*math.pi/N),loc[2]+z) for i in range(N)]
    faces=[]
    for i in range(N):
        j=(i+1)%N;faces.extend([(i,j,N+j,N+i),(N+i,N+j,3*N+j,3*N+i),(2*N+j,2*N+i,3*N+i,3*N+j),(j,i,2*N+i,2*N+j)])
    me=bpy.data.meshes.new(name);me.from_pydata(verts,[],faces);me.update()
    o=bpy.data.objects.new(name,me);C.objects.link(o);me.materials.append(m)
    if P:o.parent=P
    return o
def parent(name,loc=(0,0,0),rot=(0,0,0)):
    global P
    P=bpy.data.objects.new(name,None);C.objects.link(P);P.location=loc;P.rotation_euler=rot;return P
def contact(name,a,b,r,m):
    o=cyl(name,(Vector(a)+Vector(b))/2,r,(Vector(b)-Vector(a)).length,m)
    o.rotation_euler=(Vector(b)-Vector(a)).to_track_quat('Z','Y').to_euler();return o

# Body is constructed as a continuous sculpted surface, with curled phalanges.
bodycol=col('01 | Anatomy - closed fist and forearm')
verts=[];faces=[];N=64
sections=[(-117,27,19,53),(-103,35,23,52),(-84,33,23,51),(-65,25,21,50),(-43,28,23,50),(-20,29,24,50),(20,30,24.5,50),(40,32,26,50),(70,36,28,50),(105,39,30,50),(145,41,32,50),(168,41,32,50)]
for x,ry,rz,zc in sections:
    for i in range(N):
        t=2*math.pi*i/N;verts.append((x,ry*math.sin(t),zc+rz*math.cos(t)))
for j in range(len(sections)-1):
    for i in range(N):k=j*N+i;kk=j*N+(i+1)%N;faces.append((k,kk,kk+N,k+N))
faces.append(tuple(range(N-1,-1,-1)));faces.append(tuple((len(sections)-1)*N+i for i in range(N)))
me=bpy.data.meshes.new('Forearm and metacarpal topology');me.from_pydata(verts,[],faces);me.update()
o=bpy.data.objects.new('Body | wrist palm forearm',me);C.objects.link(o);me.materials.append(skin)
parts=[o]
for i,y in enumerate([-24,-8,9,24]):
    xx=-119+[2,-3,-1,5][i]; rr=[9.2,10,9.5,8.3][i]
    # The dorsal knuckle, vertical middle segment, and curled pad return under palm.
    parts.append(ell('Fist | metacarpal knuckle '+str(i),(xx,y,62),(14,rr,13),skin))
    parts.append(ell('Fist | proximal curled finger '+str(i),(xx-11,y,49),(rr,rr,16),skin))
    parts.append(ell('Fist | folded middle segment '+str(i),(xx-6,y,34),(13,rr*.92,9),skin))
    parts.append(ell('Fist | fingertip tucked into palm '+str(i),(xx+8,y,35),(10,rr*.88,8.4),skin))
parts.append(ell('Thumb | thenar eminence',(-85,-28,46),(22,16,18),skin))
o=ell('Thumb | proximal flexion',(-106,-33,45),(22,10,12),skin);o.rotation_euler.z=.38;parts.append(o)
o=ell('Thumb | across closed fingers',(-125,-21,41),(10,19,10),skin);o.rotation_euler.z=-.36;parts.append(o)
bpy.ops.object.select_all(action='DESELECT')
for o in parts:o.select_set(True)
bpy.context.view_layer.objects.active=parts[0];bpy.ops.object.join();body=bpy.context.object;body.name='Body | continuous closed-fist sculpt'
rem=body.modifiers.new('Unified anatomy voxel surface','REMESH');rem.mode='VOXEL';rem.voxel_size=.85
bpy.ops.object.modifier_apply(modifier=rem.name)
sm=body.modifiers.new('Relax anatomical transitions','SMOOTH');sm.factor=.7;sm.iterations=5
bpy.ops.object.modifier_apply(modifier=sm.name)
sub=body.modifiers.new('Sculpt finish','SUBSURF');sub.levels=1
for f in body.data.polygons:f.use_smooth=True
# Small nail plate on folded thumb; finger nails are tucked against the palm.
o=ell('Thumb | visible nail plate',(-133,-17,44),(1.2,7,5),nail);o.rotation_euler.z=-.35
for i,y in enumerate([-24,-8,9,24]):
    x=-125+[2,-3,-1,5][i]
    wire('Skin | knuckle transverse crease '+str(i),[(x-7,y-5,58),(x-8,y,59.3),(x-7,y+5,58)],.10,crease)
for x in [-57,-52]:
    wire('Skin | wrist flexion crease',[(x,-20,35),(x-1,-9,29.5),(x,5,29),(x+1,19,34)],.10,crease)
# Fine individual forearm hairs, sparse and naturally irregular.
hairmat=mat('Fine forearm hair',(.037,.02,.012),.85)
for i in range(145):
    x=random.uniform(45,156);t=random.uniform(-1.9,1.9);ry=32+(x-40)*.075;rz=26+(x-40)*.05
    y=ry*math.sin(t);z=50+rz*math.cos(t)
    wire('Body hair %03d'%i,[(x,y,z),(x+2,y+.4,z+1),(x+4,y+1,z+.7)],.028,hairmat)

def band(name,x0,x1,t0,t1,ry,rz,thick,m):
    V=[];F=[];nx=10;nt=max(16,int(abs(t1-t0)*35))
    for layer in [0,thick]:
        for j in range(nx+1):
            x=x0+(x1-x0)*j/nx
            for i in range(nt+1):
                t=t0+(t1-t0)*i/nt;wr=.14*math.sin(x*.27+3*t)
                V.append((x,(ry+layer+wr)*math.sin(t),50+(rz+layer+wr)*math.cos(t)))
    L=(nx+1)*(nt+1)
    for lay in [0,1]:
        for j in range(nx):
            for i in range(nt):
                a=lay*L+j*(nt+1)+i;f=(a,a+1,a+nt+2,a+nt+1);F.append(f if lay else f[::-1])
    for j in [0,nx]:
        for i in range(nt):a=j*(nt+1)+i;F.append((a,a+1,a+1+L,a+L))
    for i in [0,nt]:
        for j in range(nx):a=j*(nt+1)+i;b=a+nt+1;F.append((a,b,b+L,a+L))
    me=bpy.data.meshes.new(name);me.from_pydata(V,[],F);me.update();o=bpy.data.objects.new(name,me);C.objects.link(o);me.materials.append(m)
    for p in me.polygons:p.use_smooth=True
    return o
strapcol=col('02 | Woven strap - stitching and fastening')
band('Strap | broad elastic cuff',-38,38,-math.pi,math.pi,34,29,1.65,fabric)
band('Strap | overlapping Velcro fastening flap',-35,36,-1.25,.35,35.8,30.8,1.1,velcro)
band('Strap | reinforced transverse overlap',22,36,-1.32,.5,37,32,1.0,fabric)
for x in [-36,36]:
    pts=[(x,36*math.sin(t),50+31*math.cos(t)) for t in [i*2*math.pi/180 for i in range(181)]]
    wire('Strap | rolled bound edge',pts,.48,thread,True)
    for j in range(145):
        t=j*2*math.pi/145
        wire('Stitch | edge %s %03d'%(x,j),[(x-1,36.15*math.sin(t),50+31.15*math.cos(t)),(x-1,36.15*math.sin(t+.026),50+31.15*math.cos(t+.026))],.09,thread,True)
for x in [24,27,30,33]:band('Fastener | reinforced stitching row',x,x+.3,-1.3,.48,38.05,33.05,.1,thread)
for i in range(180):
    x=random.uniform(-33,20);t=random.uniform(-1.18,-.4)
    wire('Velcro | exposed loop %03d'%i,[(x,37*math.sin(t),50+32*math.cos(t)),(x+.3,37.4*math.sin(t+.01),50+32.4*math.cos(t+.01)),(x+.6,37*math.sin(t+.022),50+32*math.cos(t+.022))],.055,thread)
# Small sewn label visible on the real cuff.
parent('Strap | sewn fabric label',(-13,-31,68),(.8,0,0))
box('Label | black woven tag',(0,0,0),(17,6,.5),fabric,.2);txt('RAV', (0,-1,.3),2.6);P=None

# Development board: outward face is its underside, as in the worn photos.
espcol=col('03 | ESP32-S3 development board - inverted mounting')
esp=parent('ESP32 | mounted development board',(0,0,84),(0,0,0))
box('Mount | white foam tape',(0,0,-1.9),(54,22,2.5),foam,.4)
box('ESP32 | black FR4 board',(0,0,0),(63,28,1.6),pcb,.35)
txt('ESP32-S3', (0,-2,.82),2.2);txt('N16R8', (0,-5,.82),1.4)
txt('2022-V1.3', (0,-8,.82),.9)
for side in [-1,1]:
    box('ESP32 | yellow header carrier',(0,side*12.5,2),(58.7,2.5,2.4),yellow,.12)
    for i in range(23):
        x=-27.94+i*2.54
        box('ESP32 | exposed pin %s %02d'%(side,i),(x,side*12.5,6),(.55,.55,7),silver,.05)
        cyl('ESP32 | solder collar',(x,side*12.5,.88),.85,.25,silver)
        txt(str(i+1),(x,side*9.8,.82),.60,rot=math.pi/2)
# Shield, antenna and support electronics on the reverse face, retained for inspection.
box('ESP32 | RF shield underside',(17,0,-2.1),(18,17,2.4),silver,.25)
box('ESP32 | antenna substrate',(28,0,-1.1),(7,18,.5),black,.1)
wire('ESP32 | printed antenna',[(25,-7,-1.4),(30,-7,-1.4),(30,-4,-1.4),(26,-4,-1.4),(26,-1,-1.4),(30,-1,-1.4),(30,2,-1.4),(26,2,-1.4),(26,6,-1.4)],.20,gold,True)
for x in [-17,-10]:
    box('ESP32 | boot reset switch',(x,-6,-2),(4,3,2.2),silver,.2)
    cyl('ESP32 | switch plunger',(x,-6,-3.2),1.1,.5,black)
box('ESP32 | regulator',(-5,4,-1.8),(4,5,1.5),black,.2)
box('ESP32 | RGB LED',(-7,-1,-1.65),(3.5,3.5,1.2),white,.2)
for i in range(13):box('ESP32 | SMD passive',(-22+(i%6)*4,-5+(i//6)*4,-1.15),(1.8,.9,.6),tan,.08)
for yy in [-6,6]:
    # Hollow USB-C shell oriented out of board short edge.
    for z in [-3,-.9]:box('USB-C | shield wall',(-29,yy,z),(6,8.8,.3),silver,.35)
    for y in [yy-4.25,yy+4.25]:box('USB-C | shell side',(-29,y,-1.95),(6,.3,2.1),silver,.14)
    box('USB-C | receptacle tongue',(-29,yy,-2),(5,6,.55),black,.2)
P=None

def smd(name,x,y,z,m=tan,w=1.7,h=.85):
    box(name+' | package',(x,y,z),(w,h,.65),m,.08)
    for dx in [-w/2,w/2]:box(name+' | terminal',(x+dx,y,z),(w*.22,h,.65),silver,.04)
def module(kind,location,rotation):
    global P
    if kind=='optical':c=col('04 | Green MAX30100-30102 optical breakout');w,h=19,16;material=green
    elif kind=='imu':c=col('05 | Blue GY-521 MPU6050 breakout');w,h=21,16;material=blue
    else:c=col('06 | Red Muscle BioAmp Patchy and electrodes');w,h=43,15;material=red
    root=parent('Sensor | '+kind,location,rotation)
    box(kind+' | adhesive mounting backing',(0,0,-.95),(w-1,h-1,.7),foam,.2)
    box(kind+' | PCB',(0,0,0),(w,h,1.2),material,.65 if kind=='bioamp' else .18)
    if kind=='bioamp':
        for x in [-15.5,15.5]:
            cyl('BioAmp | rounded PCB ear',(x,0,0),7.3,1.2,red)
            ring('BioAmp | nickel electrode snap',(x,0,1.1),6,2,1,silver)
            ring('BioAmp | raised contact rim',(x,0,1.7),4.8,4.2,.35,silver)
            cyl('BioAmp | recessed contact',(x,0,.9),1.7,.45,silver)
            for j in range(36):
                t=j*math.pi/18;cyl('BioAmp | crimp detail',(x+5.3*math.cos(t),5.3*math.sin(t),1.7),.17,.14,silver)
        txt('MUSCLE BIOAMP PATCHY',(0,5.6,.64),1.05)
        txt('Upside Down Labs',(0,-5.8,.64),1)
        for i,t in enumerate(['VCC','GND','OUT','REF']):
            x=-4.2+i*2.8;ring('BioAmp | connector plated hole',(x,-3,.65),.8,.43,.18,silver);txt(t,(x,-1,.64),.67,rot=math.pi/2)
    else:
        for x in [-w/2+2,w/2-2]:ring(kind+' | mounting annulus',(x,h/2-2,.65),1.5,.95,.15,silver)
        box(kind+' | sensor IC',(0,0,1.25),(5.6 if kind=='optical' else 4,3.3 if kind=='optical' else 4,1.25),black,.16)
        if kind=='optical':
            for x,m in [(-1.8,gold),(1.5,black)]:box('Optical | emitter detector window',(x,0,1.9),(1.2,2.0,.12),m,.12)
            txt('MAX30100/30102',(0,5.3,.65),.82)
        else:txt('MPU-6050',(0,5.3,.65),1)
        for i in range(7):smd(kind+' passive '+str(i),-6+(i%4)*4,3.5 if i<4 else -3.4,1.0,yellow if i%3==0 else tan)
        for i in range(6 if kind=='optical' else 8):
            x=-7+i*2;ring(kind+' | solder pad',(x,-6,.7),.68,.3,.25,silver)
            box(kind+' | black header',(x,-6,-2),(1.9,2,2.4),black,.12)
            box(kind+' | pin',(x,-6,-3.6),(.5,.5,1.7),silver,.04)
    P=None
    root['identification']='Photo-visible shape; exact board dimensions approximate'
    return c,root
# Inner face placements follow the flat reference: green - red - blue along the band.
# Positive local Z points toward skin. Modules stay in the cuff/body clearance.
optcol,opt=module('optical',(0,-28,42),(1.93,0,0))
biocol,bio=module('bioamp',(0,0,23),(math.pi,0,math.pi/2))
imucol,imu=module('imu',(0,28,42),(-1.93,0,0))

wirecol=col('07 | Prototype jumper harness and taped junctions')
# Routes wrap outside the textile edge before rising into the photographed loose loops.
ends=[]
for j,mod in enumerate([opt,bio,imu]):
    bpy.context.view_layer.update()
    for k in range([4,4,5][j]):
        m=wirem[[0,1,2,3,4,5,6,0,1,8,5,1,0][len(ends)]]
        start=mod.matrix_world @ Vector((-6+k*3,-6,-3.4 if j!=1 else -.4))
        # Exit sideways along the board back, then around the edge of the cuff.
        edge=Vector((-43-k*.8,start.y,start.z))
        theta=[-1.8,math.pi,1.8][j]
        mid=Vector((-48-k*1.6,44*math.sin(theta),50+36*math.cos(theta)))
        side=-1 if j<2 else 1
        end=Vector((-25+(j*4+k)*3.65,side*12.5,94))
        top=Vector((-48-k*2,side*(42+j*4),113+k*4+j*4))
        pts=[start,edge,mid,(-60-k,side*43,70),top,(-12+k*3,side*25,122+j*4),end]
        o=wire('Jumper | %02d photo-style loose loop'%len(ends),pts,.63,m)
        o['endpoint_from']=mod.name;o['endpoint_to']='ESP32 header visual; pin mapping not inferred'
        box('Jumper | female Dupont housing',end-Vector((0,0,3)),(2.3,2.4,7),black,.2)
        # Both terminals are represented rather than wires ending in air.
        ell('Jumper | solder joint',start,(.9,.8,.65),silver)
        ends.append((start,end))
for loc in [(-52,-40,81),(-58,34,86)]:
    o=box('Harness | folded electrical tape junction',loc,(15,9,1.2),tape,.5);o.rotation_euler=(.4,.6,.2)
    for i in range(3):wire('Tape | folded edge',[(loc[0]-7,loc[1]-4+i,loc[2]+.8),(loc[0],loc[1]-4+i,loc[2]+1),(loc[0]+7,loc[1]-4+i,loc[2]+.6)],.1,black)
# Exposed hand-soldered axial resistors, as visible near the controller.
rescol=col('08 | Hand-soldered axial resistors and bare leads')
for i in range(4):
    a=Vector((-12+i*5,18,117+i*2));b=a+Vector((1,0,6))
    contact('Resistor %d | green axial body'%i,a,b,1.25,resmat)
    for j,m in enumerate([yellow,wirem[2],wirem[8],gold]):
        q=a.lerp(b,.15+j*.20);contact('Resistor %d | color band'%i,q,q+Vector((.10,0,.58)),1.28,m)
    tail=Vector((-16+i*5,12.5,91));other=Vector((-35+i*4,25,120+i*2))
    wire('Resistor | bent bare lead to header',[tail,(-16+i*5,18,108),a],.24,silver)
    wire('Resistor | twisted exposed junction',[b,b+Vector((0,0,2)),other],.24,silver)
    wire('Resistor | insulated connection',[other,(-46+i,32,126),(-50+i*2,40,82)],.62,wirem[i])
    ell('Resistor | soldered splice',other,(.8,.8,.8),silver)
# Probe and cable visible in the references; no battery is added.
probecol=col('09 | Temperature probe and connected USB cable')
contact('Probe | stainless temperature sleeve',(-49,31,51),(-68,34,57),2.3,silver)
contact('Probe | black strain relief',(-43,30,49),(-49,31,51),2.6,black)
wire('Probe | black cable',[(-43,30,49),(-48,45,58),(-31,47,89),(0,37,108)],1.75,black)
for i in range(3):wire('Probe | breakout conductor',[(0,37,108),(-4+i*3,31,112),(-13+i*5,18,117+i*2)],.55,wirem[i])
box('USB | inserted plug',(-35,-6,82),(9,8,4),black,.8)
wire('USB | power and data cable',[(-39,-6,82),(-47,-16,87),(-58,-44,73),(-5,-62,55),(82,-72,34),(125,-82,24)],2.0,black)

studio=col('10 | Studio and presentation cameras')
floor=box('Studio | warm neutral surface',(0,0,8),(2000,2000,2),mat('Studio | stone grey',(.19,.205,.21),.88),0)
def camera(name,pos,target,scale):
    d=bpy.data.cameras.new(name);o=bpy.data.objects.new(name,d);C.objects.link(o);o.location=pos
    o.rotation_euler=(Vector(target)-o.location).to_track_quat('-Z','Y').to_euler()
    d.type='ORTHO';d.ortho_scale=scale;d.clip_end=10000;return o
hero=camera('CAM | wrist and closed fist',(-285,-360,305),(-8,0,62),365)
macro=camera('CAM | controller and prototype wiring',(-150,-240,230),(-8,0,84),183)
under=camera('CAM | inner sensor arrangement',(-165,-190,-175),(0,0,45),165)
for name,pos,power,size in [('Key',(-150,-180,360),1700000,230),('Fill',(90,100,260),1300000,200),('Rim',(0,230,200),1900000,170)]:
    d=bpy.data.lights.new(name,'AREA');d.energy=power;d.shape='DISK';d.size=size
    o=bpy.data.objects.new(name,d);C.objects.link(o);o.location=pos;o.rotation_euler=(Vector((0,0,60))-o.location).to_track_quat('-Z','Y').to_euler()
S.world=bpy.data.worlds.new('Neutral studio environment');S.world.use_nodes=True
S.world.node_tree.nodes['Background'].inputs[0].default_value=(.32,.36,.42,1)
S.world.node_tree.nodes['Background'].inputs[1].default_value=.4
S.render.engine='CYCLES';S.cycles.samples=48;S.cycles.use_denoising=True
try:
    pref=bpy.context.preferences.addons['cycles'].preferences;pref.compute_device_type='OPTIX';pref.get_devices()
    for d in pref.devices:d.use=d.type=='OPTIX'
    S.cycles.device='GPU'
except Exception:pass
S.render.resolution_x=1800;S.render.resolution_y=1300;S.render.resolution_percentage=100
S.view_settings.view_transform='AgX';S.camera=hero
S['source']='Eleven user photographs dated 2026-09-19; duplicate angles reviewed.'
S['scale_note']='Nominal 2.54 mm header pitch used as scale cue; strap/body and board envelopes approximate.'
S['limitations']='Photographs do not show complete fist, hidden routing or reliable resistor values. Anatomy and hidden paths approximated; not manufacturing CAD.'
notes=bpy.data.texts.new('READ ME | Phase-1 reference interpretation')
notes.write('Phase-1 original fabric cuff, not the custom-PCB designs.\nPhotos show black elastic cuff, red Muscle BioAmp Patchy with two snap contacts, green MAX30100/30102 breakout, blue MPU6050/GY521, inverted ESP32-S3 dual USB-C development board with yellow headers, axial resistor network, electrical tape, jumper harness and metal temperature probe.\nWorn views hide the skin-facing boards. To inspect them hide collection 01 Anatomy, then use the inner sensor camera.\nAll sizes except the nominal header pitch are visual approximations. Exact resistor values and hidden pin mapping cannot be established from photos. No battery, microSD, custom carrier PCB or additional sensor invented.\n')
# Pack all supplied photographic references without adding planes to the render.
refs=col('11 | Packed photo references - hidden')
paths=sorted(Path('C:/Users/Aditya Pandey/Downloads').glob('WhatsApp Image 2026-09-19 at 15.*.jpeg'))
for path in paths:
    if any(s in path.name for s in ['15.58.08','15.57.59','15.27.18','15.27.07','15.27.01','15.23.09','15.23.08']):
        im=bpy.data.images.load(str(path),check_existing=True);im.pack()
        ob=bpy.data.objects.new('Reference | '+path.stem,None);refs.objects.link(ob);ob.empty_display_type='IMAGE';ob.data=im;ob.hide_render=True;ob.hide_set(True)
refs.hide_render=True
# Clear selection and set an opening viewport matching the hero composition.
bpy.ops.object.select_all(action='DESELECT')
for area in bpy.context.screen.areas:
    if area.type=='VIEW_3D':
        area.spaces.active.region_3d.view_perspective='CAMERA'
        area.spaces.active.clip_end=10000
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'G-one-Phase-1-Strap.blend'))
print('PHASE1_SAVED',len(bpy.data.objects),flush=True)
S.render.resolution_percentage=60;S.cycles.samples=24
S.render.filepath=str(OUT/'phase1-preview.png');bpy.ops.render.render(write_still=True)
print('PREVIEW_COMPLETE',flush=True)
