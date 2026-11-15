"""Millisecond ticks: time.ticks_ms() values on the board, plain numbers in CPython."""

try:
    from time import ticks_add, ticks_diff
except ImportError:
    # CPython, for the tests and the host tools, where times don't wrap.

    def ticks_add(ticks, delta):
        return ticks + delta

    def ticks_diff(end, start):
        return end - start


__all__ = ["ticks_add", "ticks_diff"]
