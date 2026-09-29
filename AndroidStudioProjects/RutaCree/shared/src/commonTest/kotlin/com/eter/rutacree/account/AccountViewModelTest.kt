package com.eter.rutacree.account

import com.eter.rutacree.FakeKeyValueStore
import com.eter.rutacree.api.RiderFavorites
import com.eter.rutacree.transit.AppConfig
import com.eter.rutacree.transit.SendState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Simulation mode (no server): the paths a rider or driver takes without an account. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {
    private val store = FakeKeyValueStore()
    private val demo = AppConfig()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun withoutServerTheLoginOffersDemoEntries() {
        val account = AccountViewModel(demo, store, store)
        assertFalse(account.state.value.available)
        assertEquals(SessionKind.None, account.state.value.kind)
        // Signing in needs a server: nothing happens.
        account.login("rider@example.com", "secret123")
        assertEquals(SessionKind.None, account.state.value.kind)
        assertFalse(account.state.value.busy)
    }

    @Test
    fun guestSessionIsRestoredOnTheNextLaunch() {
        AccountViewModel(demo, store, store).continueAsGuest()
        val reopened = AccountViewModel(demo, store, store)
        assertEquals(SessionKind.Guest, reopened.state.value.kind)
        assertTrue(reopened.state.value.isRiderSide)
    }

    @Test
    fun demoDriverOpensTheConsoleAndIsRestored() {
        val account = AccountViewModel(demo, store, store)
        account.continueAsDemoDriver()
        assertEquals(SessionKind.Driver, account.state.value.kind)
        assertEquals("DEMO", account.state.value.driver?.driver?.id)
        assertEquals(SessionKind.Driver, AccountViewModel(demo, store, store).state.value.kind)
    }

    @Test
    fun demoDriverIsNotAvailableWithAServer() {
        val live = AppConfig(apiBaseUrl = "http://127.0.0.1:9")
        val account = AccountViewModel(live, store, store)
        account.continueAsDemoDriver()
        assertEquals(SessionKind.None, account.state.value.kind)
    }

    @Test
    fun guestProfileIsKeptOnThePhone() {
        val account = AccountViewModel(demo, store, store)
        account.continueAsGuest()
        account.editProfile { it.copy(name = "Ana", lowVision = true, favorites = RiderFavorites(home = "Centro")) }
        assertEquals(SendState.Idle, account.state.value.save)
        account.saveProfile()
        assertEquals(SendState.Sent, account.state.value.save)

        val profile = AccountViewModel(demo, store, store).state.value.profile
        assertEquals("Ana", profile.name)
        assertTrue(profile.lowVision)
        assertEquals("Centro", profile.favorites.home)
    }

    @Test
    fun signingOutLeavesNothingOfThePreviousPerson() {
        val account = AccountViewModel(demo, store, store)
        account.continueAsGuest()
        account.editProfile { it.copy(name = "Ana") }
        account.saveProfile()
        account.signOut()

        assertEquals(SessionKind.None, account.state.value.kind)
        assertNull(account.state.value.error)
        assertTrue(store.values.isEmpty(), "left behind: ${store.values.keys}")
        assertEquals("", AccountViewModel(demo, store, store).state.value.profile.name)
    }

    @Test
    fun expiredSessionExplainsWhy() {
        val account = AccountViewModel(demo, store, store)
        account.continueAsGuest()
        account.sessionExpired()
        assertEquals(SessionKind.None, account.state.value.kind)
        assertEquals(AccountError.SessionExpired, account.state.value.error)
        account.clearError()
        assertNull(account.state.value.error)
    }

    @Test
    fun openLoginKeepsTheGuestProfile() {
        val account = AccountViewModel(demo, store, store)
        account.continueAsGuest()
        account.editProfile { it.copy(name = "Luis") }
        account.saveProfile()
        account.openLogin()
        assertEquals(SessionKind.None, account.state.value.kind)
        assertEquals("Luis", account.state.value.profile.name)
    }

    @Test
    fun registrationChecksNameAndPasswordBeforeCallingTheServer() {
        val account = AccountViewModel(AppConfig(apiBaseUrl = "http://127.0.0.1:9"), store, store)
        account.register(" ", "a@b.mx", "longenough")
        assertEquals(AccountError.MissingName, account.state.value.error)
        account.register("Ana", "a@b.mx", "short")
        assertEquals(AccountError.WeakPassword, account.state.value.error)
        assertFalse(account.state.value.busy)
    }
}
