"""Regression checks for the shipped LibTV-style clay sculpture GLB.

Run: python scripts/studio/test-emotion-head.py (requires numpy).
"""
import json
from pathlib import Path
import struct
import unittest
import numpy as np


class EmotionSculptureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        raw = (Path(__file__).resolve().parents[2] / 'public/studio/emotion/head.glb').read_bytes()
        cls.raw = raw
        magic, version, length = struct.unpack_from('<III', raw)
        assert (magic, version, length) == (0x46546c67, 2, len(raw))
        json_length = struct.unpack_from('<I', raw, 12)[0]
        cls.asset = json.loads(raw[20:20+json_length])
        cls.binary = raw[28+json_length:]

    def values(self, index):
        accessor = self.asset['accessors'][index]
        width = 3 if accessor['type'] == 'VEC3' else 1
        dtype = '<f4' if accessor['componentType'] == 5126 else '<u2'
        result = np.zeros((accessor['count'], width), dtype=dtype)

        def buffer(view_index, count, dtype):
            view = self.asset['bufferViews'][view_index]
            return np.frombuffer(self.binary, dtype=dtype, count=count, offset=view.get('byteOffset', 0))

        if 'bufferView' in accessor:
            result[:] = buffer(accessor['bufferView'], result.size, dtype).reshape(result.shape)
        if 'sparse' in accessor:
            sparse = accessor['sparse']
            indices = buffer(sparse['indices']['bufferView'], sparse['count'], '<u2')
            result[indices] = buffer(sparse['values']['bufferView'], len(indices)*width, dtype).reshape(-1, width)
        return result

    def test_ships_bald_sculpture_without_portrait_hair(self):
        names = [mesh['name'] for mesh in self.asset['meshes']]
        self.assertEqual(names, ['EmotionBust'])
        self.assertFalse(any(name == 'SweptBob' for name in names))
        self.assertIsNone(self.asset['asset'].get('extras', {}).get('portraitVersion'))

    def test_all_expression_targets_remain_on_every_face_primitive(self):
        face = self.asset['meshes'][0]
        self.assertEqual(len(face['extras']['targetNames']), 22)
        self.assertEqual(len(set(face['extras']['targetNames'])), 22)
        for primitive in face['primitives']:
            neutral = self.values(primitive['attributes']['POSITION'])
            self.assertEqual(len(primitive['targets']), 22)
            for target in primitive['targets']:
                delta = self.values(target['POSITION'])
                self.assertEqual(delta.shape, neutral.shape)
                self.assertTrue(np.isfinite(neutral+delta).all())

    def test_mesh_indices_and_unit_normals_are_valid(self):
        for mesh in self.asset['meshes']:
            for primitive in mesh['primitives']:
                positions = self.values(primitive['attributes']['POSITION'])
                indices = self.values(primitive['indices'])
                self.assertLess(int(indices.max()), len(positions))
                self.assertEqual(len(indices) % 3, 0)
                normals = self.values(primitive['attributes']['NORMAL'])
                self.assertTrue(np.isfinite(normals).all())
                self.assertTrue(np.allclose(np.linalg.norm(normals, axis=1), 1, atol=0.002))

    def test_local_asset_has_no_external_texture_dependency(self):
        self.assertTrue(all('uri' not in buffer for buffer in self.asset['buffers']))
        self.assertEqual(self.asset.get('images', []), [])

    def test_clay_skin_materials_are_near_neutral(self):
        materials = {item['name']: item['pbrMetallicRoughness'] for item in self.asset['materials']}
        skin = materials['M_Face']['baseColorFactor']
        # Neutral plaster; the renderer applies no color filter.
        self.assertAlmostEqual(skin[0], skin[1], delta=0.02)
        self.assertAlmostEqual(skin[1], skin[2], delta=0.02)
        self.assertGreater(materials['M_Face']['roughnessFactor'], 0.6)
        self.assertNotIn('M_Hair', materials)

    def test_optional_portrait_pass_rejects_already_styled_inputs(self):
        from emotion_portrait import style_portrait
        styled = bytearray(self.raw)
        json_length = struct.unpack_from('<I', styled, 12)[0]
        asset = json.loads(bytes(styled[20:20+json_length]))
        asset['asset']['extras'] = {'portraitVersion': 1}
        raw = json.dumps(asset, separators=(',', ':')).encode()
        raw += b' ' * ((-len(raw)) % 4)
        binary = styled[28+json_length:]
        fake = (struct.pack('<III', 0x46546c67, 2, 28 + len(raw) + len(binary))
                + struct.pack('<II', len(raw), 0x4e4f534a) + raw
                + struct.pack('<II', len(binary), 0x004e4942) + binary)
        with self.assertRaisesRegex(ValueError, 'unstyled base'):
            style_portrait(fake)


if __name__ == '__main__':
    unittest.main()
