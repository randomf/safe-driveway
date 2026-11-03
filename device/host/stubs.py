"""Install the MicroPython stubs for the pinned release into ../.stubs.

Editors use them for the code in ../board/ only; see ../board/pyrightconfig.json.
"""

import shutil
import subprocess
import sys

from host import console, release


def main() -> int:
    uv = shutil.which("uv")
    if uv is None:
        console.fail("uv isn't on PATH. See README.md for how to install it.")
    rel = release.load()
    shutil.rmtree(release.STUBS_DIR, ignore_errors=True)
    package = f"micropython-esp32-stubs=={rel.version}.*"
    print(f"Installing {package} into {release.STUBS_DIR.name}/")
    result = subprocess.run(
        [uv, "pip", "install", "--quiet", "--target", release.STUBS_DIR, package],
        check=False,
    )
    if result.returncode == 0:
        print(f"{console.OK} Stubs for MicroPython {rel.version} installed.")
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
