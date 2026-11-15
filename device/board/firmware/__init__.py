"""The Safe Driveway firmware, see ../../firmware.md.

main.py is the imperative shell that owns the hardware and the Bluetooth stack.
The other modules are pure: they use no MicroPython modules, so they also run
in CPython, where ../../tests/ test them and the host tools in ../../host/
share their byte layouts.
"""
