#!/usr/bin/env python3
"""Generate the Fridah Tech launcher icons (dark neon gradient + glowing bolt)."""
from PIL import Image, ImageDraw, ImageFilter
import math, os

SIZES = {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96, "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}
OUT = "/home/z/my-project/work/app/res"

C1 = (10, 16, 36)      # top-left deep navy
C2 = (34, 10, 58)      # bottom-right purple
CYAN = (0, 229, 255)
PURPLE = (124, 77, 255)
GLOW = (0, 229, 255)

def base(size):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    # diagonal gradient
    grad = Image.new("RGBA", (size, size))
    px = grad.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * size)
            r = int(C1[0] + (C2[0] - C1[0]) * t)
            g = int(C1[1] + (C2[1] - C1[1]) * t)
            b = int(C1[2] + (C2[2] - C1[2]) * t)
            px[x, y] = (r, g, b, 255)
    # rounded mask
    mask = Image.new("L", (size, size), 0)
    md = ImageDraw.Draw(mask)
    rad = int(size * 0.22)
    md.rounded_rectangle([0, 0, size - 1, size - 1], radius=rad, fill=255)
    img.paste(grad, (0, 0), mask)

    # neon ring
    ring = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    rd = ImageDraw.Draw(ring)
    m = size * 0.16
    rd.arc([m, m, size - m, size - m], start=110, end=410, fill=CYAN + (255,), width=max(2, int(size * 0.055)))
    rd.arc([m, m, size - m, size - m], start=290, end=470, fill=PURPLE + (255,), width=max(2, int(size * 0.055)))
    glow_r = ring.filter(ImageFilter.GaussianBlur(size * 0.05))
    img.alpha_composite(glow_r)
    img.alpha_composite(ring)

    # bolt glyph
    bolt = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    bd = ImageDraw.Draw(bolt)
    u = size / 48.0
    pts = [(27, 8), (14, 27), (22, 27), (20, 40), (34, 21), (25, 21)]
    poly = [(x * u * 48 / 48, y * u) for (x, y) in pts]
    bd.polygon(poly, fill=(255, 255, 255, 235))
    glow_b = bolt.filter(ImageFilter.GaussianBlur(size * 0.035))
    img.alpha_composite(glow_b)
    img.alpha_composite(bolt)
    return img

for folder, s in SIZES.items():
    d = os.path.join(OUT, folder)
    os.makedirs(d, exist_ok=True)
    base(s).save(os.path.join(d, "ic_launcher.png"))
    print("wrote", folder, s)
print("icons done")
