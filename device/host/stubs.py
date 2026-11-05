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
    package = f"micropython-esp32-stubs=={rel.version}.*"
    print(f"Installing {package} into {release.STUBS_DIR.name}/", flush=True)
    # Install next to the old stubs and swap, so a failed install keeps them.
    new = release.STUBS_DIR.with_name(release.STUBS_DIR.name + ".new")
    shutil.rmtree(new, ignore_errors=True)
    result = subprocess.run(
        [uv, "pip", "install", "--quiet", "--target", new, package],
        check=False,
    )
    if result.returncode != 0:
        shutil.rmtree(new, ignore_errors=True)
        return result.returncode
    shutil.rmtree(release.STUBS_DIR, ignore_errors=True)
    new.rename(release.STUBS_DIR)
    print(f"{console.OK} Stubs for MicroPython {rel.version} installed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
