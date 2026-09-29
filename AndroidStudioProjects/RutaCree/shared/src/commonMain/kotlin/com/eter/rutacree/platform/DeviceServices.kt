package com.eter.rutacree.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.eter.rutacree.transit.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AppPermission { Location, Notifications, Overlay }

/**
 * [Denied]: refused, but the system can still ask again. [Blocked]: only the phone's settings can
 * turn it on (iOS after any refusal, Android after "don't ask again").
 */
enum class PermissionStatus { Granted, Denied, Blocked, NotRequested, NotApplicable }

data class DeviceLocation(val point: GeoPoint, val speedKmh: Double)

/** Hardware and OS features the screens need; implemented per platform. */
interface DeviceServices {
    val location: StateFlow<DeviceLocation?>
    val permissions: StateFlow<Map<AppPermission, PermissionStatus>>

    fun startLocationUpdates()
    fun stopLocationUpdates()

    /**
     * Keeps GPS fixes coming with the screen off (trip mode, driver shift). Android shows an
     * ongoing notification for it; iOS uses its background location mode.
     */
    fun keepTrackingInBackground(on: Boolean)
    /** Shows the system prompt (or its settings page); [onResult] runs once the person answered. */
    fun requestPermission(permission: AppPermission, onResult: () -> Unit = {})
    fun refreshPermissions()

    /** Opens this app's page in the system settings; [onReturn] runs when the person comes back. */
    fun openAppSettings(onReturn: () -> Unit = {})

    /**
     * System notification, posted only while the app is in the background (in the foreground the
     * screen already shows and speaks the alert). [urgent] also brings the app to the front where allowed.
     */
    fun notify(title: String, body: String, urgent: Boolean = false)

    /** Text-to-speech in the app's current language ([languageTag] null = device language). */
    fun speak(text: String, languageTag: String?)
    fun vibrateLong()
    fun startAlarm()
    fun stopAlarm()
    fun keepScreenOn(on: Boolean)

    /** Hands the trip to the phone's maps app; [walking] asks for a path on foot instead of by car. */
    fun openNavigation(destination: GeoPoint, walking: Boolean = false)
}

@Composable
expect fun rememberDeviceServices(): DeviceServices

/** Local wall-clock time as minutes after midnight. */
expect fun currentMinuteOfDay(): Int

val LocalDeviceServices = staticCompositionLocalOf<DeviceServices> { NoOpDeviceServices }

/** Used by previews and as a safe default. */
object NoOpDeviceServices : DeviceServices {
    override val location = MutableStateFlow<DeviceLocation?>(null)
    override val permissions = MutableStateFlow(AppPermission.entries.associateWith { PermissionStatus.NotApplicable })
    override fun startLocationUpdates() = Unit
    override fun stopLocationUpdates() = Unit
    override fun keepTrackingInBackground(on: Boolean) = Unit
    override fun requestPermission(permission: AppPermission, onResult: () -> Unit) = onResult()
    override fun refreshPermissions() = Unit
    override fun openAppSettings(onReturn: () -> Unit) = onReturn()
    override fun notify(title: String, body: String, urgent: Boolean) = Unit
    override fun speak(text: String, languageTag: String?) = Unit
    override fun vibrateLong() = Unit
    override fun startAlarm() = Unit
    override fun stopAlarm() = Unit
    override fun keepScreenOn(on: Boolean) = Unit
    override fun openNavigation(destination: GeoPoint, walking: Boolean) = Unit
}
