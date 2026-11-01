#!/usr/bin/env python3
# TEST ONLY (Wine smoke test, never shipped): copies of DejaVu fonts renamed to the Windows font families Compose asks
# for (Arial, Segoe UI, Times New Roman, Consolas), because a bare Wine prefix has none of them and Wine's font
# replacements don't reach Skia's DirectWrite lookup. Real Windows has these fonts. Needs fontTools.
#   alias_fonts.py <dejavu dir> <out dir>
import sys
from fontTools.ttLib import TTFont

ALIASES = {  # family: (regular, bold) DejaVu file
    "Arial": ("DejaVuSans.ttf", "DejaVuSans-Bold.ttf"),
    "Segoe UI": ("DejaVuSans.ttf", "DejaVuSans-Bold.ttf"),
    "Times New Roman": ("DejaVuSerif.ttf", "DejaVuSerif-Bold.ttf"),
    "Consolas": ("DejaVuSansMono.ttf", "DejaVuSansMono-Bold.ttf"),
}
src, out = sys.argv[1], sys.argv[2]
for family, files in ALIASES.items():
    for style, f in zip(("Regular", "Bold"), files):
        font = TTFont(f"{src}/{f}")
        name = font["name"]
        full = family if style == "Regular" else f"{family} Bold"
        ps = (family + "-" + style).replace(" ", "")
        for rec in list(name.names):
            if rec.nameID in (16, 17, 21, 22):
                name.removeNames(nameID=rec.nameID)
        for nid, val in ((1, family), (2, style), (3, ps), (4, full), (6, ps)):
            name.setName(val, nid, 3, 1, 0x409)
            name.setName(val, nid, 1, 0, 0)
        fn = f"{out}/{ps}.ttf"
        font.save(fn)
        print(fn)
