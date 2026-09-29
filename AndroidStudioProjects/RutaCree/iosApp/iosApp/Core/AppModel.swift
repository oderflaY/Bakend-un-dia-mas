import Foundation
import Observation
import Shared

/// Root state of the iOS app. Business logic lives in the shared Kotlin ViewModels; this class
/// only owns them and republishes their StateFlows for SwiftUI.
@MainActor
@Observable
final class AppModel {
    let config: AppConfig
    let services: PhoneServices

    private(set) var theme: ThemeMode
    private(set) var language: AppLanguage
    private(set) var session: AccountUiState
    private(set) var passenger: PassengerSession?
    private(set) var driver: DriverConsole?
    var reviewingPermissions = false

    @ObservationIgnored let account: AccountViewModel
    @ObservationIgnored let store: UserDefaultsStore
    @ObservationIgnored private let settings: AppSettings
    @ObservationIgnored private let accountHost: ViewModelHost
    @ObservationIgnored private var accountWatch: Watch?

    init() {
        let config = AppConfig(
            mapboxAccessToken: "",
            mapboxStyleUrl: "",
            mapboxDarkStyleUrl: "",
            apiBaseUrl: AppModel.infoValue("RCApiBaseURL")
        )
        let store = UserDefaultsStore()
        let settings = AppSettings(store: store)
        let host = ViewModelHost()
        let account = host.account(config: config, secure: KeychainSecureStore(), store: store)
        self.config = config
        self.services = PhoneServices()
        self.store = store
        self.settings = settings
        self.accountHost = host
        self.account = account
        theme = settings.theme
        language = settings.language
        L10n.languageTag = settings.language.tag
        session = account.state.value as! AccountUiState
        accountWatch = NativeBridgeKt.watch(flow: account.state) { [weak self] value in
            guard let state = value as? AccountUiState else { return }
            MainActor.assumeIsolated { self?.sessionChanged(state) }
        }
        sessionChanged(session)
    }

    /// Which side of the app is open; decided by the server at sign-in, never by a toggle.
    var role: UserRole? {
        switch session.kind.name {
        case "Driver": return session.driver == nil ? nil : UserRole.driver
        case "Guest", "Rider": return UserRole.passenger
        default: return nil
        }
    }

    private func sessionChanged(_ state: AccountUiState) {
        session = state
        if role == UserRole.driver {
            passenger?.close()
            passenger = nil
            // A new login (same or another driver) never reuses a console holding an old token.
            if let account = state.driver, driver?.token != account.token {
                driver?.close()
                driver = DriverConsole(app: self, account: account)
            }
        } else if role == UserRole.passenger {
            driver?.close()
            driver = nil
            if passenger == nil { passenger = PassengerSession(app: self) }
        } else {
            passenger?.close()
            passenger = nil
            driver?.close()
            driver = nil
            reviewingPermissions = false
        }
    }

    func updateTheme(_ value: ThemeMode) {
        settings.updateTheme(value: value)
        theme = value
    }

    func updateLanguage(_ value: AppLanguage) {
        settings.updateLanguage(value: value)
        L10n.languageTag = value.tag
        language = value
    }

    func signOut() {
        account.signOut()
    }

    func sessionExpired() {
        account.sessionExpired()
    }

    /// Values come from Secrets.xcconfig through Info.plist; empty when not configured.
    private static func infoValue(_ key: String) -> String {
        (Bundle.main.object(forInfoDictionaryKey: key) as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }
}

enum PassengerTab: Hashable {
    case radar, routes, ramp, trip, reports, profile
}

/// The rider side: one TransitViewModel for as long as a guest or rider is signed in.
@MainActor
@Observable
final class PassengerSession {
    let transit: TransitViewModel
    private(set) var state: TransitUiState
    var tab: PassengerTab = .radar

    @ObservationIgnored private unowned let app: AppModel
    @ObservationIgnored private let host: ViewModelHost
    @ObservationIgnored private var watches: [Watch] = []

    init(app: AppModel) {
        let host = ViewModelHost()
        let transit = host.transit(config: app.config)
        self.app = app
        self.host = host
        self.transit = transit
        state = transit.state.value as! TransitUiState
        watches = [
            NativeBridgeKt.watch(flow: transit.state) { [weak self] value in
                guard let state = value as? TransitUiState else { return }
                MainActor.assumeIsolated { self?.update(state) }
            },
            // Trip mode: long vibration plus a spoken alert, in the app's language.
            NativeBridgeKt.watch(flow: transit.tripAlerts) { [weak self] value in
                MainActor.assumeIsolated { self?.tripAlert(value) }
            },
        ]
    }

    private func update(_ new: TransitUiState) {
        let old = state
        state = new
        let services = app.services
        if let incident = new.incident, incident.id != old.incident?.id {
            let title = Format.incidentTitle(incident)
            services.vibrateLong()
            services.speak(title)
            services.notify(title: title, body: L10n.t("incident_hint"))
        }
        if new.ramp.flow == .acknowledged && old.ramp.flow != .acknowledged {
            services.notify(title: L10n.t("ramp_requested"), body: L10n.t("notif_ramp_ack"))
        }
    }

    private func tripAlert(_ value: Any?) {
        let text: String
        if let alert = value as? TripAlertPrepareToExit {
            text = L10n.t("tts_prepare_exit", alert.stop.name)
        } else if let alert = value as? TripAlertArrived {
            text = L10n.t("tts_arrived", alert.stop.name)
        } else {
            return
        }
        app.services.vibrateLong()
        app.services.speak(text)
        app.services.notify(title: L10n.t("trip_title"), body: text)
    }

    // MARK: Actions that need permissions: each asks for what is missing, then goes on.

    func requestRamp() {
        app.services.ask(.rampRequest) { [transit] in transit.requestRamp() }
    }

    func startTrip(destinationStopId: String) {
        app.services.ask(.tripAlerts) { [transit] in transit.startTrip(destinationStopId: destinationStopId) }
    }

    func close() {
        watches.forEach { $0.cancel() }
        watches = []
        host.clear()
    }
}

/// The driver console: one DriverViewModel per sign-in.
@MainActor
@Observable
final class DriverConsole {
    let viewModel: DriverViewModel
    let token: String
    let info: DriverInfo
    private(set) var state: DriverUiState
    var tab: DriverTab = .shift

    enum DriverTab: Hashable { case shift, reports, settings }

    @ObservationIgnored private unowned let app: AppModel
    @ObservationIgnored private let host: ViewModelHost
    @ObservationIgnored private var watch: Watch?

    init(app: AppModel, account: DriverAccount) {
        let host = ViewModelHost()
        let viewModel = host.driver(config: app.config, account: account, store: app.store) { [weak app] in
            MainActor.assumeIsolated { app?.sessionExpired() }
        }
        self.app = app
        self.host = host
        self.viewModel = viewModel
        token = account.token
        info = account.driver
        state = viewModel.state.value as! DriverUiState
        watch = NativeBridgeKt.watch(flow: viewModel.state) { [weak self] value in
            guard let state = value as? DriverUiState else { return }
            MainActor.assumeIsolated { self?.update(state) }
        }
    }

    var onShift: Bool { state.shift != nil }

    private func update(_ new: DriverUiState) {
        let old = state
        state = new
        let services = app.services

        if (old.shift != nil) != (new.shift != nil) {
            // The console must stay readable while driving, and keep reporting with the screen locked.
            services.keepScreenOn(new.shift != nil)
            if new.shift != nil {
                services.startLocationUpdates(for: "driver", background: true)
            } else {
                services.stopLocationUpdates(for: "driver")
            }
        }

        if let alert = new.rampAlert, alert.id != old.rampAlert?.id {
            let spoken = L10n.t("cd_ramp_alert", alert.stopName)
            services.startAlarm()
            services.vibrateLong()
            services.speak(spoken)
            services.notify(title: spoken, body: L10n.t("notif_ramp_body"))
        } else if new.rampAlert == nil && old.rampAlert != nil {
            services.stopAlarm()
        }
    }

    /// Location for the bus position, notifications for ramp alerts in the background.
    func startShift() {
        app.services.ask(.driverShift) { [viewModel] in viewModel.startShift() }
    }

    func close() {
        watch?.cancel()
        watch = nil
        app.services.stopAlarm()
        app.services.keepScreenOn(false)
        app.services.stopLocationUpdates(for: "driver")
        host.clear()
    }
}
