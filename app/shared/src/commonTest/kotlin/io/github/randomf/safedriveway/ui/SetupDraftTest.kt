package io.github.randomf.safedriveway.ui

import io.github.randomf.safedriveway.demo.DemoWorld
import io.github.randomf.safedriveway.passage.Limits
import io.github.randomf.safedriveway.passage.Side
import io.github.randomf.safedriveway.passage.UnitId
import io.github.randomf.safedriveway.ui.setup.SetupDraft
import io.github.randomf.safedriveway.ui.setup.SetupProblem
import io.github.randomf.safedriveway.ui.setup.Step
import io.github.randomf.safedriveway.ui.setup.problems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetupDraftTest {
    private val new = UnitId("08ad0a5bc4f1")

    @Test
    fun theWizardAsksForGatePositionsOnlyWithMoreThanOneGate() {
        val draft = SetupDraft.forNew()
        assertEquals(Step.ZeroOffsets, draft.next(Step.Units))
        assertEquals(Step.Positions, draft.addGate().next(Step.Units))
    }

    @Test
    fun gatePositionsComeFromTheSpacingBetweenGates() {
        val draft = SetupDraft.forNew().addGate().addGate().withSpacings(listOf(1200, 2500))
        assertEquals(listOf(0, 1200, 3700), draft.setup.gatePositionsMm)
        assertEquals(listOf(1200, 2500), draft.spacingsMm)
    }

    @Test
    fun aPassageHasAtMostFourGates() {
        val draft = (1..6).fold(SetupDraft.forNew()) { draft, _ -> draft.addGate() }
        assertEquals(4, draft.setup.gateCount)
    }

    @Test
    fun anyEmptyGateCanBeRemoved() {
        val threeGates = SetupDraft.forNew().addGate().addGate().withSpacings(listOf(1000, 2000))
            .assign(new, gate = 2, side = Side.Left)
        val withoutMiddle = threeGates.removeGate(1)
        assertEquals(listOf(0, 3000), withoutMiddle.setup.gatePositionsMm)
        assertEquals(1, withoutMiddle.setup.unit(new)?.gate)
        // Removing the first gate keeps the next one at 0.
        assertEquals(listOf(0, 2000), threeGates.removeGate(0).setup.gatePositionsMm)
        // A gate with a unit, or the only gate, stays.
        assertEquals(threeGates, threeGates.removeGate(2))
        assertEquals(1, SetupDraft.forNew().removeGate(0).setup.gateCount)
    }

    @Test
    fun takingOffAUnitThePassageHadForgetsIt() {
        val draft = SetupDraft.forEdit(DemoWorld.garage).remove(DemoWorld.pillar)
        assertEquals(setOf(DemoWorld.pillar), draft.forgotten)
        assertTrue(draft.changed)
        // Putting it back on a gate keeps it.
        assertEquals(emptySet(), draft.assign(DemoWorld.pillar, 1, Side.Left).forgotten)
    }

    @Test
    fun takingOffANewUnitForgetsNothing() {
        val draft = SetupDraft.forEdit(DemoWorld.garage).addGate().assign(new, 2, Side.Right)
        assertEquals(emptySet(), draft.remove(new).forgotten)
    }

    @Test
    fun replacingForgetsTheOldUnit() {
        val draft = SetupDraft.forEdit(DemoWorld.garage).replace(DemoWorld.bins, new)
        assertEquals(setOf(DemoWorld.bins), draft.forgotten)
        assertEquals(1, draft.setup.unit(new)?.gate)
    }

    @Test
    fun anUnchangedDraftWritesNothingNew() {
        val draft = SetupDraft.forEdit(DemoWorld.garage)
        assertFalse(draft.changed)
        assertEquals(DemoWorld.garage.version + 1, draft.toWrite().version)
    }

    @Test
    fun theLastGateIsAtMost65mFromTheFirst() {
        val far = SetupDraft.forNew().addGate().withSpacings(listOf(Limits.MAX_MM + 1))
        assertTrue(SetupProblem.BadSpacing in far.setup.problems(Step.Positions))
        val near = SetupDraft.forNew().addGate().withSpacings(listOf(Limits.MAX_MM))
        assertTrue(near.setup.problems(Step.Positions).isEmpty())
    }
}
