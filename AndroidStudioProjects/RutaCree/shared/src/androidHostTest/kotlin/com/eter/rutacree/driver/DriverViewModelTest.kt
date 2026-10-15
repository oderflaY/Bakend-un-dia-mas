package com.eter.rutacree.driver

import com.eter.rutacree.FakeKeyValueStore
import com.eter.rutacree.api.DriverAccount
import com.eter.rutacree.api.DriverInfo
import com.eter.rutacree.transit.AppConfig
import com.eter.rutacree.api.Passengers
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

    /** 07:30 when the shift starts; tests move it forward. */
    private var minute = 7 * 60 + 30

    private fun viewModel(): DriverViewModel {
        val vm = DriverViewModel(AppConfig(), account, store, onSessionExpired = {}, loadRoutes = { bundled }, clock = { minute })
        scope.runCurrent()
        return vm
    }

    @Test
    fun busNumberKeepsOnlyLettersDigitsAndHyphens() {
        val vm = viewModel()
        vm.updateBusNumber("25-1451 ab!cdefghijklmn")
        assertEquals("25-1451abcdefghi", vm.state.value.busNumber)
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
    fun passengerCountsStayBetweenZeroAndTheLimit() {
        val vm = viewModel()
        vm.changePassengers(PassengerKind.Men, -1)
        repeat(3) { vm.changePassengers(PassengerKind.Men, 1) }
        vm.changePassengers(PassengerKind.Women, 1)
        repeat(DriverViewModel.MAX_PASSENGERS + 5) { vm.changePassengers(PassengerKind.Elderly, 1) }
        assertEquals(Passengers(men = 3, women = 1, elderly = DriverViewModel.MAX_PASSENGERS), vm.state.value.passengers)
    }

    @Test
    fun startingTheRouteRecordsTheTimeAndPassengersAndOpensWhatsApp() {
        val vm = viewModel()
        vm.updateBusNumber("245")
        repeat(4) { vm.changePassengers(PassengerKind.Men, 1) }
        vm.changePassengers(PassengerKind.Women, 1)
        repeat(2) { vm.changePassengers(PassengerKind.Disability, 1) }
        vm.changePassengers(PassengerKind.Companions, 1)
        vm.startShift()

        val shift = assertNotNull(vm.state.value.shift)
        assertEquals(7 * 60 + 30, shift.startMinute)
        assertEquals(Passengers(men = 4, women = 1, disability = 2, companions = 1), shift.passengers)
        val message = assertNotNull(vm.state.value.whatsApp)
        assertTrue("Inicio de ruta" in message.text)
        assertTrue("07:30" in message.text)
        assertTrue("Usuarios: 5 (4 hombres, 1 mujeres)" in message.text, message.text)
        assertTrue("Discapacidad: 2 · Adultos mayores: 0 · Embarazadas: 0 · Acompañantes: 1" in message.text, message.text)

        // Opened once; it stays available to open again.
        vm.whatsAppOpened()
        assertNull(vm.state.value.whatsApp)
        vm.resendWhatsApp()
        val again = assertNotNull(vm.state.value.whatsApp)
        assertEquals(message.text, again.text)
        assertTrue(again.id != message.id, "a reopened message must be a new event")
    }

    @Test
    fun endingTheRouteReportsStartEndAndDurationAndResetsTheCount() {
        val vm = viewModel()
        vm.updateBusNumber("245")
        vm.changePassengers(PassengerKind.Women, 1)
        vm.startShift()
        minute = 9 * 60 + 5
        vm.endShift(FinalLocation.Depot)

        val text = assertNotNull(vm.state.value.whatsApp).text
        assertTrue("Fin de ruta" in text)
        assertTrue("Inicio: 07:30 · Fin: 09:05 (1 h 35 min)" in text, text)
        assertTrue("encierro" in text)
        assertEquals(Passengers(), vm.state.value.passengers)
    }

    @Test
    fun aRampFailureIsReportedOnceAndTheRouteGoesOn() {
        val vm = viewModel()
        vm.updateBusNumber("245")
        vm.startShift()
        vm.whatsAppOpened()

        vm.reportFailure(IncidentType.RampFailure)
        val shift = assertNotNull(vm.state.value.shift, "a broken ramp doesn't end the route")
        assertTrue(shift.rampFailureReported)
        assertTrue("Falla de la rampa" in assertNotNull(vm.state.value.whatsApp).text)

        vm.whatsAppOpened()
        vm.reportFailure(IncidentType.RampFailure)
        assertNull(vm.state.value.whatsApp, "reported twice")
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
        vm.reportFailure(IncidentType.Breakdown)
        assertNull(vm.state.value.shift)
        assertEquals(ShiftEnd.Emergency, vm.state.value.lastShiftEnd)
        assertTrue("Falla del camión" in assertNotNull(vm.state.value.whatsApp).text)
    }

    @Test
    fun simulatedRiderAsksForTheRampAndTheDriverAcknowledges() {
        val vm = viewModel()
        vm.updateBusNumber("245")
        vm.startShift()
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
