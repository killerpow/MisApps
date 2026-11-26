"""Genera el icono (degradado + dibujo) y el banner de TV de cada app. Si existe apps/<id>/icono.png, usa esa imagen."""
import json, os
from PIL import Image, ImageDraw, ImageFont

EMOJI = '/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf'
BOLD = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
SIZES = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}

def rgb(h): h = h.lstrip('#'); return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))

def gradient(w, h, c1, c2):
    g = Image.new('RGB', (w, h))
    px = g.load()
    for y in range(h):
        for x in range(w):
            t = (x + y) / (w + h - 2)
            px[x, y] = tuple(int(c1[i] * (1 - t) + c2[i] * t) for i in range(3))
    return g

def emoji_img(ch, size):
    f = ImageFont.truetype(EMOJI, 109)  # tamaño nativo de Noto Color Emoji
    im = Image.new('RGBA', (160, 160), (0, 0, 0, 0))
    ImageDraw.Draw(im).text((80, 80), ch, font=f, embedded_color=True, anchor='mm')
    im = im.crop(im.getbbox())
    k = size / max(im.size)
    return im.resize((max(1, int(im.width * k)), max(1, int(im.height * k))), Image.LANCZOS)

def make_icon(a):
    big = 512
    custom = os.path.join('apps', a['id'], 'icono.png')
    mask = Image.new('L', (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle([8, 8, big - 8, big - 8], radius=120, fill=255)
    if os.path.exists(custom):
        src = Image.open(custom).convert('RGBA')
        flat = Image.new('RGBA', src.size, (255, 255, 255, 255)); flat.alpha_composite(src); src = flat
        c = src.getpixel((2, 2))
        if all(v > 225 for v in c[:3]):
            # Fondo claro: recortar el margen y centrar el dibujo en un cuadrado blanco
            from PIL import ImageChops
            diff = ImageChops.difference(src.convert('RGB'), Image.new('RGB', src.size, c[:3])).convert('L').point(lambda v: 255 if v > 40 else 0)
            box = diff.getbbox() or (0, 0, src.width, src.height)
            src = src.crop(box)
            side = int(max(src.size) * 1.16)
            sq = Image.new('RGBA', (side, side), (255, 255, 255, 255))
            sq.paste(src, ((side - src.width) // 2, (side - src.height) // 2))
            src = sq
        else:
            s = min(src.size); src = src.crop(((src.width - s) // 2, (src.height - s) // 2, (src.width + s) // 2, (src.height + s) // 2))
        bg = src.resize((big, big), Image.LANCZOS)
    else:
        bg = gradient(big, big, rgb(a['color']), rgb(a['color2'])).convert('RGBA')
        hl = Image.new('RGBA', bg.size, (0, 0, 0, 0))
        ImageDraw.Draw(hl).ellipse([-120, -160, 380, 300], fill=(255, 255, 255, 40))
        bg = Image.alpha_composite(bg, hl)
        e = emoji_img(a['emoji'], 300)
        sh = Image.new('RGBA', bg.size, (0, 0, 0, 0))
        alpha = e.split()[3].point(lambda v: v * 0.35)
        shadow = Image.new('RGBA', e.size, (0, 0, 0, 255)); shadow.putalpha(alpha)
        x, y = (big - e.width) // 2, (big - e.height) // 2
        sh.paste(shadow, (x + 8, y + 14), shadow)
        bg = Image.alpha_composite(bg, sh)
        bg.paste(e, (x, y), e)
    out = Image.new('RGBA', (big, big), (0, 0, 0, 0)); out.paste(bg, (0, 0), mask)
    return out

for a in json.load(open('apps.json', encoding='utf-8')):
    base = os.path.join('app', 'src', a['id'], 'res')
    icon = make_icon(a)
    for d, s in SIZES.items():
        o = os.path.join(base, 'mipmap-' + d); os.makedirs(o, exist_ok=True)
        icon.resize((s, s), Image.LANCZOS).save(os.path.join(o, 'ic_launcher.png'))
    # Banner TV (si hay apps/<id>/banner.png se usa esa imagen)
    cb = os.path.join('apps', a['id'], 'banner.png')
    if os.path.exists(cb):
        from PIL import ImageFilter
        src = Image.open(cb).convert('RGB')
        fondo = src.resize((640, 360), Image.LANCZOS).filter(ImageFilter.GaussianBlur(18))
        k = 360 / src.height; im = src.resize((int(src.width * k), 360), Image.LANCZOS)
        fondo.paste(im, ((640 - im.width) // 2, 0))
        o = os.path.join(base, 'drawable-xhdpi'); os.makedirs(o, exist_ok=True)
        fondo.save(os.path.join(o, 'banner.png'))
        print('icono:', a['id']); continue
    b = gradient(640, 360, rgb(a['color']), rgb(a['color2'])).convert('RGBA')
    ic = icon.resize((220, 220), Image.LANCZOS); b.paste(ic, (40, 70), ic)
    d = ImageDraw.Draw(b); f = ImageFont.truetype(BOLD, 52); name = a['name']
    while d.textlength(name, font=f) > 330: f = ImageFont.truetype(BOLD, f.size - 4)
    d.text((290, 180), name, font=f, fill='white', anchor='lm')
    o = os.path.join(base, 'drawable-xhdpi'); os.makedirs(o, exist_ok=True)
    b.convert('RGB').save(os.path.join(o, 'banner.png'))
    print('icono:', a['id'])
