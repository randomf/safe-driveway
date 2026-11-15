package io.github.randomf.safedriveway.backend

import io.github.randomf.safedriveway.passage.UnitId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgressTest {
    private val a = UnitId("08ad0a5b9fb8")
    private val b = UnitId("08ad0a5b1234")
    private val c = UnitId("08ad0a5bc4f1")

    @Test
    fun aWriteFinishesOnceEveryUnitHasBeenTried() {
        val progress = WriteProgress(listOf(a, b, c).map { UnitWrite(it, WriteState.Waiting) })
            .with(a, WriteState.Done)
            .with(b, WriteState.Failed)
        assertFalse(progress.finished)
        val tried = progress.with(c, WriteState.OutOfReach)
        assertTrue(tried.finished)
        assertTrue(tried.failed)
        assertEquals(setOf(b, c), tried.missed)
    }

    @Test
    fun tryingAgainWaitsOnlyForTheUnitsThatFailed() {
        val progress = WriteProgress(
            listOf(
                UnitWrite(a, WriteState.Done),
                UnitWrite(b, WriteState.Failed),
                UnitWrite(c, WriteState.OutOfReach)
            )
        ).retried()
        assertEquals(
            listOf(WriteState.Done, WriteState.Waiting, WriteState.OutOfReach),
            progress.units.map { it.state }
        )
    }

    @Test
    fun aPinResetFinishesOnceEveryUnitHasTheNewPin() {
        val progress = PinResetProgress(listOf(a to PinResetState.WaitingForRestart))
        assertFalse(progress.finished)
        assertTrue(progress.with(a, PinResetState.Done).finished)
    }
}
