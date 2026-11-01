#!/usr/bin/env python3
# Shoalcade for Windows: the app icon (.ico + .png) from docs/assets/logo.svg. Part of Shoalcade, GPL-3.0-or-later.
#
#   make_icons.py <logo.svg> <out dir> [--dev]
#
# The logo is small and fixed: a rounded square with a diagonal gradient and white pixel-art squares (an M/h/v/z path
# of axis-aligned 1x1 cells). This build machine has no SVG renderer, so this script reads exactly those three things
# from the SVG and draws them itself (4x4 supersampling). Anything else in the SVG stops the script, so a changed logo
# can't silently give a wrong icon.
#
# --dev: the "Shoalcade Dev" tile, orange #FF8A00 to red #D7263D, as the phone's Dev icon
# (android/app/src/debug/res/drawable/ic_launcher_background.xml, D152).
#
# Writes shoalcade.ico (16-256 px; 256 as PNG, the rest as 32-bit BMP for older tools) and shoalcade-256.png.
import re
import struct
import sys
import zlib

SIZES = [16, 20, 24, 32, 40, 48, 64, 96, 128, 256]
SS = 4  # supersamples per axis


def parse(svg):
    if not re.search(r'viewBox="0 0 24 24"', svg):
        sys.exit("make_icons: logo.svg viewBox is not 0 0 24 24")
    stops = re.findall(r'<stop offset="([01])" stop-color="#([0-9A-Fa-f]{6})"', svg)
    grad = re.search(r'<linearGradient id="sg" x1="0" y1="0" x2="1" y2="1">', svg)
    rect = re.search(r'<rect width="24" height="24" rx="([0-9.]+)" fill="url\(#sg\)"/>', svg)
    path = re.search(r'<path fill="#FFFFFF"[^>]* d="([^"]+)"/>', svg)
    if not (len(stops) == 2 and grad and rect and path):
        sys.exit("make_icons: logo.svg is not the expected shape (gradient tile + white path); update this script")
    if len(re.findall(r'<(rect|path|circle|ellipse|polygon|line|g|image|text)\b', svg)) != 2:
        sys.exit("make_icons: logo.svg has more shapes than this script draws; update it")
    cells = []
    for m in re.finditer(r'M(\d+) (\d+)h(\d+)v(\d+)h-(\d+)z', path.group(1)):
        x, y, w, h, back = map(int, m.groups())
        if back != w:
            sys.exit("make_icons: unexpected path segment " + m.group(0))
        cells.append((x, y, w, h))
    if re.sub(r'M\d+ \d+h\d+v\d+h-\d+z', '', path.group(1)).strip():
        sys.exit("make_icons: path has segments this script doesn't understand")
    col = lambda h: tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))
    return float(rect.group(1)), col(stops[0][1]), col(stops[1][1]), cells


def inside_round_rect(u, v, r):
    cx = min(max(u, r), 24 - r)
    cy = min(max(v, r), 24 - r)
    return (u - cx) ** 2 + (v - cy) ** 2 <= r * r


def render(n, rx, c0, c1, cells):
    """RGBA rows, top to bottom."""
    white = [[False] * 24 for _ in range(24)]
    for x, y, w, h in cells:
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                white[yy][xx] = True
    rows = []
    for py in range(n):
        row = bytearray()
        for px in range(n):
            acc = [0.0, 0.0, 0.0]
            cover = 0
            for sy in range(SS):
                for sx in range(SS):
                    u = (px + (sx + 0.5) / SS) * 24.0 / n
                    v = (py + (sy + 0.5) / SS) * 24.0 / n
                    if not inside_round_rect(u, v, rx):
                        continue
                    cover += 1
                    if white[min(int(v), 23)][min(int(u), 23)]:
                        c = (255, 255, 255)
                    else:
                        t = (u + v) / 48.0  # objectBoundingBox (0,0)->(1,1): projection on the diagonal
                        c = tuple(c0[i] + (c1[i] - c0[i]) * t for i in range(3))
                    for i in range(3):
                        acc[i] += c[i]
            if cover:
                row += bytes(int(round(acc[i] / cover)) for i in range(3)) + bytes([int(round(255 * cover / SS / SS))])
            else:
                row += b"\0\0\0\0"
        rows.append(bytes(row))
    return rows


def png(n, rows):
    raw = b"".join(b"\0" + r for r in rows)
    chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", n, n, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def bmp(n, rows):
    hdr = struct.pack("<IiiHHIIiiII", 40, n, n * 2, 1, 32, 0, 0, 0, 0, 0, 0)
    xor = b"".join(bytes(b for p in range(n) for b in (r[4 * p + 2], r[4 * p + 1], r[4 * p], r[4 * p + 3]))
                   for r in reversed(rows))
    mask_row = ((n + 31) // 32) * 4
    andmask = b"".join(
        bytes(sum(0x80 >> (b % 8) for b in range(8 * i, min(8 * i + 8, n)) if r[4 * b + 3] == 0) for i in range(mask_row))
        for r in reversed(rows))
    return hdr + xor + andmask


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    svg = open(sys.argv[1], encoding="utf-8").read()
    out = sys.argv[2]
    rx, c0, c1, cells = parse(svg)
    if "--dev" in sys.argv[3:]:
        c0, c1 = (0xFF, 0x8A, 0x00), (0xD7, 0x26, 0x3D)
    images = []
    for n in SIZES:
        rows = render(n, rx, c0, c1, cells)
        images.append((n, png(n, rows) if n >= 256 else bmp(n, rows)))
        if n == 256:
            open(f"{out}/shoalcade-256.png", "wb").write(png(n, rows))
    ico = struct.pack("<HHH", 0, 1, len(images))
    offset = 6 + 16 * len(images)
    body = b""
    for n, data in images:
        ico += struct.pack("<BBBBHHII", n % 256, n % 256, 0, 0, 1, 32, len(data), offset + len(body))
        body += data
    open(f"{out}/shoalcade.ico", "wb").write(ico + body)


if __name__ == "__main__":
    main()
