"""Evidence helper: measure IME screenshot pixels and parse uiautomator dumps.

Usage:
  python measure_ime.py shot <png> [x0 x1]   # nav-bar top + bottom key-row face bottom + gap
  python measure_ime.py focus <ui.xml>       # focused EditText text/bounds/password
  python measure_ime.py rows <png>           # white key-row clusters (top,bottom)
"""
import sys
import re
from PIL import Image

NAV_RGB = (136, 138, 143)


def nav_top(px, w, h):
    for y in range(h - 1, 0, -1):
        hit = 0
        total = 0
        for x in range(0, w, 4):
            c = px[x, y]
            total += 1
            if abs(c[0] - NAV_RGB[0]) <= 6 and abs(c[1] - NAV_RGB[1]) <= 6 and abs(c[2] - NAV_RGB[2]) <= 6:
                hit += 1
        if hit / total < 0.55:
            return y + 1
    return None


def white_frac(px, y, x0, x1):
    n = 0
    t = 0
    for x in range(x0, x1, 2):
        t += 1
        c = px[x, y]
        if c[0] >= 250 and c[1] >= 250 and c[2] >= 250:
            n += 1
    return n / t


def key_row_clusters(px, w, h, y0=None, y1=None, x0=0, x1=None):
    if y0 is None:
        y0 = h // 3
    if y1 is None:
        y1 = nav_top(px, w, h) or h
    if x1 is None:
        x1 = w
    rows = [y for y in range(y0, y1) if white_frac(px, y, x0, x1) > 0.30]
    clusters = []
    start = prev = None
    for y in rows:
        if start is None:
            start = y
        elif y - prev > 3:
            clusters.append((start, prev))
            start = y
        prev = y
    if start is not None:
        clusters.append((start, prev))
    return clusters


def shot(path):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    px = im.load()
    nt = nav_top(px, w, h)
    clusters = key_row_clusters(px, w, h)
    key_bottom = clusters[-1][1] if clusters else None
    print(f"image={path} size={w}x{h}")
    print(f"nav_top={nt}")
    print(f"key_row_clusters={clusters}")
    print(f"last_key_row_bottom={key_bottom}")
    if nt is not None and key_bottom is not None:
        print(f"gap_px={nt - key_bottom}")


def focus(path):
    s = open(path, encoding="utf-8-sig").read()
    for m in re.finditer(r'resource-id="app\.agentime:id/(editToken|editBackend)"[^>]*', s):
        seg = m.group(0)
        t = re.search(r'text="([^"]*)"', seg)
        f = re.search(r'focused="(true|false)"', seg)
        b = re.search(r'bounds="([^"]*)"', seg)
        p = re.search(r'password="(true|false)"', seg)
        print(m.group(1), "text=", repr(t.group(1) if t else None),
              "focused=", f.group(1) if f else None,
              "password=", p.group(1) if p else None,
              "bounds=", b.group(1) if b else None)


def rows(path):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    px = im.load()
    print(f"image={path} size={w}x{h}")
    print(f"nav_top={nav_top(px, w, h)}")
    print(f"key_row_clusters={key_row_clusters(px, w, h)}")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "shot":
        shot(sys.argv[2])
    elif cmd == "focus":
        focus(sys.argv[2])
    elif cmd == "rows":
        rows(sys.argv[2])
    else:
        raise SystemExit("unknown command")
