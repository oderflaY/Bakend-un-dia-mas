package com.eter.rutacree.driver

import com.eter.rutacree.FakeKeyValueStore
import com.eter.rutacree.api.DriverAccount
import com.eter.rutacree.api.DriverInfo
import com.eter.rutacree.transit.AppConfig
import com.eter.rutacree.transit.GeoPoint
import com.eter.rutacree.transit.IncidentType
import com.eter.rutacree.transit.parseRouteGeoJson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Driver console in simulation mode (no server), with the bundled routes. */
@OptIn(ExperimentalCoroutinesApi::class)
class DriverViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val store = FakeKeyValueStore()
    private val account = DriverAccount(token = "", driver = DriverInfo(id = "D1", name = "Rosa"))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** The route files bundled with the app, read from the source tree (Compose Resources needs a device). */
    private val bundled = listOf("ruta-1", "ruta-2").map {
        parseRouteGeoJson(File("src/commonMain/composeResources/files/routes/$it.geojson").readText())
    }

    private fun viewModel(): DriverViewModel {
        val vm = DriverViewModel(AppConfig(), account, store, onSessionExpired = {}, loadRoutes = { bundled })
        scope.runCurrent()
        return vm
    }

    @Test
    fun busNumberKeepsOnlyLettersAndDigits() {
        val vm = viewModel()
        vm.updateBusNumber("24-5 ab!cdefgh")
        assertEquals("245abcde", vm.state.value.busNumber)
    }

    @Test
    fun bundledRoutesLoadAndARouteIsPreselected() {
        val state = viewModel().state.value
        assertTrue(state.routes.isNotEmpty(), "bundled routes did not load")
        assertNotNull(state.selectedRouteId)
        assertEquals("Rosa", state.driverName)
    }

    @Test
    fun shiftStartsOnlyWithBusAndRoute() {
        val vm = viewModel()
        assertFalse(vm.state.value.canStart)
        vm.startShift()
        assertNull(vm.state.value.shift)

        vm.updateBusNumber("245")
        assertTrue(vm.state.value.canStart)
        vm.startShift()
        val shift = assertNotNull(vm.state.value.shift)
        assertEquals("245", shift.busNumber)
        assertEquals("Rosa", shift.driverName)
    }

    @Test
    fun lastBusAndRouteArePrefilledNextTime() {
        val vm = viewModel()
        val route = vm.state.value.routes.last().id
        vm.updateBusNumber("310")
        vm.selectRoute(route)
        vm.startShift()
        vm.endShift(FinalLocation.Depot)

        val next = viewModel().state.value
        assertEquals("310", next.busNumber)
        assertEquals(route, next.selectedRouteId)
    }

    @Test
    fun gpsFixPlacesTheBusAndListsTheNextStops() {
        val vm = viewModel()
        vm.updateBusNumber("245")
        vm.startShift()
        val route = vm.state.value.shift!!.route
        val start = route.stops.first().point
        vm.updateLocation(start, 20.0)

        val state = vm.state.value
        assertEquals(start, state.position)
        assertNull(state.approach, "a bus at its first stop is on the route")
        assertTrue(state.nextStops.isNotEmpty())
        assertTrue(state.nextStops.size <= 3)
        assertFalse(route.stops.first() in state.nextStops)
    }

    @Test
    fun farFromTheRouteTheDriverIsGuidedBackToIt() {
        val vm = viewModel()
        vm.updateBusNumber("245")
        vm.startShift()
        val far = vm.state.value.shift!!.route.stops.first().point.let { GeoPoint(it.lat + 0.02, it.lon) }
        vm.updateLocation(far, 0.0)

        val approach = assertNotNull(vm.state.value.approach)
        assertFalse(approach.byStreet, "no street path until the router answers")
        assertTrue(approach.remainingMeters > 1_000)
        assertEquals(listOf(far, approach.target), approach.path)
    }

    @Test
    fun endingOrAnEmergencyClosesTheShift() {
        val vm = viewModel()
        vm.updateBusNumber("245")
        vm.startShift()
        vm.endShift(FinalLocation.Terminal)
        assertNull(vm.state.value.shift)
        assertEquals(ShiftEnd.Completed, vm.state.value.lastShiftEnd)

        vm.startShift()
        assertNull(vm.state.value.lastShiftEnd)
        vm.reportEmergency(IncidentType.Breakdown)
        assertNull(vm.state.value.shift)
        assertEquals(ShiftEnd.Emergency, vm.state.value.lastShiftEnd)
    }

    @Test
    fun simulatedRiderAsksForTheRampAndTheDriverAcknowledges() {
        val vm = viewModel()
        vm.updateBusNumber("245")
        vm.startShift()
        vm.updateLocation(vm.state.value.shift!!.route.stops.first().point, 0.0)
        // The demo sends one ramp request 20 s into the shift.
        scope.testScheduler.advanceTimeBy(21_000)
        scope.runCurrent()
        val alert = assertNotNull(vm.state.value.rampAlert)
        assertTrue(alert.stopName.isNotBlank())

        vm.acknowledgeRamp()
        assertNull(vm.state.value.rampAlert)
        vm.endShift(FinalLocation.Terminal)
        scope.advanceUntilIdle()
    }

}
