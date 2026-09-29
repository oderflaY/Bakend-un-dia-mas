package com.eter.rutacree

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eter.rutacree.account.AccountViewModel
import com.eter.rutacree.api.DriverAccount
import com.eter.rutacree.api.RiderProfile
import com.eter.rutacree.driver.DriverViewModel
import com.eter.rutacree.platform.SecureStore
import com.eter.rutacree.settings.KeyValueStore
import com.eter.rutacree.transit.AppConfig
import com.eter.rutacree.transit.TransitViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/*
 * Entry points for the native SwiftUI app (iosApp). Swift cannot collect Kotlin flows or own a
 * ViewModel's lifecycle by itself, so these wrap both in plain callbacks and objects.
 */

/** A running [watch]; call [cancel] when the Swift side no longer needs updates. */
class Watch internal constructor(private val scope: CoroutineScope) {
    fun cancel() = scope.cancel()
}

/** Delivers every value of [flow] on the main thread, starting with the current one for a StateFlow. */
fun watch(flow: Flow<Any?>, onEach: (Any?) -> Unit): Watch {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    scope.launch { flow.collect { onEach(it) } }
    return Watch(scope)
}

/**
 * Owns ViewModels created from Swift. [clear] cancels their coroutines (sockets, polling, GPS
 * reports), just like leaving a screen does on Android.
 */
class ViewModelHost {
    private val store = ViewModelStore()

    fun account(config: AppConfig, secure: SecureStore, store: KeyValueStore): AccountViewModel =
        provide("account") { AccountViewModel(config, secure, store) }

    fun transit(config: AppConfig): TransitViewModel = provide("transit") { TransitViewModel(config) }

    fun driver(
        config: AppConfig,
        account: DriverAccount,
        store: KeyValueStore,
        onSessionExpired: () -> Unit,
    ): DriverViewModel = provide("driver") { DriverViewModel(config, account, store, onSessionExpired) }

    fun clear() = store.clear()

    private inline fun <reified T : ViewModel> provide(key: String, crossinline create: () -> T): T =
        ViewModelProvider.create(store, viewModelFactory { initializer { create() } }).get(key, T::class)
}

/** Swift cannot use Kotlin's named `copy` arguments; these change one field of the profile. */
object ProfileEdits {
    fun name(profile: RiderProfile, value: String) = profile.copy(name = value.take(120))
    fun userType(profile: RiderProfile, value: String) = profile.copy(userType = value)
    fun wheelchair(profile: RiderProfile, value: Boolean) = profile.copy(wheelchair = value)
    fun lowVision(profile: RiderProfile, value: Boolean) = profile.copy(lowVision = value)
    fun reducedMobility(profile: RiderProfile, value: Boolean) = profile.copy(reducedMobility = value)
    fun home(profile: RiderProfile, value: String) = profile.copy(favorites = profile.favorites.copy(home = value.take(120)))
    fun center(profile: RiderProfile, value: String) =
        profile.copy(favorites = profile.favorites.copy(center = value.take(120)))
    fun therapy(profile: RiderProfile, value: String) =
        profile.copy(favorites = profile.favorites.copy(therapy = value.take(120)))
}

