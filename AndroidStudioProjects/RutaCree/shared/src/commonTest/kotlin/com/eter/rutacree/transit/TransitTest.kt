package com.eter.rutacree.transit

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransitTest {

    // An L-shaped route: ~1 km east, then ~1 km north.
    private val routeJson = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","properties":{"id":"r","name":"Ruta R","color":"#5FC79E"},
           "geometry":{"type":"LineString","coordinates":[[-104.6700,24.0250],[-104.6602,24.0250],[-104.6602,24.0340]]}},
          {"type":"Feature","properties":{"name":"Fin","accessible":true},
           "geometry":{"type":"Point","coordinates":[-104.6602,24.0340]}},
          {"type":"Feature","properties":{"name":"Inicio","accessible":true},
           "geometry":{"type":"Point","coordinates":[-104.6700,24.0250]}},
          {"type":"Feature","properties":{"name":"Esquina","accessible":false},
           "geometry":{"type":"Point","coordinates":[-104.6602,24.0250]}}
        ]}
    """.trimIndent()

    private val route = parseRouteGeoJson(routeJson)

    private fun bus(id: Long, at: GeoPoint, ramp: Boolean = true, speed: Double = 18.0) =
        route.place(BusPosition(id, "U$id", "r", at, speed, ramp), speed)!!

    @Test
    fun parsesRouteAndSortsStopsAlongTheLine() {
        assertEquals("r", route.id)
        assertEquals(listOf("Inicio", "Esquina", "Fin"), route.stops.map { it.name })
        assertTrue(abs(route.geometry.lengthMeters - 2_000) < 50, "length was ${route.geometry.lengthMeters}")
    }

    @Test
    fun projectsOffsetPointOntoTheRoute() {
        val nearCorner = GeoPoint(24.0252, -104.6650)
        val projection = route.geometry.project(nearCorner)
        assertTrue(projection.offsetMeters < 30)
        assertTrue(abs(projection.alongMeters - 500) < 20, "along was ${projection.alongMeters}")
    }

    @Test
    fun pointAtFollowsTheCornerInsteadOfCuttingIt() {
        val corner = route.geometry.pointAt(route.stops[1].alongMeters)
        assertTrue(distanceMeters(corner, GeoPoint(24.0250, -104.6602)) < 5)
    }

    @Test
    fun rejectsBusesFarFromTheRoute() {
        val offRoute = BusPosition(9, "U9", "r", GeoPoint(24.0300, -104.6700), 18.0, true)
        assertNull(route.place(offRoute, 18.0))
    }

    @Test
    fun nextArrivalMeasuresAlongRouteAndAddsDwellForStopsBetween() {
        val target = route.stops.last()
        val arrival = assertNotNull(route.nextArrival(target, listOf(bus(1, GeoPoint(24.0250, -104.6700)))))
        assertTrue(abs(arrival.distanceMeters - 2_000) < 50)
        // 2 km at 18 km/h = 400 s, plus 20 s for the corner stop.
        assertTrue(abs(arrival.etaSeconds - 420) < 15, "eta was ${arrival.etaSeconds}")
    }

    @Test
    fun ignoresBusesWithoutRampOrAlreadyPastTheStop() {
        val target = route.stops[1]
        val broken = bus(1, GeoPoint(24.0250, -104.6690), ramp = false)
        val passed = bus(2, GeoPoint(24.0300, -104.6602))
        assertNull(route.nextArrival(target, listOf(broken, passed)))

        val coming = bus(3, GeoPoint(24.0250, -104.6680))
        assertEquals(3, route.nextArrival(target, listOf(broken, passed, coming))?.bus?.position?.deviceId)
    }

    @Test
    fun slowOrStoppedBusesUseAMinimumSpeed() {
        val stopped = bus(1, GeoPoint(24.0250, -104.6700), speed = 0.0)
        val arrival = assertNotNull(route.nextArrival(route.stops[1], listOf(stopped)))
        // 1 km at the 12 km/h floor = 300 s.
        assertTrue(abs(arrival.etaSeconds - 300) < 15, "eta was ${arrival.etaSeconds}")
    }

    @Test
    fun geofenceIsInsideOnlyWithin150Meters() {
        val stops = route.stops.filter { it.accessible }
        val near = assertNotNull(nearestStop(GeoPoint(24.0251, -104.6695), stops))
        assertEquals("Inicio", near.stop.name)
        assertTrue(near.inside)

        val far = assertNotNull(nearestStop(GeoPoint(24.0250, -104.6650), stops))
        assertTrue(!far.inside, "distance was ${far.distanceMeters}")
    }

    @Test
    fun tripAlertsWhenWithin500MetersAndOnArrival() {
        val boarding = route.stops.first()
        val destination = route.stops.last()
        val far = route.tripProgress(boarding, destination, GeoPoint(24.0250, -104.6690))
        assertEquals(TripStage.Riding, far.stage)
        assertEquals(listOf("Inicio", "Esquina", "Fin"), (far.passedStops + far.remainingStops).map { it.name })

        val near = route.tripProgress(boarding, destination, GeoPoint(24.0300, -104.6602))
        assertEquals(TripStage.PrepareToExit, near.stage)
        assertEquals(listOf("Fin"), near.remainingStops.map { it.name })

        val there = route.tripProgress(boarding, destination, GeoPoint(24.0339, -104.6602))
        assertEquals(TripStage.Arrived, there.stage)
    }

    @Test
    fun nextDeparturesWrapAroundMidnight() {
        val stop = route.stops.first().copy(schedule = listOf(5 * 60 + 30, 12 * 60, 22 * 60))
        assertEquals(listOf(12 * 60, 22 * 60), stop.nextDepartures(11 * 60, count = 2))
        assertEquals(listOf(22 * 60, 5 * 60 + 30), stop.nextDepartures(21 * 60 + 59, count = 2))
        assertEquals("05:30", formatClock(5 * 60 + 30))
    }

    @Test
    fun minutesUntilCountsIntoTomorrow() {
        assertEquals(0, minutesUntil(12 * 60, 12 * 60))
        assertEquals(12, minutesUntil(12 * 60 + 12, 12 * 60))
        assertEquals(6 * 60 + 90, minutesUntil(5 * 60 + 30, 22 * 60))
    }

    @Test
    fun routeWithoutReturnIsAllOutboundAndShownEverywhere() {
        assertNull(route.returnStartIndex)
        assertEquals(route.geometry.points, route.outbound)
        assertTrue(route.inbound.isEmpty())
        assertTrue(route.servesMunicipality("Lerdo"))
    }

    @Test
    fun serverSplitsOutboundAndReturnAndTagsMunicipios() {
        val loop = parseRouteGeoJson(
            routeJson.replace(
                "\"color\":\"#5FC79E\"",
                "\"color\":\"#5FC79E\",\"municipios\":[\"Gómez Palacio\",\"Lerdo\"],\"regreso_desde\":1",
            )
        )
        assertEquals(listOf("Gómez Palacio", "Lerdo"), loop.municipalities)
        assertEquals(2, loop.outbound.size)
        assertEquals(loop.outbound.last(), loop.inbound.first())
        assertTrue(loop.servesMunicipality("Lerdo"))
        assertTrue(!loop.servesMunicipality("Durango"))
        assertTrue(loop.servesMunicipality(null))
        // A rider in Gómez Palacio gets their own municipio; one in Durango city the nearest served one.
        assertEquals("Gómez Palacio", municipalityAt(GeoPoint(25.5605, -103.4975), listOf(loop)))
        assertEquals("Lerdo", municipalityAt(GeoPoint(24.0277, -104.6532), listOf(loop)))
    }
}
