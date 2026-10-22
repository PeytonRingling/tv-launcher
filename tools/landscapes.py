"""Procedurally generates the built-in ambient landscapes (original artwork, free to use)."""
import numpy as np, math, sys
from PIL import Image, ImageFilter
W, H = 1920, 1080
rng = np.random.default_rng(7)
yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)

def lerp(a, b, t): return a + (b - a) * t
def grad(stops):
    t = yy / H; img = np.zeros((H, W, 3), np.float32)
    for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
        m = (t >= p0) & (t <= p1); k = ((t - p0) / max(p1 - p0, 1e-6))[..., None]
        img = np.where(m[..., None], lerp(np.array(c0, np.float32), np.array(c1, np.float32), k), img)
    return img
def glow(img, cx, cy, r, col, s=1.0):
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2); a = np.exp(-(d / r) ** 2)[..., None] * s
    return img + a * np.array(col, np.float32)
def ridge(base, amp, octaves, seed, rough=0.5):
    r = np.random.default_rng(seed); x = np.arange(W, dtype=np.float32); y = np.full(W, base, np.float32)
    f, a = 1 / 1500, amp
    for _ in range(octaves):
        ph = r.uniform(0, 1000); y += a * (np.sin(x * f * 2 * math.pi + ph) * 0.6 + np.sin(x * f * 4.7 * math.pi + ph * 1.3) * 0.4)
        f *= 2.1; a *= rough
    return y
def layer(img, top, col, haze=None, hazeAmt=0.0):
    m = (yy >= top[None, :])
    c = np.array(col, np.float32)
    if haze is not None: c = lerp(c, np.array(haze, np.float32), hazeAmt * 0.55)
    shade = (1 - 0.25 * np.clip((yy - top.min()) / 500, 0, 1))[..., None]
    return np.where(m[..., None], c[None, None, :] * shade, img)
def stars(img, n, maxy):
    for _ in range(n):
        x, y = rng.integers(0, W), rng.integers(0, int(maxy)); b = rng.uniform(0.3, 1.0) ** 3 * 255
        img[y, x] = np.minimum(img[y, x] + b, 255)
        if b > 150 and 0 < x < W - 1 and 0 < y < H - 1:
            img[y, x - 1] += b * .35; img[y, x + 1] += b * .35; img[y - 1, x] += b * .35; img[y + 1, x] += b * .35
    return img
def save(img, name, blur=0.6):
    noise = rng.normal(0, 2.2, img.shape).astype(np.float32)
    im = Image.fromarray(np.clip(img + noise, 0, 255).astype(np.uint8))
    if blur: im = im.filter(ImageFilter.GaussianBlur(blur))
    im.save(name, quality=88, optimize=True, progressive=True)

out = sys.argv[1]
# 1 Alpine dawn: pastel sky, layered blue ridges, mist
s = grad([(0, (40, 52, 98)), (0.35, (196, 140, 170)), (0.55, (250, 196, 170)), (1, (250, 210, 180))])
s = glow(s, 1250, 560, 520, (255, 190, 120), 0.55)
for i, (b, a, c, h) in enumerate([(520, 140, (96, 98, 140), .9), (610, 110, (70, 72, 112), .7), (700, 90, (46, 48, 82), .5), (800, 60, (26, 28, 52), .3), (900, 40, (14, 15, 30), 0)]):
    s = layer(s, ridge(b, a, 5, 10 + i, 0.36), c, haze=(232, 190, 186), hazeAmt=h)
    s = glow(s, 960, b + 60, 1400, (255, 220, 220), 0.05)
save(s, f"{out}/landscape_1.jpg")
# 2 Canyon golden hour
s = grad([(0, (34, 30, 62)), (0.3, (150, 80, 70)), (0.5, (246, 160, 80)), (1, (255, 200, 120))])
s = glow(s, 560, 590, 300, (255, 230, 170), 0.9)
for i, (b, a, c) in enumerate([(600, 50, (150, 80, 60)), (660, 40, (110, 56, 46)), (740, 70, (72, 36, 34)), (860, 50, (40, 20, 24)), (960, 30, (20, 10, 14))]):
    s = layer(s, ridge(b, a, 4, 30 + i, 0.3), c, haze=(246, 170, 110), hazeAmt=.6 - i * .12)
save(s, f"{out}/landscape_2.jpg")
# 3 Night dunes + milky way
s = grad([(0, (6, 8, 26)), (0.6, (28, 30, 78)), (1, (40, 44, 96))])
band = np.exp(-(((yy - (H * 0.95 - xx * 0.55)) / 120) ** 2))[..., None]
s = s + band * np.array([70, 60, 110], np.float32) * (0.6 + 0.4 * rng.random((H, W, 1)).astype(np.float32))
s = stars(s, 3500, 760)
for i, (b, a, c) in enumerate([(700, 60, (38, 40, 90)), (790, 80, (30, 30, 72)), (900, 60, (20, 20, 52))]):
    s = layer(s, ridge(b, a, 3, 50 + i, 0.35), c)
save(s, f"{out}/landscape_3.jpg", 0.4)
# 4 Ocean twilight: horizon, sea gradient, sun reflection
s = grad([(0, (20, 40, 84)), (0.45, (120, 160, 200)), (0.52, (250, 210, 180)), (0.53, (60, 110, 150)), (1, (8, 28, 54))])
s = glow(s, 960, 570, 140, (255, 230, 200), 0.9)
ref = (np.exp(-((xx - 960) / 70) ** 2) * (yy > 575) * np.exp(-(yy - 575) / 380) * (0.6 + 0.4 * np.sin(yy * 0.9) ** 2))[..., None]
s = s + ref * np.array([255, 210, 160], np.float32) * 0.8
save(s, f"{out}/landscape_4.jpg", 1.0)
# 5 Aurora over lake + forest silhouettes
s = grad([(0, (4, 10, 24)), (0.6, (10, 34, 52)), (1, (6, 18, 30))])
for k, (col, off) in enumerate([((40, 220, 140), 0), ((90, 120, 230), 120)]):
    cy = 330 + off + 60 * np.sin(xx / 420 + k) + 18 * np.sin(xx / 157)
    curtain = np.exp(-((yy - cy) / 40) ** 2) * (0.75 + 0.25 * np.sin(xx / 37 + k) ** 2)
    tail = np.exp(-np.clip(cy - yy, 0, None) / 110) * (yy < cy) * (0.5 + 0.5 * np.sin(xx / 11 + k * 3) ** 2)
    s = s + (curtain + tail * 0.35)[..., None] * np.array(col, np.float32) * (0.55 if k == 0 else 0.3)
s = stars(s, 1500, 640)
shore = 700 + 10 * np.sin(np.arange(W) / 150)
trees = shore.copy() - 8
for _ in range(220):
    tx = rng.uniform(0, W); th = rng.uniform(50, 210) * (0.6 + 0.4 * abs(math.sin(tx / 400))); tw = th * rng.uniform(0.16, 0.24)
    cols = np.arange(max(0, int(tx - tw)), min(W, int(tx + tw) + 1))
    trees[cols] = np.minimum(trees[cols], shore[cols] - th * (1 - np.abs(cols - tx) / tw))
s = layer(s, trees.astype(np.float32), (4, 10, 14))
lake = yy > 740
mir = s[np.clip((2 * 740 - yy).astype(int), 0, H - 1), xx.astype(int)] * 0.45
s = np.where(lake[..., None], mir + np.array([2, 8, 14], np.float32), s)
save(s, f"{out}/landscape_5.jpg", 0.8)
print("done")
