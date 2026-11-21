#!/usr/bin/env python3
"""Generate every Android icon resource from docs/branding/original-icon.png (deterministic, re-runnable).

  pip install pillow numpy scipy
  python3 scripts/make_icons.py            # writes app/src/main/res/... and docs/branding/...

What it makes (all derived from the one source image; nothing is redrawn by hand):
  mipmap-<dpi>/ic_launcher.png            legacy square icon (API 24-25)
  mipmap-<dpi>/ic_launcher_round.png      legacy round icon (API 25)
  mipmap-<dpi>/ic_launcher_foreground.png adaptive foreground, 108dp canvas, artwork inside the 66dp safe circle
  mipmap-<dpi>/ic_launcher_monochrome.png adaptive monochrome layer for Android 13 themed icons (black + alpha)
  mipmap-anydpi-v26/ic_launcher(.xml|_round.xml), values/ic_launcher_background.xml
  drawable-<dpi>/ic_stat_viddown.png      status-bar/notification icon (white + alpha, 24dp)
  drawable-nodpi/viddown_logo.png         in-app logo (transparent)
  docs/branding/ic_launcher-playstore.png 512x512 store/listing icon, docs/branding/logo.png 256px logo (README), docs/branding/preview.png contact sheet
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
BRAND = ROOT / "docs/branding"
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
# Background of the launcher icons: plain white, as the logo is presented in the supplied file.
BACKGROUND = (255, 255, 255)
SAFE_RADIUS_DP = 33.0  # adaptive icons: all artwork must stay inside a 66dp circle in the 108dp canvas


def extract_artwork(source: Image.Image) -> Image.Image:
    """Take the logo from a PNG that has a transparent background (the supplied file does)."""
    a = np.array(source.convert("RGBA")).astype(np.float32)
    rgb, coverage = a[..., :3], a[..., 3] / 255.0
    if (coverage < 0.02).mean() < 0.15:
        raise SystemExit("The source logo must have a transparent background (an alpha channel).")
    # drop speckles (compression noise); the real dots are far larger than 20 px
    labels, n = ndimage.label(coverage > 0.3)
    if n:
        sizes = ndimage.sum(np.ones_like(coverage), labels, index=np.arange(1, n + 1))
        for i, size in enumerate(sizes, start=1):
            if size < 20:
                coverage[labels == i] = 0.0
    # Semi-transparent edge pixels may carry washed-out or dark colour: give them the colour of the nearest solid pixel
    # so that no halo shows on light or dark backgrounds.
    solid = coverage >= 0.98
    iy, ix = ndimage.distance_transform_edt(~solid, return_distances=False, return_indices=True)
    colour = np.where(solid[..., None], rgb, rgb[iy, ix])
    out = np.dstack([colour, coverage * 255.0]).clip(0, 255).astype(np.uint8)
    img = Image.fromarray(out, "RGBA")
    bbox = img.getchannel("A").point(lambda v: 255 if v > 5 else 0).getbbox()
    return img.crop(bbox)


def with_colour(art: Image.Image, rgb: tuple[int, int, int]) -> Image.Image:
    solid = Image.new("RGBA", art.size, rgb + (255,))
    solid.putalpha(art.getchannel("A"))
    return solid


def max_radius(art: Image.Image) -> float:
    """Largest distance of any visible artwork pixel from the bounding-box centre."""
    alpha = np.array(art.getchannel("A"))
    ys, xs = np.where(alpha > 50)
    cx, cy = art.width / 2.0, art.height / 2.0
    return float(np.sqrt((xs + 0.5 - cx) ** 2 + (ys + 0.5 - cy) ** 2).max())


def scaled(art: Image.Image, factor: float) -> Image.Image:
    size = (max(1, round(art.width * factor)), max(1, round(art.height * factor)))
    return art.resize(size, Image.LANCZOS)


def paste_centred(canvas: Image.Image, art: Image.Image) -> None:
    canvas.alpha_composite(art, ((canvas.width - art.width) // 2, (canvas.height - art.height) // 2))


def fit_radius(art: Image.Image, radius_px: float) -> Image.Image:
    return scaled(art, radius_px / max_radius(art))


def fit_height(art: Image.Image, height_px: float) -> Image.Image:
    return scaled(art, height_px / art.height)


def rounded_tile(size: int, inset: int, radius: int) -> Image.Image:
    s = 4  # supersample for smooth corners
    big = Image.new("RGBA", (size * s, size * s), (0, 0, 0, 0))
    ImageDraw.Draw(big).rounded_rectangle(
        [inset * s, inset * s, (size - inset) * s - 1, (size - inset) * s - 1], radius * s, fill=BACKGROUND + (255,))
    return big.resize((size, size), Image.LANCZOS)


def circle_tile(size: int, inset: int) -> Image.Image:
    s = 4
    big = Image.new("RGBA", (size * s, size * s), (0, 0, 0, 0))
    ImageDraw.Draw(big).ellipse([inset * s, inset * s, (size - inset) * s - 1, (size - inset) * s - 1], fill=BACKGROUND + (255,))
    return big.resize((size, size), Image.LANCZOS)


def save(img: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, optimize=True)


def build(source_path: Path) -> dict:
    art = extract_artwork(Image.open(source_path))
    made = {}
    for name, d in DENSITIES.items():
        # adaptive foreground and monochrome: 108dp canvas, artwork inside the 66dp safe circle
        canvas = round(108 * d)
        placed = fit_radius(art, SAFE_RADIUS_DP * 0.97 * d)
        fg = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        paste_centred(fg, placed)
        save(fg, RES / f"mipmap-{name}/ic_launcher_foreground.png")
        mono = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        paste_centred(mono, with_colour(placed, (0, 0, 0)))
        save(mono, RES / f"mipmap-{name}/ic_launcher_monochrome.png")
        # legacy launcher icons: 48dp
        size = round(48 * d)
        square = rounded_tile(size, round(2 * d), round(10 * d))
        paste_centred(square, fit_height(art, 0.74 * 44 * d))
        save(square, RES / f"mipmap-{name}/ic_launcher.png")
        round_icon = circle_tile(size, round(2 * d))
        paste_centred(round_icon, fit_height(art, 0.62 * 44 * d))
        save(round_icon, RES / f"mipmap-{name}/ic_launcher_round.png")
        # status-bar / notification icon: 24dp, white + alpha only
        n = round(24 * d)
        stat = Image.new("RGBA", (n, n), (0, 0, 0, 0))
        paste_centred(stat, with_colour(fit_height(art, 20 * d), (255, 255, 255)))
        save(stat, RES / f"drawable-{name}/ic_stat_viddown.png")
        made[name] = (canvas, size, n)
    # in-app logo, transparent, 512 px tall
    save(fit_height(art, 512), RES / "drawable-nodpi/viddown_logo.png")
    # store icon: full-bleed square
    store = Image.new("RGBA", (512, 512), BACKGROUND + (255,))
    paste_centred(store, fit_radius(art, 205))
    save(store, BRAND / "ic_launcher-playstore.png")
    save(fit_height(art, 256), BRAND / "logo.png")

    (RES / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)
    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />\n'
        '</adaptive-icon>\n'
    )
    (RES / "mipmap-anydpi-v26/ic_launcher.xml").write_text(adaptive)
    (RES / "mipmap-anydpi-v26/ic_launcher_round.xml").write_text(adaptive)
    (RES / "values").mkdir(parents=True, exist_ok=True)
    hex_bg = "#%02X%02X%02X" % BACKGROUND
    (RES / "values/ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        f'    <color name="ic_launcher_background">{hex_bg}</color>\n</resources>\n')
    contact_sheet(art)
    return made


def contact_sheet(art: Image.Image) -> None:
    """One picture to eyeball every variant, including how launchers will mask the adaptive icon."""
    d = 4.0
    px = round(108 * d)
    fg = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    paste_centred(fg, fit_radius(art, SAFE_RADIUS_DP * 0.97 * d))
    cell, pad = 300, 20
    sheet = Image.new("RGBA", (cell * 4 + pad * 5, cell * 2 + pad * 3 + 40), (120, 120, 130, 255))
    def adaptive_with(mask_kind: str) -> Image.Image:
        base = Image.new("RGBA", (px, px), BACKGROUND + (255,))
        base.alpha_composite(fg)
        mask = Image.new("L", (px, px), 0)
        vis = round(72 * d)
        o = (px - vis) // 2
        dr = ImageDraw.Draw(mask)
        if mask_kind == "circle":
            dr.ellipse([o, o, o + vis, o + vis], fill=255)
        elif mask_kind == "squircle":
            dr.rounded_rectangle([o, o, o + vis, o + vis], round(vis * 0.30), fill=255)
        else:
            dr.rounded_rectangle([o, o, o + vis, o + vis], round(vis * 0.14), fill=255)
        base.putalpha(mask)
        return base.resize((cell, cell), Image.LANCZOS)
    items = [adaptive_with("circle"), adaptive_with("squircle"), adaptive_with("square")]
    size = round(48 * d)
    legacy = rounded_tile(size, round(2 * d), round(10 * d))
    paste_centred(legacy, fit_height(art, 0.74 * 44 * d))
    items.append(legacy.resize((cell, cell), Image.LANCZOS))
    rnd = circle_tile(size, round(2 * d))
    paste_centred(rnd, fit_height(art, 0.62 * 44 * d))
    items.append(rnd.resize((cell, cell), Image.LANCZOS))
    mono = Image.new("RGBA", (px, px), (235, 235, 245, 255))
    ms = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    paste_centred(ms, with_colour(fit_radius(art, SAFE_RADIUS_DP * 0.97 * d), (40, 40, 90)))
    mono.alpha_composite(ms)
    items.append(mono.resize((cell, cell), Image.LANCZOS))
    stat_bg = Image.new("RGBA", (cell, cell), (30, 30, 34, 255))
    stat = Image.new("RGBA", (96, 96), (0, 0, 0, 0))
    paste_centred(stat, with_colour(fit_height(art, 80), (255, 255, 255)))
    stat_bg.alpha_composite(stat, ((cell - 96) // 2, (cell - 96) // 2))
    items.append(stat_bg)
    dark = Image.new("RGBA", (cell, cell), (18, 18, 20, 255))
    logo = fit_height(art, 230)
    paste_centred(dark, logo)
    items.append(dark)
    for i, im in enumerate(items):
        sheet.alpha_composite(im, (pad + (i % 4) * (cell + pad), pad + (i // 4) * (cell + pad)))
    BRAND.mkdir(parents=True, exist_ok=True)
    sheet.convert("RGB").save(BRAND / "preview.png", optimize=True)


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--source", default=str(BRAND / "original-icon.png"))
    args = p.parse_args()
    made = build(Path(args.source))
    print("generated icon resources:", made)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
