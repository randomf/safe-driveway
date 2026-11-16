"""Pictures of the enclosure for the docs, drawn from the model. See README.md.

A renderer small enough to keep here, in parallel projection. Each part is cut into
triangles no longer than a few millimetres, those facing away from the eye are
dropped, and the rest are painted from the farthest to the nearest, shaded by a light
from over the viewer's shoulder. The labels point at positions taken from the model,
so the pictures follow it when it changes.

It draws the model's own, left-handed coordinates (see model.py), in which the screen's
right is up × forward. The pictures therefore show the real enclosure, not its mirror
image.
"""

import functools
from collections.abc import Iterable, Mapping
from dataclasses import dataclass

import numpy as np
from build123d import Align, Box, Part, Pos
from matplotlib.axes import Axes
from matplotlib.backends.backend_agg import FigureCanvasAgg
from matplotlib.collections import PolyCollection
from matplotlib.figure import Figure
from matplotlib.patheffects import withStroke
from PIL import Image

from enclosure import model as m

type Point = tuple[float, float, float]
type Colour = tuple[float, float, float]

PX_PER_MM = 5
DPI = 100
SUPERSAMPLE = 2  # drawn this many times larger, then scaled down, for smooth edges
FONT_PT = 9
LABEL_GAP = 6.0  # mm between labels
LABELS_W = 64.0  # mm to the right of the drawing for the labels
MARGIN = 6.0
CAPTION_H = 4.0  # mm, the height of a caption's text
LONGEST_EDGE = 3.0  # mm, of the triangles painted
FOG = 0.3  # how much darker the farthest triangles are than the nearest
INK = "#333333"
HALO = withStroke(linewidth=2.2, foreground="white")  # keeps lines readable over the parts

ENCLOSURE: Colour = (0.50, 0.52, 0.55)
PLATE: Colour = (0.46, 0.49, 0.57)
COLOURS: dict[str, Colour] = {
    "battery": (0.25, 0.48, 0.85),
    "board": (0.20, 0.60, 0.33),
    "sensor": (0.62, 0.32, 0.72),
    "switch": (0.95, 0.60, 0.15),
    "buzzer": (0.22, 0.22, 0.22),
    "green LED": (0.30, 0.85, 0.35),
    "red LED": (0.92, 0.20, 0.18),
    "magnets": (0.82, 0.83, 0.86),
}


@dataclass(frozen=True)
class Label:
    text: str
    at: tuple[Point, ...]  # where it points, in the model's outside coordinates


@dataclass(frozen=True)
class Dimension:
    text: str
    start: Point
    end: Point


@dataclass(frozen=True)
class Caption:
    text: str
    at: Point  # the middle of its top edge


@dataclass(frozen=True)
class Picture:
    name: str
    forward: Point  # the direction the eye looks in
    parts: tuple[tuple[Part, Colour], ...]
    labels: tuple[Label, ...] = ()
    dimensions: tuple[Dimension, ...] = ()
    captions: tuple[Caption, ...] = ()


PICTURED_UNIT = 8  # the unit number on the tub in the pictures, with all its dots


def outside(x: float, y: float, z: float) -> Point:
    """A position given from the inside corner, as the model's `inside` takes it."""
    return (m.WALL + x, m.WALL + y, m.FRONT + z)


def unit(v: Iterable[float]) -> np.ndarray:
    a = np.asarray(tuple(v), float)
    return a / np.linalg.norm(a)


def camera(forward: Point) -> np.ndarray:
    """Rows: the screen's right, its up, and the direction into the screen."""
    f = unit(forward)
    looking_down = abs(f[1]) > 0.99  # then the back of the box is up on the screen
    r = unit(np.cross((0.0, 0.0, 1.0) if looking_down else (0.0, 1.0, 0.0), f))
    return np.array([r, np.cross(f, r), f])


def subdivide(t: np.ndarray) -> np.ndarray:
    """Split triangles in four until no edge is longer than LONGEST_EDGE, so that
    painting them by their depth gets the order right."""
    edges = np.max([np.linalg.norm(t[:, i] - t[:, (i + 1) % 3], axis=1) for i in range(3)], axis=0)
    long_ = t[edges > LONGEST_EDGE]
    if not len(long_):
        return t
    a, b, c = long_[:, 0], long_[:, 1], long_[:, 2]
    ab, bc, ca = (a + b) / 2, (b + c) / 2, (c + a) / 2
    quarters = [np.stack(q, axis=1) for q in ((a, ab, ca), (ab, b, bc), (ca, bc, c), (ab, bc, ca))]
    return np.concatenate([t[edges <= LONGEST_EDGE], subdivide(np.concatenate(quarters))])


def triangles(part: Part) -> np.ndarray:
    vertices, faces = part.tessellate(0.03, 0.15)
    v = np.array([(p.X, p.Y, p.Z) for p in vertices])
    return subdivide(v[np.array(faces)])


def painted(
    parts: Iterable[tuple[Part, Colour]], view: np.ndarray
) -> tuple[np.ndarray, np.ndarray]:
    """The triangles that face the eye, on the screen and farthest first, and their colours.
    Farther triangles are darker, so that faces turned the same way stand apart."""
    light = unit(-view[2] + 0.3 * view[1] + 0.3 * view[0])

    def one(part: Part, colour: Colour) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        t = triangles(part)
        n = np.cross(t[:, 1] - t[:, 0], t[:, 2] - t[:, 0])
        normals = n / (np.linalg.norm(n, axis=1, keepdims=True) + 1e-12)
        facing = normals @ view[2] < 0
        shade = 0.35 + 0.65 * np.clip(normals[facing] @ light, 0, 1)
        return t[facing] @ view[:2].T, np.asarray(colour) * shade[:, None], t[facing] @ view[2]

    drawn = [one(part, colour) for part, colour in parts]
    polygons = np.concatenate([p for p, _, _ in drawn])
    colours = np.concatenate([c for _, c, _ in drawn])
    depths = np.concatenate([d.mean(axis=1) for _, _, d in drawn])
    fog = 1 - FOG * (depths - depths.min()) / (np.ptp(depths) or 1.0)
    order = np.argsort(-depths, kind="stable")
    return polygons[order], (colours * fog[:, None])[order]


def spread(heights: list[float], gap: float) -> list[float]:
    """Label heights: each at its own height, pushed down to keep `gap` below the one above."""
    order = sorted(range(len(heights)), key=lambda i: -heights[i])
    placed = functools.reduce(
        lambda done, i: [*done, (i, min(heights[i], done[-1][1] - gap) if done else heights[i])],
        order,
        [],
    )
    return [y for _, y in sorted(placed)]


def draw_labels(ax: Axes, labels: tuple[Label, ...], project, text_x: float) -> None:
    anchors = [np.array([project(p) for p in label.at]) for label in labels]
    heights = spread([float(a[:, 1].mean()) for a in anchors], LABEL_GAP)
    for label, points, y in zip(labels, anchors, heights, strict=True):
        for x0, y0 in points:
            ax.plot([x0, text_x - 1.5], [y0, y], color=INK, lw=0.7, path_effects=[HALO])
            ax.plot([x0], [y0], "o", color=INK, ms=2.5, path_effects=[HALO])
        ax.text(text_x, y, label.text, color=INK, fontsize=FONT_PT, va="center", ha="left")


def draw_dimension(ax: Axes, dimension: Dimension, project) -> None:
    start, end = np.array(project(dimension.start)), np.array(project(dimension.end))
    ax.annotate("", xy=end, xytext=start, arrowprops={"arrowstyle": "<->", "color": INK, "lw": 0.8})
    along = unit((*(end - start), 0.0))[:2]
    angle = np.degrees(np.arctan2(along[1], along[0]))
    if angle > 90 or angle <= -90:  # keep the text upright
        angle -= 180 if angle > 0 else -180
    across = np.array([-np.sin(np.radians(angle)), np.cos(np.radians(angle))])
    ax.text(
        *((start + end) / 2 + 1.5 * across),
        dimension.text,
        color=INK,
        fontsize=FONT_PT,
        ha="center",
        va="bottom",
        rotation=angle,
        rotation_mode="anchor",
    )


def draw(picture: Picture) -> Image.Image:
    """The picture as a PNG-ready image with 256 colours."""
    view = camera(picture.forward)

    def project(p: Point) -> tuple[float, float]:
        x, y = np.asarray(p) @ view[:2].T
        return float(x), float(y)

    polygons, colours = painted(picture.parts, view)
    extra = [project(p) for d in picture.dimensions for p in (d.start, d.end)] + [
        (x, y - CAPTION_H) for x, y in (project(c.at) for c in picture.captions)
    ]
    corners = np.concatenate([polygons.reshape(-1, 2), np.array(extra).reshape(-1, 2)])
    lo, hi = corners.min(axis=0) - MARGIN, corners.max(axis=0) + MARGIN
    labels_w = LABELS_W if picture.labels else 0.0
    lowest = min(
        [lo[1], *(y - MARGIN for y in spread_heights(picture.labels, project))], default=lo[1]
    )
    width, height = hi[0] - lo[0] + labels_w, hi[1] - lowest

    size = (round(width * PX_PER_MM), round(height * PX_PER_MM))
    fig = Figure(figsize=(size[0] / DPI, size[1] / DPI), dpi=DPI * SUPERSAMPLE)
    FigureCanvasAgg(fig)
    ax = fig.add_axes((0, 0, 1, 1))
    ax.set_xlim(lo[0], hi[0] + labels_w)
    ax.set_ylim(lowest, hi[1])
    ax.set_aspect("equal")
    ax.axis("off")
    ax.add_collection(
        # Without smoothing, so that the parts behind don't show through the seams.
        PolyCollection(list(polygons), facecolors=colours, edgecolors=colours, antialiased=False)
    )
    draw_labels(ax, picture.labels, project, hi[0])
    for dimension in picture.dimensions:
        draw_dimension(ax, dimension, project)
    for caption in picture.captions:
        x, y = project(caption.at)
        ax.text(x, y, caption.text, color=INK, fontsize=FONT_PT + 2, ha="center", va="top")
    fig.canvas.draw()
    drawn = Image.fromarray(np.asarray(fig.canvas.buffer_rgba())).convert("RGB")
    return drawn.resize(size, Image.Resampling.LANCZOS).quantize(256, dither=Image.Dither.NONE)


def spread_heights(labels: tuple[Label, ...], project) -> list[float]:
    heights = [float(np.mean([project(p)[1] for p in label.at])) for label in labels]
    return spread(heights, LABEL_GAP)


def halved(parts: Iterable[tuple[Part, Colour]]) -> tuple[tuple[Part, Colour], ...]:
    """The parts cut through the middle of the width, keeping the left half."""
    half = Pos(-10, -10, -10) * Box(m.WIDTH / 2 + 10, m.HEIGHT + 20, m.DEPTH + 20, align=Align.MIN)
    cut = [(part & half, colour) for part, colour in parts]
    return tuple((part, colour) for part, colour in cut if part.volume > 1e-6)


def numbers(tub: Part) -> Picture:
    """The tops of the tubs for each unit number, side by side, seen from above."""
    top = tub & Pos(-1, m.HEIGHT - 4, -1) * Box(
        m.WIDTH + 2, m.HOOK_H + 6, m.DEPTH + 2, align=Align.MIN
    )
    spacing = m.WIDTH + 10
    units = range(1, m.UNITS + 1)
    return Picture(
        "numbers",
        forward=(0, -1, 0),
        parts=tuple(
            (Pos(i * spacing, 0, 0) * (top + m.dots(number)), ENCLOSURE)
            for i, number in enumerate(units)
        ),
        captions=tuple(
            Caption(str(number), (i * spacing + m.WIDTH / 2, m.HEIGHT, -3))
            for i, number in enumerate(units)
        ),
    )


def pictures(tub: Part, plate: Part, parts: Mapping[str, Part]) -> tuple[Picture, ...]:
    def coloured(*names: str) -> tuple[tuple[Part, Colour], ...]:
        return tuple((parts[name], COLOURS[name]) for name in names)

    plain_tub = tub
    tub = m.numbered_tub(plain_tub, PICTURED_UNIT)
    shell = ((tub, ENCLOSURE), (plate, PLATE))
    everything = (*shell, *coloured(*parts))
    cx = m.CENTRE_X
    front = -m.FRONT  # the outside of the front, from the inside corner
    led_tip = m.LED_COLLAR_H + m.LED_FLANGE_T - m.LED_LENGTH
    led_legs = m.LED_COLLAR_H + m.LED_FLANGE_T + m.LED_LEGS
    usb = (m.BOARD_X + m.USB_X, m.BOARD_POST_H + m.BOARD_T + m.USB_H / 2)
    plugs_y = m.SENSOR_Y - m.CHIP_FROM_BOTTOM + m.SENSOR_W - m.PIN_STRIP / 2
    (green_x, green_y), (red_x, red_y) = m.LEDS
    board_middle = (m.BOARD_X + m.BOARD_W / 2, m.BOARD_L / 2)
    bx, by = m.buzzer_centre()
    battery_middle = m.BATTERY_Y + m.BATTERY_L / 2
    upper_magnet = (m.WIDTH / 2, m.MAGNETS_Y[-1], m.DEPTH)
    # Where there are several, a label points at the one nearest to it and gives the count.
    # Seen from the back, the model's left is on the right of the picture.
    left_screw = m.CASE_SCREWS[1]
    dot_x, dot_z = m.dot_positions(PICTURED_UNIT)[m.DOT_GRID[0] - 1]  # front right
    corner_dot = (dot_x, m.HEIGHT + m.DOT_H, dot_z)
    hook_top = m.HEIGHT + m.HOOK_HOLE_Y + m.HOOK_HOLE_D / 2 + 1.5
    hook_front = (m.WIDTH / 2 + 3, hook_top, m.DEPTH - m.HOOK_T)
    hook_back = (m.WIDTH / 2, hook_top, m.DEPTH)
    w, h, d = m.WIDTH, m.HEIGHT, m.DEPTH
    return (
        Picture(
            "front",
            forward=(-0.5, -0.35, 1),
            parts=everything,
            labels=(
                Label("Laser sensor aperture", (outside(cx, m.SENSOR_Y, front),)),
                Label("Power switch", (outside(cx, m.SWITCH_Y, 0),)),
                Label("Green LED", (outside(green_x, green_y, led_tip),)),
                Label("Red LED", (outside(red_x, red_y, led_tip),)),
                Label("Buzzer sound holes", (outside(bx, by, front),)),
                Label("Dots for the unit's number", (corner_dot,)),
                Label("Hook for hanging", (hook_front,)),
            ),
        ),
        Picture(
            "back",
            forward=(0.5, 0.35, -1),
            parts=everything,
            labels=(
                Label(
                    f"{len(m.CASE_SCREWS)} countersunk M3 screws",
                    (outside(*m.CASE_SCREWS[2], m.INNER_D + m.PLATE),),
                ),
                Label(f"{len(m.MAGNETS_Y)} magnets, flush", (upper_magnet,)),
                Label("USB-C port", (outside(usb[0], -m.WALL, usb[1]),)),
                Label("Hook for hanging from a nail", (hook_back,)),
            ),
        ),
        Picture(
            "inside",
            forward=(0, 0, -1),
            parts=(
                (tub, ENCLOSURE),
                *coloured("sensor", "switch", "red LED", "green LED", "buzzer", "board"),
            ),
            labels=(
                Label(
                    "Laser sensor, with its plugs",
                    (outside(cx, plugs_y, m.CHIP_GAP + m.SENSOR_STACK),),
                ),
                Label("Power switch", (outside(cx, m.SWITCH_Y, m.SWITCH_DEPTH),)),
                Label("Green and red LEDs", tuple(outside(x, y, led_legs) for x, y in m.LEDS)),
                Label("Buzzer", (outside(cx, by, m.BUZZER_TALL),)),
                Label(
                    "ESP32 board, with its plugs",
                    (outside(board_middle[0], 40, m.BOARD_POST_H + m.BOARD_STACK),),
                ),
                Label(
                    f"{len(m.CASE_SCREWS)} inserts for the back plate",
                    (outside(*left_screw, m.INNER_D),),
                ),
            ),
            dimensions=(Dimension(f"{w:g} mm", (0, -5, d), (w, -5, d)),),
        ),
        Picture(
            "section",
            forward=(-1, 0, 0),
            parts=halved(everything),
            labels=(
                Label("Laser sensor", (outside(cx, m.SENSOR_Y, m.CHIP_GAP + m.CHIP_H + 1),)),
                Label("Power switch", (outside(cx, m.SWITCH_Y, 10),)),
                Label("Green LED, the red one beside it", (outside(green_x, green_y, led_tip),)),
                Label("Buzzer", (outside(cx, by, m.BUZZER_TALL - m.BUZZER_PCB_T / 2),)),
                Label("Battery", (outside(cx, battery_middle, m.INNER_D - m.BATTERY_T / 2),)),
                Label("Magnets", (upper_magnet,)),
                Label("ESP32 board, with its plugs", (outside(cx, board_middle[1], 14),)),
                Label("USB-C port", (outside(usb[0], -m.WALL / 2, usb[1]),)),
            ),
            dimensions=(
                Dimension(f"{d:g} mm", (w / 2, h + 5, 0), (w / 2, h + 5, d)),
                Dimension(f"{h:g} mm", (w / 2, 0, -5), (w / 2, h, -5)),
            ),
        ),
        Picture(
            "tub",
            forward=(0.3, -0.12, -1),
            parts=((tub, ENCLOSURE),),
            labels=(
                Label(
                    "Pins for the sensor",
                    tuple(
                        outside(x, y, m.CHIP_GAP + m.CHIP_H + m.SENSOR_T + m.MELT)
                        for x, y in m.sensor_holes()
                    ),
                ),
                Label("Switch hole", (outside(cx, m.SWITCH_Y - m.SWITCH_HOLE_D / 2, 0),)),
                Label("LED collars", tuple(outside(x, y - 3.3, m.LED_COLLAR_H) for x, y in m.LEDS)),
                Label(
                    "Post for the buzzer's screw",
                    (outside(*m.buzzer_hole(), m.BUZZER_TALL - m.BUZZER_PCB_T),),
                ),
                Label("Step for the back plate", (outside(-m.WALL + m.LIP + 0.5, 80, m.INNER_D),)),
                Label(
                    "Posts for the board",
                    tuple(outside(x, y, m.BOARD_POST_H) for x, y in m.board_holes()),
                ),
                Label(f"{len(m.CASE_SCREWS)} heat-set inserts", (outside(*left_screw, m.INNER_D),)),
                Label("USB-C slot", (outside(usb[0], 0, usb[1]),)),
            ),
        ),
        numbers(plain_tub),
    )
