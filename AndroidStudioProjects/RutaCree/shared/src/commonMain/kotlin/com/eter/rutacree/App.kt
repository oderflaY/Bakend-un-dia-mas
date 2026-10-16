package com.eter.rutacree

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eter.rutacree.account.AccountUiState
import com.eter.rutacree.account.SessionKind
import com.eter.rutacree.api.DriverAccount
import com.eter.rutacree.driver.DriverSettingsScreen
import com.eter.rutacree.settings.KeyValueStore
import com.eter.rutacree.account.AccountViewModel
import com.eter.rutacree.account.LoginScreen
import com.eter.rutacree.platform.rememberSecureStore
import com.eter.rutacree.driver.DriverReportsScreen
import com.eter.rutacree.driver.DriverShiftScreen
import com.eter.rutacree.driver.DriverViewModel
import com.eter.rutacree.driver.RampAlertOverlay
import com.eter.rutacree.platform.AppPermission
import com.eter.rutacree.platform.DeviceServices
import com.eter.rutacree.platform.LocalDeviceServices
import com.eter.rutacree.platform.PermissionAction
import com.eter.rutacree.platform.PermissionNeed
import com.eter.rutacree.platform.PermissionStatus
import com.eter.rutacree.platform.permissionAction
import com.eter.rutacree.screens.PermissionAsker
import com.eter.rutacree.screens.PermissionPromptDialog
import com.eter.rutacree.transit.IncidentType
import com.eter.rutacree.transit.RampFlow
import com.eter.rutacree.platform.rememberDeviceServices
import com.eter.rutacree.screens.AssistanceScreen
import com.eter.rutacree.screens.DashboardScreen
import com.eter.rutacree.screens.IncidentDialog
import com.eter.rutacree.screens.PermissionsScreen
import com.eter.rutacree.screens.ProfileScreen
import com.eter.rutacree.screens.ReportsScreen
import com.eter.rutacree.screens.RoutesScreen
import com.eter.rutacree.screens.SettingsSection
import com.eter.rutacree.screens.TripScreen
import com.eter.rutacree.screens.incidentTitle
import com.eter.rutacree.settings.AppSettings
import com.eter.rutacree.settings.ApplySystemTheme
import com.eter.rutacree.settings.LocalAppLocale
import com.eter.rutacree.settings.ThemeMode
import com.eter.rutacree.settings.UserRole
import com.eter.rutacree.settings.rememberKeyValueStore
import com.eter.rutacree.transit.AppConfig
import com.eter.rutacree.transit.TripAlert
import com.eter.rutacree.transit.TransitViewModel
import com.eter.rutacree.ui.AppHeader
import com.eter.rutacree.ui.AppIcons
import com.eter.rutacree.ui.DifColor
import com.eter.rutacree.ui.SectionColor
import com.eter.rutacree.ui.tint
import com.eter.rutacree.ui.DarkNeuPalette
import com.eter.rutacree.ui.LightNeuPalette
import com.eter.rutacree.ui.LocalNeuPalette
import com.eter.rutacree.ui.Neu
import com.eter.rutacree.ui.ScreenHeader
import com.eter.rutacree.ui.neuCard
import com.eter.rutacree.ui.neuInset
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import rutacree.shared.generated.resources.Res
import rutacree.shared.generated.resources.cd_ramp_alert
import rutacree.shared.generated.resources.incident_hint
import rutacree.shared.generated.resources.notif_ramp_ack
import rutacree.shared.generated.resources.notif_ramp_body
import rutacree.shared.generated.resources.ramp_requested
import rutacree.shared.generated.resources.trip_title
import rutacree.shared.generated.resources.driver_tab_reports
import rutacree.shared.generated.resources.driver_tab_shift
import rutacree.shared.generated.resources.tab_profile
import rutacree.shared.generated.resources.tab_radar
import rutacree.shared.generated.resources.tab_ramp
import rutacree.shared.generated.resources.tab_reports
import rutacree.shared.generated.resources.tab_routes
import rutacree.shared.generated.resources.tab_settings
import rutacree.shared.generated.resources.tab_trip
import rutacree.shared.generated.resources.tts_arrived
import rutacree.shared.generated.resources.tts_prepare_exit

// Cada pestaña con su color DIF, como las secciones del panel web.
enum class Tab(val label: StringResource, val icon: ImageVector, val color: DifColor) {
    Radar(Res.string.tab_radar, AppIcons.MapPin, DifColor.Azul),
    Rutas(Res.string.tab_routes, AppIcons.Navigate, DifColor.Amarillo),
    Rampa(Res.string.tab_ramp, AppIcons.Accessibility, DifColor.Magenta),
    Viaje(Res.string.tab_trip, AppIcons.Bus, DifColor.Turquesa),
    Reportes(Res.string.tab_reports, AppIcons.Alert, DifColor.Rojo),
    Perfil(Res.string.tab_profile, AppIcons.User, DifColor.Gris),
}

enum class DriverTab(val label: StringResource, val icon: ImageVector, val color: DifColor) {
    Turno(Res.string.driver_tab_shift, AppIcons.Bus, DifColor.Naranja),
    Reportes(Res.string.driver_tab_reports, AppIcons.Alert, DifColor.Rojo),
    Ajustes(Res.string.tab_settings, AppIcons.User, DifColor.Gris),
}

@Composable
@Preview
fun App(config: AppConfig = AppConfig()) {
    val store = rememberKeyValueStore()
    val settings = remember(store) { AppSettings(store) }
    val services = rememberDeviceServices()
    val secureStore = rememberSecureStore()
    val account = viewModel { AccountViewModel(config, secureStore, store) }
    val session by account.state.collectAsStateWithLifecycle()
    // Hoisted above the language key so switching language keeps the current screen.
    var tab by remember { mutableStateOf(Tab.Radar) }
    var driverTab by remember { mutableStateOf(DriverTab.Turno) }
    var reviewingPermissions by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val permissionAsker = remember(services) { PermissionAsker(services, scope) }

    CompositionLocalProvider(LocalAppLocale provides settings.language.tag) {
        // Rebuild the tree when the language changes so every string resource is read again.
        key(settings.language) {
            val darkTheme = when (settings.theme) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            ApplySystemTheme(settings.theme, darkTheme)
            MaterialTheme(colorScheme = if (darkTheme) DifDarkScheme else DifLightScheme) {
                CompositionLocalProvider(
                    LocalNeuPalette provides if (darkTheme) DarkNeuPalette else LightNeuPalette,
                    LocalDeviceServices provides services,
                ) {
                    Box(Modifier.fillMaxSize().background(Neu.Bg).safeDrawingPadding()) {
                        val permissions by services.permissions.collectAsStateWithLifecycle()
                        // The role comes from the account the server recognized, never from a user toggle.
                        val role = when (session.kind) {
                            SessionKind.None -> null
                            SessionKind.Driver -> UserRole.Driver
                            SessionKind.Guest, SessionKind.Rider -> UserRole.Passenger
                        }
                        val driverAccount = session.driver
                        when {
                            role == null -> LoginScreen(
                                state = session,
                                onSignIn = account::login,
                                onRegister = account::register,
                                onClearError = account::clearError,
                                onContinueAsGuest = account::continueAsGuest,
                                onDemoDriver = account::continueAsDemoDriver,
                            )
                            // Permissions are asked when a feature needs them; this screen is only a review.
                            reviewingPermissions -> PermissionsScreen(
                                role = role,
                                statuses = permissions,
                                onRequest = { permission ->
                                    when (permissionAction(permissions[permission])) {
                                        PermissionAction.AskSystem -> services.requestPermission(permission)
                                        PermissionAction.OpenSettings -> services.openAppSettings()
                                        PermissionAction.None -> Unit
                                    }
                                },
                                onContinue = { reviewingPermissions = false },
                            )
                            role == UserRole.Driver && driverAccount != null -> DriverApp(
                                config, settings, services, permissionAsker, store, driverAccount, driverTab, { driverTab = it },
                                onSignOut = {
                                    driverTab = DriverTab.Turno
                                    account.signOut()
                                },
                                onSessionExpired = account::sessionExpired,
                                onReviewPermissions = { reviewingPermissions = true },
                            )
                            else -> PassengerApp(
                                config, settings, services, permissionAsker, tab, { tab = it },
                                account = session,
                                accountActions = account,
                                onReviewPermissions = { reviewingPermissions = true },
                            )
                        }
                        permissionAsker.prompt?.let { PermissionPromptDialog(it, permissionAsker::answer) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PassengerApp(
    config: AppConfig,
    settings: AppSettings,
    services: DeviceServices,
    permissions: PermissionAsker,
    tab: Tab,
    onTabChange: (Tab) -> Unit,
    account: AccountUiState,
    accountActions: AccountViewModel,
    onReviewPermissions: () -> Unit,
) {
    val transit = viewModel { TransitViewModel(config) }
    val state by transit.state.collectAsStateWithLifecycle()

    // A municipio picked in an earlier session wins over the GPS guess.
    LaunchedEffect(transit) { settings.municipality?.let { transit.selectMunicipality(it.ifEmpty { null }) } }
    val onSelectMunicipality: (String?) -> Unit = {
        settings.updateMunicipality(it)
        transit.selectMunicipality(it)
    }

    TrackDeviceLocation(services) { transit.updateUserLocation(it?.point) }

    // Screens that locate the rider ask for location every time they open without it.
    LaunchedEffect(tab) {
        if (tab == Tab.Radar || tab == Tab.Rampa || tab == Tab.Viaje) permissions.ask(PermissionNeed.RiderLocation)
    }

    // Trip Mode: long vibration plus spoken alert, in the app's language.
    LaunchedEffect(transit) {
        transit.tripAlerts.collect { alert ->
            val text = when (alert) {
                is TripAlert.PrepareToExit -> getString(Res.string.tts_prepare_exit, alert.stop.name)
                is TripAlert.Arrived -> getString(Res.string.tts_arrived, alert.stop.name)
            }
            services.vibrateLong()
            services.speak(text, settings.language.tag)
            services.notify(getString(Res.string.trip_title), text, urgent = true)
        }
    }

    // Trip mode warns before the stop also with the screen off.
    val tripActive = state.trip != null
    DisposableEffect(tripActive) {
        services.keepTrackingInBackground(tripActive)
        onDispose { services.keepTrackingInBackground(false) }
    }

    val rampAcknowledged = state.ramp.flow == RampFlow.Acknowledged
    if (rampAcknowledged) {
        LaunchedEffect(Unit) {
            services.notify(getString(Res.string.ramp_requested), getString(Res.string.notif_ramp_ack))
        }
    }

    val incident = state.incident
    if (incident != null) {
        val title = incidentTitle(incident)
        LaunchedEffect(incident.id) {
            services.vibrateLong()
            services.speak(title, settings.language.tag)
            services.notify(title, getString(Res.string.incident_hint), urgent = true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        SectionColor(tab.color) { AppHeader(stringResource(tab.label), tab.icon) }
        Crossfade(tab, Modifier.weight(1f).fillMaxWidth()) { current ->
          SectionColor(current.color) {
            when (current) {
                Tab.Radar -> DashboardScreen(
                    config = config,
                    state = state,
                    onSelectRoute = transit::selectRoute,
                    onSelectStop = transit::selectStop,
                    onOpenProfile = { onTabChange(Tab.Perfil) },
                    onSelectMunicipality = onSelectMunicipality,
                )
                Tab.Rutas -> RoutesScreen(
                    config = config,
                    state = state,
                    onShowOnRadar = { routeId, stopId ->
                        transit.selectRoute(routeId)
                        stopId?.let(transit::selectStop)
                        onTabChange(Tab.Radar)
                    },
                    onSelectMunicipality = onSelectMunicipality,
                )
                Tab.Rampa -> AssistanceScreen(
                    state,
                    onRequestRamp = { permissions.ask(PermissionNeed.RampRequest, transit::requestRamp) },
                    onCancel = transit::cancelRamp,
                )
                Tab.Viaje -> TripScreen(
                    state,
                    onStart = { stopId -> permissions.ask(PermissionNeed.TripAlerts) { transit.startTrip(stopId) } },
                    onEnd = transit::endTrip,
                )
                Tab.Reportes -> ReportsScreen(state, onSend = transit::sendReport, onEdit = transit::resetReport)
                Tab.Perfil -> ProfileScreen(
                    settings = settings,
                    account = account,
                    onEdit = accountActions::editProfile,
                    onSave = accountActions::saveProfile,
                    onOpenLogin = accountActions::openLogin,
                    onSignOut = {
                        onTabChange(Tab.Radar)
                        accountActions.signOut()
                    },
                    onDeleteAccount = accountActions::deleteAccount,
                    onReviewPermissions = onReviewPermissions,
                )
            }
          }
        }
        BottomBar(Tab.entries, tab, { it.label }, { it.icon }, { it.color }, onTabChange)
    }

    if (incident != null) {
        IncidentDialog(
            incident = incident,
            onAlternatives = {
                transit.showAlternativeRoutes()
                onTabChange(Tab.Radar)
            },
            onDismiss = transit::dismissIncident,
        )
    }
}

@Composable
private fun DriverApp(
    config: AppConfig,
    settings: AppSettings,
    services: DeviceServices,
    permissions: PermissionAsker,
    store: KeyValueStore,
    account: DriverAccount,
    tab: DriverTab,
    onTabChange: (DriverTab) -> Unit,
    onSignOut: () -> Unit,
    onSessionExpired: () -> Unit,
    onReviewPermissions: () -> Unit,
) {
    // Keyed by login: signing in again (same or another driver) never reuses a console holding an old token.
    val driver = viewModel(key = "driver-${account.driver.id}-${account.token.hashCode()}") {
        DriverViewModel(config, account, store, onSessionExpired)
    }
    val state by driver.state.collectAsStateWithLifecycle()
    val onShift = state.shift != null

    // Ramp alerts must be seen while driving; released as soon as the shift ends.
    DisposableEffect(onShift) {
        services.keepScreenOn(onShift)
        onDispose { services.keepScreenOn(false) }
    }
    // Demo: the phone's GPS is the bus position while the route is open, also with the screen off.
    if (onShift && config.driverPhoneTracking) {
        TrackDeviceLocation(services, driver::onPhoneLocation)
        DisposableEffect(Unit) {
            services.keepTrackingInBackground(true)
            onDispose { services.keepTrackingInBackground(false) }
        }
    }

    // Every report (start, end, failures) opens WhatsApp once, ready to send to dispatch.
    val whatsApp = state.whatsApp
    LaunchedEffect(whatsApp?.id) {
        if (whatsApp != null) {
            services.openWhatsApp(whatsApp.phone, whatsApp.text)
            driver.whatsAppOpened()
        }
    }

    val rampAlert = state.rampAlert
    if (rampAlert != null) {
        val spoken = stringResource(Res.string.cd_ramp_alert, rampAlert.stopName)
        DisposableEffect(rampAlert.id) {
            services.startAlarm()
            services.vibrateLong()
            onDispose { services.stopAlarm() }
        }
        LaunchedEffect(rampAlert.id) {
            services.speak(spoken, settings.language.tag)
            services.notify(spoken, getString(Res.string.notif_ramp_body), urgent = true)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SectionColor(tab.color) { AppHeader(stringResource(tab.label), tab.icon) }
            Crossfade(tab, Modifier.weight(1f).fillMaxWidth()) { current ->
              SectionColor(current.color) {
                when (current) {
                    DriverTab.Turno -> DriverShiftScreen(
                        state = state,
                        reportsToServer = config.isLive,
                        onBusNumber = driver::updateBusNumber,
                        onSelectRoute = driver::selectRoute,
                        onPassengers = driver::changePassengers,
                        onStart = { permissions.ask(PermissionNeed.DriverShift, driver::startShift) },
                        onResendWhatsApp = driver::resendWhatsApp,
                        municipality = settings.municipality?.ifEmpty { null },
                        onSelectMunicipality = settings::updateMunicipality,
                    )
                    DriverTab.Reportes -> DriverReportsScreen(
                        state = state,
                        onFailure = {
                            driver.reportFailure(it)
                            // A ramp failure keeps the route going: stay here to see it was reported.
                            if (it != IncidentType.RampFailure) onTabChange(DriverTab.Turno)
                        },
                        onEndShift = {
                            driver.endShift(it)
                            onTabChange(DriverTab.Turno)
                        },
                    )
                    DriverTab.Ajustes -> DriverSettingsScreen(
                        driver = account.driver,
                        onShift = onShift,
                        settings = settings,
                        onSignOut = onSignOut,
                        onReviewPermissions = onReviewPermissions,
                    )
                }
              }
            }
            BottomBar(DriverTab.entries, tab, { it.label }, { it.icon }, { it.color }, onTabChange)
        }
        if (rampAlert != null) RampAlertOverlay(rampAlert, onAcknowledge = driver::acknowledgeRamp)
    }
}

/** Streams GPS fixes while location permission is granted. */
@Composable
private fun TrackDeviceLocation(services: DeviceServices, onFix: (com.eter.rutacree.platform.DeviceLocation?) -> Unit) {
    val permissions by services.permissions.collectAsStateWithLifecycle()
    val granted = permissions[AppPermission.Location] == PermissionStatus.Granted
    DisposableEffect(granted) {
        if (granted) services.startLocationUpdates()
        onDispose { services.stopLocationUpdates() }
    }
    // Not lifecycle-bound: with the screen off, trip alerts and the bus position still need every fix.
    val latestOnFix by rememberUpdatedState(onFix)
    LaunchedEffect(services) { services.location.collect { latestOnFix(it) } }
}

/** Barra de pestañas como el menú del panel: cada ícono en un recuadro de su color; la activa, rellena. */
@Composable
private fun <T> BottomBar(
    tabs: List<T>,
    current: T,
    label: (T) -> StringResource,
    icon: (T) -> ImageVector,
    color: (T) -> DifColor,
    onSelect: (T) -> Unit,
) {
    val itemShape = RoundedCornerShape(16.dp)
    val chipShape = RoundedCornerShape(11.dp)
    val dark = Neu.palette.isDark
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .neuCard(RoundedCornerShape(24.dp), 6.dp)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tabs.forEach { tab ->
            val selected = tab == current
            val a = color(tab).accent(dark)
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 64.dp)
                    .clip(itemShape)
                    // Role.Tab + selected lets TalkBack/VoiceOver announce "Radar, tab, selected, 1 of 5".
                    .selectable(selected, role = Role.Tab) { onSelect(tab) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 44.dp, height = 32.dp)
                        .clip(chipShape)
                        .background(if (selected) a.fill else tint(a.fill, if (dark) 0.2f else 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon(tab), null, tint = if (selected) a.onFill else a.ink, modifier = Modifier.size(20.dp))
                }
                Text(
                    stringResource(label(tab)),
                    fontSize = 11.sp,
                    maxLines = 1,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) a.ink else Neu.TextMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

// Material (diálogos, campos de texto, menús) con los mismos colores DIF que la paleta neumórfica.
private val DifLightScheme = lightColorScheme(
    primary = Color(0xFF2F5DA8), onPrimary = Color.White, secondary = Color(0xFFD91178), onSecondary = Color.White,
    background = Color(0xFFE6ECF5), surface = Color(0xFFE6ECF5), onBackground = Color(0xFF2C3E50), onSurface = Color(0xFF2C3E50),
    error = Color(0xFFE31E31),
)
private val DifDarkScheme = darkColorScheme(
    primary = Color(0xFF7FA6EE), onPrimary = Color(0xFF0D1B33), secondary = Color(0xFFF56AAE), onSecondary = Color(0xFF2A0716),
    background = Color(0xFF161C27), surface = Color(0xFF1B2230), surfaceVariant = Color(0xFF222A38),
    onBackground = Color(0xFFE8EDF5), onSurface = Color(0xFFE8EDF5), onSurfaceVariant = Color(0xFF98A4B8),
    outline = Color(0xFF354157), error = Color(0xFFFF6F86),
)
