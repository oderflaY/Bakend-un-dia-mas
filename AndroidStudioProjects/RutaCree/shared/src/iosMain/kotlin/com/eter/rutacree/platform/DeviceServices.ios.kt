package com.eter.rutacree.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.eter.rutacree.transit.GeoPoint
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.AudioToolbox.kSystemSoundID_Vibrate
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSDate
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIApplicationState
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject

actual fun currentMinuteOfDay(): Int {
    val components = NSCalendar.currentCalendar.components(NSCalendarUnitHour or NSCalendarUnitMinute, NSDate())
    return (components.hour * 60 + components.minute).toInt()
}

@Composable
actual fun rememberDeviceServices(): DeviceServices {
    val services = remember { IosDeviceServices() }
    DisposableEffect(services) { onDispose { services.release() } }
    return services
}

private class IosDeviceServices : DeviceServices {
    private val scope = MainScope()
    private val manager = CLLocationManager()
    private val synthesizer = AVSpeechSynthesizer()
    private var alarmJob: Job? = null
    private var notificationStatus = PermissionStatus.NotRequested

    override val location = MutableStateFlow<DeviceLocation?>(null)
    private val _permissions = MutableStateFlow(readPermissions())
    override val permissions: StateFlow<Map<AppPermission, PermissionStatus>> = _permissions

    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        @OptIn(ExperimentalForeignApi::class)
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val fix = didUpdateLocations.lastOrNull() as? CLLocation ?: return
            fix.coordinate.useContents {
                location.value = DeviceLocation(GeoPoint(latitude, longitude), maxOf(fix.speed, 0.0) * 3.6)
            }
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            refreshPermissions()
            if (tracking) startLocationUpdates()
            if (manager.authorizationStatus != kCLAuthorizationStatusNotDetermined) {
                pendingLocation?.invoke()
                pendingLocation = null
            }
        }
    }
    private var tracking = false
    private var pendingLocation: (() -> Unit)? = null

    init {
        manager.delegate = delegate
        manager.desiredAccuracy = kCLLocationAccuracyBest
        refreshPermissions()
    }

    override fun requestPermission(permission: AppPermission, onResult: () -> Unit) {
        when (permission) {
            AppPermission.Location -> {
                if (manager.authorizationStatus != kCLAuthorizationStatusNotDetermined) return onResult()
                pendingLocation = onResult
                manager.requestWhenInUseAuthorization()
            }
            AppPermission.Notifications -> UNUserNotificationCenter.currentNotificationCenter()
                .requestAuthorizationWithOptions(
                    UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
                ) { _, _ ->
                    scope.launch(Dispatchers.Main) {
                        refreshPermissions()
                        onResult()
                    }
                }
            AppPermission.Overlay -> onResult()
        }
    }

    override fun openAppSettings(onReturn: () -> Unit) {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return onReturn()
        UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any?>()) { _ -> onReturn() }
    }

    override fun notify(title: String, body: String, urgent: Boolean) {
        if (UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive) return
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(body)
            setSound(UNNotificationSound.defaultSound)
        }
        val request = UNNotificationRequest.requestWithIdentifier(title, content, null)
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
    }

    override fun refreshPermissions() {
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus
            notificationStatus = when (status) {
                UNAuthorizationStatusAuthorized, UNAuthorizationStatusProvisional -> PermissionStatus.Granted
                UNAuthorizationStatusNotDetermined -> PermissionStatus.NotRequested
                else -> PermissionStatus.Blocked
            }
            scope.launch(Dispatchers.Main) { _permissions.value = readPermissions() }
        }
        _permissions.value = readPermissions()
    }

    private fun readPermissions(): Map<AppPermission, PermissionStatus> {
        val locationStatus = when (manager.authorizationStatus) {
            kCLAuthorizationStatusAuthorizedWhenInUse, kCLAuthorizationStatusAuthorizedAlways -> PermissionStatus.Granted
            kCLAuthorizationStatusNotDetermined -> PermissionStatus.NotRequested
            // iOS shows its prompt only once: after that, only Settings can grant it.
            else -> PermissionStatus.Blocked
        }
        return mapOf(
            AppPermission.Location to locationStatus,
            AppPermission.Notifications to notificationStatus,
            // iOS has no "draw over other apps"; the driver alert lives inside the app.
            AppPermission.Overlay to PermissionStatus.NotApplicable,
        )
    }

    override fun startLocationUpdates() {
        tracking = true
        if (_permissions.value[AppPermission.Location] == PermissionStatus.Granted) manager.startUpdatingLocation()
    }

    override fun keepTrackingInBackground(on: Boolean) {
        manager.allowsBackgroundLocationUpdates = on
        manager.showsBackgroundLocationIndicator = on
    }

    override fun stopLocationUpdates() {
        tracking = false
        manager.stopUpdatingLocation()
    }

    override fun speak(text: String, languageTag: String?) {
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        val utterance = AVSpeechUtterance.speechUtteranceWithString(text)
        utterance.voice = AVSpeechSynthesisVoice.voiceWithLanguage(languageTag)
        synthesizer.speakUtterance(utterance)
    }

    override fun vibrateLong() {
        UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeWarning)
        scope.launch {
            repeat(3) {
                AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
                delay(700)
            }
        }
    }

    override fun startAlarm() {
        if (alarmJob?.isActive == true) return
        alarmJob = scope.launch {
            while (isActive) {
                AudioServicesPlaySystemSound(ALARM_SOUND_ID)
                AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
                delay(1_200)
            }
        }
    }

    override fun stopAlarm() {
        alarmJob?.cancel()
        alarmJob = null
    }

    override fun keepScreenOn(on: Boolean) {
        UIApplication.sharedApplication.idleTimerDisabled = on
    }

    override fun openNavigation(destination: GeoPoint, walking: Boolean) {
        val flag = if (walking) "w" else "d"
        val url = NSURL.URLWithString("http://maps.apple.com/?daddr=${destination.lat},${destination.lon}&dirflg=$flag") ?: return
        UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any?>(), null)
    }

    fun release() {
        pendingLocation = null
        stopLocationUpdates()
        stopAlarm()
        scope.cancel()
    }

    private companion object {
        /** Built-in "alarm" system sound. */
        const val ALARM_SOUND_ID = 1005u
    }
}
