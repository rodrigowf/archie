"""Regenerates memory-links.tsv (B-07 link-resolver fixture, spec 14 §4.1).

1. GET the live tree read-only:  curl -s http://<jetson>/api/memory/tree > live-tree.json
2. python3 gen_links.py  (reads every file of the live tree from context/memory/, extracts each
   markdown link once per (file, href), and classifies it with an independent reference of MEM-2:
   posixpath-style normalisation, urljoin for targets outside the memory root).
"""
import json, re, os, posixpath, urllib.parse, urllib.request
tree = json.load(open('live-tree.json'))
files = []
def walk(n):
    for x in n:
        if x['is_dir']: walk(x['children'] or [])
        else: files.append(x['path'])
walk(tree)
fileset = set(files)
MEM = '/home/rodrigo/assistant/context/memory'
SCHEME = re.compile(r'^[a-zA-Z][a-zA-Z0-9+.-]*:')
LINK = re.compile(r'\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)')
def norm(segs):
    out = []
    for raw in segs:
        s = urllib.parse.unquote(raw)
        if s in ('', '.'): continue
        if s == '..':
            if not out: return None
            out.pop(); continue
        out.append(s)
    return '/'.join(out) if out else None
def classify(frm, href):
    h = href.strip()
    if h.startswith('#'): return ('anchor', h[1:], '', '')
    if h.startswith('//') or SCHEME.match(h): return ('external', h, '', '')
    frag = h.split('#', 1)[1] if '#' in h else ''
    path = h.split('#', 1)[0].split('?', 1)[0]
    if path.startswith('/memory/') and urllib.parse.unquote(path).lower().endswith('.md'):
        p = norm(path[len('/memory/'):].split('/'))
        if p: return ('memory', p, frag, 'true' if p in fileset else 'false')
    if not path.startswith('/') and urllib.parse.unquote(path).lower().endswith('.md'):
        base = frm.split('/')[:-1]
        p = norm(base + path.split('/'))
        if p: return ('memory', p, frag, 'true' if p in fileset else 'false')
    if h.startswith('/'): return ('backend', h, '', '')
    # relative non-memory target / escapes the root → opened externally, resolved like a browser would
    u = urllib.parse.urlsplit(urllib.parse.urljoin('http://h/memory/' + frm, h))
    return ('outside', u.path + ('?' + u.query if u.query else '') + ('#' + u.fragment if u.fragment else ''), '', '')
rows = set()
inside_code = re.compile(r'```.*?```', re.S)
for f in files:
    p = os.path.join(MEM, f)
    if not os.path.exists(p): continue
    text = inside_code.sub('', open(p, encoding='utf-8', errors='replace').read())
    for m in LINK.finditer(text):
        rows.add((f, m.group(1)))
out = ['# from\thref\tkind\ttarget\tfragment\tinTree  (generated from the live /api/memory/tree + context/memory links, B-07)']
for f, h in sorted(rows):
    k = classify(f, h)
    out.append('\t'.join([f, h] + list(k)))
open('memory-links.tsv', 'w').write('\n'.join(out) + '\n')
from collections import Counter
print(len(rows), Counter(classify(f,h)[0] for f,h in rows), Counter(classify(f,h)[3] for f,h in rows))
