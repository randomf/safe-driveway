"""The pinned MicroPython release, read from ../micropython.toml."""

import tomllib
from dataclasses import dataclass
from pathlib import Path

DEVICE_DIR = Path(__file__).resolve().parent.parent
CACHE_DIR = DEVICE_DIR / ".cache" / "firmware"
STUBS_DIR = DEVICE_DIR / ".stubs"


@dataclass(frozen=True)
class Release:
    board: str
    version: str
    date: str
    sha256: str

    @property
    def filename(self) -> str:
        return f"{self.board}-{self.date}-v{self.version}.bin"

    @property
    def url(self) -> str:
        return f"https://micropython.org/resources/firmware/{self.filename}"

    @property
    def cache_path(self) -> Path:
        return CACHE_DIR / self.filename


def load() -> Release:
    with open(DEVICE_DIR / "micropython.toml", "rb") as f:
        data = tomllib.load(f)
    return Release(
        board=data["board"], version=data["version"], date=data["date"], sha256=data["sha256"]
    )
