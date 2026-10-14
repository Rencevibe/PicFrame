"""Colors, fonts, icons and text helpers from the design spec."""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path

import pygame

ASSETS = Path(__file__).resolve().parents[2] / "assets"


def rgb(hex_: str) -> tuple[int, int, int]:
    h = hex_.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)


# Palette (spec "Colors")
BG = rgb("#0E1726")
PANEL = rgb("#172538")
RAISED = rgb("#22364B")
PRESSED = rgb("#2D4862")
DIVIDER = rgb("#2B4054")
TEXT = rgb("#F4F7FB")
SECONDARY = rgb("#B7C6D6")
ACCENT = rgb("#43D9C3")
ACCENT_PRESSED = rgb("#61E5D2")
HIGHLIGHT = rgb("#F2B66D")
ALARM = rgb("#FF766F")
NIGHT = rgb("#929DAA")
BLACK = (0, 0, 0)
WHITE = (255, 255, 255)


def _blend(fg, bg, alpha: float):
    return tuple(round(f * alpha + b * (1 - alpha)) for f, b in zip(fg, bg))


CHIP_ON_WHITE = _blend(BG, WHITE, 0.8)  # photo chips: bg at 80% over the brightest photo
BANNER_ON_WHITE = _blend(BG, WHITE, 0.9)

# Every text pair the screens draw, including pressed and disabled states (spec "Contrast")
TEXT_PAIRS = [
    (TEXT, BG, "text on bg"), (TEXT, PANEL, "text on panel"), (TEXT, RAISED, "text on raised"),
    (TEXT, PRESSED, "text on pressed"),
    (SECONDARY, BG, "secondary on bg"), (SECONDARY, PANEL, "secondary on panel"),
    (SECONDARY, RAISED, "secondary on raised / disabled"), (SECONDARY, PRESSED, "secondary on pressed"),
    (BG, ACCENT, "bg on accent"), (BG, ACCENT_PRESSED, "bg on pressed accent"),
    (ALARM, BG, "ALARM caption"), (NIGHT, BLACK, "night clock"),
    (TEXT, CHIP_ON_WHITE, "text on photo chip"), (SECONDARY, CHIP_ON_WHITE, "secondary on photo chip"),
    (TEXT, BANNER_ON_WHITE, "banner text over photo"),
]
# Icons, markers, outlines and stripes (≥ 3:1)
ICON_PAIRS = [
    (TEXT, RAISED, "icon on raised"), (TEXT, PRESSED, "icon on pressed"),
    (SECONDARY, RAISED, "toggle-off knob"), (SECONDARY, PANEL, "disabled icon / day-chip outline"),
    (ACCENT, PANEL, "appointment marker / on dot"), (HIGHLIGHT, PANEL, "to-do marker"),
    (ALARM, RAISED, "snoozed bell"), (ALARM, PRESSED, "snoozed bell pressed"),
    (ALARM, BANNER_ON_WHITE, "missed-alarm stripe"), (HIGHLIGHT, BANNER_ON_WHITE, "warning stripe"),
    (BG, ACCENT, "toggle-on knob"),
]


def _lin(c: int) -> float:
    c /= 255
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def contrast(fg, bg) -> float:
    """WCAG 2.1 contrast ratio; colors as '#RRGGBB' or RGB tuples."""
    def lum(c):
        r, g, b = rgb(c) if isinstance(c, str) else c[:3]
        return 0.2126 * _lin(r) + 0.7152 * _lin(g) + 0.0722 * _lin(b)
    a, b = sorted((lum(fg), lum(bg)), reverse=True)
    return (a + 0.05) / (b + 0.05)


# Fonts (spec "Type scale"): Inter for clock, numbers and headings; Atkinson for UI text
FONT_FILES = {
    "inter": ASSETS / "fonts" / "Inter-Regular.otf",
    "inter-medium": ASSETS / "fonts" / "Inter-Medium.otf",
    "inter-semibold": ASSETS / "fonts" / "Inter-SemiBold.otf",
    "ui": ASSETS / "fonts" / "AtkinsonHyperlegible-Regular.ttf",
    "ui-bold": ASSETS / "fonts" / "AtkinsonHyperlegible-Bold.ttf",
}


@lru_cache(maxsize=None)
def font(name: str, size: int) -> pygame.font.Font:
    if not pygame.font.get_init():
        pygame.font.init()
    return pygame.font.Font(str(FONT_FILES[name]), size)  # KeyError/FileNotFoundError: never a fallback


WEATHER_ICONS = ["clear", "partly-cloudy", "cloudy", "fog", "rain", "snow", "storm", "unknown"]
UI_ICONS = ["expand", "dashboard", "chevron-left", "chevron-right", "chevron-forward", "bell", "close",
            "minus", "plus", "photo"]


@lru_cache(maxsize=None)
def icon(name: str, size: int, color: tuple | None = None) -> pygame.Surface:
    """Icon PNG (32 or 48 px). `color` tints white strokes; amber suns stay amber unless tinted."""
    surf = pygame.image.load(str(ASSETS / "icons" / f"{name}-{size}.png"))
    if color is not None:
        surf = surf.copy()
        surf.fill((*color[:3], 255), special_flags=pygame.BLEND_RGBA_MULT)
    return surf


# --- text helpers ---

def text(surf, s: str, f: pygame.font.Font, color, pos, anchor: str = "lt", tracking: float = 0) -> pygame.Rect:
    """Draw s with anchor = horizontal (l/m/r) + vertical (t/m/s=baseline/b). Returns the drawn rect."""
    img = _render(s, f, color, tracking)
    x, y = pos
    w, h = img.get_size()
    x -= {"l": 0, "m": w / 2, "r": w}[anchor[0]]
    y -= {"t": 0, "m": h / 2, "s": f.get_ascent(), "b": h}[anchor[1]]
    return surf.blit(img, (round(x), round(y)))


def _render(s: str, f: pygame.font.Font, color, tracking: float) -> pygame.Surface:
    if not tracking:
        return f.render(s, True, color)
    glyphs = [f.render(ch, True, color) for ch in s]
    w = sum(g.get_width() for g in glyphs) + tracking * (len(glyphs) - 1)
    out = pygame.Surface((max(1, round(w)), f.get_height()), pygame.SRCALPHA)
    x = 0.0
    for g in glyphs:
        out.blit(g, (round(x), 0))
        x += g.get_width() + tracking
    return out


def fit(s: str, f: pygame.font.Font, max_w: int) -> str:
    """s, shortened with an ellipsis so it renders no wider than max_w."""
    if f.size(s)[0] <= max_w:
        return s
    while s and f.size(s.rstrip() + "…")[0] > max_w:
        s = s[:-1]
    return s.rstrip() + "…"


def digits(s: str, f: pygame.font.Font, color) -> pygame.Surface:
    """Clock text with every digit centered in a cell as wide as "0", so it never jiggles."""
    cell = f.size("0")[0]
    widths = [cell if ch.isdigit() else f.size(ch)[0] for ch in s]
    out = pygame.Surface((max(1, sum(widths)), f.get_height()), pygame.SRCALPHA)
    x = 0
    for ch, w in zip(s, widths):
        g = f.render(ch, True, color)
        out.blit(g, (x + (w - g.get_width()) // 2, 0))
        x += w
    return out
