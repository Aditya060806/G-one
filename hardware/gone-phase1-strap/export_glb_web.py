"""Export the saved hardware to self-contained GLB without modifying either .blend.
Run with Blender --background --python export_glb.py. Dimensions become metres.
Procedural microscopic bump is omitted; geometry markings and PBR values survive.
"""
import bpy, json, hashlib, struct
from pathlib import Path
from mathutils import Matrix, Vector

OUT = Path(__file__).resolve().parent
REPORT = {}

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def bounds(objects):
    pts = [o.matrix_world @ Vector(v) for o in objects for v in o.bound_box]
    return [[min(p[i] for p in pts) for i in range(3)],
            [max(p[i] for p in pts) for i in range(3)]]

for stem in ['G-one-Phase-1-Strap']:
    source = OUT / (stem + '.blend')
    before = digest(source)
    bpy.ops.wm.open_mainfile(filepath=str(source))
    scene = bpy.data.scenes['01 | Worn Phase-1 prototype']
    bpy.context.window.scene = scene
    candidates = [o for o in scene.objects
                  if o.type in {'MESH', 'CURVE', 'FONT', 'SURFACE'}
                  and not any(c.name.startswith(('10 |', '11 |')) for c in o.users_collection)]
    assert candidates
    original_board = next(o for o in candidates if o.parent and o.parent.name == 'ESP32 | mounted development board' and o.type == 'MESH')
    board_name = original_board.name
    expected_board = [v * .001 for v in original_board.dimensions]
    deps = bpy.context.evaluated_depsgraph_get()
    snapshots = []
    for o in candidates:
        mesh = bpy.data.meshes.new_from_object(o.evaluated_get(deps), preserve_all_data_layers=True, depsgraph=deps)
        if not mesh.polygons:
            bpy.data.meshes.remove(mesh)
            continue
        mesh.transform(Matrix.Scale(.001, 4) @ o.matrix_world)
        mesh.update()
        snapshots.append((o.name, mesh, o.users_collection[0].name, dict(o.items())))
    # Build a hardware-only export scene in memory. No source file is saved.
    for o in list(bpy.data.objects):
        bpy.data.objects.remove(o, do_unlink=True)
    for c in list(bpy.data.collections):
        bpy.data.collections.remove(c)
    scene.unit_settings.system = 'METRIC'
    scene.unit_settings.scale_length = 1
    root = bpy.data.objects.new(stem, None)
    scene.collection.objects.link(root)
    root['units'] = 'metres'
    root['source_file'] = source.name
    root['purpose'] = 'Phase-1 strap prototype with closed fist; approximate photo-derived visualization'
    groups = {}
    hardware = []
    for name, mesh, group, props in snapshots:
        if group not in groups:
            g = bpy.data.objects.new(group, None)
            scene.collection.objects.link(g)
            g.parent = root
            groups[group] = g
        o = bpy.data.objects.new(name, mesh)
        scene.collection.objects.link(o)
        o.parent = groups[group]
        for key, value in props.items():
            if isinstance(value, (str, int, float, bool)):
                o[key] = value
        hardware.append(o)
    # Lower tessellation on dense curved parts, then consolidate by physical group.
    for o in hardware:
        if len(o.data.polygons) > 1500:
            bpy.context.view_layer.objects.active = o
            mod = o.modifiers.new('Web surface reduction', 'DECIMATE')
            mod.ratio = .25
            bpy.ops.object.modifier_apply(modifier=mod.name)
    combined = []
    for group in groups.values():
        members = [o for o in scene.objects if o.type == 'MESH' and o.parent == group]
        if not members: continue
        bpy.ops.object.select_all(action='DESELECT')
        for o in members: o.select_set(True)
        bpy.context.view_layer.objects.active = members[0]
        bpy.ops.object.join()
        o = bpy.context.object
        o.name = group.name + ' | web geometry'
        combined.append(o)
    hardware = combined
    # Portable PBR materials.
    used = {m for o in hardware for m in o.data.materials if m}
    for m in used:
        p = next((n for n in m.node_tree.nodes if n.type == 'BSDF_PRINCIPLED'), None) if m.use_nodes else None
        color = tuple(p.inputs['Base Color'].default_value) if p else tuple(m.diffuse_color)
        rough = p.inputs['Roughness'].default_value if p else .5
        metal = p.inputs['Metallic'].default_value if p else 0
        m.use_nodes = True
        m.node_tree.nodes.clear()
        p = m.node_tree.nodes.new('ShaderNodeBsdfPrincipled')
        p.inputs['Base Color'].default_value = color
        p.inputs['Metallic'].default_value = metal
        p.inputs['Roughness'].default_value = rough
        out = m.node_tree.nodes.new('ShaderNodeOutputMaterial')
        m.node_tree.links.new(p.outputs['BSDF'], out.inputs['Surface'])
    expected_bounds = bounds(hardware)
    for o in scene.objects:
        o.select_set(True)
    target = OUT / (stem + '-web.glb')
    bpy.ops.export_scene.gltf(filepath=str(target), export_format='GLB',
        use_selection=True, export_apply=False, export_yup=True,
        export_materials='EXPORT', export_extras=True,
        export_cameras=False, export_lights=False, export_animations=False)
    blob = target.read_bytes()
    magic, version, total = struct.unpack_from('<4sII', blob)
    assert magic == b'glTF' and version == 2 and total == len(blob)
    length, kind = struct.unpack_from('<I4s', blob, 12)
    assert kind == b'JSON'
    doc = json.loads(blob[20:20+length])
    assert not doc.get('cameras')
    assert not any('uri' in b for b in doc.get('buffers', []))
    assert len(doc['meshes']) == len(hardware)
    assert not any(n.get('name', '').startswith(('Studio |', 'CAM |')) for n in doc['nodes'])
    triangles = sum(doc['accessors'][p['indices']]['count']//3 for m in doc['meshes'] for p in m['primitives'])
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(target))
    imported = [o for o in bpy.context.scene.objects if o.type == 'MESH']
    actual_bounds = bounds(imported)
    max_error = max(abs(a-b) for x,y in zip(expected_bounds,actual_bounds) for a,b in zip(x,y))
    assert max_error < 1e-6, (expected_bounds, actual_bounds)
    assert len(imported) == len(groups)
    board = imported[0]

    assert all(len(o.data.materials) for o in imported)
    assert digest(source) == before, 'Source .blend changed'
    REPORT[stem] = dict(file=target.name, bytes=len(blob), meshes=len(imported),
        triangles=triangles, materials=len(doc['materials']),
        bounds_metres=actual_bounds, geometry_optimized=True,
        roundtrip_max_error_metres=max_error, source_sha256=before,
        source_unchanged=True, self_contained=True,
        material_note='Portable PBR colors, metallic and roughness; procedural micro-bump omitted.')
    print('EXPORT_VALIDATED', json.dumps(REPORT[stem]), flush=True)

(OUT / 'glb-web-export-report.json').write_text(json.dumps(REPORT, indent=2), encoding='utf-8')
print('PHASE1_EXPORT_VALIDATED', flush=True)



