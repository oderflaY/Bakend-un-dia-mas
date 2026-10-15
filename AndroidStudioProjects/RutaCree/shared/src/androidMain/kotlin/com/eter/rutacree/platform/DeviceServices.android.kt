package com.eter.rutacree.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.eter.rutacree.transit.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar
import java.util.Locale

actual fun currentMinuteOfDay(): Int {
    val now = Calendar.getInstance()
    return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
}

@Composable
actual fun rememberDeviceServices(): DeviceServices {
    val context = LocalContext.current
    val services = remember(context) { AndroidDeviceServices(context) }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        services.onPermissionResult()
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        services.onPermissionResult()
    }
    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        services.onPermissionResult()
    }
    services.launchLocation = {
        locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    services.launchNotifications = {
        if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    services.launchOverlay = {
        overlayLauncher.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        )
    }

    // Permissions can change in system settings while the app is in the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, services) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> services.onResume()
                Lifecycle.Event.ON_PAUSE -> services.foreground = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            services.release()
        }
    }
    return services
}

private class AndroidDeviceServices(private val context: Context) : DeviceServices {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("rutacree_permissions", Context.MODE_PRIVATE)

    var launchLocation: () -> Unit = {}
    var launchNotifications: () -> Unit = {}
    var launchOverlay: () -> Unit = {}
    var foreground = true

    /** Waiting for the answer to a system prompt, or for the person to come back from settings. */
    private var pendingResult: (() -> Unit)? = null
    private var pendingSettings: (() -> Unit)? = null

    override val location = MutableStateFlow<DeviceLocation?>(null)
    private val _permissions = MutableStateFlow(readPermissions())
    override val permissions: StateFlow<Map<AppPermission, PermissionStatus>> = _permissions

    private val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val listener = LocationListener { update(it) }
    private var tracking = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: Pair<String, String?>? = null
    private var ringtone: Ringtone? = null

    override fun requestPermission(permission: AppPermission, onResult: () -> Unit) {
        // Notifications before Android 13 need no prompt.
        if (permission == AppPermission.Notifications && Build.VERSION.SDK_INT < 33) return onResult()
        pendingResult?.invoke()
        pendingResult = onResult
        prefs.edit { putBoolean(permission.name, true) }
        when (permission) {
            AppPermission.Location -> launchLocation()
            AppPermission.Notifications -> launchNotifications()
            AppPermission.Overlay -> launchOverlay()
        }
    }

    fun onPermissionResult() {
        refreshPermissions()
        if (tracking) startLocationUpdates()
        pendingResult?.let {
            pendingResult = null
            it()
        }
    }

    override fun openAppSettings(onReturn: () -> Unit) {
        pendingSettings?.invoke()
        pendingSettings = onReturn
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            pendingSettings = null
            onReturn()
        }
    }

    fun onResume() {
        foreground = true
        refreshPermissions()
        if (tracking) startLocationUpdates()
        pendingSettings?.let {
            pendingSettings = null
            it()
        }
    }

    override fun notify(title: String, body: String, urgent: Boolean) {
        if (foreground) return
        val manager = NotificationManagerCompat.from(app)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(ALERTS_CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(app.applicationInfo.loadLabel(app.packageManager).toString())
                    .setVibrationEnabled(true)
                    .build()
            )
        }
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val open = launch?.let {
            PendingIntent.getActivity(app, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        if (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)) {
            val notification = NotificationCompat.Builder(app, ALERTS_CHANNEL)
                .setSmallIcon(app.applicationInfo.icon)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(if (urgent) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            try {
                manager.notify(title.hashCode(), notification)
            } catch (_: SecurityException) {
            }
        }
        // "Display over other apps" is what allows an app in the background to come to the front.
        if (urgent && launch != null && Settings.canDrawOverlays(app)) {
            try {
                app.startActivity(launch)
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    override fun refreshPermissions() {
        _permissions.value = readPermissions()
    }

    private fun readPermissions(): Map<AppPermission, PermissionStatus> = AppPermission.entries.associateWith { p ->
        val granted = when (p) {
            AppPermission.Location -> hasLocationPermission()
            AppPermission.Notifications -> Build.VERSION.SDK_INT < 33 ||
                granted(Manifest.permission.POST_NOTIFICATIONS)
            AppPermission.Overlay -> Settings.canDrawOverlays(app)
        }
        when {
            granted -> PermissionStatus.Granted
            !prefs.getBoolean(p.name, false) -> PermissionStatus.NotRequested
            // Overlay is always granted on its settings page; the others can be asked again until
            // the person picks "don't ask again", which leaves only the app settings.
            p == AppPermission.Overlay || canAskAgain(p) -> PermissionStatus.Denied
            else -> PermissionStatus.Blocked
        }
    }

    private fun canAskAgain(p: AppPermission): Boolean {
        val activity = context.findActivity() ?: return true
        return when (p) {
            AppPermission.Location -> activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
            AppPermission.Notifications -> Build.VERSION.SDK_INT < 33 ||
                activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            AppPermission.Overlay -> true
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    @SuppressLint("MissingPermission")
    override fun startLocationUpdates() {
        tracking = true
        if (!hasLocationPermission()) return
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { locationManager.isProviderEnabled(it) }
            .forEach { provider ->
                locationManager.getLastKnownLocation(provider)?.let { if (location.value == null) update(it) }
                locationManager.requestLocationUpdates(provider, 3_000L, 5f, listener, Looper.getMainLooper())
            }
    }

    override fun keepTrackingInBackground(on: Boolean) {
        if (on && !hasLocationPermission()) return
        val intent = Intent(app, TrackingService::class.java)
        try {
            if (on) ContextCompat.startForegroundService(app, intent) else app.stopService(intent)
        } catch (_: RuntimeException) {
            // Not allowed right now (e.g. started from the background): fixes keep coming while the app is open.
        }
    }

    override fun stopLocationUpdates() {
        tracking = false
        locationManager.removeUpdates(listener)
    }

    private fun update(fix: Location) {
        location.value = DeviceLocation(GeoPoint(fix.latitude, fix.longitude), fix.speed * 3.6)
    }

    override fun speak(text: String, languageTag: String?) {
        val engine = tts
        if (engine == null || !ttsReady) {
            pendingSpeech = text to languageTag
            if (engine == null) {
                tts = TextToSpeech(app) { status ->
                    ttsReady = status == TextToSpeech.SUCCESS
                    pendingSpeech?.let { (t, l) -> if (ttsReady) speak(t, l) }
                    pendingSpeech = null
                }
            }
            return
        }
        engine.language = languageTag?.let(Locale::forLanguageTag) ?: Locale.getDefault()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rutacree-${text.hashCode()}")
    }

    override fun vibrateLong() {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 900, 250, 900, 250, 1_400)
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    override fun startAlarm() {
        if (ringtone?.isPlaying == true) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ringtone = RingtoneManager.getRingtone(app, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= 28) isLooping = true
            play()
        }
    }

    override fun stopAlarm() {
        ringtone?.stop()
        ringtone = null
    }

    override fun keepScreenOn(on: Boolean) {
        val window = context.findActivity()?.window ?: return
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun openNavigation(destination: GeoPoint, walking: Boolean) {
        val (lat, lon) = destination
        val navigate = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon&mode=${if (walking) "w" else "d"}"))
            .setPackage("com.google.android.apps.maps")
        // Without Google Maps, any app that opens map links (or the browser) gets the same trip.
        val fallback = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon&travelmode=${if (walking) "walking" else "driving"}"),
        )
        for (intent in listOf(navigate, fallback)) {
            try {
                if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    override fun openWhatsApp(phone: String, text: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(whatsAppUrl(phone, text)))
        if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        }
    }

    fun release() {
        keepTrackingInBackground(false)
        pendingResult = null
        pendingSettings = null
        stopLocationUpdates()
        stopAlarm()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}

private const val ALERTS_CHANNEL = "alerts"

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
