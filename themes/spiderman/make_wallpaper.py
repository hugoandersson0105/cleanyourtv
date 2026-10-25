#!/usr/bin/env python3
"""Generate a 1280x720 Spider-Man-inspired + motivational-quote wallpaper collection.

Original artwork only (gradients, web lines, a drawn spider silhouette), dark enough for the
launcher's white type and the app tiles to stay readable. Quotes are interleaved with the art
cards so a slideshow alternates the two. Writes to work/theme-slideshow/wallpapers/.
"""
import math
import os
import random

from PIL import Image, ImageDraw, ImageFont, ImageFilter

W, H = 1280, 720
FONT = "launcher/app/src/main/res/font/outfit.ttf"
OUT = "work/theme-slideshow/wallpapers"

ACCENTS = {
    "red": (226, 54, 54),
    "blue": (47, 111, 237),
    "gold": (240, 180, 41),
    "teal": (47, 212, 196),
    "violet": (150, 92, 240),
    "crimson": (200, 30, 60),
}

# Two per art card, so twelve quotes interleave with six art cards exactly.
QUOTES = [
    ("With great power comes great responsibility.", "Uncle Ben", "red"),
    ("Anyone can wear the mask.", "Into the Spider-Verse", "blue"),
    ("Fall seven times, stand up eight.", "Japanese proverb", "gold"),
    ("It always seems impossible until it is done.", "Nelson Mandela", "teal"),
    ("Courage is not the absence of fear,\nbut the triumph over it.", "Nelson Mandela", "red"),
    ("The best time to plant a tree was twenty years ago.\nThe second best time is now.", "Chinese proverb", "blue"),
    ("You miss one hundred percent of the shots you don't take.", "Wayne Gretzky", "gold"),
    ("Stay hungry. Stay foolish.", "Steve Jobs", "violet"),
    ("Not all heroes wear capes.", "Anonymous", "crimson"),
    ("When you can't find the sunshine, be the sunshine.", "Anonymous", "teal"),
    ("It's not who I am underneath,\nbut what I do that defines me.", "Batman Begins", "crimson"),
    ("The only way out is through.", "Robert Frost", "violet"),
]

ART = [
    ("red", (1180, 40), (300, 210), 150),
    ("blue", (100, 40), (980, 220), 150),
    ("gold", (1180, 660), (330, 250), 130),
    ("teal", (80, 680), (940, 240), 130),
    ("violet", (640, -80), (640, 250), 160),
    ("crimson", (1180, 360), (250, 250), 140),
]


def font(size, weight):
    f = ImageFont.truetype(FONT, size)
    try:
        f.set_variation_by_name(weight)
    except Exception:
        pass
    return f


def base(seed, accent):
    rnd = random.Random(seed)
    im = Image.new("RGB", (W, H), (4, 4, 7))
    d = ImageDraw.Draw(im)
    for y in range(H):
        t = y / H
        v = int(9 - 5 * t)
        d.line([(0, y), (W, y)], fill=(v, v, v + 2))
    glow = Image.new("RGB", (W, H), (0, 0, 0))
    gd = ImageDraw.Draw(glow)
    cx, cy = rnd.choice([(1180, 60), (100, 640), (1150, 620), (90, 90)])
    r = 720
    for i in range(60, 0, -1):
        rr = int(r * i / 60)
        a = (1 - i / 60) ** 2
        col = tuple(int(c * a * 0.5) for c in accent)
        gd.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=col)
    glow = glow.filter(ImageFilter.GaussianBlur(90))
    return Image.blend(im, glow, 0.55)


def lift(accent, amount):
    """Blend an accent toward white so line art carries on a near-black background."""
    return tuple(int(c + (255 - c) * amount) for c in accent)


def web(im, accent, origin, alpha=26, spokes=16, rings=7, seed=1):
    rnd = random.Random(seed)
    ov = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    col = lift(accent, 0.3) + (alpha,)
    ox, oy = origin
    maxr = int(math.hypot(W, H))
    for i in range(spokes):
        ang = 2 * math.pi * i / spokes + rnd.uniform(-0.03, 0.03)
        d.line([(ox, oy), (ox + maxr * math.cos(ang), oy + maxr * math.sin(ang))], fill=col, width=2)
    for k in range(1, rings + 1):
        r = maxr * (k / (rings + 1.4))
        d.arc([ox - r, oy - r, ox + r, oy + r], 0, 360, fill=col, width=2)
    im.paste(Image.alpha_composite(im.convert("RGBA"), ov).convert("RGB"), (0, 0))


def spider(im, accent, cx, cy, size, alpha=95):
    ov = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    col = lift(accent, 0.45) + (alpha,)
    lw = max(3, int(size * 0.035))
    for side in (-1, 1):
        for dy, spread, rise in [(-0.42, 0.95, -0.34), (-0.18, 1.15, -0.12),
                                 (0.08, 1.1, 0.1), (0.32, 0.92, 0.3)]:
            x0 = cx + side * size * 0.12
            y0 = cy + size * dy
            jx = cx + side * size * spread * 0.55
            jy = cy + size * (dy + rise * 0.45)
            tx = cx + side * size * spread
            ty = cy + size * (dy + rise)
            d.line([(x0, y0), (jx, jy)], fill=col, width=lw)
            d.line([(jx, jy), (tx, ty)], fill=col, width=lw)
            d.ellipse([jx - lw, jy - lw, jx + lw, jy + lw], fill=col)
    d.ellipse([cx - size * 0.2, cy - size * 0.02, cx + size * 0.2, cy + size * 0.45], fill=col)
    d.ellipse([cx - size * 0.12, cy - size * 0.3, cx + size * 0.12, cy + size * 0.02], fill=col)
    im.paste(Image.alpha_composite(im.convert("RGBA"), ov).convert("RGB"), (0, 0))


def vignette(im):
    mask = Image.new("L", (W, H), 0)
    ImageDraw.Draw(mask).ellipse([-W * 0.25, -H * 0.45, W * 1.25, H * 1.45], fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(160))
    return Image.composite(im, Image.new("RGB", (W, H), (0, 0, 0)), mask)


def wrap(draw, text, fnt, maxw):
    out = []
    for para in text.split("\n"):
        line = ""
        for word in para.split():
            trial = (line + " " + word).strip()
            if draw.textlength(trial, font=fnt) <= maxw or not line:
                line = trial
            else:
                out.append(line)
                line = word
        out.append(line)
    return out


def quote_card(im, text, who, accent):
    """Quote block sits between the clock (ends ~y150) and the dock title (~y370)."""
    d = ImageDraw.Draw(im)
    fa = font(22, "Medium")
    # Shrink long quotes rather than run into the dock title band below.
    for size, maxw in ((44, 860), (42, 900), (40, 940), (38, 980), (36, 1020)):
        fq = font(size, "Regular")
        lines = wrap(d, text, fq, maxw)
        if len(lines) <= 3:
            break
    leading = size + 6
    y = 168
    d.rectangle([56, y + 4, 60, y + 22 + leading * (len(lines) - 1)], fill=accent)
    for line in lines:
        d.text((92, y), line, font=fq, fill=(247, 247, 250))
        y += leading
    y += 16
    d.text((92, y), "\u2014 " + who, font=fa, fill=(176, 176, 188))
    return im


def art_card(accent, origin, spot, size, seed):
    im = base(100 + seed, ACCENTS[accent])
    web(im, ACCENTS[accent], origin, alpha=48, seed=seed)
    spider(im, ACCENTS[accent], spot[0], spot[1], size, alpha=200)
    return vignette(im)


def slug(text):
    return "".join(c if c.isalnum() else "-" for c in text.lower()).strip("-")[:20].strip("-")


def main():
    os.makedirs(OUT, exist_ok=True)
    for old in os.listdir(OUT):
        if old.lower().endswith((".jpg", ".jpeg", ".png", ".webp")):
            os.unlink(os.path.join(OUT, old))

    # Quotes come in pairs, then an art card: a slideshow alternates the two and ends on art.
    plan = []
    for i, (text, who, colour) in enumerate(QUOTES):
        plan.append(("quote", (text, who, colour)))
        if i % 2 == 1:
            plan.append(("art", ART[i // 2]))

    n = 0
    for kind, item in plan:
        n += 1
        if kind == "quote":
            text, who, colour = item
            accent = ACCENTS[colour]
            im = base(200 + n, accent)
            web(im, accent, [(1180, 40), (100, 60), (1160, 660), (90, 660)][n % 4], alpha=22, seed=10 + n)
            spider(im, accent, 1110 if n % 2 else 200, 250, 96, alpha=70)
            im = vignette(quote_card(im, text, who, accent))
            im.save(f"{OUT}/{n:02d}-quote-{slug(who)}.jpg", quality=92)
        else:
            accent, origin, spot, size = item
            im = art_card(accent, origin, spot, size, n)
            im.save(f"{OUT}/{n:02d}-web-{accent}.jpg", quality=92)

    print(f"wrote {n} images to {OUT}")


if __name__ == "__main__":
    main()
