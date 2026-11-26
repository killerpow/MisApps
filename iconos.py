"""Genera el icono y el banner de TV de cada app (se ejecuta una vez y se suben los PNG)."""
import json, os
from PIL import Image, ImageDraw, ImageFont

FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
SIZES = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}

def hex2rgb(h):
    h = h.lstrip('#'); return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))

for a in json.load(open('apps.json', encoding='utf-8')):
    col = hex2rgb(a['color'])
    base = os.path.join('app', 'src', a['id'], 'res')
    for d, s in SIZES.items():
        big = 512
        im = Image.new('RGBA', (big, big), (0, 0, 0, 0))
        dr = ImageDraw.Draw(im)
        dr.rounded_rectangle([16, 16, big - 16, big - 16], radius=110, fill=col)
        dr.rounded_rectangle([16, 16, big - 16, big // 2], radius=110, fill=tuple(min(255, c + 25) for c in col))
        dr.rectangle([16, big // 2 - 120, big - 16, big // 2], fill=tuple(min(255, c + 25) for c in col))
        dr.rectangle([16, big // 2, big - 16, big // 2 + 110], fill=col)
        letra = a['letra']
        f = ImageFont.truetype(FONT, 300 if len(letra) == 1 else 200)
        w = dr.textlength(letra, font=f)
        dr.text(((big - w) / 2, big / 2), letra, font=f, fill='white', anchor='lm')
        out = os.path.join(base, 'mipmap-' + d)
        os.makedirs(out, exist_ok=True)
        im.resize((s, s), Image.LANCZOS).save(os.path.join(out, 'ic_launcher.png'))
    # Banner para Android TV (320x180)
    b = Image.new('RGB', (640, 360), col)
    dr = ImageDraw.Draw(b)
    f = ImageFont.truetype(FONT, 64)
    name = a['name']
    while dr.textlength(name, font=f) > 580:
        f = ImageFont.truetype(FONT, f.size - 4)
    dr.text((320, 180), name, font=f, fill='white', anchor='mm')
    out = os.path.join(base, 'drawable-xhdpi')
    os.makedirs(out, exist_ok=True)
    b.save(os.path.join(out, 'banner.png'))
    print('icono:', a['id'])
