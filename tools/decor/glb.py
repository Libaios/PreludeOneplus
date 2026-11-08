"""Petit écrivain glTF binaire (.glb) et outils de maillage pour les décors de Prelude Dash.

Repère : y vers le haut, mètres. Les triangles sont orientés automatiquement selon les
normales fournies, pour que les faces visibles soient toujours dans le bon sens.
"""
import io
import json
import math
import struct

import numpy as np


class Mesh:
    def __init__(self):
        self.P, self.N, self.T, self.I = [], [], [], []
        self.n = 0

    def add(self, P, N, T, I):
        P = np.asarray(P, np.float64).reshape(-1, 3)
        N = np.asarray(N, np.float64).reshape(-1, 3)
        T = np.asarray(T, np.float64).reshape(-1, 2)
        I = np.asarray(I, np.int64).reshape(-1, 3).copy()
        # Remet chaque triangle dans le sens trigonométrique vu depuis sa normale
        a, b, c = P[I[:, 0]], P[I[:, 1]], P[I[:, 2]]
        g = np.cross(b - a, c - a)
        want = N[I[:, 0]] + N[I[:, 1]] + N[I[:, 2]]
        flip = (g * want).sum(1) < 0
        I[flip] = I[flip][:, [0, 2, 1]]
        self.P.append(P)
        self.N.append(N)
        self.T.append(T)
        self.I.append(I.reshape(-1) + self.n)
        self.n += len(P)
        return self

    def arrays(self):
        if not self.P:
            return np.zeros((0, 3)), np.zeros((0, 3)), np.zeros((0, 2)), np.zeros(0, np.int64)
        return (np.concatenate(self.P), np.concatenate(self.N),
                np.concatenate(self.T), np.concatenate(self.I))

    def merge(self, other, M=None):
        P, N, T, I = other.arrays()
        if len(P) == 0:
            return self
        if M is not None:
            M = np.asarray(M, np.float64)
            P = (np.c_[P, np.ones(len(P))] @ M.T)[:, :3]
            R = np.linalg.inv(M[:3, :3]).T
            N = N @ R.T
            N /= np.linalg.norm(N, axis=1, keepdims=True)
        # Les index de l'autre maillage sont déjà orientés : on les ajoute tels quels
        self.P.append(P)
        self.N.append(N)
        self.T.append(T)
        self.I.append(I + self.n)
        self.n += len(P)
        return self

    # ----------------------------------------------------------- formes de base

    def quad(self, o, U, V, uv0=(0, 1), uv1=(1, 0)):
        """Quadrilatère o, o+U, o+U+V, o+V. uv0 va au coin o, uv1 au coin opposé."""
        o, U, V = (np.asarray(x, np.float64) for x in (o, U, V))
        n = np.cross(U, V)
        n /= np.linalg.norm(n)
        (u0, v0), (u1, v1) = uv0, uv1
        return self.add([o, o + U, o + U + V, o + V], [n] * 4,
                        [(u0, v0), (u1, v0), (u1, v1), (u0, v1)], [0, 1, 2, 0, 2, 3])

    def box(self, lo, hi, s=None, faces=('+x', '-x', '+y', '-y', '+z', '-z'), uvo=(0.0, 0.0)):
        """Pavé. [s] : mètres par répétition de texture (None = une image par face)."""
        x0, y0, z0 = lo
        x1, y1, z1 = hi
        dx, dy, dz = x1 - x0, y1 - y0, z1 - z0
        ou, ov = uvo

        def uv(a, b):
            if s is None:
                return (0, 1), (1, 0)
            return (ou, ov + b / s), (ou + a / s, ov)

        if '+x' in faces:
            self.quad((x1, y0, z1), (0, 0, -dz), (0, dy, 0), *uv(dz, dy))
        if '-x' in faces:
            self.quad((x0, y0, z0), (0, 0, dz), (0, dy, 0), *uv(dz, dy))
        if '+z' in faces:
            self.quad((x0, y0, z1), (dx, 0, 0), (0, dy, 0), *uv(dx, dy))
        if '-z' in faces:
            self.quad((x1, y0, z0), (-dx, 0, 0), (0, dy, 0), *uv(dx, dy))
        if '+y' in faces:
            self.quad((x0, y1, z1), (dx, 0, 0), (0, 0, -dz), *uv(dx, dz))
        if '-y' in faces:
            self.quad((x0, y0, z0), (dx, 0, 0), (0, 0, dz), *uv(dx, dz))
        return self

    def cylinder(self, c, r, h, seg=24, top=True, bottom=False, inward=False, urep=1.0, vrep=1.0):
        """Cylindre d'axe y, base au point c."""
        cx, cy, cz = c
        P, N, T, I = [], [], [], []
        for i in range(seg + 1):
            a = 2 * math.pi * i / seg
            ca, sa = math.cos(a), math.sin(a)
            n = (-ca, 0, -sa) if inward else (ca, 0, sa)
            P += [(cx + r * ca, cy, cz + r * sa), (cx + r * ca, cy + h, cz + r * sa)]
            N += [n, n]
            T += [(i / seg * urep, vrep), (i / seg * urep, 0)]
        for i in range(seg):
            b = 2 * i
            I += [b, b + 1, b + 3, b, b + 3, b + 2]
        self.add(P, N, T, I)
        for yy, ny, on in ((cy + h, 1, top), (cy, -1, bottom)):
            if not on:
                continue
            P, N, T, I = [(cx, yy, cz)], [(0, ny, 0)], [(0.5, 0.5)], []
            for i in range(seg + 1):
                a = 2 * math.pi * i / seg
                P.append((cx + r * math.cos(a), yy, cz + r * math.sin(a)))
                N.append((0, ny, 0))
                T.append((0.5 + 0.5 * math.cos(a), 0.5 + 0.5 * math.sin(a)))
            for i in range(seg):
                I += [0, i + 1, i + 2]
            self.add(P, N, T, I)
        return self

    def annulus(self, c, r0, r1, seg=48, ny=1.0, uv_planar=None):
        """Couronne plate (normale +y ou -y). uv_planar = rayon couvert par la texture."""
        cx, cy, cz = c
        P, N, T, I = [], [], [], []
        R = uv_planar or r1
        for i in range(seg + 1):
            a = 2 * math.pi * i / seg
            for r in (r0, r1):
                x, z = r * math.cos(a), r * math.sin(a)
                P.append((cx + x, cy, cz + z))
                N.append((0, ny, 0))
                T.append((0.5 + 0.5 * x / R, 0.5 + 0.5 * z / R))
        for i in range(seg):
            b = 2 * i
            I += [b, b + 1, b + 3, b, b + 3, b + 2]
        return self.add(P, N, T, I)


def translate(x, y, z):
    M = np.eye(4)
    M[:3, 3] = (x, y, z)
    return M


def rot_y(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s, 0], [0, 1, 0, 0], [-s, 0, c, 0], [0, 0, 0, 1]], np.float64)


def rot_x(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0, 0], [0, c, -s, 0], [0, s, c, 0], [0, 0, 0, 1]], np.float64)


def rot_z(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0, 0], [s, c, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]], np.float64)


class GLB:
    def __init__(self, generator='Prelude Dash'):
        self.j = {
            'asset': {'version': '2.0', 'generator': generator},
            'scene': 0, 'scenes': [{'nodes': []}],
            'nodes': [], 'meshes': [], 'materials': [], 'accessors': [], 'bufferViews': [],
            'buffers': [{'byteLength': 0}], 'textures': [], 'images': [],
            'samplers': [
                {'magFilter': 9729, 'minFilter': 9987, 'wrapS': 10497, 'wrapT': 10497},
                {'magFilter': 9729, 'minFilter': 9987, 'wrapS': 33071, 'wrapT': 33071},
            ],
        }
        self.bin = bytearray()
        self.ext = set()

    def _view(self, data, target=None):
        while len(self.bin) % 4:
            self.bin.append(0)
        bv = {'buffer': 0, 'byteOffset': len(self.bin), 'byteLength': len(data)}
        if target:
            bv['target'] = target
        self.bin += data
        self.j['bufferViews'].append(bv)
        return len(self.j['bufferViews']) - 1

    def _acc(self, a, typ, target, minmax=False):
        ct = {np.dtype('float32'): 5126, np.dtype('uint16'): 5123, np.dtype('uint32'): 5125}[a.dtype]
        d = {'bufferView': self._view(a.tobytes(), target), 'componentType': ct,
             'count': int(len(a)), 'type': typ}
        if minmax:
            d['min'] = [float(x) for x in a.min(0)]
            d['max'] = [float(x) for x in a.max(0)]
        self.j['accessors'].append(d)
        return len(self.j['accessors']) - 1

    def texture(self, img, fmt='JPEG', clamp=False, quality=86):
        b = io.BytesIO()
        if fmt == 'JPEG':
            img.convert('RGB').save(b, 'JPEG', quality=quality, optimize=True)
            mime = 'image/jpeg'
        else:
            img.save(b, 'PNG', optimize=True)
            mime = 'image/png'
        self.j['images'].append({'bufferView': self._view(b.getvalue()), 'mimeType': mime})
        self.j['textures'].append({'source': len(self.j['images']) - 1, 'sampler': 1 if clamp else 0})
        return len(self.j['textures']) - 1

    def material(self, name, color=(1, 1, 1, 1), metal=0.0, rough=0.8, tex=None, mr=None,
                 emis=None, etex=None, strength=1.0, unlit=False, blend=None, double=False,
                 cutoff=0.5):
        m = {'name': name, 'pbrMetallicRoughness': {
            'baseColorFactor': [float(x) for x in color],
            'metallicFactor': float(metal), 'roughnessFactor': float(rough)}}
        if tex is not None:
            m['pbrMetallicRoughness']['baseColorTexture'] = {'index': tex}
        if mr is not None:
            m['pbrMetallicRoughness']['metallicRoughnessTexture'] = {'index': mr}
        ext = {}
        if emis is not None:
            m['emissiveFactor'] = [float(x) for x in emis]
            if etex is not None:
                m['emissiveTexture'] = {'index': etex}
            if strength != 1.0:
                ext['KHR_materials_emissive_strength'] = {'emissiveStrength': float(strength)}
        if unlit:
            ext['KHR_materials_unlit'] = {}
        if ext:
            m['extensions'] = ext
            self.ext.update(ext.keys())
        if blend:
            m['alphaMode'] = blend
            if blend == 'MASK':
                m['alphaCutoff'] = cutoff
        if double:
            m['doubleSided'] = True
        self.j['materials'].append(m)
        return len(self.j['materials']) - 1

    def mesh(self, name, parts):
        prims = []
        for me, mat in parts:
            P, N, T, I = me.arrays()
            if len(I) == 0:
                continue
            idx = I.astype(np.uint32 if len(P) > 65535 else np.uint16)
            prims.append({
                'attributes': {
                    'POSITION': self._acc(P.astype(np.float32), 'VEC3', 34962, True),
                    'NORMAL': self._acc(N.astype(np.float32), 'VEC3', 34962),
                    'TEXCOORD_0': self._acc(T.astype(np.float32), 'VEC2', 34962),
                },
                'indices': self._acc(idx, 'SCALAR', 34963),
                'material': mat,
            })
        self.j['meshes'].append({'name': name, 'primitives': prims})
        return len(self.j['meshes']) - 1

    def node(self, name, mesh=None, children=None, t=None, root=False):
        n = {'name': name}
        if mesh is not None:
            n['mesh'] = mesh
        if children:
            n['children'] = list(children)
        if t is not None:
            n['translation'] = [float(x) for x in t]
        self.j['nodes'].append(n)
        i = len(self.j['nodes']) - 1
        if root:
            self.j['scenes'][0]['nodes'].append(i)
        return i

    def mesh_node(self, name, parts, root=False):
        return self.node(name, mesh=self.mesh(name, parts), root=root)

    def save(self, path):
        if self.ext:
            self.j['extensionsUsed'] = sorted(self.ext)
        while len(self.bin) % 4:
            self.bin.append(0)
        self.j['buffers'][0]['byteLength'] = len(self.bin)
        js = json.dumps(self.j, separators=(',', ':')).encode()
        js += b' ' * ((4 - len(js) % 4) % 4)
        total = 12 + 8 + len(js) + 8 + len(self.bin)
        with open(path, 'wb') as f:
            f.write(struct.pack('<III', 0x46546C67, 2, total))
            f.write(struct.pack('<II', len(js), 0x4E4F534A))
            f.write(js)
            f.write(struct.pack('<II', len(self.bin), 0x004E4942))
            f.write(bytes(self.bin))
        return total
