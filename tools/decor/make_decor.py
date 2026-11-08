#!/usr/bin/env python3
"""
Décors 3D de Prelude Dash, entièrement générés par programme (géométrie et textures).

  garage.glb : atelier (plafond à hexagones lumineux, plateau tournant, servante,
               pneus, étagère, pont élévateur, néon « Prelude »)
  route.glb  : rue de nuit (chaussée mouillée, trottoirs, lampadaires, immeubles,
               pont, silhouette de ville, ciel étoilé)

Usage : python3 tools/decor/make_decor.py app/src/main/assets

Repère : y vers le haut, mètres, la voiture au centre avec l'avant vers +x.
Les noms de nœuds sont lus par l'appli (Stage3D.kt) : ne pas les renommer sans elle.
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from glb import GLB, Mesh, rot_y, translate  # noqa: E402

RNG = np.random.default_rng(1997)
FONT = '/usr/share/fonts/truetype/google-fonts/Poppins-BoldItalic.ttf'

# Garage
ROOM = 7.0          # demi-côté de la pièce
CEIL = 3.6          # hauteur sous plafond
TT_R = 2.8          # rayon du plateau tournant
TT_H = 0.035        # hauteur du plateau (la voiture est posée dessus)

# Route
ROAD_Z0, ROAD_Z1 = -9.2, 4.8     # chaussée
WALK = 3.6                        # largeur des trottoirs
BLOCKS = [-60.0 + 120.0 * k for k in range(4)]  # pâtés de maisons de 120 m
X0, X1 = -60.0, 420.0             # étendue des éléments qui défilent (loin, pour qu'ils apparaissent dans la brume)


# ------------------------------------------------------------------ textures

def pnoise(h, w, sigma):
    """Bruit lisse et périodique (raccord parfait quand la texture se répète)."""
    f = np.fft.fft2(RNG.standard_normal((h, w)))
    ky = np.fft.fftfreq(h)[:, None]
    kx = np.fft.fftfreq(w)[None, :]
    g = np.exp(-2 * (math.pi * sigma) ** 2 * (kx ** 2 + ky ** 2))
    n = np.real(np.fft.ifft2(f * g))
    return (n - n.mean()) / (n.std() + 1e-9)


def img(a):
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))


def mr_tex(rough):
    """Texture métal/rugosité glTF : rugosité dans le vert, métal (0) dans le bleu."""
    h, w = rough.shape
    a = np.zeros((h, w, 3))
    a[..., 1] = rough * 255
    return img(a)


def tex_floor():
    n = 512
    base = 60 + 7 * pnoise(n, n, 40) + 3 * pnoise(n, n, 6)
    spk = RNG.random((n, n))
    base += np.where(spk > 0.996, 40, 0) - np.where(spk < 0.004, 18, 0)
    rgb = np.stack([base * 0.97, base * 0.99, base * 1.04], -1)
    rough = np.clip(0.22 + 0.07 * pnoise(n, n, 30), 0.1, 0.4)
    return img(rgb), mr_tex(rough)


def tex_wall():
    # 4 panneaux de 1,2 m sur 3,6 m de haut : u = 4,8 m, v = 3,6 m
    w, h = 512, 384
    a = np.zeros((h, w, 3)) + (44, 47, 53)
    a += 3 * pnoise(h, w, 20)[..., None]
    for k in range(5):
        x = int(k * w / 4)
        a[:, max(0, x - 2):x + 2] = (24, 26, 30)
    lower = int(h * (1 - 1.0 / 3.6))
    a[lower:, :] = (30, 32, 36) + 2 * pnoise(h, w, 10)[lower:, :, None]
    a[lower - 3:lower, :] = (90, 94, 100)
    return img(a)


def tex_turntable():
    n = 512
    y, x = np.mgrid[0:n, 0:n] / (n - 1) * 2 - 1
    r = np.hypot(x, y)
    rings = 0.5 + 0.5 * np.sin(r * 260 + 3 * pnoise(n, n, 50))
    v = 46 + 10 * rings + 4 * pnoise(n, n, 3)
    v = np.where(np.abs(r - 0.62) < 0.006, 25, v)
    v = np.where(np.abs(r - 0.30) < 0.006, 25, v)
    return img(np.stack([v, v * 1.01, v * 1.05], -1))


def tex_shadow():
    n = 128
    y, x = np.mgrid[0:n, 0:n] / (n - 1) * 2 - 1
    d = np.sqrt(x ** 2 + y ** 2)
    a = np.clip(1 - d, 0, 1) ** 1.6
    out = np.zeros((n, n, 4))
    out[..., 3] = a * 235
    return Image.fromarray(out.astype(np.uint8), 'RGBA')


def tex_neon():
    w, h = 1024, 320
    im = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    try:
        big = ImageFont.truetype(FONT, 190)
        small = ImageFont.truetype(FONT, 64)
    except OSError:
        big = small = ImageFont.load_default()
    # Tubes : trait clair au centre, couleur autour
    d.text((40, -20), 'Prelude', font=big, fill=(255, 70, 170, 255), stroke_width=9, stroke_fill=(255, 40, 150, 255))
    d.text((40, -20), 'Prelude', font=big, fill=(255, 150, 215, 255))
    d.line((60, 236, 760, 236), fill=(255, 40, 150, 255), width=12)
    d.line((60, 236, 760, 236), fill=(255, 150, 215, 255), width=4)
    d.text((620, 238), 'F20A  E85', font=small, fill=(60, 230, 255, 255), stroke_width=4, stroke_fill=(30, 200, 255, 255))
    d.text((620, 238), 'F20A  E85', font=small, fill=(140, 240, 255, 255))
    return im


def tex_road():
    # u : 12 m le long de la route ; v : de z = -9,2 (haut) à z = +4,8 (bas)
    w, h = 1024, 1024
    zz = ROAD_Z0 + (np.arange(h) + 0.5) / h * (ROAD_Z1 - ROAD_Z0)
    xx = (np.arange(w) + 0.5) / w * 12.0
    Z = zz[:, None] * np.ones((1, w))
    X = np.ones((h, 1)) * xx[None, :]
    g = 34 + 5 * pnoise(h, w, 2) + 6 * pnoise(h, w, 40)
    # Traces de roues plus sombres et plus lisses
    tracks = np.zeros((h, w))
    for zc in (-0.45 - 0.75, -0.45 + 0.75, 3.05 - 0.75, 3.05 + 0.75, -4.0, -5.4, -7.5, -6.1):
        tracks += np.exp(-((Z - zc) / 0.35) ** 2)
    g -= 6 * tracks
    wet = pnoise(h, w, 60) + 0.6 * tracks
    puddle = np.clip((wet - 0.55) * 2.5, 0, 1)
    rough = 0.86 - 0.22 * tracks.clip(0, 1) - 0.50 * puddle
    paint = np.zeros((h, w))

    def line(zc, half, dashed):
        on = np.abs(Z - zc) < half
        if dashed:
            on &= (X % 12.0) < 3.0
        return on

    for zc, half, dashed in ((-9.0, 0.08, False), (-5.7, 0.07, True), (-2.2, 0.08, True),
                             (1.3, 0.07, True), (4.6, 0.08, False)):
        paint = np.maximum(paint, line(zc, half, dashed))
    wear = np.clip(0.75 + 0.25 * pnoise(h, w, 4), 0, 1)
    g = g * (1 - paint) + 175 * wear * paint
    rough = rough * (1 - paint) + 0.55 * paint
    g -= 8 * puddle
    rgb = np.stack([g, g * 1.01, g * 1.05], -1)
    return img(rgb), mr_tex(np.clip(rough, 0.05, 1))


def tex_sidewalk():
    # dalles de 1,5 m ; u = 3 m (2 dalles), v = 3,6 m
    w, h = 256, 308
    a = 70 + 6 * pnoise(h, w, 10) + 3 * pnoise(h, w, 2)
    for k in range(3):
        x = int(k * w / 2)
        a[:, max(0, x - 1):x + 1] -= 30
    for k in range(4):
        y = int(k * h * 1.2 / 3.6)
        a[max(0, y - 1):y + 1, :] -= 30
    return img(np.stack([a, a * 0.99, a * 0.97], -1))


def tex_facade(kind):
    """Façade 8 x 8 fenêtres (3 m x 3,5 m chacune) + image des fenêtres allumées."""
    n = 512
    cw, ch = n // 8, n // 8
    wall = {0: (58, 54, 50), 1: (24, 28, 36), 2: (62, 34, 28)}[kind]
    a = np.zeros((n, n, 3)) + wall
    a += 5 * pnoise(n, n, 6)[..., None]
    e = np.zeros((n, n, 3))
    for j in range(8):
        for i in range(8):
            x0, y0 = i * cw, j * ch
            if kind == 1:
                wx0, wx1, wy0, wy1 = x0 + 3, x0 + cw - 3, y0 + 6, y0 + ch - 4
            else:
                wx0, wx1, wy0, wy1 = x0 + 14, x0 + cw - 14, y0 + 14, y0 + ch - 12
            a[wy0:wy1, wx0:wx1] = (14, 17, 22)
            r = RNG.random()
            if r < 0.38:
                col = [(255, 196, 120), (255, 170, 90), (200, 220, 255), (150, 190, 255)][RNG.integers(0, 4)]
                k = 0.55 + 0.45 * RNG.random()
                grad = np.linspace(1.0, 0.65, wy1 - wy0)[:, None, None]
                e[wy0:wy1, wx0:wx1] = np.array(col) * k * grad
                if RNG.random() < 0.4:  # store à moitié baissé
                    cut = wy0 + int((wy1 - wy0) * RNG.uniform(0.2, 0.6))
                    e[wy0:cut, wx0:wx1] *= 0.25
    return img(a), img(e)


def tex_shop():
    """Vitrine de 4 m : intérieur éclairé par le haut, étagères garnies, montants."""
    w, h = 256, 160
    v = np.linspace(0, 1, h)[:, None]
    inside = (0.25 + 0.75 * (1 - v) ** 1.5) * np.ones((1, w))
    a = np.stack([inside * 200, inside * 160, inside * 125], -1)
    for y in (int(h * 0.45), int(h * 0.68), int(h * 0.9)):
        a[y:y + 3, :] = (40, 34, 30)
        x = 6
        while x < w - 10:
            bw = int(RNG.integers(4, 12))
            bh = int(RNG.integers(5, 14))
            col = np.array([(170, 60, 60), (60, 120, 170), (190, 160, 70), (90, 150, 90), (200, 200, 200)][int(RNG.integers(0, 5))])
            a[y - bh:y, x:x + bw] = col * (0.5 + 0.5 * (1 - y / h))
            x += bw + int(RNG.integers(1, 6))
    for x in (0, w // 2, w - 1):
        a[:, max(0, x - 3):x + 3] = (18, 18, 20)
    a[:6, :] = (18, 18, 20)
    a[-8:, :] = (18, 18, 20)
    return img(a)


def tex_skyline():
    w, h = 2048, 256
    a = np.zeros((h, w, 4))
    x = 0
    while x < w:
        bw = int(RNG.integers(24, 90))
        bh = int(h * RNG.uniform(0.18, 0.92) ** 1.5)
        shade = RNG.uniform(14, 24)
        a[h - bh:, x:x + bw, :3] = (shade * 1.1, shade * 0.55, shade * 1.6)
        a[h - bh:, x:x + bw, 3] = 255
        # fenêtres allumées
        for _ in range(int(bw * bh / 90)):
            px = x + int(RNG.integers(2, max(3, bw - 2)))
            py = h - int(RNG.integers(2, max(3, bh - 2)))
            if RNG.random() < 0.5:
                a[py:py + 2, px:px + 2, :3] = [(230, 170, 100), (255, 90, 190), (90, 220, 255)][int(RNG.integers(0, 3))]
        if RNG.random() < 0.15 and bh > h * 0.5:  # antenne
            ax = x + bw // 2
            a[h - bh - 18:h - bh, ax:ax + 2, :3] = shade
            a[h - bh - 18:h - bh, ax:ax + 2, 3] = 255
            a[h - bh - 20:h - bh - 17, ax - 1:ax + 3] = (255, 40, 40, 255)
        x += bw + int(RNG.integers(0, 8))
    return Image.fromarray(a.astype(np.uint8), 'RGBA')


def tex_sky():
    """Ciel « retrowave » : u = azimut (0 = vers +x, le soleil), v = 0 au zénith, 1 à l'horizon."""
    w, h = 2048, 512
    v = np.linspace(0, 1, h)[:, None]
    u = np.linspace(0, 1, w)[None, :]
    daz = np.minimum(u, 1 - u) * 2 * math.pi           # écart angulaire avec le soleil
    top = np.array([7, 4, 20.0])
    hor = np.array([52, 16, 66.0])
    base = top + (hor - top) * (v ** 1.6)[..., None]
    g = np.exp(-(daz / 0.75) ** 2)[..., None] * (v ** 2.5)[..., None]
    warm = np.array([255, 92, 128.0]) * (1 - v[..., None] ** 8 * 0.0) 
    glow = np.where(v[..., None] > 0.85, np.array([255, 140, 90.0]), warm)
    a = base + (glow - base) * np.clip(g * 1.15, 0, 1)
    a = a * np.ones((1, w, 1))
    stars = RNG.random((h, w))
    mask = (stars > 0.9985) & (v < 0.6) & (np.exp(-(daz / 0.6) ** 2) < 0.4)
    a[mask] = 150 + 105 * RNG.random((int(mask.sum()), 1))
    return img(a)


def tex_sun():
    """Soleil rétro : dégradé jaune -> rose, bandes horizontales dans le bas."""
    n = 512
    y, x = np.mgrid[0:n, 0:n] / (n - 1)
    r = np.hypot(x - 0.5, y - 0.5)
    alpha = np.clip((0.5 - r) * n / 2, 0, 1)
    # bandes : de plus en plus épaisses vers le bas
    yy = y - 0.45
    for k in range(9):
        y0 = 0.45 + 0.055 * k + 0.004 * k * k
        th = 0.008 + 0.0045 * k
        alpha = np.where((y >= y0) & (y < y0 + th), 0, alpha)
    top = np.array([255, 214, 40.0])
    mid = np.array([255, 96, 70.0])
    bot = np.array([235, 20, 150.0])
    t = y[..., None]
    c = np.where(t < 0.5, top + (mid - top) * (t / 0.5), mid + (bot - mid) * ((t - 0.5) / 0.5))
    out = np.zeros((n, n, 4))
    out[..., :3] = c
    out[..., 3] = alpha * 255
    return Image.fromarray(out.astype(np.uint8), 'RGBA')


def tex_halo():
    n = 256
    y, x = np.mgrid[0:n, 0:n] / (n - 1) * 2 - 1
    r = np.hypot(x, y)
    out = np.zeros((n, n, 4))
    out[..., :3] = (255, 70, 150)
    out[..., 3] = np.clip(1 - r, 0, 1) ** 2.2 * 150
    return Image.fromarray(out.astype(np.uint8), 'RGBA')


def tex_leaf():
    """Palme : nervure centrale et folioles en biais (alpha = découpe)."""
    w, h = 256, 64
    im = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    c = h // 2
    for x in range(4, w - 6, 5):
        k = 1 - x / w
        half = int((h // 2 - 2) * (0.35 + 0.65 * math.sin(math.pi * min(1, x / w * 1.15)))) 
        col = (34, 70 + int(20 * k), 30, 255)
        d.line([(x, c), (x + 14, c - half)], fill=col, width=2)
        d.line([(x, c), (x + 14, c + half)], fill=col, width=2)
    d.line([(0, c), (w - 1, c)], fill=(60, 52, 30, 255), width=3)
    return im


# ------------------------------------------------------------ objets route

def ring_tube(m, pts, radii, sides=6):
    """Tube le long d'une suite de points (tronc), sections circulaires horizontales."""
    P, N, T, I = [], [], [], []
    for j, (p, r) in enumerate(zip(pts, radii)):
        for i in range(sides + 1):
            a = 2 * math.pi * i / sides
            n = (math.cos(a), 0.0, math.sin(a))
            P.append((p[0] + r * n[0], p[1], p[2] + r * n[2]))
            N.append(n)
            T.append((i / sides, j / (len(pts) - 1)))
    for j in range(len(pts) - 1):
        for i in range(sides):
            a = j * (sides + 1) + i
            b = a + sides + 1
            I += [a, b, a + 1, a + 1, b, b + 1]
    m.add(P, N, T, I)


def palm(trunks, leaves, x, y0, z, lean):
    """Palmier de 8,5 m, légèrement penché vers la route, couronne de 9 palmes."""
    H = 8.4 + float(RNG.uniform(-0.8, 0.8))
    bend = 1.1 * lean
    pts, radii = [], []
    for j in range(11):
        t = j / 10
        pts.append((x + 0.3 * t * t, y0 + H * t, z + bend * t * t))
        radii.append(0.21 - 0.08 * t)
    ring_tube(trunks, pts, radii)
    top = np.array(pts[-1])
    for k in range(9):
        th = 2 * math.pi * k / 9 + float(RNG.uniform(-0.2, 0.2))
        d = np.array([math.cos(th), 0.0, math.sin(th)])
        side = np.array([-d[2], 0.0, d[0]])
        L = float(RNG.uniform(2.8, 3.6))
        rise = float(RNG.uniform(0.3, 0.8))
        P, N, T, I = [], [], [], []
        segs = 5
        for j in range(segs + 1):
            s = j / segs
            c = top + d * L * s + np.array([0, rise * s - 1.9 * s * s, 0])
            hw = 0.45 * math.sin(math.pi * min(1.0, 0.15 + s * 0.9)) + 0.05
            for sgn, v in ((-1, 0.0), (1, 1.0)):
                q = c + side * hw * sgn - np.array([0, 0.08 * hw, 0])
                P.append(q)
                N.append((0, 1, 0))
                T.append((s, v))
        for j in range(segs):
            a = 2 * j
            I += [a, a + 2, a + 1, a + 1, a + 2, a + 3]
        leaves.add(P, N, T, I)


def traffic_car():
    """Berline générique (de nuit on voit surtout ses feux), avant vers +x."""
    body, glass, tires, head, tail = Mesh(), Mesh(), Mesh(), Mesh(), Mesh()
    body.box((-2.2, 0.28, -0.86), (2.2, 0.92, 0.86))
    body.box((-1.3, 0.92, -0.78), (0.9, 0.96, 0.78), faces=('+y',))
    glass.box((-1.25, 0.92, -0.74), (0.85, 1.36, 0.74), faces=('+x', '-x', '+z', '-z', '+y'))
    for wx in (-1.35, 1.35):
        for wz in (-0.8, 0.8):
            part = Mesh().cylinder((0, -0.11, 0), 0.31, 0.22, seg=12, top=True, bottom=True)
            tires.merge(part, translate(wx, 0.31, wz) @ np.array(
                [[1, 0, 0, 0], [0, 0, -1, 0], [0, 1, 0, 0], [0, 0, 0, 1]], np.float64))
    for z in (-0.62, 0.62):
        head.box((2.2, 0.62, z - 0.2), (2.22, 0.74, z + 0.2), faces=('+x',))
        tail.box((-2.22, 0.66, z - 0.22), (-2.2, 0.8, z + 0.22), faces=('-x',))
    return body, glass, tires, head, tail


# ---------------------------------------------------------------- garage

def hex_edges(R, half):
    """Arêtes uniques d'un nid d'abeille (hexagones à sommet plat) dans un carré."""
    edges = {}
    dx, dz = 1.5 * R, math.sqrt(3) * R
    for i in range(-12, 13):
        for j in range(-12, 13):
            cx, cz = i * dx, (j + 0.5 * (i % 2)) * dz
            if abs(cx) > half - R * 0.9 or abs(cz) > half - R * 0.9:
                continue
            pts = [(cx + R * math.cos(k * math.pi / 3), cz + R * math.sin(k * math.pi / 3)) for k in range(6)]
            for k in range(6):
                a, b = pts[k], pts[(k + 1) % 6]
                key = tuple(sorted([(round(a[0], 3), round(a[1], 3)), (round(b[0], 3), round(b[1], 3))]))
                edges[key] = (a, b)
    return list(edges.values())


def tube(m, a, b, y, w=0.06, t=0.035, gap=0.07):
    """Tube lumineux horizontal entre a et b (x, z), raccourci aux extrémités."""
    (ax, az), (bx, bz) = a, b
    L = math.hypot(bx - ax, bz - az) - 2 * gap
    if L <= 0:
        return
    ang = math.atan2(-(bz - az), bx - ax)
    part = Mesh().box((-L / 2, -t, -w / 2), (L / 2, 0, w / 2), faces=('-y', '+z', '-z', '+x', '-x'))
    m.merge(part, translate((ax + bx) / 2, y, (az + bz) / 2) @ rot_y(ang))


def build_garage(path):
    g = GLB('Prelude Dash garage')
    floor_c, floor_mr = tex_floor()
    t_floor = g.texture(floor_c)
    t_floor_mr = g.texture(floor_mr)
    t_wall = g.texture(tex_wall())
    t_tt = g.texture(tex_turntable(), clamp=True)
    t_shadow = g.texture(tex_shadow(), fmt='PNG', clamp=True)
    t_neon = g.texture(tex_neon(), fmt='PNG', clamp=True)

    m_floor = g.material('garage_floor', color=(1, 1, 1, 0.8), metal=0, rough=1, tex=t_floor, mr=t_floor_mr, blend='BLEND')
    m_wall = g.material('wall', color=(1, 1, 1, 1), metal=0.15, rough=0.7, tex=t_wall)
    m_ceil = g.material('ceiling', color=(0.025, 0.026, 0.03, 1), rough=0.9)
    m_hex = g.material('hex_light', color=(1, 1, 1, 1), rough=0.5, emis=(1, 1, 1), strength=6)
    m_led = g.material('led_white', color=(1, 1, 1, 1), rough=0.5, emis=(0.85, 0.93, 1.0), strength=4.5)
    m_accent = g.material('accent', color=(0, 0, 0, 1), rough=0.5, emis=(0.17, 0.83, 0.75), strength=1.2)
    m_tt_top = g.material('turntable_top', color=(1, 1, 1, 0.84), metal=0.8, rough=0.36, tex=t_tt, blend='BLEND')
    m_tt_side = g.material('turntable_side', color=(0.05, 0.05, 0.06, 1), metal=0.8, rough=0.4)
    m_red = g.material('chest_red', color=(0.42, 0.012, 0.015, 1), metal=0.25, rough=0.28)
    m_chrome = g.material('chrome', color=(0.92, 0.92, 0.94, 1), metal=1, rough=0.12)
    m_gap = g.material('dark_gap', color=(0.01, 0.01, 0.012, 1), rough=0.8)
    m_rubber = g.material('rubber', color=(0.018, 0.018, 0.02, 1), rough=0.88)
    m_orange = g.material('rack_orange', color=(0.62, 0.16, 0.012, 1), metal=0.35, rough=0.45)
    m_blue = g.material('rack_blue', color=(0.02, 0.06, 0.22, 1), metal=0.35, rough=0.45)
    m_card = g.material('cardboard', color=(0.30, 0.17, 0.08, 1), rough=0.92)
    m_card2 = g.material('cardboard2', color=(0.22, 0.13, 0.06, 1), rough=0.92)
    m_can = [g.material('can_%d' % i, color=c, metal=0.6, rough=0.3) for i, c in
             enumerate([(0.55, 0.38, 0.02, 1), (0.02, 0.18, 0.05, 1), (0.6, 0.6, 0.62, 1), (0.45, 0.02, 0.02, 1)])]
    m_lift = g.material('lift', color=(0.12, 0.14, 0.17, 1), metal=0.5, rough=0.4)
    m_yellow = g.material('floor_line', color=(0.75, 0.48, 0.02, 1), rough=0.5)
    m_neon = g.material('neon_sign', color=(0, 0, 0, 1), tex=t_neon, emis=(1, 1, 1), etex=t_neon,
                        strength=1.9, blend='MASK', cutoff=0.3, rough=0.6)
    m_shadow = g.material('blob_shadow', color=(0, 0, 0, 1), tex=t_shadow, unlit=True, blend='BLEND')

    R = ROOM
    # Sol (transparent : il laisse voir le reflet de la voiture)
    floor = Mesh().quad((-R, 0, R), (2 * R, 0, 0), (0, 0, -2 * R), (0, 2 * R / 4), (2 * R / 4, 0))
    n_floor = g.mesh_node('garage_floor', [(floor, m_floor)])

    # Marquage jaune de la baie
    lines = Mesh()
    for z in (-3.6, 3.6):
        lines.box((-6.2, 0, z - 0.06), (6.2, 0.003, z + 0.06), faces=('+y',))
    # Murs, plafond
    walls = Mesh()
    rep = 2 * R / 4.8
    walls.quad((-R, 0, R), (0, 0, -2 * R), (0, CEIL, 0), (0, 1), (rep, 0))
    walls.quad((R, 0, -R), (0, 0, 2 * R), (0, CEIL, 0), (0, 1), (rep, 0))
    walls.quad((-R, 0, -R), (2 * R, 0, 0), (0, CEIL, 0), (0, 1), (rep, 0))
    walls.quad((R, 0, R), (-2 * R, 0, 0), (0, CEIL, 0), (0, 1), (rep, 0))
    ceil = Mesh().quad((-R, CEIL, -R), (2 * R, 0, 0), (0, 0, 2 * R))

    # Plafond à hexagones lumineux, avec un cadre
    hexm = Mesh()
    for a, b in hex_edges(0.78, 5.6):
        tube(hexm, a, b, CEIL - 0.01)
    fr = 5.5
    for a, b in (((-fr, -fr), (fr, -fr)), ((fr, -fr), (fr, fr)), ((fr, fr), (-fr, fr)), ((-fr, fr), (-fr, -fr))):
        tube(hexm, a, b, CEIL - 0.01, gap=0.0)

    # Bandeaux LED : blanc à 2,7 m, couleur d'ambiance au ras du sol
    led, acc = Mesh(), Mesh()
    e = 0.03
    for y0, y1, m in ((2.70, 2.745, led), (0.10, 0.135, acc)):
        m.box((-R + 0.05, y0, -R), (R - 0.05, y1, -R + e), faces=('+z', '+y', '-y'))
        m.box((-R + 0.05, y0, R - e), (R - 0.05, y1, R), faces=('-z', '+y', '-y'))
        m.box((-R, y0, -R + 0.05), (-R + e, y1, R - 0.05), faces=('+x', '+y', '-y'))
        m.box((R - e, y0, -R + 0.05), (R, y1, R - 0.05), faces=('-x', '+y', '-y'))

    # Servante d'atelier rouge (contre le mur de gauche)
    chest, chrome, gap = Mesh(), Mesh(), Mesh()
    cx0, cx1, cz0, cz1 = -6.95, -6.38, -1.6, -0.2
    chest.box((cx0, 0.12, cz0), (cx1, 1.02, cz1))
    chest.box((cx0, 1.05, cz0 + 0.06), (cx1 - 0.08, 1.5, cz1 - 0.06))
    for k, (y0, y1) in enumerate(((0.16, 0.36), (0.40, 0.56), (0.60, 0.72), (0.76, 0.86), (0.90, 0.98),
                                  (1.10, 1.22), (1.26, 1.36), (1.40, 1.46))):
        xf = cx1 if k < 5 else cx1 - 0.08
        za, zb = (cz0 + 0.04, cz1 - 0.04) if k < 5 else (cz0 + 0.1, cz1 - 0.1)
        gap.box((xf, y0 - 0.012, za), (xf + 0.004, y0, zb), faces=('+x',))
        chrome.box((xf, (y0 + y1) / 2 - 0.012, za + 0.15), (xf + 0.025, (y0 + y1) / 2 + 0.012, zb - 0.15))
    for z in (cz0 + 0.1, cz1 - 0.1):  # roulettes
        gap.cylinder((cx1 - 0.12, 0.0, z), 0.06, 0.12, seg=10)
        gap.cylinder((cx0 + 0.12, 0.0, z), 0.06, 0.12, seg=10)

    # Pile de pneus
    tires = Mesh()
    for k in range(4):
        y = k * 0.215
        tires.cylinder((-6.25, y, 1.25), 0.33, 0.2, seg=28, top=False)
        tires.cylinder((-6.25, y, 1.25), 0.21, 0.2, seg=20, top=False, inward=True)
        tires.annulus((-6.25, y + 0.2, 1.25), 0.21, 0.33, seg=28)

    # Étagère (mur du fond à droite) avec cartons et bidons
    rack_o, rack_b, card, card2 = Mesh(), Mesh(), Mesh(), Mesh()
    cans = [Mesh() for _ in m_can]
    sx0, sx1, sz0, sz1 = -2.3, 0.9, -6.98, -6.4
    for x in (sx0, (sx0 + sx1) / 2, sx1):
        rack_b.box((x - 0.04, 0, sz0), (x + 0.04, 2.3, sz0 + 0.08))
        rack_b.box((x - 0.04, 0, sz1 - 0.08), (x + 0.04, 2.3, sz1))
    for y in (0.15, 0.85, 1.55, 2.22):
        rack_o.box((sx0, y, sz1 - 0.07), (sx1, y + 0.08, sz1))
        rack_o.box((sx0, y + 0.06, sz0), (sx1, y + 0.08, sz1), faces=('+y',))
    for y in (0.23, 0.93, 1.63):
        x = sx0 + 0.08
        while x < sx1 - 0.35:
            kind = RNG.random()
            if kind < 0.55:
                w = RNG.uniform(0.35, 0.6)
                hgt = RNG.uniform(0.25, 0.55)
                (card if RNG.random() < 0.6 else card2).box((x, y, sz0 + 0.05), (x + w, y + hgt, sz1 - 0.1))
                x += w + 0.05
            elif kind < 0.9:
                for _ in range(int(RNG.integers(2, 5))):
                    cans[int(RNG.integers(0, len(cans)))].cylinder((x + 0.1, y, (sz0 + sz1) / 2), 0.09, 0.28, seg=12)
                    x += 0.21
                x += 0.05
            else:
                x += 0.3

    # Pont élévateur deux colonnes (au fond, derrière la voiture)
    lift = Mesh()
    for x in (-5.2, -2.6):
        lift.box((x - 0.18, 0, -6.2), (x + 0.18, 3.2, -5.85))
        lift.box((x - 0.3, 0, -6.3), (x + 0.3, 0.02, -5.75), faces=('+y', '+x', '-x', '+z'))
        lift.box((x - 0.05, 0.25, -5.85), (x + 0.05, 0.32, -4.6))
    lift.box((-5.38, 3.2, -6.2), (-2.42, 3.32, -5.85))

    # Néon « Prelude » sur le mur du fond, au-dessus de la voiture
    neon = Mesh().quad((-2.2, 2.45, -R + 0.04), (3.6, 0, 0), (0, 1.125, 0))

    n_static = g.mesh_node('garage_static', [
        (walls, m_wall), (ceil, m_ceil), (lines, m_yellow), (chest, m_red), (chrome, m_chrome),
        (gap, m_gap), (tires, m_rubber), (rack_o, m_orange), (rack_b, m_blue), (card, m_card),
        (card2, m_card2), (lift, m_lift)] + [(c, m) for c, m in zip(cans, m_can)])
    n_hex = g.mesh_node('hex_lights', [(hexm, m_hex), (led, m_led)])
    n_neon = g.mesh_node('neon_sign', [(neon, m_neon)])
    n_acc = g.mesh_node('accent_strip', [(acc, m_accent)])

    # Plateau tournant : dessus brillant (transparent pour le reflet), flanc et liseré lumineux
    top = Mesh().annulus((0, TT_H, 0), 0.0, TT_R - 0.07, seg=64, uv_planar=TT_R)
    side = Mesh().cylinder((0, 0, 0), TT_R - 0.07, TT_H, seg=64, top=False)
    ring = Mesh().annulus((0, TT_H + 0.0005, 0), TT_R - 0.07, TT_R, seg=64)
    ring.cylinder((0, 0, 0), TT_R, TT_H, seg=64, top=False)
    n_top = g.mesh_node('turntable_top', [(top, m_tt_top)])
    n_side = g.mesh_node('turntable_side', [(side, m_tt_side)])
    n_ring = g.mesh_node('accent_ring', [(ring, m_accent)])
    shadow = Mesh().quad((-2.75, TT_H + 0.003, 1.25), (5.5, 0, 0), (0, 0, -2.5))
    n_shadow = g.mesh_node('blob_shadow', [(shadow, m_shadow)])
    g.node('turntable', children=[n_top, n_side, n_ring, n_shadow], root=True)

    g.node('garage', children=[n_floor, n_static, n_hex, n_neon, n_acc], root=True)
    return g.save(path)


# ------------------------------------------------------------------- route

def build_route(path):
    g = GLB('Prelude Dash route')
    road_c, road_mr = tex_road()
    t_road = g.texture(road_c)
    t_road_mr = g.texture(road_mr)
    t_walk = g.texture(tex_sidewalk())
    facades = [tex_facade(k) for k in range(3)]
    t_fac = [(g.texture(a), g.texture(e)) for a, e in facades]
    t_sky = g.texture(tex_sky())
    t_skyline = g.texture(tex_skyline(), fmt='PNG')
    t_shadow = g.texture(tex_shadow(), fmt='PNG', clamp=True)
    t_shop = g.texture(tex_shop())
    t_sun = g.texture(tex_sun(), fmt='PNG', clamp=True)
    t_halo = g.texture(tex_halo(), fmt='PNG', clamp=True)
    t_leaf = g.texture(tex_leaf(), fmt='PNG', clamp=True)

    m_road = g.material('road', color=(1, 1, 1, 0.9), rough=1, tex=t_road, mr=t_road_mr, blend='BLEND')
    m_walk = g.material('sidewalk', color=(1, 1, 1, 1), rough=0.85, tex=t_walk)
    m_curb = g.material('curb', color=(0.16, 0.16, 0.16, 1), rough=0.8)
    m_pole = g.material('pole', color=(0.05, 0.055, 0.06, 1), metal=0.6, rough=0.45)
    m_lamp = g.material('lamp_head', color=(1, 1, 1, 1), rough=0.5, emis=(1.0, 0.78, 0.52), strength=9)
    m_fac = [g.material('facade_%d' % k, color=(1, 1, 1, 1), rough=0.75, metal=0.1 if k != 1 else 0.5,
                        tex=a, emis=(1, 1, 1), etex=e, strength=1.3) for k, (a, e) in enumerate(t_fac)]
    m_roof = g.material('roof', color=(0.03, 0.03, 0.035, 1), rough=0.9)
    m_shop = g.material('shop_window', color=(0.03, 0.03, 0.03, 1), rough=0.15, emis=(1, 1, 1), etex=t_shop, strength=0.8)
    m_neons = [g.material('shop_neon_%d' % k, color=(0, 0, 0, 1), rough=0.5, emis=c, strength=2.6) for k, c in
               enumerate([(1.0, 0.15, 0.55), (0.15, 0.85, 1.0), (1.0, 0.6, 0.1), (0.4, 1.0, 0.3)])]
    m_concrete = g.material('concrete', color=(0.07, 0.07, 0.075, 1), rough=0.85)
    m_under = g.material('under_light', color=(1, 1, 1, 1), rough=0.5, emis=(0.75, 0.88, 1.0), strength=4)
    m_ground = g.material('ground', color=(0.012, 0.012, 0.014, 1), rough=0.95)
    m_sky = g.material('sky', color=(1, 1, 1, 1), tex=t_sky, unlit=True)
    m_skyline = g.material('skyline', color=(1, 1, 1, 1), tex=t_skyline, unlit=True, blend='MASK', cutoff=0.5)
    m_shadow = g.material('blob_shadow', color=(0, 0, 0, 1), tex=t_shadow, unlit=True, blend='BLEND')
    m_sun = g.material('sun', color=(0, 0, 0, 1), tex=t_sun, emis=(1, 1, 1), etex=t_sun, strength=0.95,
                       blend='MASK', cutoff=0.5, rough=1)
    m_halo = g.material('sun_halo', color=(1, 1, 1, 1), tex=t_halo, unlit=True, blend='BLEND')
    m_trunk = g.material('palm_trunk', color=(0.07, 0.045, 0.03, 1), rough=0.9)
    m_leaf = g.material('palm_leaf', color=(1, 1, 1, 1), tex=t_leaf, rough=0.7, blend='MASK', cutoff=0.4, double=True)
    m_edge = [g.material('edge_neon_%d' % k, color=(0, 0, 0, 1), rough=0.5, emis=c, strength=3.2) for k, c in
              enumerate([(1.0, 0.18, 0.62), (0.2, 0.9, 1.0)])]
    m_tbody = g.material('traffic_body', color=(0.025, 0.025, 0.03, 1), metal=0.6, rough=0.28)
    m_tglass = g.material('traffic_glass', color=(0.01, 0.01, 0.012, 1), rough=0.05)
    m_thead = g.material('traffic_head', color=(1, 1, 1, 1), emis=(1, 0.96, 0.88), strength=9)
    m_ttail = g.material('traffic_tail', color=(0.3, 0, 0, 1), emis=(1, 0.04, 0.04), strength=3.5)
    m_trubber = g.material('traffic_tire', color=(0.015, 0.015, 0.017, 1), rough=0.9)

    L = X1 - X0
    # --- défile tous les 12 m : chaussée, trottoirs, bordures
    road = Mesh().quad((X0, 0, ROAD_Z1), (L, 0, 0), (0, 0, ROAD_Z0 - ROAD_Z1), (0, 1), (L / 12, 0))
    walk, curb = Mesh(), Mesh()
    hcurb = 0.15
    walk.quad((X0, hcurb, ROAD_Z1 + WALK), (L, 0, 0), (0, 0, -WALK), (0, 1), (L / 3, 0))
    walk.quad((X0, hcurb, ROAD_Z0), (L, 0, 0), (0, 0, -WALK), (0, 1), (L / 3, 0))
    curb.quad((X1, 0, ROAD_Z1), (-L, 0, 0), (0, hcurb, 0))   # bordure droite, face à la route
    curb.quad((X0, 0, ROAD_Z0), (L, 0, 0), (0, hcurb, 0))    # bordure gauche
    n_road = g.mesh_node('road', [(road, m_road)])
    n_walk = g.mesh_node('sidewalks', [(walk, m_walk), (curb, m_curb)])
    g.node('scroll_12', children=[n_road, n_walk], root=True)

    # --- défile tous les 30 m : lampadaires (droite tous les 30 m, gauche décalés de 15 m)
    poles, heads = Mesh(), Mesh()
    for side, zp, za, xo in ((1, ROAD_Z1 + 0.5, ROAD_Z1 - 1.9, 0.0), (-1, ROAD_Z0 - 0.5, ROAD_Z0 + 1.9, 15.0)):
        x = X0 + xo
        while x < X1:
            poles.cylinder((x, hcurb, zp), 0.09, 8.0, seg=10, top=True)
            z0, z1 = sorted((zp, za))
            poles.box((x - 0.06, 7.86, z0), (x + 0.06, 7.98, z1))
            heads.box((x - 0.35, 7.74, za - 0.18), (x + 0.35, 7.86, za + 0.18))
            poles.box((x - 0.37, 7.86, za - 0.2), (x + 0.37, 7.95, za + 0.2), faces=('+y', '+x', '-x', '+z', '-z'))
            x += 30.0
    trunks, leaves = Mesh(), Mesh()
    for zp, lean, xo in ((ROAD_Z1 + 1.9, -1.0, 15.0), (ROAD_Z0 - 1.8, 1.0, 0.0)):
        x = X0 + xo
        while x < X1:
            palm(trunks, leaves, x, hcurb, zp, lean)
            x += 30.0
    g.node('scroll_30', children=[g.mesh_node('lamps', [(poles, m_pole), (heads, m_lamp), (trunks, m_trunk),
                                                         (leaves, m_leaf)])], root=True)

    # --- défile tous les 120 m : immeubles, vitrines, néons, pont
    fac = [Mesh() for _ in m_fac]
    roof, shop, conc, under = Mesh(), Mesh(), Mesh(), Mesh()
    neons = [Mesh() for _ in m_neons]
    edges = [Mesh() for _ in m_edge]
    zr = ROAD_Z1 + WALK + 0.2
    zl = ROAD_Z0 - WALK - 0.2
    for side in (1, -1):
        x = 0.0
        while x < 112:
            w = float(RNG.uniform(11, 26))
            w = min(w, 118 - x)
            if w < 8:
                break
            hgt = float(RNG.choice([9, 12.5, 16, 19.5, 23, 26.5, 33.5])) + 4
            d = float(RNG.uniform(12, 20))
            k = int(RNG.integers(0, 3))
            zf = zr if side > 0 else zl
            zb = zf + side * d
            z0, z1 = sorted((zf, zb))
            uo, vo = int(RNG.integers(0, 8)) * 3.0, int(RNG.integers(0, 8)) * 3.5
            has_neon = RNG.random() < 0.8
            nk = int(RNG.integers(0, len(neons)))
            nl = min(w - 2, float(RNG.uniform(3, 8)))
            nxo = float(RNG.uniform(1, max(1.1, w - nl - 1)))
            face = ('-z',) if side > 0 else ('+z',)
            edge_k = int(RNG.integers(0, len(edges))) if RNG.random() < 0.5 else -1
            edge_roof = RNG.random() < 0.6
            edge_x = 0.0 if RNG.random() < 0.5 else w
            for xo in BLOCKS:  # le pâté de maisons répété jusqu'au bout de la rue
                xa = xo + x
                # façade côté route, étages au-dessus de 4 m ; une fenêtre = 3 m x 3,5 m
                part = Mesh().box((xa, 4, z0), (xa + w, hgt, z1), faces=face + ('+x', '-x'))
                P, N, T = part.P[0], None, None
                for q in range(len(part.P)):
                    P, N, T = part.P[q], part.N[q], part.T[q]
                    along = np.where(np.abs(N[:, 2]) > 0.5, P[:, 0] - xa, P[:, 2])
                    T[:, 0] = (along + uo) / 24.0
                    T[:, 1] = -(P[:, 1] - 4.0 + vo) / 28.0
                fac[k].merge(part)
                roof.box((xa, hgt, z0), (xa + w, hgt + 0.01, z1), faces=('+y',))
                # rez-de-chaussée : vitrine éclairée et bandeau néon
                conc.box((xa, 0, z0), (xa + w, 4, z1), faces=face + ('+x', '-x'))
                za, zb = sorted((zf, zf - side * 0.03))
                part = Mesh().box((xa + 0.8, 0.4, za), (xa + w - 0.8, 3.0, zb), faces=face)
                part.T[0][:, 0] *= (w - 1.6) / 4.0
                shop.merge(part)
                if has_neon:
                    za, zb = sorted((zf - side * 0.06, zf - side * 0.12))
                    neons[nk].box((xa + nxo, 3.25, za), (xa + nxo + nl, 3.6, zb),
                                  faces=face + ('+y', '-y', '+x', '-x'))
                if edge_k >= 0:
                    ze0, ze1 = sorted((zf - side * 0.02, zf - side * 0.14))
                    ex = xa + edge_x
                    edges[edge_k].box((ex - 0.07, 4.2, ze0), (ex + 0.07, hgt - 0.2, ze1))
                    if edge_roof:
                        edges[edge_k].box((xa, hgt - 0.22, ze0), (xa + w, hgt - 0.08, ze1))
            x += w + float(RNG.uniform(2, 6))
    # Pont au-dessus de la route
    for xo in BLOCKS:
        xa = xo + 50
        conc.box((xa, 6.4, ROAD_Z0 - WALK - 4), (xa + 9, 7.5, ROAD_Z1 + WALK + 4))
        conc.box((xa, 7.5, ROAD_Z0 - WALK - 4), (xa + 0.3, 8.6, ROAD_Z1 + WALK + 4))
        conc.box((xa + 8.7, 7.5, ROAD_Z0 - WALK - 4), (xa + 9, 8.6, ROAD_Z1 + WALK + 4))
        for z in (ROAD_Z1 + 1.6, ROAD_Z0 - 1.6):
            conc.box((xa + 3.9, 0.15, z - 0.6), (xa + 5.1, 6.4, z + 0.6))
        for xx in (xa + 2, xa + 7):
            under.box((xx - 0.08, 6.36, ROAD_Z0 + 0.5), (xx + 0.08, 6.4, ROAD_Z1 - 0.5), faces=('-y', '+x', '-x'))
    city = [(f, m) for f, m in zip(fac, m_fac)] + [(roof, m_roof), (shop, m_shop), (conc, m_concrete), (under, m_under)]
    city += [(n, m) for n, m in zip(neons, m_neons)]
    city += [(e, m) for e, m in zip(edges, m_edge)]
    g.node('scroll_120', children=[g.mesh_node('city', city)], root=True)

    # --- fixe : sol lointain, silhouette de la ville, ciel
    # sol lointain sous la chaussée, assez bas pour laisser voir le reflet de la voiture
    ground = Mesh().quad((-600, -2.5, 600), (1200, 0, 0), (0, 0, -1200))
    skyline = Mesh().cylinder((0, -2, 0), 460, 95, seg=48, top=False, inward=True, urep=3, vrep=1)
    dome = Mesh()
    P, N, T, I = [], [], [], []
    seg, rings, Rd = 32, 8, 520
    for r in range(rings + 1):
        el = math.pi / 2 * r / rings
        for i in range(seg + 1):
            az = 2 * math.pi * i / seg
            d = (math.cos(el) * math.cos(az), math.sin(el), math.cos(el) * math.sin(az))
            P.append((Rd * d[0], Rd * d[1] - 10, Rd * d[2]))
            N.append((-d[0], -d[1], -d[2]))
            T.append((i / seg, 1 - r / rings))
    for r in range(rings):
        for i in range(seg):
            a = r * (seg + 1) + i
            b = a + seg + 1
            I += [a, b, a + 1, a + 1, b, b + 1]
    dome.add(P, N, T, I)
    shadow = Mesh().quad((-2.75, 0.01, 1.25), (5.5, 0, 0), (0, 0, -2.5))
    # Soleil couchant au bout de la route, derrière la silhouette de la ville
    sun = Mesh().quad((505, -14, -52), (0, 0, 104), (0, 104, 0))
    halo = Mesh().quad((512, -110, -150), (0, 0, 300), (0, 300, 0))
    g.node('route_static', children=[
        g.mesh_node('far', [(ground, m_ground), (skyline, m_skyline), (dome, m_sky)]),
        g.mesh_node('sun', [(sun, m_sun), (halo, m_halo)]),
    ], root=True)
    # Voitures de la circulation (déplacées par l'appli)
    tcar = traffic_car()
    mesh = g.mesh('traffic_car', [(tcar[0], m_tbody), (tcar[1], m_tglass), (tcar[2], m_trubber),
                                  (tcar[3], m_thead), (tcar[4], m_ttail)])
    for k in range(4):
        g.node('traffic_%d' % k, mesh=mesh, t=(-500, 0, 0), root=True)
    g.node('route_shadow', children=[g.mesh_node('blob_shadow', [(shadow, m_shadow)])], root=True)
    return g.save(path)


if __name__ == '__main__':
    out = sys.argv[1] if len(sys.argv) > 1 else '.'
    print('garage.glb', build_garage(os.path.join(out, 'garage.glb')))
    print('route.glb', build_route(os.path.join(out, 'route.glb')))
