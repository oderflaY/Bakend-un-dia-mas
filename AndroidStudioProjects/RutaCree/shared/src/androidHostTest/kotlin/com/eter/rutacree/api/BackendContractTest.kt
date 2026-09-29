package com.eter.rutacree.api

import com.eter.rutacree.transit.FeedUpdate
import com.eter.rutacree.transit.TraccarSocketFeed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Runs the app's API client against a live backend: `RUTACREE_API=http://localhost:8080 ./gradlew …`.
 * POSTs use inputs the server rejects during validation, so no rows are created.
 */
class BackendContractTest {
    private val baseUrl = System.getenv("RUTACREE_API").orEmpty()
    private val api by lazy { RutaCreeApi(baseUrl) }

    /** The login limit (5 in a burst per IP) is real protection; tests wait it out instead of failing. */
    private suspend fun <T> patiently(call: suspend () -> T): T {
        repeat(12) {
            try {
                return call()
            } catch (e: ApiException) {
                if (e.status != 429 || e.code == "locked") throw e
                kotlinx.coroutines.delay(10_000)
            }
        }
        return call()
    }

    private fun live(block: suspend () -> Unit) {
        assumeTrue("RUTACREE_API not set", baseUrl.isNotBlank())
        runBlocking { block() }
    }

    @Test
    fun routesParseWithTheAppParser() = live {
        val routes = api.routes()
        assertTrue(routes.isNotEmpty())
        assertTrue(routes.all { it.stops.isNotEmpty() && it.geometry.lengthMeters > 0 })
    }

    @Test
    fun publicSocketDeliversTheSnapshot() = live {
        val first = withTimeout(10_000) {
            TraccarSocketFeed(api.publicSocketUrl).updates.first { it !is FeedUpdate.Connection || !it.online }
        }
        assertTrue(first is FeedUpdate.Positions, "socket dropped: $first")
    }

    @Test
    fun rampRequestBodyIsAccepted() = live {
        val routes = api.routes()
        val otherRouteStop = routes[1].stops.first()
        // Right field names, wrong pairing: the server must complain about the stop, not the JSON.
        val e = assertFailsWith<ApiException> { api.requestRamp(routes[0].id, otherRouteStop.id) }
        assertEquals(400, e.status)
        assertTrue("parada" in e.message.orEmpty().lowercase(), e.message)
    }

    @Test
    fun wrongPasswordIsUnauthorized() = live {
        val e = assertFailsWith<ApiException> { patiently { api.login("nadie@rutacree.mx", "incorrecta-123") } }
        assertTrue(e.unauthorized, "status ${e.status}: ${e.message}")
    }

    @Test
    fun rampStatusOfUnknownIdIsNotFound() = live {
        val e = assertFailsWith<ApiException> { api.rampStatus(999_999_999) }
        assertEquals(404, e.status)
    }

    @Test
    fun riderAccountLifecycle() = live {
        val email = "contract-${(100000..999999).random()}@rutacree.mx"
        val session = patiently { api.register(email, "clave-de-prueba-1", "Prueba App") }
        try {
            assertEquals(email, session.rider.email)
            assertFailsWith<ApiException> { patiently { api.register(email, "clave-de-prueba-1", "Otra") } }
                .also { assertTrue(it.conflict) }
            assertFailsWith<ApiException> { patiently { api.login(email, "incorrecta") } }.also { assertTrue(it.unauthorized) }
            val login = (patiently { api.login(email, "clave-de-prueba-1") } as? LoginResult.Rider)?.session
                ?: error("a rider account must sign in as a rider")
            val updated = api.updateProfile(
                login.token,
                login.rider.copy(wheelchair = true, favorites = RiderFavorites(home = "Parada Juárez")),
            )
            assertTrue(updated.wheelchair)
            assertEquals("Parada Juárez", api.me(login.token).favorites.home)
        } finally {
            api.deleteAccount(session.token)
        }
        assertFailsWith<ApiException> { api.me(session.token) }.also { assertTrue(it.unauthorized) }
    }

    /** Needs a driver login: RUTACREE_TEST_DRIVER_EMAIL=… RUTACREE_TEST_DRIVER_PASSWORD=…. */
    @Test
    fun driverSignsInWithTheSameLoginAndStartsATraccarShift() = live {
        val email = System.getenv("RUTACREE_TEST_DRIVER_EMAIL").orEmpty()
        val password = System.getenv("RUTACREE_TEST_DRIVER_PASSWORD").orEmpty()
        assumeTrue("RUTACREE_TEST_DRIVER_EMAIL not set", email.isNotBlank() && password.isNotBlank())
        val account = (patiently { api.login(email, password) } as? LoginResult.Driver)?.account
            ?: error("a driver account must sign in as a driver")
        assertEquals(account.driver.id, api.driverMe(account.token).id)
        val session = api.startShift(account.token, "PRUEBA1", "ruta-2")
        try {
            assertTrue(session.usesTraccar, "positionSource=${session.positionSource}")
            assertEquals("PRUEBA1", session.trackerDeviceId)
            val status = api.trackerStatus(session)
            assertTrue(status.trackerConnected, "backend not connected to Traccar")
        } finally {
            api.endShift(session, "terminal")
        }
    }
}
