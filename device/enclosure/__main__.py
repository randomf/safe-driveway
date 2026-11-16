"""Check that the parts fit in the enclosure, then write its print files and pictures.

Run with `just enclosure` from device/. See README.md in this folder.
"""

import sys
from pathlib import Path

from build123d import Compound, export_step, export_stl

from enclosure import model, pictures

HERE = Path(__file__).parent
PRINT = HERE / "print"
PICTURES = HERE / "pictures"


def main() -> int:
    tub, plate, parts = model.tub(), model.back_plate(), model.contents()
    problems = model.fit_problems(tub, plate, parts)
    if problems:
        print("\n".join(problems))
        return 1

    PRINT.mkdir(exist_ok=True)
    for number in range(1, model.UNITS + 1):
        numbered = model.numbered_tub(tub, number)
        export_stl(numbered, PRINT / f"tub-{number}.stl", tolerance=0.01, angular_tolerance=0.1)
    export_stl(plate, PRINT / "back-plate.stl", tolerance=0.01, angular_tolerance=0.1)
    # A fixed timestamp, so that the file changes only when the model does.
    export_step(
        Compound([tub, plate], label="enclosure"),
        PRINT / "enclosure.step",
        timestamp="2026-01-01T00:00:00",
    )

    PICTURES.mkdir(exist_ok=True)
    for picture in pictures.pictures(tub, plate, parts):
        pictures.draw(picture).save(PICTURES / f"{picture.name}.png", optimize=True)

    print(
        f"Everything fits. Outside: {model.WIDTH} × {model.HEIGHT} × {model.DEPTH} mm"
        " (width × height × depth)."
    )
    for folder in (PRINT, PICTURES):
        print(f"Wrote {', '.join(sorted(p.name for p in folder.iterdir()))} to {folder}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
