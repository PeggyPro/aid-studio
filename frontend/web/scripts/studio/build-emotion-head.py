"""Build the locally served sculpture from MIT ICT-FaceKit. Requires numpy.
Usage: python scripts/studio/build-emotion-head.py
Downloads pinned upstream OBJ files into a temporary directory; emits GLB + license.
"""
import concurrent.futures
import json
from pathlib import Path
import struct
import tempfile
import urllib.request
import numpy as np
REVISION = 'da5f95a607f5e6b37755b38d3385d7f2853732e5'
BASE = f'https://raw.githubusercontent.com/ICT-VGL/ICT-FaceKit/{REVISION}/'
OUTPUT = Path(__file__).resolve().parents[2] / 'public/studio/emotion'
NAMES = ['jawOpen', 'mouthFunnel'] + [name + '_' + side for name in (
    'browInnerUp', 'browDown', 'browOuterUp', 'eyeWide', 'eyeSquint', 'eyeBlink',
    'mouthSmile', 'mouthFrown', 'mouthPress', 'cheekSquint') for side in ('L', 'R')]
# Released PCA identity modes only (a softer, East Asian feminine face);
# hand-authored vertex offsets create creases that break under expression morphs.
IDENTITY_WEIGHTS = {0: 1.2, 3: -1.6}
# Relaxed upper lids rest on the iris instead of the source's wide-open stare.
NEUTRAL_EXPRESSION = {'eyeBlink_L': 0.2, 'eyeBlink_R': 0.2}
NOSE_TIP = 4841


def source_vertices(path):
    return np.array([list(map(float, line.split()[1:4])) for line in path.read_text().splitlines()
                     if line.startswith('v ')], dtype='<f4') * 0.01


def nose_occlusion(position, normal, faces, radius=0.024, reach=0.03, samples=96):
    """Cosine-weighted hemisphere occlusion of vertices near the nose tip by nearby triangles."""
    near = np.flatnonzero(np.linalg.norm(position - position[NOSE_TIP], axis=1) < radius)
    local = faces[np.all(np.linalg.norm(position[faces] - position[NOSE_TIP], axis=2) < radius + reach, axis=1)]
    a, b, c = (position[local[:, i]].astype('f8') for i in range(3))
    edge1, edge2 = b - a, c - a
    k = np.arange(samples) + 0.5
    polar, azimuth = np.arccos(1 - 2 * k / samples), np.pi * (1 + 5**0.5) * k
    directions = np.stack([np.sin(polar)*np.cos(azimuth), np.sin(polar)*np.sin(azimuth), np.cos(polar)], 1)
    hit_weight, total_weight = np.zeros(len(near)), np.zeros(len(near))
    for direction in directions:
        p = np.cross(direction, edge2)
        det = np.einsum('ij,ij->i', edge1, p)
        valid = np.abs(det) > 1e-12
        inverse = np.where(valid, 1 / np.where(valid, det, 1), 0)
        for start in range(0, len(near), 128):
            chunk = near[start:start + 128]
            s = position[chunk].astype('f8')[:, None, :] + normal[chunk, None, :] * 1e-4 - a[None]
            u = np.einsum('nmj,mj->nm', s, p) * inverse
            q = np.cross(s, edge1[None])
            v = (q @ direction) * inverse
            t = np.einsum('nmj,mj->nm', q, edge2) * inverse
            hit = (valid & (u >= 0) & (v >= 0) & (u + v <= 1) & (t > 1e-5) & (t < reach)).any(axis=1)
            weight = np.clip(normal[chunk] @ direction, 0, None)
            hit_weight[start:start + 128] += weight * hit
            total_weight[start:start + 128] += weight
    occlusion = np.zeros(len(position))
    occlusion[near] = hit_weight / np.maximum(total_weight, 1e-9)
    # Fade toward the sample boundary so the baked term blends into the cheeks.
    fade = np.clip((radius - np.linalg.norm(position[near] - position[NOSE_TIP], axis=1)) / 0.006, 0, 1)
    occlusion[near] *= fade
    return occlusion


def build():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='aid-emotion-model-') as directory:
        cache = Path(directory)
        def download(name):
            urllib.request.urlretrieve(BASE + 'FaceXModel/' + name + '.obj', cache / (name + '.obj'))
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
            assets = ['generic_neutral_mesh'] + NAMES + [f'identity{index:03d}' for index in IDENTITY_WEIGHTS]
            list(pool.map(download, assets))
        neutral = cache / 'generic_neutral_mesh.obj'
        generic = source_vertices(neutral)
        identity = generic.copy()
        for index, weight in IDENTITY_WEIGHTS.items():
            mode = source_vertices(cache / f'identity{index:03d}.obj') - generic
            identity += weight * mode
        for name, weight in NEUTRAL_EXPRESSION.items():
            identity += weight * (source_vertices(cache / (name + '.obj')) - generic)
        position = identity
        groups, material = {}, None
        for line in neutral.read_text().splitlines():
            if line.startswith('usemtl '):
                material = line.split()[1]
            elif line.startswith('f ') and material not in ('M_LacrimalFluid','M_EyeBlend','M_EyeOcclusion','M_EyeLashes'):
                indices = [int(v.split('/')[0])-1 for v in line.split()[1:]]
                for i in range(1, len(indices)-1):
                    groups.setdefault(material, []).append([indices[0],indices[i],indices[i+1]])
        all_faces = np.array([f for faces in groups.values() for f in faces])
        def normals(points):
            triangles = points[all_faces]
            cross = np.cross(triangles[:,1]-triangles[:,0], triangles[:,2]-triangles[:,0])
            values = np.zeros_like(points)
            for i in range(3): np.add.at(values, all_faces[:,i], cross)
            return values / np.maximum(np.linalg.norm(values,axis=1,keepdims=True), 1e-12)
        normal = normals(position)
        binary = bytearray()
        views, accessors = [], []
        def view(values):
            while len(binary)%4: binary.append(0)
            index = len(views)
            raw = values.tobytes()
            views.append({'buffer':0,'byteOffset':len(binary),'byteLength':len(raw)})
            binary.extend(raw)
            return index
        def accessor(values, sparse=False):
            values = np.asarray(values)
            item = {'componentType':5126 if values.dtype.kind=='f' else 5123,
                    'count':len(values),'type':'VEC3' if values.ndim==2 else 'SCALAR'}
            if values.ndim==2:
                item.update(min=values.min(axis=0).tolist(), max=values.max(axis=0).tolist())
            if sparse:
                indices = np.flatnonzero(np.max(np.abs(values),axis=1)>1e-7).astype('<u2')
                if len(indices):
                    item['sparse']={'count':len(indices),'indices':{'bufferView':view(indices),'componentType':5123},
                                    'values':{'bufferView':view(values[indices].astype('<f4'))}}
            else:
                item['bufferView']=view(values)
            accessors.append(item)
            return len(accessors)-1
        # Geometry-derived cavity occlusion is the only baked shading: a clay
        # sculpture reads through form and light, not painted brows or lips.
        edges = np.concatenate([all_faces[:,[0,1]], all_faces[:,[1,2]], all_faces[:,[2,0]]])
        edges = np.unique(np.sort(edges,axis=1),axis=0)
        sources = np.concatenate([edges[:,0],edges[:,1]])
        neighbours = np.concatenate([edges[:,1],edges[:,0]])
        degree = np.bincount(sources,minlength=len(position)).clip(1)
        delta = position[neighbours]-position[sources]
        average = np.zeros_like(position)
        np.add.at(average,sources,delta)
        length = np.bincount(sources,weights=np.linalg.norm(delta,axis=1),minlength=len(position)) / degree
        cavity = np.clip(np.sum(average/degree[:,None]*normal,axis=1) / np.maximum(length,1e-8)*3,0,1)
        smooth = np.bincount(sources,weights=cavity[neighbours],minlength=len(position))/degree
        # The runtime has no shadows, so the nostril interior would catch the rim
        # light through the nose itself; bake ray-traced occlusion there.
        nostril = nose_occlusion(position, normal, all_faces)
        shade = (1 - 0.32*(cavity*0.6+smooth*0.4)) * (1 - 0.85*nostril)
        colors = np.repeat(shade[:,None],3,axis=1).astype('<f4')
        # The opaque sclera shell covers the iris geometry, so the iris, limbal
        # ring and pupil are shaded onto its front at anatomical proportions.
        for start, end in ((21451, 22221), (23021, 23791)):
            eye = position[start:end]
            center = (eye.min(axis=0)+eye.max(axis=0))*0.5
            radius = np.linalg.norm(eye[:,:2]-center[:2],axis=1)
            front = eye[:,2] > center[2]
            iris = np.clip((0.0071 - radius) / 0.0006, 0, 1) * front
            limbus = np.exp(-((radius - 0.0064) / 0.0007)**2) * front
            pupil = np.clip((0.0026 - radius) / 0.0004, 0, 1) * front
            tone = 1.08 - iris*0.5 - limbus*0.18 - pupil*0.38
            colors[start:end] = np.clip(tone, 0.1, 1)[:, None]
        targets = []
        for name in NAMES:
            expression_delta = source_vertices(cache/(name+'.obj')) - generic
            points = identity + expression_delta
            targets.append((points-position, normals(points)-normal))
        materials, primitives = [], []
        for name, faces in groups.items():
            used, indices = np.unique(np.array(faces).flatten(), return_inverse=True)
            attributes = {'POSITION':accessor(position[used]), 'NORMAL':accessor(normal[used]), 'COLOR_0':accessor(colors[used])}
            local_targets = [{'POSITION':accessor(p[used],True), 'NORMAL':accessor(n[used],True)} for p,n in targets]
            # One neutral plaster for the whole bust; only the mouth interior is darker.
            if 'Gums' in name:
                base_color, roughness = [0.14, 0.14, 0.14, 1], 0.8
            elif 'Teeth' in name:
                base_color, roughness = [0.4, 0.4, 0.4, 1], 0.7
            else:
                base_color, roughness = [0.46, 0.46, 0.46, 1], 0.72
            materials.append({'name':name,'pbrMetallicRoughness':{'baseColorFactor':base_color,
                               'metallicFactor':0,'roughnessFactor':roughness},
                              'doubleSided':False})
            primitives.append({'attributes':attributes,'indices':accessor(indices.astype('<u2')),
                               'material':len(materials)-1,'targets':local_targets})
        data={'asset':{'version':'2.0','generator':'AID ICT-FaceKit sculpture converter',
                      'copyright':'Copyright (c) 2020 USC Institute for Creative Technologies; MIT'},
              'scene':0,'scenes':[{'nodes':[0]}],'nodes':[{'mesh':0}],
              'meshes':[{'name':'EmotionBust','primitives':primitives,'weights':[0]*len(NAMES),'extras':{'targetNames':NAMES}}],
              'materials':materials,'accessors':accessors,'bufferViews':views,'buffers':[{'byteLength':len(binary)}]}
        raw=json.dumps(data,separators=(',',':')).encode()
        raw+=b' '*((-len(raw))%4)
        binary+=b'\0'*((-len(binary))%4)
        result=struct.pack('<III',0x46546c67,2,12+8+len(raw)+8+len(binary))+struct.pack('<II',len(raw),0x4e4f534a)+raw+struct.pack('<II',len(binary),0x004e4942)+binary
        # Ship the bald grayscale clay bust (LibTV-style emotion schematic).
        # Optional colored+hair art pass remains offline-only:
        #   python scripts/studio/emotion_portrait.py --source head.glb --output portrait.glb
        (OUTPUT/'head.glb').write_bytes(result)
        urllib.request.urlretrieve(BASE+'LICENSE', OUTPUT/'ICT-FaceKit-LICENSE.txt')
        print(f'Built {len(result):,} bytes, {len(NAMES)} expression targets')

if __name__ == '__main__': build()
