"""Render a verified GLB in a fresh local Blender process; never execute imported scripts."""
import bpy, json, math, sys
from pathlib import Path
from mathutils import Vector, Euler, Quaternion

request_path = Path(sys.argv[sys.argv.index('--') + 1])
request = json.loads(request_path.read_text(encoding='utf-8'))
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=request['input'])
scene = bpy.context.scene
imported_objects=list(scene.objects)
objects = {str(obj.get('hf_id')): obj for obj in scene.objects if obj.get('hf_id')}
camera_id = request.get('cameraId')
camera = objects.get(camera_id) if camera_id else None
if camera_id and (camera is None or camera.type != 'CAMERA'):
    raise RuntimeError('PREVIEW_CAMERA_NOT_FOUND')
if camera is None:
    data = bpy.data.cameras.new('preview_camera')
    camera = bpy.data.objects.new('preview_camera', data)
    scene.collection.objects.link(camera)
    data.type = 'ORTHO'
scene.camera = camera
scene.render.engine = 'CYCLES'
scene.cycles.device = 'CPU'; scene.cycles.samples = 8
scene.cycles.use_denoising = True
scene.render.resolution_x=request['width'];scene.render.resolution_y=request['height'];scene.render.resolution_percentage=100
scene.render.image_settings.file_format='PNG';scene.render.filepath=request['output']
scene.render.threads_mode='FIXED';scene.render.threads=2
scene.world = scene.world or bpy.data.worlds.new('preview_world')
scene.world.use_nodes=True
scene.world.node_tree.nodes['Background'].inputs['Color'].default_value=(.18,.18,.18,1)
scene.world.node_tree.nodes['Background'].inputs['Strength'].default_value=.7
added_light=False
if not any(obj.type=='LIGHT' for obj in scene.objects):
    light=bpy.data.lights.new('preview_fill','SUN');light.energy=2
    obj=bpy.data.objects.new('preview_fill',light);scene.collection.objects.link(obj);obj.rotation_euler=(.5,-.5,-.5);added_light=True
# GLB importer places distinct clips in named NLA tracks. Activate only the requested clip.
clips=request.get('animationNames',[])
index=request.get('animationIndex',0)
clip=clips[index] if clips else None
end=0.0
for obj in scene.objects:
    data=obj.animation_data
    if not data: continue
    for track in data.nla_tracks:
        track.mute=bool(clip and track.name != clip)
        if not track.mute:
            for strip in track.strips: end=max(end,strip.frame_end)
fps=scene.render.fps/scene.render.fps_base
requested=request.get('time',0)
actual=(requested % (end/fps) if request.get('loopAnimation') else min(requested,end/fps)) if end else 0
frame=actual*fps
scene.frame_set(int(frame),subframe=frame-int(frame))
# The Web overlay is in glTF Y-up coordinates; Blender import uses Z-up.
conversion=Quaternion((1,0,0),math.pi/2)
for identity,patch in request.get('editorOverlay',{}).items():
    obj=objects.get(identity)
    if obj is None: raise RuntimeError('EDITOR_OBJECT_NOT_FOUND')
    if 'positionOffset' in patch:
        x,y,z=patch['positionOffset'];obj.location+=Vector((x,-z,y))
    if 'rotationOffset' in patch:
        delta=conversion @ Euler(patch['rotationOffset'],'XYZ').to_quaternion() @ conversion.inverted()
        base=obj.rotation_euler.to_quaternion() if obj.rotation_mode!='QUATERNION' else obj.rotation_quaternion.copy()
        obj.rotation_mode='QUATERNION';obj.rotation_quaternion=base @ delta
    if 'scaleMultiplier' in patch:
        x,y,z=patch['scaleMultiplier'];obj.scale=Vector((obj.scale.x*x,obj.scale.y*z,obj.scale.z*y))
    if 'visible' in patch: obj.hide_render=not patch['visible']
    if 'color' in patch and obj.type=='MESH':
        color=patch['color'];rgb=[int(color[i:i+2],16)/255 for i in (1,3,5)]
        linear=[v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4 for v in rgb]
        for slot in obj.material_slots:
            if slot.material:
                slot.material=slot.material.copy();slot.material.diffuse_color=(*linear,1)
                if slot.material.use_nodes:
                    for shader in slot.material.node_tree.nodes:
                        if shader.type=='BSDF_PRINCIPLED': shader.inputs['Base Color'].default_value=(*linear,1)
transform=request.get('editorTransform')
if transform:
    roots=[obj for obj in imported_objects if obj.parent is None]
    host_root=bpy.data.objects.new('verified_editor_transform',None);scene.collection.objects.link(host_root)
    for obj in roots: obj.parent=host_root
    x,y,z=transform['position'];host_root.location=(x,-z,y)
    host_root.rotation_mode='QUATERNION'
    host_root.rotation_quaternion=conversion @ Euler(transform['rotation'],'XYZ').to_quaternion() @ conversion.inverted()
    x,y,z=transform['scale'];host_root.scale=(x,z,y)
scene.view_settings.view_transform='Standard'
scene.view_settings.exposure=request.get('exposureEV',0)
bpy.context.view_layer.update()
if not camera_id:
    # Frame the evaluated animation and retained edits, not the import-time pose.
    points = [obj.matrix_world @ Vector(corner) for obj in scene.objects
              if obj.type == 'MESH' and not obj.hide_render for corner in obj.bound_box]
    if not points: raise RuntimeError('PREVIEW_EMPTY_SCENE')
    minimum = Vector(tuple(min(point[axis] for point in points) for axis in range(3)))
    maximum = Vector(tuple(max(point[axis] for point in points) for axis in range(3)))
    center = (minimum + maximum) * .5
    radius = max((maximum-minimum).length * .5, .1)
    directions = {'front': Vector((0,-1,0)), 'side': Vector((1,0,0)), 'top': Vector((0,0,1)), 'isometric': Vector((1,-1,.8))}
    direction = directions[request.get('view','isometric')].normalized()
    camera.location = center + direction * radius * 3.2
    camera.rotation_euler = (center-camera.location).to_track_quat('-Z','Y').to_euler()
    # ortho_scale is the horizontal extent in a landscape render.
    camera.data.ortho_scale = radius * 2.4 * max(1,request['width']/request['height'])
    camera.data.clip_start = .001; camera.data.clip_end = max(radius*20,1000)
    bpy.context.view_layer.update()
from bpy_extras.object_utils import world_to_camera_view
inspection=[]
for identity in request.get('inspectIds',[]):
    obj=objects.get(identity)
    if obj is None: inspection.append({'hfId':identity,'found':False});continue
    projected=[world_to_camera_view(scene,camera,obj.matrix_world@Vector(c)) for c in obj.bound_box] if obj.type=='MESH' else []
    bounds={'left':min(p.x for p in projected),'right':max(p.x for p in projected),
            'bottom':min(p.y for p in projected),'top':max(p.y for p in projected)} if projected else None
    inspection.append({'hfId':identity,'found':True,'type':obj.type,'visible':not obj.hide_render,
      'inFrame':bool(bounds and any(p.z>0 for p in projected) and bounds['right']>=0 and bounds['left']<=1 and bounds['top']>=0 and bounds['bottom']<=1),
      'fullyInFrame':bool(projected and all(p.z>0 and 0<=p.x<=1 and 0<=p.y<=1 for p in projected)),
      'screenBounds':bounds,'occlusionEvaluated':False,'worldPosition':list(obj.matrix_world.translation)})
bpy.ops.render.render(write_still=True)
Path(request['metadata']).write_text(json.dumps({'cameraId':camera_id,'view':None if camera_id else request.get('view','isometric'),
 'requestedTime':requested,'actualTime':actual,'animationIndex':index if clips else None,'inspection':inspection,
 'addedPreviewLight':added_light,'editorOverlayApplied':bool(request.get('editorOverlay')),'editorTransformApplied':bool(transform),'visualReviewed':False,'reviewScope':'SINGLE_FRAME','width':request['width'],'height':request['height']}),encoding='utf-8')
