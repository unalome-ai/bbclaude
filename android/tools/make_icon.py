"""Draws the bbclaude launcher icon and writes PNGs (no image libraries needed).

An original design: a berry of coral drupelets (a nod to Claude's colour and to the
BlackBerry community), BB10-blue leaves, and a small chat bubble.
"""
import math, os, struct, sys, zlib

CORAL, CORAL_DARK, HIGHLIGHT = (0xE0, 0x7A, 0x58), (0x9E, 0x45, 0x30), (0xFF, 0xD9, 0xC8)
BLUE, BLUE_DARK = (0x00, 0xA8, 0xDF), (0x00, 0x6E, 0x96)
BUBBLE, BUBBLE_DOT = (0xF4, 0xF4, 0xF4), (0x2A, 0x2A, 0x2A)

def layout(n):
    """Drupelet circles (cx, cy, r), rows from top to bottom of a rounded berry."""
    rows = [(3, 0.40), (4, 0.505), (4, 0.61), (3, 0.715), (2, 0.81)]
    r = 0.062 * n
    circles = []
    for count, y in rows:
        span = (count - 1) * 2.05 * r
        for i in range(count):
            circles.append((n * 0.46 - span / 2 + i * 2.05 * r, y * n, r))
    return circles

def leaf(x, y, n, cx, cy, angle, length, width):
    """Inside test for a pointed leaf (lens shape) rotated by angle."""
    dx, dy = x - cx, y - cy
    ca, sa = math.cos(angle), math.sin(angle)
    u, v = dx * ca + dy * sa, -dx * sa + dy * ca
    if u < 0 or u > length:
        return None
    half = width * math.sin(math.pi * u / length)
    if abs(v) > half:
        return None
    return BLUE_DARK if abs(v) < half * 0.12 else BLUE  # centre vein

def render(size, ss=4):
    n = size * ss
    corner = n * 0.22
    circles = layout(n)
    out = [[None] * n for _ in range(n)]
    for y in range(n):
        for x in range(n):
            px, py = x + 0.5, y + 0.5
            ddx = max(abs(px - n / 2) - (n / 2 - corner), 0)
            ddy = max(abs(py - n / 2) - (n / 2 - corner), 0)
            if ddx * ddx + ddy * ddy > corner * corner:
                out[y][x] = (0, 0, 0, 0)
                continue
            g = int(0x20 - 0x10 * (py / n))
            col = (g, g, g)
            # leaves above the berry
            for args in ((n * 0.46, n * 0.34, -2.2, n * 0.24, n * 0.07),
                         (n * 0.46, n * 0.34, -0.95, n * 0.22, n * 0.065)):
                c = leaf(px, py, n, *args)
                if c:
                    col = c
            # drupelets, later rows drawn on top
            for cx, cy, r in circles:
                d = math.hypot(px - cx, py - cy)
                if d <= r:
                    col = CORAL_DARK if d > r * 0.82 else CORAL
                    if math.hypot(px - (cx - r * 0.32), py - (cy - r * 0.34)) < r * 0.22:
                        col = HIGHLIGHT
            # chat bubble badge, bottom right
            bx0, by0, bx1, by1, br = n * 0.60, n * 0.60, n * 0.86, n * 0.80, n * 0.07
            qx = min(max(px, bx0 + br), bx1 - br)
            qy = min(max(py, by0 + br), by1 - br)
            in_bubble = math.hypot(px - qx, py - qy) <= br
            # tail: triangle under the bubble's left side
            tx, ty = px - n * 0.64, py - n * 0.79
            if 0 <= ty <= n * 0.07 and 0 <= tx <= n * 0.07 - ty:
                in_bubble = True
            if in_bubble:
                col = BUBBLE
                for k in range(3):
                    if math.hypot(px - (n * 0.665 + k * n * 0.065), py - n * 0.70) < n * 0.022:
                        col = BUBBLE_DOT
            out[y][x] = col + (255,)
    img = []
    for y in range(size):
        row = []
        for x in range(size):
            acc = [0, 0, 0, 0]
            for sy in range(ss):
                for sx in range(ss):
                    p = out[y * ss + sy][x * ss + sx]
                    acc[0] += p[0] * p[3]; acc[1] += p[1] * p[3]; acc[2] += p[2] * p[3]; acc[3] += p[3]
            a = acc[3]
            row.append((acc[0] // a, acc[1] // a, acc[2] // a, a // (ss * ss)) if a else (0, 0, 0, 0))
        img.append(row)
    return img

def write_png(path, pixels):
    h, w = len(pixels), len(pixels[0])
    raw = b"".join(b"\x00" + b"".join(bytes(p) for p in row) for row in pixels)
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    data += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(data)

if __name__ == "__main__":
    res = sys.argv[1]
    for folder, size in (("mipmap-xhdpi", 96), ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)):
        os.makedirs(os.path.join(res, folder), exist_ok=True)
        write_png(os.path.join(res, folder, "ic_launcher.png"), render(size))
    write_png(os.path.join(os.path.dirname(os.path.abspath(res)), "..", "icon.png"), render(512, 2))
    print("icons written")
