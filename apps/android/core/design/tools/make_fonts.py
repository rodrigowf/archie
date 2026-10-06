#!/usr/bin/env python3
"""Builds the bundled fonts of :core:design (B-01) from the upstream variable fonts.

Inputs (google/fonts, OFL 1.1):
  RobotoFlex[GRAD,XOPQ,XTRA,YOPQ,YTAS,YTDE,YTFI,YTLC,YTUC,opsz,slnt,wdth,wght].ttf
  JetBrainsMono[wght].ttf

Output: static instances in src/main/res/font/, subset to Latin + Latin Extended + punctuation,
arrows and math (the same ranges the web bundles, frontend/src/styles/fonts.css). Every axis
other than wght stays at its default, as on the web (opsz 14 for Roboto Flex). Static instances
instead of one variable file: Compose picks them by FontWeight on every API level and Robolectric
renders them identically.

  python make_fonts.py <RobotoFlex.ttf> <JetBrainsMono.ttf>   (needs fontTools)
"""
import pathlib
import sys

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

OUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/res/font"
UNICODES = (
    list(range(0x0020, 0x0250))      # Basic Latin, Latin-1, Latin Extended-A/B
    + list(range(0x02B0, 0x0370))    # spacing modifiers, combining marks
    + list(range(0x1E00, 0x1F00))    # Latin Extended Additional
    + list(range(0x2000, 0x2070))    # General Punctuation
    + list(range(0x20A0, 0x20C1))    # currency
    + list(range(0x2100, 0x2200))    # letterlike, number forms, arrows
    + list(range(0x2200, 0x2300))    # math operators
    + list(range(0x2500, 0x25A0))    # box drawing, block elements (code output)
    + list(range(0x25A0, 0x2600))    # geometric shapes (todo marks)
    + list(range(0x2700, 0x27C0))    # dingbats (check marks)
    + [0xFEFF, 0xFFFD]
)
FONTS = {
    "roboto_flex": {400: "regular", 500: "medium", 600: "semibold"},
    "jetbrains_mono": {400: "regular", 500: "medium"},
}


def build(src: pathlib.Path, family: str) -> None:
    for weight, suffix in FONTS[family].items():
        font = TTFont(src)
        static = instancer.instantiateVariableFont(font, {"wght": weight}, updateFontNames=False)
        # Pin every other axis to its default.
        if "fvar" in static:
            static = instancer.instantiateVariableFont(static, {a.axisTag: None for a in static["fvar"].axes})
        opts = subset.Options()
        opts.layout_features = ["*"]
        opts.name_IDs = ["*"]
        opts.notdef_outline = True
        opts.glyph_names = False
        opts.hinting = False
        sub = subset.Subsetter(opts)
        sub.populate(unicodes=UNICODES)
        sub.subset(static)
        out = OUT / f"{family}_{suffix}.ttf"
        static.save(out)
        print(f"{out.name}: {out.stat().st_size // 1024} KB")


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    build(pathlib.Path(sys.argv[1]), "roboto_flex")
    build(pathlib.Path(sys.argv[2]), "jetbrains_mono")
