"""WCAG contrast of the VidDown palette, parsed straight from Color.kt (so the test cannot drift from the code)."""
import re
import unittest
from pathlib import Path

COLOR_KT = Path(__file__).resolve().parents[2] / "core/src/main/kotlin/com/viddown/core/ui/theme/Color.kt"


def load():
    colors = {}
    for name, hexv in re.findall(r"val (\w+) = Color\(0xFF([0-9A-Fa-f]{6})\)", COLOR_KT.read_text()):
        colors[name] = tuple(int(hexv[i:i + 2], 16) for i in (0, 2, 4))
    return colors


def luminance(rgb):
    def ch(c):
        c = c / 255.0
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = (ch(c) for c in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a, b):
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


TEXT_PAIRS = [  # (foreground, background): normal text needs >= 4.5
    ("OnPrimary", "Primary"), ("OnPrimaryContainer", "PrimaryContainer"),
    ("OnSecondary", "Secondary"), ("OnSecondaryContainer", "SecondaryContainer"),
    ("OnTertiary", "Tertiary"), ("OnTertiaryContainer", "TertiaryContainer"),
    ("OnBackground", "Background"), ("OnSurface", "Surface"), ("OnSurfaceVariant", "SurfaceVariant"),
    ("OnError", "Error"), ("OnErrorContainer", "ErrorContainer"),
    ("Primary", "Surface"),            # primary used as text/link colour on the page background
    ("OnSurfaceVariant", "Surface"),   # secondary text on the page background
    ("Error", "Surface"),              # error text on the page background
]
UI_PAIRS = [("Outline", "Surface")]  # borders, icons, focus rings: >= 3.0


class PaletteContrast(unittest.TestCase):
    def test_all_names_exist_for_both_themes(self):
        colors = load()
        names = {n[len(p):] for n in colors for p in ("Light", "Dark") if n.startswith(p)}
        for theme in ("Light", "Dark"):
            for name in names:
                self.assertIn(theme + name, colors, f"{theme}{name} missing")

    def test_text_pairs_meet_wcag_aa(self):
        colors = load()
        for theme in ("Light", "Dark"):
            for fg, bg in TEXT_PAIRS:
                ratio = contrast(colors[theme + fg], colors[theme + bg])
                self.assertGreaterEqual(ratio, 4.5, f"{theme}: {fg} on {bg} = {ratio:.2f}")

    def test_ui_component_pairs_meet_3_to_1(self):
        colors = load()
        for theme in ("Light", "Dark"):
            for fg, bg in UI_PAIRS:
                ratio = contrast(colors[theme + fg], colors[theme + bg])
                self.assertGreaterEqual(ratio, 3.0, f"{theme}: {fg} on {bg} = {ratio:.2f}")


if __name__ == "__main__":
    unittest.main()
