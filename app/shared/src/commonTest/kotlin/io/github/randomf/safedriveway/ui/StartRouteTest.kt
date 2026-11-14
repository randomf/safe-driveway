package io.github.randomf.safedriveway.ui

import io.github.randomf.safedriveway.backend.Nearby
import io.github.randomf.safedriveway.backend.NearbyPassage
import io.github.randomf.safedriveway.passage.PassageId
import io.github.randomf.safedriveway.ui.start.StartRoute
import io.github.randomf.safedriveway.ui.start.startRoute
import kotlin.test.Test
import kotlin.test.assertEquals

class StartRouteTest {
    private val garage = NearbyPassage(PassageId(1), "Garage", inUse = false)
    private val gate = NearbyPassage(PassageId(2), "Front gate", inUse = false)

    @Test
    fun onePassageInRangeOpensAtOnce() {
        assertEquals(StartRoute.Open(garage.id), startRoute(Nearby(true, listOf(garage))) { null })
    }

    @Test
    fun severalPassagesAskWhichOne() {
        assertEquals(StartRoute.Choose, startRoute(Nearby(true, listOf(garage, gate))) { null })
    }

    @Test
    fun severalPassagesOpenTheOneChosenBefore() {
        assertEquals(
            StartRoute.Open(gate.id),
            startRoute(Nearby(true, listOf(garage, gate))) { gate.id }
        )
    }

    @Test
    fun noPassageInRangeGoesToTheList() {
        assertEquals(StartRoute.NoneInRange, startRoute(Nearby(true)) { null })
    }
}
