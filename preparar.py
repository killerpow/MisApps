"""Copia cada app web de apps/<id>/ dentro de su app Android y le añade el puente con Android (shim.js)."""
import json, os, re, shutil

APPS = json.load(open('apps.json', encoding='utf-8'))
TAG = '<script src="shim.js"></script>'

for a in APPS:
    src = os.path.join('apps', a['id'])
    if not os.path.exists(os.path.join(src, 'index.html')):
        print('-- sin preparar:', a['id'])
        continue
    dst = os.path.join('app', 'src', a['id'], 'assets')
    shutil.rmtree(dst, ignore_errors=True)
    os.makedirs(dst)
    for name in os.listdir(src):
        p = os.path.join(src, name)
        if os.path.isdir(p):
            shutil.copytree(p, os.path.join(dst, name))
            continue
        if name.endswith('.html'):
            html = open(p, encoding='utf-8').read()
            m = re.search(r'<head[^>]*>', html, re.I)
            if m:
                html = html[:m.end()] + TAG + html[m.end():]
            else:
                html = TAG + html
            open(os.path.join(dst, name), 'w', encoding='utf-8').write(html)
        else:
            shutil.copy(p, dst)
    print('ok:', a['id'])
