"""PIN, locking and the control point commands."""

from examples import EXAMPLE, OTHER, PILLAR
from firmware import control
from firmware import protocol as p
from firmware.control import Access

PIN = b"1234"

ASSIGNED = control.setup_of(EXAMPLE, PIN, PILLAR)
ASSIGNED_LOCKED = control.started(ASSIGNED, 0)
UNASSIGNED = control.started(control.UNASSIGNED, 0)


def run(access, data, pin_reset_window=False):
    return control.command(access, data, PILLAR, pin_reset_window)


def run_all(access, commands):
    results = []
    for data in commands:
        access, result, _ = run(access, data)
        results.append(result)
    return access, results


def unlock(pin=PIN):
    return bytes((p.UNLOCK,)) + pin


def set_pin(pin):
    return bytes((p.SET_PIN,)) + pin


def test_setup_of_finds_this_units_entry():
    assert control.assigned(ASSIGNED_LOCKED)
    assert ASSIGNED.entry.zero_offset == 60


def test_a_record_that_doesnt_list_the_unit_leaves_it_unassigned():
    setup = control.setup_of(EXAMPLE, PIN, bytes(6))
    assert setup == control.Setup(b"", None, None, PIN)


def test_setup_file_round_trip():
    assert control.restored(control.stored(ASSIGNED), PILLAR) == ASSIGNED
    pin_only = control.Setup(b"", None, None, PIN)
    assert control.restored(control.stored(pin_only), PILLAR) == pin_only


def test_an_unreadable_setup_file_leaves_the_unit_unassigned():
    for data in (b"", b"\x02\x04", b"\x01\x09" + PIN, b"\x01\x02ab"):
        assert control.restored(data, PILLAR) == control.UNASSIGNED


def test_unlock_on_an_unassigned_unit_changes_nothing():
    assert run(UNASSIGNED, unlock(b"x")) == (UNASSIGNED, p.DONE, None)
    assert control.unlocked(UNASSIGNED)


def test_unlock_with_the_right_pin():
    access, result, effect = run(ASSIGNED_LOCKED, unlock())
    assert (result, effect) == (p.DONE, None)
    assert control.unlocked(access)


def test_unlock_with_an_invalid_pin():
    for pin in (b"123", b"123456789", b"12a4"):
        assert run(ASSIGNED_LOCKED, unlock(pin))[1] == p.INVALID_PARAMETERS


def test_five_wrong_pins_lock_the_unit_out():
    access, results = run_all(ASSIGNED_LOCKED, [unlock(b"0000")] * 6 + [unlock()])
    assert results == [p.WRONG_PIN] * 4 + [p.LOCKED_OUT_RESULT] * 3
    assert control.locked_out(access)
    assert not control.unlocked(access)


def test_a_right_pin_resets_the_count():
    access, results = run_all(ASSIGNED_LOCKED, [unlock(b"0000")] * 4 + [unlock(), unlock(b"0000")])
    assert results == [p.WRONG_PIN] * 4 + [p.DONE, p.WRONG_PIN]
    assert access.wrong_pins == 1


def test_set_pin_needs_an_unlock_or_the_reset_window():
    assert run(ASSIGNED_LOCKED, set_pin(b"5678"))[1] == p.NOT_ALLOWED
    access, result, effect = run(ASSIGNED_LOCKED, set_pin(b"5678"), pin_reset_window=True)
    assert (result, effect) == (p.DONE, control.SAVE)
    assert access.setup.pin == b"5678"
    assert control.unlocked(access)


def test_set_pin_on_an_unassigned_unit():
    access, result, effect = run(UNASSIGNED, set_pin(PIN))
    assert (result, effect, access.setup.pin) == (p.DONE, control.SAVE, PIN)
    assert run(UNASSIGNED, set_pin(b"12"))[1] == p.INVALID_PARAMETERS


def test_commit_needs_a_pin():
    staged = control.staged(UNASSIGNED, EXAMPLE)
    assert run(staged, bytes((p.COMMIT,)))[1] == p.NOT_ALLOWED


def test_set_up_a_new_unit():
    access, results = run_all(UNASSIGNED, [set_pin(PIN)])
    access = control.staged(access, EXAMPLE)
    access, result, effect = run(access, bytes((p.COMMIT,)))
    assert (results, result, effect) == ([p.DONE], p.DONE, control.SAVE)
    assert access.setup == ASSIGNED
    assert access.staged == b""
    assert not control.unlocked(access)


def test_commit_needs_an_unlock():
    access = control.staged(ASSIGNED_LOCKED, EXAMPLE)
    assert run(access, bytes((p.COMMIT,)))[1] == p.NOT_ALLOWED


def test_commit_checks_the_staged_record():
    unlocked = run(ASSIGNED_LOCKED, unlock())[0]
    assert run(unlocked, bytes((p.COMMIT,)))[1] == p.INVALID_PARAMETERS
    broken = control.staged(unlocked, EXAMPLE[:30])
    assert run(broken, bytes((p.COMMIT,)))[1] == p.INVALID_PARAMETERS
    other_unit = control.command(control.staged(unlocked, EXAMPLE), b"\x03", bytes(6), False)
    assert other_unit[1] == p.NOT_LISTED


def test_identify_and_unknown_opcodes():
    assert run(ASSIGNED_LOCKED, bytes((p.IDENTIFY,))) == (ASSIGNED_LOCKED, p.DONE, control.IDENTIFY)
    assert run(ASSIGNED_LOCKED, b"\x09")[1] == p.UNKNOWN_OPCODE
    assert run(ASSIGNED_LOCKED, b"")[1] == p.UNKNOWN_OPCODE


def test_forget_needs_an_unlock():
    assert run(ASSIGNED_LOCKED, bytes((p.FORGET,)))[1] == p.NOT_ALLOWED
    unlocked = run(ASSIGNED_LOCKED, unlock())[0]
    access, result, effect = run(unlocked, bytes((p.FORGET,)))
    assert (result, effect, access.setup) == (p.DONE, control.FORGET, control.UNASSIGNED)


def test_lock_and_release():
    unlocked = run(ASSIGNED_LOCKED, unlock())[0]
    assert not control.unlocked(run(unlocked, bytes((p.LOCK,)))[0])
    assert run(unlocked, bytes((p.RELEASE,)))[0].released


def test_a_new_session_locks_and_forgets_the_staged_record():
    unlocked = control.staged(run(ASSIGNED_LOCKED, unlock())[0], EXAMPLE)
    released = run(unlocked, bytes((p.RELEASE,)))[0]
    assert control.session_ended(released) == ASSIGNED_LOCKED


def test_signals_only_while_assigned_locked_and_set_to_signal():
    assert control.signals(ASSIGNED_LOCKED)
    assert not control.signals(run(ASSIGNED_LOCKED, unlock())[0])
    assert not control.signals(UNASSIGNED)
    other = control.started(control.setup_of(EXAMPLE, PIN, OTHER), 0)
    assert control.signals(other)


def test_flags():
    unlocked = run(ASSIGNED_LOCKED, unlock())[0]
    assert control.flags(unlocked, connected=True, pin_reset_window=False) == p.IN_USE | p.UNLOCKED
    assert control.flags(unlocked, connected=False, pin_reset_window=True) == p.PIN_RESET_WINDOW
    assert (
        control.flags(UNASSIGNED, connected=True, pin_reset_window=False) == p.IN_USE | p.UNLOCKED
    )
    locked_out = Access(ASSIGNED, False, b"", False, 5)
    assert control.flags(locked_out, connected=False, pin_reset_window=False) == p.LOCKED_OUT
