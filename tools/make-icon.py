"""Draws the mod icon: a slice of Nether floor, bedrock thinning out upwards into netherrack, with a sprouting seed
on top, as 32x32 pixel art scaled to 128x128.

Run from the repo root:  python -I tools/make-icon.py
Writes src/main/resources/assets/seed-cracker/icon.png. No dependencies beyond the standard library.
"""
import struct
import sys
import zlib
from pathlib import Path

SIZE = 32
SCALE = 4
OUT = Path("src/main/resources/assets/seed-cracker/icon.png")

NETHERRACK = [(0x6F, 0x2A, 0x2A), (0x61, 0x22, 0x22), (0x7D, 0x33, 0x30), (0x55, 0x1C, 0x1D)]
BEDROCK = [(0x55, 0x55, 0x55), (0x3A, 0x3A, 0x3A), (0x6E, 0x6E, 0x6E), (0x2A, 0x2A, 0x2A)]
SKY = (0x2B, 0x0E, 0x10)
SEED = [(0xC8, 0x9B, 0x5A), (0xB0, 0x82, 0x45), (0xDB, 0xB2, 0x73)]
SEED_EDGE = (0x5A, 0x3B, 0x1A)
LEAF = (0x5B, 0xC2, 0x3C)
LEAF_DARK = (0x3A, 0x8A, 0x26)
EDGE = (0x14, 0x08, 0x08)

img = [[None] * SIZE for _ in range(SIZE)]


def px(x, y, c):
    if 0 <= x < SIZE and 0 <= y < SIZE:
        img[y][x] = c


def block(bx, by, palette):
    """A 4x4 block with a fixed speckle."""
    for y in range(4):
        for x in range(4):
            px(bx * 4 + x, by * 4 + y, palette[(x * 3 + y * 5 + bx * 7 + by * 11) % len(palette)])


# Rows of 4x4 blocks, bottom up: always bedrock, then 80/60/40/20% bedrock (a fixed pattern), then netherrack.
PATTERN = [
    "########",
    "##.####.",
    "#.##.#.#",
    ".#..#.#.",
    "...#....",
]
for by in range(8):
    row = 7 - by
    for bx in range(8):
        if row < len(PATTERN) and PATTERN[row][bx] == "#":
            block(bx, by, BEDROCK)
        elif by >= 3:
            block(bx, by, NETHERRACK)
        else:
            for y in range(4):
                for x in range(4):
                    px(bx * 4 + x, by * 4 + y, SKY)

# The seed: an oval resting on the netherrack, with a two-leaf sprout.
cx, cy = 15.5, 10.5
for y in range(SIZE):
    for x in range(SIZE):
        d = ((x - cx) / 5.0) ** 2 + ((y - cy) / 3.6) ** 2
        if d <= 1.0:
            px(x, y, SEED[(x + 2 * y) % len(SEED)] if d < 0.72 else SEED_EDGE)
for x, y in ((13, 8), (14, 8), (13, 9)):
    px(x, y, (0xEE, 0xCC, 0x90))
for y in range(3, 8):
    px(16, y, LEAF_DARK)
for x, y in ((17, 3), (18, 3), (19, 3), (18, 2), (19, 2), (20, 2), (17, 4), (18, 4)):
    px(x, y, LEAF)
for x, y in ((15, 4), (14, 4), (13, 4), (14, 3), (13, 3), (12, 3), (15, 5)):
    px(x, y, LEAF)


def write_png(path, pixels, scale):
    rows = []
    for row in pixels:
        line = bytearray()
        for c in row:
            rgba = (0, 0, 0, 0) if c is None else (*c, 255)
            line += bytes(rgba) * scale
        rows += [b"\x00" + bytes(line)] * scale
    width = len(pixels[0]) * scale
    height = len(pixels) * scale

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(b"".join(rows), 9))
    png += chunk(b"IEND", b"")
    path.write_bytes(png)


if __name__ == "__main__":
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else OUT
    out.parent.mkdir(parents=True, exist_ok=True)
    write_png(out, img, SCALE)
    print(f"wrote {out} ({SIZE * SCALE}x{SIZE * SCALE})")
