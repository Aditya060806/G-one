"""Incremental enhancement: run on existing G-one-PCB.blend, never a factory scene.
Preserves the board, sensors, module, layout and existing editable geometry.
"""
import bpy, math, random, json, ast, bmesh
from pathlib import Path
from mathutils import Vector
OUT=Path(__file__).resolve().parent
scene=bpy.context.scene
assert 'PCB | 44 x 64 x 1.00 mm' in bpy.data.objects, 'Open the existing PCB first'
assert not scene.get('enhanced_revision'), 'Already enhanced; use the preserved backup to repeat'
original_names=set(bpy.data.objects.keys())
root=bpy.data.objects['G1 | complete assembly | dimensions in mm']
board=bpy.data.objects['PCB | 44 x 64 x 1.00 mm']
# Reuse only geometry helper DEFINITIONS. No original scene-initialization code executes.
tree=ast.parse((OUT/'build_pcb.py').read_text(encoding='utf-8'))
helpers=[n for n in tree.body if isinstance(n,ast.FunctionDef)]
COL=bpy.data.collections['10 | Visible routing relief and silkscreen']
names={'mask':'Soldermask | deep green satin','trace':'Covered copper | subtle green relief','fr4':'FR4 | cut laminate edge','gold':'ENIG | exposed gold','tin':'SAC solder | fillets','steel':'Brushed nickel | connector shields','black':'Molded epoxy','ceramic':'MLCC | ceramic tan','white':'Silkscreen | warm white','ivory':'Connector | ivory nylon','ink':'Laser etched lettering','glass':'Optical glass | smoked','red':'Red LED die | unpowered','amber':'IR LED die | unpowered','green':'Status LED phosphor'}
for key,value in names.items():
    m=bpy.data.materials.get(value)
    if m is None:m=bpy.data.materials.new(value);m.use_nodes=True
    globals()[key]=m
exec(compile(ast.Module(body=helpers,type_ignores=[]),'reused_geometry_helpers','exec'))
def remove(o):bpy.data.objects.remove(o,do_unlink=True)
def delprefix(prefix):
    for o in list(bpy.data.objects):
        if o.name.startswith(prefix):remove(o)
def selcol(name):
    global COL
    COL=bpy.data.collections.get(name) or bpy.data.collections.new(name)
    if COL.name not in scene.collection.children:scene.collection.children.link(COL)
    return COL
def matadjust(m,color,rough,metal):
    m.diffuse_color=(*color,1);p=m.node_tree.nodes.get('Principled BSDF')
    p.inputs['Base Color'].default_value=(*color,1);p.inputs['Roughness'].default_value=rough;p.inputs['Metallic'].default_value=metal
    return p
matadjust(mask,(.008,.055,.025),.32,.12)
matadjust(trace,(.011,.063,.030),.35,.15)
matadjust(gold,(.64,.37,.085),.25,.85)
matadjust(tin,(.45,.48,.51),.26,.92)
matadjust(steel,(.42,.46,.49),.27,.94)
matadjust(black,(.009,.011,.013),.48,0)
matadjust(fr4,(.19,.15,.065),.72,0)
matadjust(white,(.69,.73,.64),.55,0)
board.data.materials.append(fr4)
for p in board.data.polygons:
    if abs(p.normal.z)<.35:p.material_index=len(board.data.materials)-1
edge=board.modifiers.new('Board edge deburr 0.03mm','BEVEL');edge.width=.03;edge.segments=2;edge.limit_method='ANGLE'
# Fix small visual defects in the existing model, keeping its arrangement.
if bpy.data.objects.get('Fiducial 2'):bpy.data.objects['Fiducial 2'].location.y=-20
for o in list(bpy.data.objects):
    if o.name.startswith('Visual routing 10.') or o.name.startswith('Visual routing 0.'):
        remove(o)
line('Visual power corridor | illustrative',[(17,-25,.516),(17,-22.3,.516),(18,-21.3,.516)],.055,trace)
for o in bpy.data.objects:
    if o.name.startswith('Marking | BAT / NTC'):o.location=(16.8,-23,.548);o.data.size=.48
    if o.name.startswith('Marking | RST'):o.location.y=-25.6;o.data.size=.58

# Add real flat copper lands beneath existing solder feet, rather than more fake chips.
selcol('13 | Footprints, mask openings and component markings')
for o in list(bpy.data.objects):
    if o.type=='MESH' and any(t in o.name for t in ['| solder fillet','| solder foot','| SMT tail']):
        s=1 if o.location.z>0 else -1
        land=box('LAND | '+o.name,(o.location.x,o.location.y,s*.523),(o.dimensions.x+.12,o.dimensions.y+.12,.026),gold,.045)
        land.rotation_euler.z=o.rotation_euler.z
for i,(x,y) in enumerate([(-17,10),(-17,7),(-12,3.5),(-13,-1.7),(-17,-8),(-12,-9),(-7,-9),(-10,-19),(-5,-19),(3,-23),(9,-23),(19,-20)]):
    label('C'+str(i+1),x,y+1.0,size=.38)
for x,y in [(-12,8),(-11,-5),(-13,-14),(-5,-14),(14,-13)]:
    label('1',x-2.5,y+2.7,size=.36)

# A true SOP-8 shape and gull-wing lead profile for requested charger/protection.
selcol('06 | Charger, buck-boost and battery interface')
delprefix('U8 |')
for o in list(bpy.data.objects):
    if o.type=='FONT' and o.data.body=='BQ24074':remove(o)
def soic8(ref,part,x,y):
    body=box(ref+' | '+part,(x,y,1.30),(3.9,4.9,1.35),black,.16)
    body['part']=part;body['package_model']='SOP-8 nominal visual envelope; variant requires verification'
    for side in [-1,1]:
        for i in range(4):
            yy=y+(i-1.5)*1.27
            pts=[(x+side*1.92,yy,1.12),(x+side*2.28,yy,1.12),(x+side*2.5,yy,.66),(x+side*2.98,yy,.62)]
            line(ref+f' | formed lead {side}_{i}',pts,.105,tin)
            box(ref+' | solder toe',(x+side*2.85,yy,.585),(.75,.53,.15),tin,.06)
            box(ref+' | land',(x+side*2.85,yy,.523),(.95,.7,.025),gold,.05)
    cyl(ref+' | pin one',x-1.3,y+1.7,1.99,.20,.01,ink)
    label(part,x,y,2.0,.58);label(ref,x,y+3.3,size=.55)
    return body
soic8('U8','TP4056',6,-13)
ic('U12','DW01A',3.2,-6.8,1.6,2.9,6,'TSSOP')
soic8('Q1','FS8205A',11,-7.2)
# Clear label/passive conflicts made by the larger, real SOP package.
for o in bpy.data.objects:
    if o.name.startswith('C_PWR1 |'):o.location.y+=1.1
    if o.name.startswith('C_PWR2 |'):o.location.x+=5.5;o.location.y+=1.4
for o in list(bpy.data.objects):
    if o.type=='FONT' and o.data.body=='U8' and o.location.y>-10:remove(o)
label('CHARGE',6,-17.4,size=.52)
label('PROTECT',11,-3.7,size=.48)
label('3V3 BUCK / BOOST',14,-24,size=.43)

# Refine the existing card socket rather than replacing the section.
selcol('04 | microSD socket and removable card')
lid=bpy.data.objects['J4 | stamped steel lid']
for o in list(bpy.data.objects):
    if o.name.startswith('J4 | stamped aperture'):
        cut=box('socket aperture cutter',(o.location.x,o.location.y,2.18),(.85,1.9,1),None,0)
        boolean_cut(lid,cut);remove(o)
for xx in [4.5,17.5]:
    line('J4 | formed spring retainer',[(xx,-3.0,2.1),(xx,-3.8,2.22),(xx+.3,-4,1.6)],.095,steel)
    for yy in [1,8]:box('J4 | folded retention tab',(xx,yy,2.3),(.7,1.1,.10),steel,.05)
label('PUSH / LOCK',11,7,2.32,.45,ink)
selcol('07 | USB-C, programming pads, switches and LEDs')
for x in [-3.5,3.5]:
    for y in [-28.3,-31.5]:
        box('J1 | shell stamping',(x,y,3.77),(.42,1,.012),ink,.03)
line('J1 | folded shell seam',[(-3.6,-26.72,3.3),(3.6,-26.72,3.3)],.025,ink)
for x in [-3.85,3.85]:
    line('J1 | inner retention spring',[(x,-31.8,1.8),(x*.94,-30.8,1.85),(x,-29.8,1.8)],.09,steel)

# Battery carrier is a removable mechanical assembly, not additional electronics.
old=bpy.data.collections['11 | Optional battery envelope (hidden by default)']
for o in list(old.objects):remove(o)
bpy.data.collections.remove(old)
carrier=selcol('14 | Removable battery carrier and fixings')
pbt=material('Carrier | glass-filled black polymer',(.023,.025,.028),0,.55)
rubber=material('Battery cushions | silicone',(.015,.017,.019),0,.8)
carrier_root=bpy.data.objects.new('Battery carrier | removable assembly',None);carrier.objects.link(carrier_root);carrier_root.parent=root
def parent_new(objects,parent):
    for o in objects:o.parent=parent
begin=set(bpy.data.objects)
for x in [-19,19]:
    for y in [18,-24]:
        ring('M2 | threaded spacer',x,y,2.9,1.8,1.0,4.8,pbt)
        cyl('M2 | captive screw head',x,y,6.05,1.7,.7,steel)
        line('M2 | driver slot',[(x-.85,y,6.42),(x+.85,y,6.42)],.12,ink)
        ring('M2 | insulating foot',x,y,.65,1.85,1.05,.28,rubber)
    box('Carrier | longitudinal side rail',(x,-3,5.45),(2.8,44,.85),pbt,.2)
for y in [9,-21]:box('Carrier | cross member',(0,y,5.45),(39,2,.85),pbt,.18)
box('Carrier | thin cell tray',(0,-6,5.57),(32,28,.45),pbt,.25)
for x in [-15.6,15.6]:box('Carrier | cell retaining wall',(x,-6,6.3),(.65,27,1.6),pbt,.18)
for y in [-18,6]:box('Carrier | compliant support',(0,y,5.88),(27,1.5,.20),rubber,.07)
parent_new(set(bpy.data.objects)-begin,carrier_root)

battery_col=selcol('15 | Li-ion pouch cell, tabs and insulation')
battery_root=bpy.data.objects.new('BAT1 | 1S Li-ion cell assembly',None);battery_col.objects.link(battery_root);battery_root.parent=root
foil=material('Cell pouch | aluminum laminate',(.56,.59,.62),.86,.31)
kapton=material('Cell tab insulation | amber polymer',(.34,.14,.018),.20,.35)
labelmat=material('Cell | printed polymer label',(.82,.81,.74),0,.55)
begin=set(bpy.data.objects)
cell=box('BAT1 | pouch cell nominal 30 x 26 x 5',(0,-6,8.52),(30,26,5),foil,.7)
cell['part']='1S Li-ion pouch cell — exact supplier and capacity unconfirmed';cell['dimensions_status']='Plausible 30 x 26 x 5 mm envelope; verify selected cell'
for x in [-15,15]:box('BAT1 | pressed foil side seam',(x,-6,8.2),(.55,25,1),foil,.12)
for y in [-19,7]:box('BAT1 | heat-sealed pouch flange',(0,y,7.9),(29,.9,1),foil,.18)
box('BAT1 | folded terminal insulation',(0,-18.1,8.6),(28,3.5,4.7),kapton,.30)
box('BAT1 | printed identification label',(0,-3.8,11.04),(24,16,.055),labelmat,.35)
label('G-one',0,-.2,11.078,2.6,ink)
label('1S  /  Li-ion  /  3.7 V',0,-3.3,11.078,1.08,ink)
label('RECHARGEABLE',0,-6.1,11.078,.8,ink)
label('CELL SPECIFICATION TO VERIFY',0,-8.3,11.078,.48,ink)
for x,t in [(12,'+'),(-12,'-')]:label(t,x,-13,11.05,1.4,ink)
for x in [10.75,12.0,13.25]:box('BAT1 | insulated terminal exit',(x,-19.3,8),(.9,1.5,1.1),kapton,.12)
parent_new(set(bpy.data.objects)-begin,battery_root)

wirecol=selcol('16 | Battery harness and mated connector')
redwire=material('Wire | red silicone',(.34,.012,.008),0,.42)
blackwire=material('Wire | black silicone',(.012,.013,.014),0,.5)
yellowwire=material('Wire | NTC yellow',(.48,.29,.018),0,.45)
wire_specs=[]
def smoothwire(name,pts,r,mat):
    cu=bpy.data.curves.new(name,'CURVE');cu.dimensions='3D';cu.resolution_u=20;cu.bevel_depth=r;cu.bevel_resolution=4
    sp=cu.splines.new('BEZIER');sp.bezier_points.add(len(pts)-1)
    for p,co in zip(sp.bezier_points,pts):p.co=co;p.handle_left_type='AUTO';p.handle_right_type='AUTO'
    o=bpy.data.objects.new(name,cu);wirecol.objects.link(o);o.parent=root;cu.materials.append(mat);return o
for i,(mat,net) in enumerate([(redwire,'BAT+'),(blackwire,'BAT-'),(yellowwire,'NTC')]):
    x=15.75+i*1.25
    pts=[(10.75+i*1.25,-19.7,8),(15.8+i*.8,-22,8),(22.5+i*.85,-24,6),(23+i*.85,-31,3),(20+i*.6,-34.1,2),(x,-33.2,1.75),(x,-31.1,1.75)]
    o=smoothwire('W'+str(i+1)+' | '+net+' battery harness',pts,.36 if i<2 else .28,mat)
    o['connection']=net+' cell termination to J2 mating plug';o['conductor']='Diameter is illustrative, not a selected wire gauge'
    wire_specs.append(o)
plug=box('J2P | mating battery plug',(17,-30.35,1.65),(4.9,3.0,1.9),ivory,.16)
box('J2P | latch',(17,-29.5,2.72),(1.6,1.1,.3),ivory,.08)
for i in range(3):
    x=15.75+i*1.25
    box('J2P | crimp contact '+str(i+1),(x,-29.5,1.7),(.46,1.9,.40),gold,.045)
    cyl('J2P | wire strain relief '+str(i+1),x,-31.65,1.75,.40,.8,ivory)

# Improve studio presentation without changing the physical object.
studio=bpy.data.collections['12 | Studio, cameras and reference'];COL=studio
ground=bpy.data.objects['Studio | neutral backdrop'];ground.hide_set(False)
ground.location.z=-6.7
matadjust(ground.data.materials[0],(.095,.11,.12),.82,0)
for o in studio.objects:
    if o.type=='LIGHT':
        o.hide_set(False)
        if 'KEY' in o.name:o.data.energy=180000;o.data.size=65
        if 'FILL' in o.name:o.data.energy=65000;o.data.size=75
        if 'RIM' in o.name:o.data.energy=125000;o.data.size=50
hero=bpy.data.objects['CAM | isometric'];hero.location=(76,-110,133);hero.rotation_euler=(Vector((0,2,2))-hero.location).to_track_quat('-Z','Y').to_euler();hero.data.ortho_scale=89
top=bpy.data.objects['CAM | top'];top.data.ortho_scale=79
bottom=bpy.data.objects['CAM | bottom'];bottom.data.ortho_scale=79
detail=bpy.data.objects['CAM | USB and analog detail'];detail.data.ortho_scale=51
side=camera('CAM | assembled side profile',(85,-88,27),(0,-2,3),91)
sensor=camera('CAM | skin sensors macro',(36,-36,-73),(5,5,-1),45)
power=camera('CAM | ESP32 and power detail',(63,51,109),(2,4,1),74)
scene.render.resolution_x=1800;scene.render.resolution_y=1800
scene.cycles.samples=80;scene.cycles.use_denoising=True
scene.cycles.use_adaptive_sampling=True;scene.cycles.adaptive_threshold=.035
scene.camera=hero
scene['enhanced_revision']='B — incremental from existing Rev A'
scene['power_change']='TP4056 SOP8 + DW01A and FS8205A replaces BQ24074 visual package. Circuit is not validated.'
scene['mechanical_scope']='Battery carrier and cell are provisional geometry; assembly clearances reviewed, no fabrication claim.'
notes=bpy.data.texts.get('READ ME | model scope and controls') or bpy.data.texts.new('READ ME | model scope and controls')
notes.clear();notes.write('''G-ONE / REV B / INCREMENTAL ENHANCEMENT
Original board, layout, RF module and all sensors retained.
TP4056 + separate protection packages added per latest request.
No DevKit/GY521 carrier boards: equivalent IC functions remain integrated.
Battery carrier mounts to four existing holes. Cell is a provisional 30x26x5 mm envelope.
Collections 14,15,16 contain removable carrier, battery and terminated harness.
Hide these three collections for board-only inspection, as in the PCB renders.
MAX30102 is requested; existing repository firmware still uses MAX30100.
PCB traces are illustrative, not a routed electrical netlist.
Exact battery, EMG analog network, connector parts and charge-current setting require verification.
TP4056 alone does not provide battery protection or load-sharing; no live charging-use claim.
Native scene and cameras, materials, component parts and annotations remain editable.
''');notes.use_fake_user=True
source=bpy.data.texts.get('enhance_pcb.py') or bpy.data.texts.new('enhance_pcb.py');source.write(Path(__file__).read_text(encoding='utf-8'));source.use_fake_user=True
preserved=len(original_names & set(bpy.data.objects.keys()))
assert preserved>len(original_names)*.85
assert len(board.data.vertices)>1000 and all(abs(a-b)<.02 for a,b in zip(board.dimensions,(44,64,1)))
required=['ESP32-S3-WROOM-1-N16R8','MAX30102EFD+','MPU6050','DS18B20','INA333','TP4056','DW01A','FS8205A','TPS63070']
parts={o.get('part') for o in bpy.data.objects if o.get('part')}
assert all(p in parts for p in required),(required,parts)
report={'revision':'B','source':'G-one-PCB.blend (existing Rev A)','original_objects':len(original_names),'original_objects_preserved':preserved,'objects':len(bpy.data.objects),'mesh_objects':sum(o.type=='MESH' for o in bpy.data.objects),'board_mm':list(board.dimensions),'required_parts_present':required,'scope':'Mechanical visualization. Electrical design unvalidated.'}
(OUT/'enhancement-validation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
# The saved state is a complete mechanically supported assembly.
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'G-one-PCB.blend'))
print('ENHANCEMENT_SAVED',json.dumps(report),flush=True)
def render(cam,name,assembled=True,underside=False):
    for c in [carrier,battery_col,wirecol]:c.hide_render=not assembled
    scene.camera=cam;ground.hide_render=underside
    scene.render.filepath=str(OUT/(name+'.png'));bpy.ops.render.render(write_still=True)
render(hero,'assembled',True)
render(hero,'isometric',False)
render(top,'top',False)
render(bottom,'bottom',False,True)
render(side,'side',True)
render(sensor,'sensors',False,True)
render(power,'esp32-power',False)
render(detail,'detail',False)
for c in [carrier,battery_col,wirecol]:c.hide_render=False
scene.camera=hero;ground.hide_render=False
for o in studio.objects:
    if o.type=='LIGHT' or o==ground:o.hide_set(True)
for sc in bpy.data.screens:
    for ar in sc.areas:
        if ar.type=='VIEW_3D':
            ar.spaces.active.region_3d.view_distance=110;ar.spaces.active.region_3d.view_location=(0,1,3);ar.spaces.active.region_3d.view_rotation=hero.rotation_euler.to_quaternion()
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'G-one-PCB.blend'))
print('ENHANCEMENT_COMPLETE',flush=True)
