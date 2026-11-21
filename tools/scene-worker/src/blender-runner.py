"""Execute a scene entry and export the current Blender scene."""
import bpy
import json
import math
import os
import runpy
import sys

entry, output, keep_blend = sys.argv[sys.argv.index('--') + 1:]
bpy.ops.wm.read_factory_settings(use_empty=True)
runpy.run_path(os.path.abspath(entry), run_name='__main__')
scene = bpy.context.scene
# Scene root is platform metadata, unlike authored object identities. Blender's
# scene is not an Object, so the object contract must not require model repetition.
if scene.get('hf_id') is None:
    scene['hf_id'] = 'aid_scene_root'
identities = set()
for obj in [scene, *scene.objects]:
    identity = obj.get('hf_id')
    if not isinstance(identity, str) or not identity.strip() or identity in identities:
        raise ValueError('INVALID_OBJECT_ID: scene 与 object 的 hf_id 必须非空且唯一')
    identities.add(identity)
warnings = []
for obj in scene.objects:
    if obj.type == 'LIGHT' and obj.data.type not in ('SUN', 'POINT', 'SPOT'):
        warnings.append({'code': 'LIGHT_NOT_EXPORTED', 'objectId': obj['hf_id'], 'type': obj.data.type})
if keep_blend == 'true':
    bpy.ops.wm.save_as_mainfile(filepath=os.path.join(os.path.dirname(output), 'scene.blend'))
result = bpy.ops.export_scene.gltf(
    filepath=os.path.abspath(output), export_format='GLB', export_extras=True,
    export_cameras=True, export_lights=True, export_animations=True,
    export_yup=True, export_force_sampling=True, export_import_convert_lighting_mode='SPEC')
if 'FINISHED' not in result:
    raise RuntimeError('GLB_EXPORT_FAILED: Blender 未确认导出完成')
with open(output + '.metadata.json', 'w', encoding='utf-8') as target:
    # Blender exports radiometric light strengths to glTF photometric units.
    # Carry an initial viewing exposure separately; do not alter authored lights.
    preview = None
    if any(obj.type == 'LIGHT' and obj.data.type in ('SUN', 'POINT', 'SPOT') for obj in scene.objects):
        exposure = math.pow(2.0, scene.view_settings.exposure) / 683.0
        if math.isfinite(exposure) and 2.0 ** -16 <= exposure <= 2.0 ** 8:
            preview = {'exposure': exposure, 'lightingUnits': 'PHOTOMETRIC', 'sourceExposureEV': scene.view_settings.exposure}
    json.dump({'engine': 'blender', 'engineVersion': bpy.app.version_string,
               'warnings': warnings, 'previewSettings': preview}, target, ensure_ascii=False)
