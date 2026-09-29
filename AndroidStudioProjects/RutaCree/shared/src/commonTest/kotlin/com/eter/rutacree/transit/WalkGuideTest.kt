package com.eter.rutacree.transit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalkGuideTest {
    private val stop = Stop("s1", "20 de Noviembre", GeoPoint(24.030, -104.650), accessible = true, alongMeters = 0.0)

    @Test
    fun compassFollowsTheBearing() {
        val origin = GeoPoint(24.0, -104.66)
        assertEquals(Compass.North, compassOf(bearingDegrees(origin, GeoPoint(24.01, -104.66))))
        assertEquals(Compass.East, compassOf(bearingDegrees(origin, GeoPoint(24.0, -104.65))))
        assertEquals(Compass.SouthWest, compassOf(bearingDegrees(origin, GeoPoint(23.99, -104.67))))
        assertEquals(Compass.North, compassOf(359.0))
    }

    @Test
    fun straightLineUntilTheStreetPathArrives() {
        val rider = GeoPoint(24.020, -104.650)
        val guide = walkGuide(rider, stop, directions = null)!!
        assertFalse(guide.byStreet)
        assertFalse(guide.arrived)
        assertEquals(Compass.North, guide.heading)
        assertEquals(listOf(rider, stop.point), guide.path)
        assertEquals(guide.remainingMeters / WALK_SPEED_MS, guide.remainingSeconds, 0.1)
    }

    @Test
    fun followsTheStreetPathAndItsNextTurn() {
        // East along one street, then north to the stop.
        val corner = GeoPoint(24.020, -104.645)
        val path = RouteGeometry(listOf(GeoPoint(24.020, -104.655), corner, GeoPoint(24.030, -104.645), stop.point))
        val directions = Directions(
            path,
            durationSeconds = 1_200.0,
            steps = listOf(
                DirectionStep(Maneuver.Left, "Calle Negrete", corner),
                DirectionStep(Maneuver.Arrive, "", stop.point),
            ),
        )
        val guide = walkGuide(GeoPoint(24.020, -104.652), stop, directions)!!
        assertTrue(guide.byStreet)
        assertEquals(Compass.East, guide.heading)
        assertEquals(Maneuver.Left, guide.nextStep?.maneuver)
        assertTrue(guide.nextStepMeters!! in 600.0..800.0)
        assertTrue(guide.remainingMeters < path.lengthMeters)
    }

    @Test
    fun leavingThePathFallsBackToAStraightLine() {
        val path = RouteGeometry(listOf(GeoPoint(24.020, -104.655), stop.point))
        val directions = Directions(path, 600.0, emptyList())
        val guide = walkGuide(GeoPoint(24.025, -104.640), stop, directions)!!
        assertFalse(guide.byStreet)
    }

    @Test
    fun atTheStopOrTooFarAway() {
        assertTrue(walkGuide(GeoPoint(24.0301, -104.6501), stop, null)!!.arrived)
        assertNull(walkGuide(GeoPoint(24.2, -104.65), stop, null))
    }
}
