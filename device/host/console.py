"""Terminal output and questions shared by the tasks."""

import sys
from typing import NoReturn

OK = "✓"
FAIL = "✗"
WARN = "!"
SKIP = "–"


def interactive() -> bool:
    return sys.stdin.isatty()


def ask(question: str, default: bool | None = None) -> bool | None:
    """Ask a yes/no question. Returns None when nobody can answer it."""
    if not interactive():
        return None
    hint = {None: "[y/n]", True: "[Y/n]", False: "[y/N]"}[default]
    while True:
        answer = input(f"  {question} {hint} ").strip().lower()
        if answer in ("y", "yes"):
            return True
        if answer in ("n", "no"):
            return False
        if answer == "" and default is not None:
            return default


def fail(message: str) -> NoReturn:
    sys.stdout.flush()
    print(f"{FAIL} {message}", file=sys.stderr)
    sys.exit(1)
