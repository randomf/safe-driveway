"""The unit's 3D-printed enclosure: a front tub and a back plate. See README.md.

All sizes are in millimetres. Coordinates are seen from the passage, looking at the
front of a unit on the wall: x runs across the front from left to right, y up from
the bottom, and z from the outside of the front toward the wall, which the magnets in
the back plate hold on to. The tub prints with its front on the bed, the back plate
with its outside on the bed.

With z pointing away from the viewer, those coordinates are left-handed: built as they
are, the parts are mirror images of the real ones. `real` mirrors them back across the
middle of the width, and everything that leaves the model for printing goes through it.

Positions inside the box are given from the inside corner where the left wall, the
bottom wall and the front meet, and placed with `inside`.

The parts it holds are modelled as plain blocks and cylinders from their measured
sizes. `fit_problems` checks that none of them runs into the enclosure or into each
other, before `__main__.py` writes the print files and the pictures.
"""

import functools
import math
import operator
from collections.abc import Iterable

from build123d import (
    Align,
    Axis,
    Box,
    Cone,
    Cylinder,
    Part,
    Plane,
    Polygon,
    Pos,
    Rectangle,
    RectangleRounded,
    Rot,
    Sphere,
    extrude,
    fillet,
    mirror,
)

# Walls. The front is at most 2 mm thick around the sensor aperture (ADR 0012), and
# the switch's clips grip walls up to 3.5 mm.
WALL = 2.0  # sides, top and bottom
FRONT = 2.0
PLATE = 2.5  # the back plate
LIP = 1.0  # outer part of the walls, around the back plate
FIT = 0.15  # gap between the back plate and the lip, on each side
CORNER_R = 4.0  # outside corners, seen from the front
INNER_CORNER_R = 0.5  # tight, so that the board fits into the bottom right corner
FRONT_EDGE_R = 1.5

INNER_W = 35.0  # the battery, 34 mm, with 0.5 mm on each side
INNER_D = 25.0  # the Dupont plugs on the board and the sensor need 23 mm, ideally more

WIDTH = INNER_W + 2 * WALL
DEPTH = FRONT + INNER_D + PLATE
MAX_DEPTH = 30.0

CENTRE_X = INNER_W / 2

# The parts sit one above the other: the board at the bottom, then the battery against
# the back plate with the buzzer, the switch between the two LEDs, and the lower half of
# the sensor in front of it, and the sensor's pins and plugs above it.

# ESP32 board: component side toward the back, USB-C end down against the bottom wall,
# so that the port's face is flush with the outside. Seen from the front, the board's
# left and right are swapped from how it was measured, on its component side.
BOARD_W, BOARD_L, BOARD_T = 26.0, 49.0, 2.0
BOARD_X = INNER_W - 0.5 - BOARD_W  # to the right, leaving room for two case screws on the left
BOARD_POST_H = 3.5  # clears pin tails trimmed to 1.5 mm, and holds a 6 mm screw
BOARD_STACK = 21.5  # from the back of the board: its parts, the plugs and their bent wires
BOARD_HOLES = ((2.0, 9.0), (23.0, 47.0))  # from the lower left corner, seen from the front
BOARD_HOLE_D = 2.5  # as it comes, for M2 self-tapping screws
BOARD_PILOT_D = 1.7  # for the M2 self-tapping screws to cut their thread in
BOARD_PILOT_DEPTH = 4.5  # leaves 1 mm of the front under the post
USB_X = 7.0  # centre of the USB-C from the board's left edge, seen from the front
USB_W, USB_H, USB_L = 9.0, 3.0, 7.4
USB_OVERHANG = 2.0  # past the board's edge, the thickness of the bottom wall
USB_SLOT_W, USB_SLOT_H = 10.0, 4.0  # some room to wiggle
ANTENNA_CLEARANCE = 10.0  # from the antenna end of the board to the battery

# 803450 battery, flat against the back plate, between two ribs on the plate.
BATTERY_W, BATTERY_L, BATTERY_T = 34.0, 51.0, 8.0
BATTERY_Y = BOARD_L + ANTENNA_CLEARANCE + 0.5
BATTERY_GAP = 0.3  # between each end of the battery and its rib
RIB_T, RIB_H = 1.2, 3.0
RIBS_X = ((2.0, 33.0), (12.0, 33.0))  # bottom rib, top rib; the cable leaves at the top left
BATTERY_TOP = BATTERY_Y + BATTERY_L + BATTERY_GAP + RIB_T  # the top of the upper rib

# YL-44 buzzer module: lying across the box in front of the battery, its buzzer at the
# right end facing the sound holes, its pins at the left end. An M3 × 8 screw through its
# hole cuts its own thread in a post. The hole is about 3 mm from the buzzer, too close
# for a post wide enough for an insert, so the post is cut flat on that side.
BUZZER_W, BUZZER_L, BUZZER_PCB_T = 13.0, 32.0, 1.6  # up, across, thick
BUZZER_Y = BOARD_L + 12.0  # lower edge of the module, clear of the antenna
BUZZER_TALL = 12.0  # board and buzzer
BUZZER_D = 12.0
BUZZER_FROM_END = 7.0  # buzzer centre from the module's right end
BUZZER_HOLE_D = 3.0
BUZZER_POST_D = 6.5
BUZZER_POST_GAP = 0.4  # between the post's flat side and the buzzer
BUZZER_PILOT_D = 2.7  # for the M3 screw to cut its own thread in
BUZZER_PILOT_DEPTH = 7.5  # an M3 × 8 screw, less the 1.6 mm board, and some to spare
SOUND_HOLE_D = 2.5
SOUND_HOLE_RING_R = 3.5

# VL53L1X module: lying across the box, chip toward the front, pins along its top edge.
# Its lower half sits in front of the battery, and its pins and their Dupont plugs just
# above it.
SENSOR_W, SENSOR_L, SENSOR_T = 13.0, 25.0, 2.0  # up, across, thick, from ear to ear
# The CJMCU-531 is a 17 mm long body with an ear for a hole at each end, narrower than
# the body, so its corners are empty. Estimated from a photo of the module.
SENSOR_EAR_L, SENSOR_EAR_W = 4.0, 6.0  # beyond the body, and across it
CHIP_SIZE = (5.0, 3.0)  # across, up
CHIP_H = 2.0
CHIP_FROM_BOTTOM = 5.0  # chip centre from the module's lower edge, the one without pins
PIN_STRIP = 4.0  # along the top edge: the pin header and the Dupont plugs on it
SENSOR_STACK = 23.0  # from the chip's face: the module, the plugs and their bent wires
SENSOR_HOLE_INSET = 2.5  # two holes on the module's centre line, 20 mm apart
CHIP_GAP = 0.1  # between the chip and the inside of the front
SENSOR_Y = BATTERY_TOP + 0.5 + PIN_STRIP - (SENSOR_W - CHIP_FROM_BOTTOM)  # the chip's centre
APERTURE_W, APERTURE_H = 6.0, 4.0  # at the chip
APERTURE_FLARE = 20.0  # degrees outward, more than half the sensor's 27° field of view

# Rocker switch, in front of the battery between the buzzer and the sensor, with an LED
# on either side of it: green on the left, red on the right.
SWITCH_HOLE_D = 15.2  # it snaps into a 15 mm hole, and printed holes come out a little small
SWITCH_D, SWITCH_DEPTH = 15.0, 17.0  # 16 mm behind the front, and its wires bent sideways
SWITCH_RIM_D = 16.5  # on the outside of the front
SWITCH_Y = (BUZZER_Y + BUZZER_W + SENSOR_Y - CHIP_FROM_BOTTOM) / 2

# 5 mm LEDs in printed collars, with their domes standing out of the front.
LED_SPREAD = 13.0  # from the switch's centre, clear of its clips
LEDS = ((CENTRE_X - LED_SPREAD, SWITCH_Y), (CENTRE_X + LED_SPREAD, SWITCH_Y))  # green, red
LED_D, LED_HOLE_D = 5.0, 5.1
LED_FLANGE_D, LED_FLANGE_T = 5.8, 1.0
LED_LENGTH = 8.6  # from the back of the flange to the top of the dome
LED_LEGS = 10.0  # legs and the resistor soldered to them
LED_COLLAR_D, LED_COLLAR_H = 8.0, 2.0

# The top two case screws sit in the corners beside the sensor's body, and their round
# posts come down over its ears as far as they can without touching them.
BOSS_R = 3.75
SENSOR_EAR_TOP = SENSOR_Y - CHIP_FROM_BOTTOM + SENSOR_W / 2 + SENSOR_EAR_W / 2
EAR_TIP_X = CENTRE_X - SENSOR_L / 2  # from the left wall; the right one is as far from its wall
TOP_SCREW_Y = SENSOR_EAR_TOP + 0.3 + math.sqrt(BOSS_R**2 - (EAR_TIP_X - BOSS_R) ** 2)
INNER_H = float(math.ceil(max(TOP_SCREW_Y + BOSS_R, SENSOR_Y - CHIP_FROM_BOTTOM + SENSOR_W + 0.5)))
HEIGHT = INNER_H + 2 * WALL

# Printed pins through the sensor's holes, melted over once it is fitted.
PIN_D = 2.8
MELT = 1.5  # length of pin above the board, to melt over

# Case screws: countersunk M3 × 8 through the back plate into heat-set inserts in the tub,
# two beside the board and two in the top corners.
CASE_SCREWS = (
    (BOSS_R, BOSS_R),
    (BOSS_R, 30.0),
    (BOSS_R, TOP_SCREW_Y),
    (INNER_W - BOSS_R, TOP_SCREW_Y),
)
INSERT_HOLE_D, INSERT_HOLE_DEPTH = 4.1, 6.5  # inserts about 4.5 mm across and 5 mm long
SCREW_HOLE_D, COUNTERSINK_D = 3.4, 6.4

# Two 10 × 5 × 1 mm magnets glued flush into pockets in the outside of the back plate,
# spread evenly along its length. The pockets leave 0.5 mm all round them.
MAGNET = (5.0, 10.0, 1.0)
MAGNET_POCKET = (6.0, 11.0, 1.1)
MAGNETS_Y = tuple(HEIGHT * k / 4 for k in (1, 3))

# Unit numbers: a tub for each of the up to 8 units at a passage, with as many raised
# dots on top as its number. They fill a 4 × 2 grid from the front left, a row at a time.
UNITS = 8
DOT_D, DOT_H = 3.0, 1.0
DOT_PITCH = 7.0
DOT_GRID = (4, 2)  # across the width, and from the front back

# A hook on top, flush with the back, to hang the unit from a nail or a screw: a tab with
# a hole, braced underneath at 45° so that it prints without supports. A nail can stick
# through the hole and the brace.
HOOK_W, HOOK_H, HOOK_T = 12.0, 10.0, 3.0  # across, above the top, thick
HOOK_HOLE_D = 5.0
HOOK_HOLE_Y = HOOK_H - HOOK_W / 2  # above the top, at the centre of the tab's round end
HOOK_BRACE_Z = DEPTH - HOOK_T - HOOK_H  # where the brace meets the top, from the front

# The dots sit between the front's rounded edge and the hook's brace.
DOTS_Z = (FRONT_EDGE_R + HOOK_BRACE_Z) / 2


def real(part: Part) -> Part:
    """The part as it really is: the model's mirror image across the middle of the width."""
    return mirror(part, about=Plane.YZ.offset(WIDTH / 2))


def inside(x: float, y: float, z: float = 0.0) -> Pos:
    """Place at a position given from the inside corner of the left, bottom and front walls."""
    return Pos(WALL + x, WALL + y, FRONT + z)


def block(x0: float, y0: float, z0: float, x1: float, y1: float, z1: float) -> Part:
    """A box between two corners, given inside the box."""
    return inside(x0, y0, z0) * Box(x1 - x0, y1 - y0, z1 - z0, align=Align.MIN)


def column(x: float, y: float, d: float, z0: float, z1: float) -> Part:
    """A cylinder along z, given inside the box."""
    return inside(x, y, z0) * Cylinder(
        d / 2, z1 - z0, align=(Align.CENTER, Align.CENTER, Align.MIN)
    )


def rounded_slab(x0: float, y0: float, w: float, h: float, r: float, z0: float, d: float) -> Part:
    """A plate with rounded corners, in outside coordinates."""
    return Pos(x0 + w / 2, y0 + h / 2, z0) * extrude(RectangleRounded(w, h, r), amount=d)


def union(parts: Iterable[Part]) -> Part:
    return functools.reduce(operator.add, parts)


def boss(x: float, y: float) -> Part:
    """A column for a case screw, joined to the walls next to it."""
    to_side = (
        block(x, y - BOSS_R, 0, INNER_W + 0.1, y + BOSS_R, INNER_D)
        if x > CENTRE_X
        else block(-0.1, y - BOSS_R, 0, x, y + BOSS_R, INNER_D)
    )
    to_bottom = [block(x - BOSS_R, -0.1, 0, x + BOSS_R, y, INNER_D)] if y <= BOSS_R else []
    to_top = (
        [block(x - BOSS_R, y, 0, x + BOSS_R, INNER_H + 0.1, INNER_D)]
        if y + BOSS_R > INNER_H - 2.0  # close enough to the top to join it
        else []
    )
    return union([column(x, y, 2 * BOSS_R, -0.1, INNER_D), to_side, *to_bottom, *to_top])


def sensor_holes() -> tuple[tuple[float, float], ...]:
    y = SENSOR_Y - CHIP_FROM_BOTTOM + SENSOR_W / 2  # the module's centre line
    half = SENSOR_L / 2 - SENSOR_HOLE_INSET
    return ((CENTRE_X - half, y), (CENTRE_X + half, y))


def board_holes() -> tuple[tuple[float, float], ...]:
    return tuple((BOARD_X + x, y) for x, y in BOARD_HOLES)


def buzzer_hole() -> tuple[float, float]:
    return CENTRE_X, BUZZER_Y + BUZZER_W / 2


def buzzer_centre() -> tuple[float, float]:
    return CENTRE_X + BUZZER_L / 2 - BUZZER_FROM_END, BUZZER_Y + BUZZER_W / 2


def stadium(w: float, h: float, y0: float, y1: float) -> Part:
    """A bar with rounded ends along x, running along y, centred on the USB-C."""
    x = BOARD_X + USB_X
    z = BOARD_POST_H + BOARD_T + USB_H / 2
    straight = w - h
    along_y = Rot(-90, 0, 0) * Cylinder(
        h / 2, y1 - y0, align=(Align.CENTER, Align.CENTER, Align.MIN)
    )
    ends = [inside(x + dx, y0, z) * along_y for dx in (-straight / 2, straight / 2)]
    middle = inside(x, y0, z) * Box(
        straight, y1 - y0, h, align=(Align.CENTER, Align.MIN, Align.CENTER)
    )
    return union([middle, *ends])


def usb_slot() -> Part:
    """The opening for the USB-C in the bottom wall."""
    return stadium(USB_SLOT_W, USB_SLOT_H, -WALL - 1, 1)


def aperture() -> Part:
    """The sensor's open aperture: a rectangle at the chip, flaring out so that its sides
    stay out of the sensor's field of view."""
    at_chip = Plane.XY.offset(FRONT + 0.01) * Pos(WALL + CENTRE_X, WALL + SENSOR_Y)
    return extrude(
        at_chip * Rectangle(APERTURE_W, APERTURE_H),
        amount=FRONT + 1,
        dir=(0, 0, -1),
        taper=-APERTURE_FLARE,
    )


def sound_holes() -> Part:
    x, y = buzzer_centre()
    ring = [
        (x + SOUND_HOLE_RING_R * dx, y + SOUND_HOLE_RING_R * dy)
        for dx, dy in ((1, 0), (0.5, 0.866), (-0.5, 0.866), (-1, 0), (-0.5, -0.866), (0.5, -0.866))
    ]
    return union(column(hx, hy, SOUND_HOLE_D, -FRONT - 1, 0.5) for hx, hy in [(x, y), *ring])


def hook() -> Part:
    """The hook on top: a tab with a round end and a hole, and the brace underneath it."""
    x0, hole_y = WIDTH / 2 - HOOK_W / 2, HEIGHT + HOOK_HOLE_Y

    def outline(z0: float, depth: float) -> Part:
        """The tab's outline, seen from the back, as a solid from z0 to the back. It
        stops at the top of the box, clear of the back plate below."""
        rounded = Pos(x0, HEIGHT - 0.1, z0) * Box(
            HOOK_W, HOOK_HOLE_Y + 0.1, depth, align=Align.MIN
        ) + Pos(WIDTH / 2, hole_y, z0) * Cylinder(
            HOOK_W / 2, depth, align=(Align.CENTER, Align.CENTER, Align.MIN)
        )
        return rounded & Pos(x0, HEIGHT - 0.1, z0) * Box(
            HOOK_W, HOOK_H + 0.1, depth, align=Align.MIN
        )

    tab_z = DEPTH - HOOK_T
    tab = outline(tab_z, HOOK_T)
    wedge = extrude(
        Plane.YZ.offset(x0)
        * Polygon(
            (HEIGHT - 0.1, HOOK_BRACE_Z),
            (HEIGHT - 0.1, tab_z + 0.1),
            (HEIGHT + HOOK_H, tab_z + 0.1),
            align=None,
        ),
        amount=HOOK_W,
    )
    brace = wedge & outline(HOOK_BRACE_Z, DEPTH - HOOK_BRACE_Z)
    hole = Pos(WIDTH / 2, hole_y, HOOK_BRACE_Z - 1) * Cylinder(
        HOOK_HOLE_D / 2, DEPTH - HOOK_BRACE_Z + 2, align=(Align.CENTER, Align.CENTER, Align.MIN)
    )
    return tab + brace - hole


def tub() -> Part:
    shell = rounded_slab(0, 0, WIDTH, HEIGHT, CORNER_R, 0, DEPTH)
    shell = fillet(shell.faces().sort_by(Axis.Z)[0].edges(), FRONT_EDGE_R)
    cavity = rounded_slab(WALL, WALL, INNER_W, INNER_H, INNER_CORNER_R, FRONT, DEPTH)
    rebate = rounded_slab(
        LIP, LIP, WIDTH - 2 * LIP, HEIGHT - 2 * LIP, CORNER_R - LIP, FRONT + INNER_D, PLATE + 1
    )

    bosses = [boss(x, y) for x, y in CASE_SCREWS]
    board_posts = [column(x, y, 6.0, -0.1, BOARD_POST_H) for x, y in board_holes()]
    sensor_post_h = CHIP_GAP + CHIP_H
    sensor_posts = [
        column(x, y, 5.0, -0.1, sensor_post_h)
        + column(x, y, PIN_D, 0, sensor_post_h + SENSOR_T + MELT)
        for x, y in sensor_holes()
    ]
    bx, by = buzzer_hole()
    buzzer_post_h = BUZZER_TALL - BUZZER_PCB_T
    flat = buzzer_centre()[0] - BUZZER_D / 2 - BUZZER_POST_GAP
    r = BUZZER_POST_D / 2
    buzzer_post = column(bx, by, BUZZER_POST_D, -0.1, buzzer_post_h) & block(
        bx - r, by - r, -0.2, flat, by + r, buzzer_post_h + 0.1
    )
    led_collars = [column(x, y, LED_COLLAR_D, -0.1, LED_COLLAR_H) for x, y in LEDS]
    solid = (
        shell
        - cavity
        - rebate
        + union([*bosses, *board_posts, *sensor_posts, buzzer_post, *led_collars])
    )

    insert_holes = [
        column(x, y, INSERT_HOLE_D, INNER_D - INSERT_HOLE_DEPTH, INNER_D + 1)
        for x, y in CASE_SCREWS
    ]
    pilots = [
        *(
            column(x, y, BOARD_PILOT_D, BOARD_POST_H - BOARD_PILOT_DEPTH, BOARD_POST_H + 1)
            for x, y in board_holes()
        ),
        column(bx, by, BUZZER_PILOT_D, buzzer_post_h - BUZZER_PILOT_DEPTH, buzzer_post_h + 1),
    ]
    led_holes = [column(x, y, LED_HOLE_D, -FRONT - 1, LED_COLLAR_H + 1) for x, y in LEDS]
    switch_hole = column(CENTRE_X, SWITCH_Y, SWITCH_HOLE_D, -FRONT - 1, 0.5)
    openings = [
        aperture(),
        sound_holes(),
        switch_hole,
        usb_slot(),
        *led_holes,
        *insert_holes,
        *pilots,
    ]
    return solid - union(openings) + hook()


def back_plate() -> Part:
    inset = LIP + FIT
    plate = rounded_slab(
        inset,
        inset,
        WIDTH - 2 * inset,
        HEIGHT - 2 * inset,
        CORNER_R - inset,
        FRONT + INNER_D,
        PLATE,
    )
    bottom_rib = BATTERY_Y - BATTERY_GAP - RIB_T
    top_rib = BATTERY_Y + BATTERY_L + BATTERY_GAP
    ribs = [
        block(x0, y, INNER_D - RIB_H, x1, y + RIB_T, INNER_D + 0.1)
        for (x0, x1), y in zip(RIBS_X, (bottom_rib, top_rib), strict=True)
    ]
    screw_holes = [
        column(x, y, SCREW_HOLE_D, INNER_D - 1, INNER_D + PLATE + 1)
        + inside(x, y, INNER_D + PLATE - 1.6)
        * Cone(
            SCREW_HOLE_D / 2,
            COUNTERSINK_D / 2 + 0.1,
            1.7,
            align=(Align.CENTER, Align.CENTER, Align.MIN),
        )
        for x, y in CASE_SCREWS
    ]
    pw, ph, pd = MAGNET_POCKET
    pockets = [
        Pos(WIDTH / 2, y, DEPTH - pd)
        * Box(pw, ph, pd + 1, align=(Align.CENTER, Align.CENTER, Align.MIN))
        for y in MAGNETS_Y
    ]
    return plate + union(ribs) - union([*screw_holes, *pockets])


def dot_positions(number: int) -> tuple[tuple[float, float], ...]:
    """Where the dots of the unit with this number sit on top, as (x, z) outside."""
    columns, rows = DOT_GRID
    if not 1 <= number <= columns * rows:
        raise ValueError(f"A unit number is 1 to {columns * rows}, not {number}.")
    return tuple(
        (
            WIDTH / 2 + (k % columns - (columns - 1) / 2) * DOT_PITCH,
            DOTS_Z + (k // columns - (rows - 1) / 2) * DOT_PITCH,
        )
        for k in range(number)
    )


def dots(number: int) -> Part:
    """The dots for a unit number: low domes standing out of the top."""
    a, h = DOT_D / 2, DOT_H
    r = (a * a + h * h) / (2 * h)  # of the sphere each dome is cut from
    above = (Align.CENTER, Align.MIN, Align.CENTER)
    return union(
        Pos(x, HEIGHT + h - r, z) * Sphere(r)
        & Pos(x, HEIGHT - 0.1, z) * Box(DOT_D + 1, h + 0.1, DOT_D + 1, align=above)
        for x, z in dot_positions(number)
    )


def numbered_tub(tub_: Part, number: int) -> Part:
    """The tub for the unit with this number, 1 to 8."""
    return tub_ + dots(number)


# The parts the enclosure holds, as their measured outlines.


def battery() -> Part:
    x0 = (INNER_W - BATTERY_W) / 2
    return block(x0, BATTERY_Y, INNER_D - BATTERY_T, x0 + BATTERY_W, BATTERY_Y + BATTERY_L, INNER_D)


def board() -> Part:
    back = BOARD_POST_H
    pcb = block(BOARD_X, 0, back, BOARD_X + BOARD_W, BOARD_L, back + BOARD_T)
    holes = union(
        column(x, y, BOARD_HOLE_D, back - 1, back + BOARD_T + 1) for x, y in board_holes()
    )
    top = back + BOARD_T
    usb = stadium(USB_W, USB_H, -USB_OVERHANG, USB_L - USB_OVERHANG)
    stack = block(BOARD_X, 0, top, BOARD_X + BOARD_W, BOARD_L, back + BOARD_STACK)
    return pcb - holes + usb + stack


def antenna_gap() -> float:
    return BATTERY_Y - BOARD_L


def sensor() -> Part:
    x0, y0 = CENTRE_X - SENSOR_L / 2, SENSOR_Y - CHIP_FROM_BOTTOM
    across, up = CHIP_SIZE
    chip = block(
        CENTRE_X - across / 2,
        SENSOR_Y - up / 2,
        CHIP_GAP,
        CENTRE_X + across / 2,
        SENSOR_Y + up / 2,
        CHIP_GAP + CHIP_H,
    )
    back = CHIP_GAP + CHIP_H
    body_x0, body_x1 = x0 + SENSOR_EAR_L, x0 + SENSOR_L - SENSOR_EAR_L
    middle = y0 + SENSOR_W / 2
    ear_y0, ear_y1 = middle - SENSOR_EAR_W / 2, middle + SENSOR_EAR_W / 2
    pcb = union(
        [
            block(body_x0, y0, back, body_x1, y0 + SENSOR_W, back + SENSOR_T),
            block(x0, ear_y0, back, body_x0, ear_y1, back + SENSOR_T),
            block(body_x1, ear_y0, back, x0 + SENSOR_L, ear_y1, back + SENSOR_T),
        ]
    )
    holes = union(column(x, y, PIN_D + 0.2, 0, back + SENSOR_T + 1) for x, y in sensor_holes())
    top = y0 + SENSOR_W
    plugs = block(body_x0, top - PIN_STRIP, back + SENSOR_T, body_x1, top, CHIP_GAP + SENSOR_STACK)
    return chip + pcb - holes + plugs


def switch() -> Part:
    return column(CENTRE_X, SWITCH_Y, SWITCH_D, 0, SWITCH_DEPTH)


def buzzer() -> Part:
    x0 = CENTRE_X - BUZZER_L / 2
    front = BUZZER_TALL - BUZZER_PCB_T
    pcb = block(x0, BUZZER_Y, front, x0 + BUZZER_L, BUZZER_Y + BUZZER_W, BUZZER_TALL)
    hx, hy = buzzer_hole()
    hole = column(hx, hy, BUZZER_HOLE_D, front - 1, BUZZER_TALL + 1)
    screw_head = column(hx, hy, 7.0, BUZZER_TALL, BUZZER_TALL + 2.5)  # with a washer
    cx, cy = buzzer_centre()
    can = column(cx, cy, BUZZER_D, 0.05, front)
    pins = block(x0 + 0.5, BUZZER_Y + 2.5, 0.5, x0 + 3.5, BUZZER_Y + BUZZER_W - 2.5, front)
    return pcb - hole + can + pins + screw_head


def led(x: float, y: float) -> Part:
    flange = LED_COLLAR_H
    return (
        column(x, y, LED_D, flange + LED_FLANGE_T - LED_LENGTH, flange)
        + column(x, y, LED_FLANGE_D, flange, flange + LED_FLANGE_T)
        + column(x, y, 6.0, flange + LED_FLANGE_T, flange + LED_FLANGE_T + LED_LEGS)
    )


def magnets() -> Part:
    w, h, d = MAGNET
    return union(
        Pos(WIDTH / 2, y, DEPTH - d) * Box(w, h, d, align=(Align.CENTER, Align.CENTER, Align.MIN))
        for y in MAGNETS_Y
    )


def contents() -> dict[str, Part]:
    return {
        "battery": battery(),
        "board": board(),
        "sensor": sensor(),
        "switch": switch(),
        "buzzer": buzzer(),
        "green LED": led(*LEDS[0]),
        "red LED": led(*LEDS[1]),
        "magnets": magnets(),
    }


def overlap(a: Part, b: Part) -> float:
    common = a & b
    return common.volume if common else 0.0


def fit_problems(tub_: Part, plate: Part, parts: dict[str, Part]) -> list[str]:
    """What doesn't fit: parts that run into the enclosure or each other, and the limits."""
    tolerance = 0.01  # mm³, for faces that only touch
    problems = [
        f"The {name} runs into the {shell_name}: {volume:.2f} mm³."
        for name, part in parts.items()
        for shell_name, shell in (("tub", tub_), ("back plate", plate))
        if (volume := overlap(part, shell)) > tolerance
    ]
    names = list(parts)
    problems += [
        f"The {a} runs into the {b}: {volume:.2f} mm³."
        for i, a in enumerate(names)
        for b in names[i + 1 :]
        if (volume := overlap(parts[a], parts[b])) > tolerance
    ]
    if (volume := overlap(tub_, plate)) > tolerance:
        problems.append(f"The back plate runs into the tub: {volume:.2f} mm³.")
    if DEPTH > MAX_DEPTH:
        problems.append(f"The box is {DEPTH} mm deep, more than {MAX_DEPTH} mm.")
    if antenna_gap() < ANTENNA_CLEARANCE:
        problems.append(
            f"The battery is {antenna_gap()} mm from the antenna, less than {ANTENNA_CLEARANCE} mm."
        )
    return problems
