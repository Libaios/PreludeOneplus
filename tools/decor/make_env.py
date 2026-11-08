#!/usr/bin/env python3
"""
Environnements lumineux (HDR équirectangulaires) pour les reflets de la carrosserie,
cohérents avec les décors de make_decor.py, puis convertis en KTX par cmgen (Filament).

  garage : plafond à hexagones, bandeaux LED, néon rose, sol brillant
  night  : ciel de nuit, lampadaires de part et d'autre, fenêtres et néons des immeubles

Usage : python3 tools/decor/make_env.py <dossier de sortie .hdr>
        cmgen --format=ktx --size=256 --deploy=<dossier> garage.hdr   (idem night.hdr)
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_decor as deco  # noqa: E402

W, H = 1024, 512


def directions():
    u = (np.arange(W) + 0.5) / W
    v = (np.arange(H) + 0.5) / H
    lon = (u - 0.5) * 2 * math.pi
    lat = (0.5 - v) * math.pi
    LON, LAT = np.meshgrid(lon, lat)
    x = np.cos(LAT) * np.cos(LON)
    y = np.sin(LAT)
    z = np.cos(LAT) * np.sin(LON)
    return x, y, z


def write_hdr(path, img):
    """Radiance RGBE non compressé."""
    m = img.max(-1)
    e = np.where(m > 1e-32, np.floor(np.log2(np.maximum(m, 1e-32))) + 1, 0)
    s = np.where(m > 1e-32, 256.0 / np.power(2.0, e), 0)
    rgbe = np.zeros(img.shape[:2] + (4,), np.uint8)
    rgbe[..., :3] = np.clip(img * s[..., None], 0, 255).astype(np.uint8)
    rgbe[..., 3] = np.where(m > 1e-32, e + 128, 0).astype(np.uint8)
    with open(path, 'wb') as f:
        f.write(b"#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y %d +X %d\n" % (H, W))
        f.write(rgbe.tobytes())


def ceiling_mask(scale=146):
    """Plafond vu de dessus (14 m x 14 m), tubes lumineux en blanc."""
    n = int(2 * deco.ROOM * scale)
    im = Image.new('L', (n, n), 0)
    d = ImageDraw.Draw(im)

    def p(x, z):
        return ((x + deco.ROOM) * scale, (z + deco.ROOM) * scale)

    for (a, b) in deco.hex_edges(0.78, 5.6):
        d.line([p(*a), p(*b)], fill=255, width=int(0.06 * scale))
    fr = 5.5
    d.line([p(-fr, -fr), p(fr, -fr), p(fr, fr), p(-fr, fr), p(-fr, -fr)], fill=255, width=int(0.06 * scale))
    return np.asarray(im, np.float64) / 255.0, scale


def garage():
    x, y, z = directions()
    eye = np.array([0.0, 0.8, 0.0])
    R, C = deco.ROOM, deco.CEIL
    big = 1e9
    with np.errstate(divide='ignore', invalid='ignore'):
        tx = np.where(x > 0, (R - eye[0]) / x, np.where(x < 0, (-R - eye[0]) / x, big))
        tz = np.where(z > 0, (R - eye[2]) / z, np.where(z < 0, (-R - eye[2]) / z, big))
        ty = np.where(y > 0, (C - eye[1]) / y, np.where(y < 0, (0 - eye[1]) / y, big))
    t = np.minimum(np.minimum(tx, tz), ty)
    px, py, pz = eye[0] + t * x, eye[1] + t * y, eye[2] + t * z
    out = np.zeros((H, W, 3)) + (0.018, 0.019, 0.022)

    mask, sc = ceiling_mask()
    n = mask.shape[0]

    def ceil_at(qx, qz):
        i = np.clip(((qz + R) * sc).astype(int), 0, n - 1)
        j = np.clip(((qx + R) * sc).astype(int), 0, n - 1)
        return mask[i, j]

    hit_ceil = (ty <= tx) & (ty <= tz) & (y > 0)
    hit_floor = (ty <= tx) & (ty <= tz) & (y < 0)
    hit_wall = ~(hit_ceil | hit_floor)
    c = ceil_at(px, pz)
    out[hit_ceil] = (0.02, 0.02, 0.024)
    out[hit_ceil & (c > 0.5)] = (9.0, 9.0, 9.3)

    # Murs : bandeau LED blanc, liseré coloré au sol, néon rose, panneaux
    wall = hit_wall
    led = wall & (py > 2.70) & (py < 2.745)
    out[led] = (7.0, 7.6, 8.2)
    acc = wall & (py > 0.10) & (py < 0.135)
    out[acc] = (0.3, 1.6, 1.4)
    neon = wall & (pz < -R + 0.05) & (px > -2.2) & (px < 1.4) & (py > 2.45) & (py < 3.57)
    out[neon] = (2.4, 0.35, 1.2)
    seam = wall & ((np.abs(((px + pz) % 1.2) - 0.6) > 0.58))
    out[seam] *= 0.5
    lower = wall & (py < 1.0)
    out[lower] *= 0.7

    # Sol brillant : il renvoie un peu le plafond
    fl = hit_floor
    mx, mz = px, pz  # point du sol ; le plafond se reflète au même endroit
    out[fl] = (0.02, 0.02, 0.022)
    out[fl & (ceil_at(mx, mz) > 0.5)] = (1.1, 1.1, 1.15)
    return out.astype(np.float32)


def night():
    x, y, z = directions()
    out = np.zeros((H, W, 3))
    el = np.arcsin(np.clip(y, -1, 1))
    # Ciel : zénith presque noir, horizon violet-orangé (pollution lumineuse)
    k = np.clip(el / (math.pi / 2), 0, 1)
    sky = (np.array([0.005, 0.003, 0.014]) * k[..., None]
           + np.array([0.06, 0.02, 0.07]) * (1 - k[..., None]) ** 3)
    out[:] = sky
    # Couchant rose/orange vers +x (le soleil rétro au bout de la route)
    toward = np.arccos(np.clip(x / np.maximum(np.sqrt(x * x + z * z), 1e-6), -1, 1))
    glow = np.exp(-(toward / 0.7) ** 2) * np.exp(-(np.maximum(el, 0) / 0.22) ** 2) * (y > -0.02)
    out += glow[..., None] * np.array([0.9, 0.25, 0.35])
    out[y < 0] = (0.006, 0.006, 0.007)
    # Immeubles des deux côtés : bande sombre avec fenêtres éclairées
    rng = np.random.default_rng(3)
    side = (np.abs(z) > 0.35) & (el > -0.02) & (el < 0.55)
    out[side] = (0.008, 0.008, 0.011)
    win = side & (rng.random((H, W)) > 0.985)
    out[win] = (1.6, 1.1, 0.6)
    # Néons de rez-de-chaussée
    for col, lon0 in (((2.0, 0.2, 0.8), 0.8), ((0.2, 1.4, 2.0), 2.1), ((2.0, 1.0, 0.2), -1.1), ((0.3, 2.0, 0.5), -2.3)):
        lon = np.arctan2(z, x)
        band = (np.abs(lon - lon0) < 0.12) & (el > 0.02) & (el < 0.05)
        out[band] = col
    # Lampadaires : têtes à 7,8 m, de part et d'autre, tous les 30 m
    eye = np.array([0.0, 1.0, 0.0])
    for zl, xo in ((2.9, 0.0), (-7.3, 15.0)):
        for xl in np.arange(-60 + xo, 180, 30.0):
            if abs(xl) < 25:
                continue  # les plus proches sont de vraies lumières qui défilent
            d = np.array([xl, 7.8, zl]) - eye
            dist = np.linalg.norm(d)
            d /= dist
            ang = np.arccos(np.clip(x * d[0] + y * d[1] + z * d[2], -1, 1))
            rad = max(0.012, 0.5 / dist)
            out += np.array([6.0, 4.4, 2.6])[None, None, :] * (ang < rad)[..., None]
            out += np.array([0.12, 0.08, 0.045])[None, None, :] * np.exp(-(ang / (rad * 4)) ** 2)[..., None]
    return out.astype(np.float32)


if __name__ == '__main__':
    out = sys.argv[1] if len(sys.argv) > 1 else '.'
    write_hdr(os.path.join(out, 'garage.hdr'), garage())
    write_hdr(os.path.join(out, 'night.hdr'), night())
    print('ok')
