package com.eter.rutacree.transit

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectionsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun step(type: String, modifier: String?, name: String, lon: Double, lat: Double) =
        """{"name":"$name","maneuver":{"type":"$type",${modifier?.let { "\"modifier\":\"$it\"," } ?: ""}"location":[$lon,$lat]}}"""

    // Shape of a real OSRM answer in Durango, trimmed to what the app reads.
    private val body = """
        {"code":"Ok","routes":[{"duration":258.8,"distance":2219,
          "geometry":{"type":"LineString","coordinates":[[-104.660,24.020],[-104.660,24.025],[-104.655,24.025],[-104.655,24.030],[-104.650,24.030]]},
          "legs":[{"steps":[
            ${step("depart", "right", "Blvd. Carrola Antuna", -104.660, 24.020)},
            ${step("turn", "right", "Calle Tlacotal", -104.660, 24.025)},
            ${step("new name", "straight", "Calle Porras", -104.6575, 24.025)},
            ${step("turn", "left", "Calle Negrete", -104.655, 24.025)},
            ${step("roundabout", "right", "", -104.655, 24.030)},
            ${step("arrive", "right", "Calle Ibarra", -104.650, 24.030)}
          ]}]}]}
    """.trimIndent()

    @Test
    fun keepsOnlyStepsThatNeedAnAction() {
        val directions = parseOsrmRoute(json, body)!!
        assertEquals(
            listOf(Maneuver.Right, Maneuver.Left, Maneuver.Roundabout, Maneuver.Arrive),
            directions.steps.map { it.maneuver },
        )
        assertEquals("Calle Tlacotal", directions.steps.first().street)
        assertEquals(258.8, directions.durationSeconds)
    }

    @Test
    fun stepsAreOrderedAlongThePath() {
        val directions = parseOsrmRoute(json, body)!!
        assertTrue(directions.stepAlong.zipWithNext().all { (a, b) -> a < b })
        assertEquals(directions.path.lengthMeters, directions.stepAlong.last(), 1.0)
    }

    @Test
    fun noRouteIsNull() {
        assertNull(parseOsrmRoute(json, """{"code":"NoRoute","routes":[]}"""))
    }

    @Test
    fun pointsAfterStartsWhereTheBusIs() {
        val g = RouteGeometry(listOf(GeoPoint(24.0, -104.0), GeoPoint(24.01, -104.0), GeoPoint(24.02, -104.0)))
        val rest = g.pointsAfter(g.lengthMeters * 0.75)
        assertEquals(2, rest.size)
        assertEquals(24.015, rest.first().lat, 1e-6)
        assertEquals(GeoPoint(24.02, -104.0), rest.last())
    }
}
