"""Optional offline portrait art pass (colored skin + procedural bob).

Not used by the shipped LibTV-style grayscale sculpture. The product preview
ships the bald clay bust from build-emotion-head.py.

Offline iteration (source must be the unstyled builder output):
  python scripts/studio/emotion_portrait.py --source public/studio/emotion/head.glb --output portrait.glb

The input must be the unstyled base; versioned inputs are rejected to prevent
stacking colour transforms or duplicating hair.
"""
import argparse
import json
from pathlib import Path
import struct
import numpy as np


def style_portrait(source):
    size = struct.unpack_from('<I', source, 12)[0]
    data = json.loads(source[20:20+size])
    if data['asset'].get('extras', {}).get('portraitVersion'):
        raise ValueError('Use the unstyled base GLB, not an already styled portrait')
    binary = bytearray(source[28+size:])
    views, accessors, materials = data['bufferViews'], data['accessors'], data['materials']

    def values(index):
        item = accessors[index]
        view = views[item['bufferView']]
        return np.frombuffer(binary, dtype='<f4', count=item['count']*3,
                             offset=view.get('byteOffset',0)).reshape(-1,3).copy()

    def accessor(values):
        while len(binary)%4: binary.append(0)
        values = np.asarray(values)
        views.append({'buffer':0,'byteOffset':len(binary),'byteLength':values.nbytes})
        binary.extend(values.tobytes())
        item = {'bufferView':len(views)-1,'componentType':5126 if values.dtype.kind=='f' else 5123,
                'count':len(values),'type':'VEC3' if values.ndim==2 else 'SCALAR'}
        if values.ndim==2:
            item.update(min=values.min(axis=0).tolist(),max=values.max(axis=0).tolist())
        accessors.append(item)
        return len(accessors)-1

    for primitive in data['meshes'][0]['primitives']:
        material = materials[primitive['material']]
        if 'Sclera' in material['name']:
            eye = values(primitive['attributes']['POSITION'])
            center = (eye.min(axis=0)+eye.max(axis=0))*0.5
            radius = np.linalg.norm(eye[:,:2]-center[:2],axis=1)
            colors = np.ones_like(eye)
            front = eye[:,2] > center[2]
            colors[front & (radius < 0.0055)] = [0.08,0.042,0.024]
            colors[front & (radius < 0.0025)] = [0.008,0.007,0.006]
            item = accessors[primitive['attributes']['COLOR_0']]
            start = views[item['bufferView']].get('byteOffset',0)
            binary[start:start+colors.nbytes] = colors.astype('<f4').tobytes()
            item.update(min=colors.min(axis=0).tolist(),max=colors.max(axis=0).tolist())
        if material['name'] not in ('M_Face','M_BackHead'):
            continue
        material['pbrMetallicRoughness'].update(baseColorFactor=[0.72,0.49,0.39,1],roughnessFactor=0.62)
        x,y,z = values(primitive['attributes']['POSITION']).T
        colors = values(primitive['attributes']['COLOR_0'])
        # The legacy tint was centred below the actual lip topology. Remove it
        # before colouring the vermilion, rather than tinting the chin again.
        legacy_lips = np.exp(-(x/0.036)**6-((y+0.049)/0.010)**4)*np.clip((z-0.065)/0.045,0,1)
        colors /= 1-legacy_lips[:,None]*np.array([0.04,0.34,0.30])
        # Soften baked recess shading without changing neutral geometry or any
        # morph buffer. Retain anatomical brows, and add restrained lip/cheek tint.
        brow = np.exp(-((y-0.063)/0.012)**4)*np.clip((z-0.055)/0.04,0,1)
        exponent = 0.55+0.65*brow
        colors = colors ** exponent[:,None]
        lip_center = -0.032 + 0.004*(np.abs(x)/0.030)**2
        lips = np.exp(-(x/0.030)**6-((y-lip_center)/0.008)**4)*np.clip((z-0.102)/0.011,0,1)
        cheeks = np.exp(-((np.abs(x)-0.047)/0.024)**2-((y+0.005)/0.027)**2)*np.clip((z-0.045)/0.05,0,1)
        colors *= 1-lips[:,None]*np.array([0.12,0.53,0.43])
        colors *= 1-cheeks[:,None]*np.array([0,0.065,0.05])
        item = accessors[primitive['attributes']['COLOR_0']]
        start = views[item['bufferView']].get('byteOffset',0)
        binary[start:start+colors.nbytes] = colors.astype('<f4').tobytes()
        item.update(min=colors.min(axis=0).tolist(),max=colors.max(axis=0).tolist())
    # A continuous, open-face bob: the swept hairline keeps brows/eyes clear
    # through every morph. Fine fluting and vertex colour share one material;
    # no transparent hair cards, external textures or runtime geometry work.
    rows, columns = 64, 192
    hair_points, hair_colors, hair_faces = [], [], []
    for row in range(rows + 1):
        t = row / rows
        for column in range(columns + 1):
            theta = -np.pi + 2*np.pi*column/columns
            opening = np.clip((abs(theta)-0.48)/0.72, 0, 1)
            opening = opening*opening*(3-2*opening)
            bottom = 0.107 + 0.014*np.sin(theta*3)*(1-opening) - 0.237*opening
            y = 0.155 + (bottom-0.155)*t
            crown = np.sqrt(max(0.00001, 1-((max(y,0.04)-0.04)/0.116)**2))
            flow = theta*78 + 7*t + 2*np.sin(theta*2)*t
            ridge = (0.00045*np.cos(flow) + 0.0002*np.sin(flow*2.3))*t
            radius = crown*(1 + 0.035*np.sin(t*np.pi*2)*opening)
            x = (0.093*radius+ridge)*np.sin(theta)
            z = 0.013 + (0.101*radius+ridge)*np.cos(theta)
            # Soft inward ends, not a flat cylindrical helmet.
            x *= 1-0.07*max(0, (t-0.8)/0.2)*opening
            hair_points.append([x,y,z])
            tone = 0.84 + 0.10*np.cos(flow) + 0.06*np.sin(theta*11+t*5)
            hair_colors.append([tone,tone,tone])
            if row < rows and column < columns:
                a = row*(columns+1)+column
                hair_faces.extend([[a,a+columns+1,a+1], [a+1,a+columns+1,a+columns+2]])
    hair_points = np.array(hair_points,dtype='<f4')
    hair_faces = np.array(hair_faces)
    triangles = hair_points[hair_faces]
    crosses = np.cross(triangles[:,1]-triangles[:,0],triangles[:,2]-triangles[:,0])
    hair_normals = np.zeros_like(hair_points)
    for corner in range(3): np.add.at(hair_normals,hair_faces[:,corner],crosses)
    # Weld the angular seam before normalizing.
    for row in range(rows+1):
        first, last = row*(columns+1), row*(columns+1)+columns
        hair_normals[first] = hair_normals[last] = hair_normals[first]+hair_normals[last]
    hair_normals /= np.maximum(np.linalg.norm(hair_normals,axis=1,keepdims=True),1e-12)
    materials.append({'name':'M_Hair','pbrMetallicRoughness':{
            'baseColorFactor':[0.035,0.016,0.011,1],'metallicFactor':0,'roughnessFactor':0.56},
        'doubleSided':True})
    hair_primitive = {'attributes':{'POSITION':accessor(hair_points),'NORMAL':accessor(hair_normals),
        'COLOR_0':accessor(np.array(hair_colors,dtype='<f4'))},
        'indices':accessor(hair_faces.flatten().astype('<u2')),'material':len(materials)-1}

    data['meshes'].append({'name':'SweptBob','primitives':[hair_primitive]})
    # One shared transform for the head, eyes and all expression shapes; avoid
    # independently squeezing only neutral vertices and breaking animation.
    data['nodes'][0]['scale'] = [0.96,1,1]
    data['nodes'].append({'mesh':len(data['meshes'])-1})
    data['scenes'][data.get('scene',0)]['nodes'].append(len(data['nodes'])-1)
    data['asset']['extras'] = {'portraitVersion':1}
    data['asset']['generator'] = 'AID ICT-FaceKit portrait converter'
    data['buffers'][0]['byteLength'] = len(binary)
    raw = json.dumps(data,separators=(',',':')).encode()
    raw += b' '*((-len(raw))%4)
    binary += b'\0'*((-len(binary))%4)
    return (struct.pack('<III',0x46546c67,2,28+len(raw)+len(binary))
            +struct.pack('<II',len(raw),0x4e4f534a)+raw
            +struct.pack('<II',len(binary),0x004e4942)+binary)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args = parser.parse_args()
    result = style_portrait(args.source.read_bytes())
    args.output.write_bytes(result)
    print(f'Built portrait: {len(result):,} bytes')
