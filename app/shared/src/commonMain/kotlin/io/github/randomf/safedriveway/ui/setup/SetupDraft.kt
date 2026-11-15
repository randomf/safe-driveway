package io.github.randomf.safedriveway.ui.setup

import io.github.randomf.safedriveway.passage.Limits
import io.github.randomf.safedriveway.passage.PassageId
import io.github.randomf.safedriveway.passage.PassageSetup
import io.github.randomf.safedriveway.passage.Side
import io.github.randomf.safedriveway.passage.UnitId
import io.github.randomf.safedriveway.passage.UnitSetup
import io.github.randomf.safedriveway.passage.utf8Size
import kotlin.random.Random

/** A step of the new-passage wizard, and a section of an existing passage's setup. */
enum class Step { Name, Units, Positions, ZeroOffsets, Thresholds, Pin }

/**
 * A setup being made or changed. Nothing reaches the units until it's written: the new-passage
 * wizard writes at its end, and setup editing on Save. Each change returns a new draft.
 */
data class SetupDraft(
    /** The setup being changed, or null for a new passage. */
    val original: PassageSetup?,
    val setup: PassageSetup,
    /** The new PIN, if it's being set or changed. */
    val newPin: String? = null,
    /** Units of the passage that are to be reset to belong to no passage. */
    val forgotten: Set<UnitId> = emptySet()
) {
    val isNew: Boolean get() = original == null
    val changed: Boolean get() = setup != original || newPin != null

    /** The wizard's steps for this draft: gate positions only with more than one gate. */
    val steps: List<Step>
        get() = Step.entries.filter { it != Step.Positions || setup.gateCount > 1 }

    fun next(after: Step): Step? = steps.getOrNull(steps.indexOf(after) + 1)

    /** The setup to write: one version above the one it changes. */
    fun toWrite(): PassageSetup = setup.copy(version = (original?.version ?: 0) + 1)

    fun edit(change: (PassageSetup) -> PassageSetup): SetupDraft = copy(setup = change(setup))

    fun assign(unit: UnitId, gate: Int, side: Side): SetupDraft = copy(
        setup = setup.copy(
            units = setup.units + UnitSetup(unit, "", gate, side, zeroOffsetMm = 0, signals = true)
        ),
        forgotten = forgotten - unit
    )

    fun update(unit: UnitId, change: (UnitSetup) -> UnitSetup): SetupDraft =
        edit { it.copy(units = it.units.map { u -> if (u.id == unit) change(u) else u }) }

    /** Takes the unit off its gate. A unit the passage had is reset when the setup is saved. */
    fun remove(unit: UnitId): SetupDraft = copy(
        setup = setup.copy(units = setup.units.filter { it.id != unit }),
        forgotten = forgotten + listOfNotNull(unit.takeIf { original?.unit(it) != null })
    )

    fun replace(old: UnitId, new: UnitId): SetupDraft = copy(
        setup = setup.withReplaced(old, new),
        forgotten = forgotten + listOfNotNull(old.takeIf { original?.unit(it) != null })
    )

    /** Adds a gate after the last, 1 m further on, until it's measured. */
    fun addGate(): SetupDraft = if (setup.gateCount >= Limits.MAX_GATES) {
        this
    } else {
        edit {
            it.copy(gatePositionsMm = it.gatePositionsMm + (it.gatePositionsMm.last() + 1000))
        }
    }

    /** Whether [gate] can be removed: it's empty, and not the passage's only gate. */
    fun canRemoveGate(gate: Int): Boolean =
        setup.gateCount > 1 && setup.units.none { it.gate == gate }

    fun removeGate(gate: Int): SetupDraft = if (canRemoveGate(gate)) {
        edit {
            it.withoutGate(gate)
        }
    } else {
        this
    }

    /** The gaps between neighbouring gates, which the driver measures. */
    val spacingsMm: List<Int> get() = setup.gatePositionsMm.zipWithNext { a, b -> b - a }

    /** Sets the gaps between neighbouring gates; gate 1 is at 0. */
    fun withSpacings(spacingsMm: List<Int>): SetupDraft = edit {
        it.copy(gatePositionsMm = spacingsMm.runningFold(0) { position, gap -> position + gap })
    }

    companion object {
        fun forNew(): SetupDraft = SetupDraft(
            original = null,
            setup = PassageSetup(
                id = PassageId(Random.nextLong(1, 0x1_0000_0000)),
                name = "",
                version = 0,
                warningGapMm = Limits.DEFAULT_WARNING_GAP_MM,
                criticalGapMm = Limits.DEFAULT_CRITICAL_GAP_MM,
                gatePositionsMm = listOf(0),
                units = emptyList()
            )
        )

        fun forEdit(setup: PassageSetup): SetupDraft = SetupDraft(original = setup, setup = setup)
    }
}

/** What stops a step from going on, if anything. */
enum class SetupProblem {
    NoName,
    NameTooLong,
    NoUnits,
    EmptyGate,
    TooManyUnits,
    BadSpacing,
    BadThresholds
}

fun PassageSetup.problems(step: Step): List<SetupProblem> = when (step) {
    Step.Name -> listOfNotNull(
        SetupProblem.NoName.takeIf { name.isBlank() },
        SetupProblem.NameTooLong.takeIf { name.utf8Size() > Limits.PASSAGE_NAME_BYTES }
    )

    Step.Units -> listOfNotNull(
        SetupProblem.NoUnits.takeIf { units.isEmpty() },
        SetupProblem.EmptyGate.takeIf {
            (0 until gateCount).any { gate -> units.none { it.gate == gate } }
        },
        SetupProblem.TooManyUnits.takeIf { units.size > Limits.MAX_UNITS }
    )

    Step.Positions -> listOfNotNull(
        SetupProblem.BadSpacing.takeIf {
            gatePositionsMm.zipWithNext().any { (a, b) -> b <= a } ||
                gatePositionsMm.last() > Limits.MAX_MM
        }
    )

    Step.Thresholds -> listOfNotNull(
        SetupProblem.BadThresholds.takeIf {
            criticalGapMm >= warningGapMm || criticalGapMm < 0 || warningGapMm > Limits.MAX_MM
        }
    )

    Step.ZeroOffsets, Step.Pin -> emptyList()
}

/** Every step's problems, for saving a changed setup. */
fun PassageSetup.allProblems(): List<SetupProblem> = Step.entries.flatMap { problems(it) }
