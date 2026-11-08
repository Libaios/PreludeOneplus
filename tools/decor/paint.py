#!/usr/bin/env python3
"""
Ajuste la peinture de la Prelude dans prelude.glb : bleu nacré foncé, métallisé, avec vernis.
Le modèle d'origine (Black Snow, Sketchfab, CC BY 4.0) a un bleu très saturé et plat.

Usage : python3 tools/decor/paint.py app/src/main/assets/prelude.glb
"""
import json
import struct
import sys

PAINT = 'Black_Metal_Paint'


def main(path):
    d = bytearray(open(path, 'rb').read())
    jlen = struct.unpack('<I', d[12:16])[0]
    j = json.loads(d[20:20 + jlen])
    for m in j['materials']:
        if m.get('name') == PAINT:
            pbr = m.setdefault('pbrMetallicRoughness', {})
            pbr['baseColorFactor'] = [0.010, 0.050, 0.30, 1.0]
            pbr['metallicFactor'] = 0.55
            pbr['roughnessFactor'] = 0.30
            m.setdefault('extensions', {})['KHR_materials_clearcoat'] = {
                'clearcoatFactor': 1.0, 'clearcoatRoughnessFactor': 0.03}
    used = set(j.get('extensionsUsed', []))
    used.add('KHR_materials_clearcoat')
    j['extensionsUsed'] = sorted(used)
    js = json.dumps(j, separators=(',', ':')).encode()
    js += b' ' * ((4 - len(js) % 4) % 4)
    rest = bytes(d[20 + jlen:])  # tronçon binaire (en-tête compris)
    out = struct.pack('<III', 0x46546C67, 2, 12 + 8 + len(js) + len(rest))
    out += struct.pack('<II', len(js), 0x4E4F534A) + js + rest
    open(path, 'wb').write(out)
    print('peinture mise à jour', path)


if __name__ == '__main__':
    main(sys.argv[1])
