import AVFoundation
import AudioToolbox
import CoreLocation
import Observation
import Shared
import UIKit
import UserNotifications

/// GPS, permissions, voice, vibration and notifications: the iOS side of `DeviceServices` in Kotlin.
@MainActor
@Observable
final class PhoneServices: NSObject, CLLocationManagerDelegate {

    // MARK: Permissions

    private(set) var locationStatus: PermissionStatus = .notRequested
    private(set) var notificationStatus: PermissionStatus = .notRequested

    /// Same map the Kotlin code uses; iOS has no "display over other apps".
    var statuses: [AppPermission: PermissionStatus] {
        [.location: locationStatus, .notifications: notificationStatus, .overlay: .notApplicable]
    }

    /// The explanation on screen before a system prompt; see `ask`.
    private(set) var prompt: PermissionPrompt?

    struct PermissionPrompt: Identifiable {
        let id = UUID()
        let permission: AppPermission
        let need: PermissionNeed
        let opensSettings: Bool
        fileprivate let answer: (Bool) -> Void
    }

    // MARK: Location

    private(set) var location: CLLocation?
    /// Screens that currently want GPS fixes (radar, trip, driver shift...).
    private var locationClients = Set<String>()
    /// Screens that must keep receiving fixes with the screen locked (trip mode, driver shift).
    private var backgroundClients = Set<String>()

    private let manager = CLLocationManager()
    private let synthesizer = AVSpeechSynthesizer()
    private var alarmTask: Task<Void, Never>?
    private var locationAnswer: CheckedContinuation<Void, Never>?
    private var askTask: Task<Void, Never>?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.activityType = .otherNavigation
        refreshPermissions()
    }

    func refreshPermissions() {
        locationStatus = Self.status(manager.authorizationStatus)
        Task { await refreshNotificationStatus() }
    }

    private func refreshNotificationStatus() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral: notificationStatus = .granted
        case .notDetermined: notificationStatus = .notRequested
        default: notificationStatus = .blocked
        }
    }

    private nonisolated static func status(_ status: CLAuthorizationStatus) -> PermissionStatus {
        switch status {
        case .authorizedAlways, .authorizedWhenInUse: return .granted
        case .notDetermined: return .notRequested
        // iOS shows its prompt only once; afterwards only Settings can turn it on.
        default: return .blocked
        }
    }

    /**
     Asks, one at a time, for whatever `need` still lacks: first an explanation in the app, then the
     system prompt (or Settings when iOS no longer asks). `then` runs afterwards whatever the answers,
     so the feature goes on with what it was given. Called every time a feature needs permissions.
     */
    func ask(_ need: PermissionNeed, then action: @escaping () -> Void = {}) {
        guard askTask == nil else { return }
        askTask = Task {
            for permission in PermissionFlowKt.missingPermissions(need: need, statuses: self.statuses) {
                let next = PermissionFlowKt.permissionAction(status: self.statuses[permission])
                if next.name == "None" { continue }
                let opensSettings = next.name == "OpenSettings"
                let allow = await withCheckedContinuation { (answer: CheckedContinuation<Bool, Never>) in
                    self.prompt = PermissionPrompt(permission: permission, need: need, opensSettings: opensSettings) {
                        answer.resume(returning: $0)
                    }
                }
                self.prompt = nil
                guard allow else { continue }
                if opensSettings {
                    await self.openSettingsAndWait()
                } else {
                    await self.requestSystem(permission)
                }
            }
            self.askTask = nil
            action()
        }
    }

    /// Safe to call more than once (a button and the alert closing both answer): only the first counts.
    func answer(_ prompt: PermissionPrompt, allow: Bool) {
        guard self.prompt?.id == prompt.id else { return }
        self.prompt = nil
        prompt.answer(allow)
    }

    /// For the "review permissions" screen: the system prompt or, when iOS no longer asks, Settings.
    func request(_ permission: AppPermission) {
        Task {
            if PermissionFlowKt.permissionAction(status: self.statuses[permission]).name == "OpenSettings" {
                await self.openSettingsAndWait()
            } else {
                await self.requestSystem(permission)
            }
        }
    }

    private func requestSystem(_ permission: AppPermission) async {
        if permission == .location {
            guard manager.authorizationStatus == .notDetermined else { return }
            await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
                locationAnswer = done
                manager.requestWhenInUseAuthorization()
            }
        } else if permission == .notifications {
            _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
            await refreshNotificationStatus()
        }
    }

    private func openSettingsAndWait() async {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        guard await UIApplication.shared.open(url) else { return }
        // Back in the app: whatever changed in Settings is read again.
        for await _ in NotificationCenter.default.notifications(named: UIApplication.didBecomeActiveNotification) {
            break
        }
        locationStatus = Self.status(manager.authorizationStatus)
        updateTracking()
        await refreshNotificationStatus()
    }

    // MARK: CLLocationManagerDelegate (called on the main thread, where the manager was created)

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        MainActor.assumeIsolated { authorizationChanged(status) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let fix = locations.last, fix.horizontalAccuracy >= 0 else { return }
        MainActor.assumeIsolated { location = fix }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}

    private func authorizationChanged(_ status: CLAuthorizationStatus) {
        locationStatus = Self.status(status)
        updateTracking()
        if status != .notDetermined, let answer = locationAnswer {
            locationAnswer = nil
            answer.resume()
        }
    }

    /// `id` identifies the screen asking; updates stop once no screen needs them.
    func startLocationUpdates(for id: String, background: Bool = false) {
        locationClients.insert(id)
        if background { backgroundClients.insert(id) }
        updateTracking()
    }

    func stopLocationUpdates(for id: String) {
        locationClients.remove(id)
        backgroundClients.remove(id)
        updateTracking()
    }

    private func updateTracking() {
        guard locationStatus == .granted, !locationClients.isEmpty else {
            manager.stopUpdatingLocation()
            return
        }
        let background = !backgroundClients.isEmpty
        manager.allowsBackgroundLocationUpdates = background
        manager.showsBackgroundLocationIndicator = background
        manager.pausesLocationUpdatesAutomatically = !background
        manager.startUpdatingLocation()
    }

    /// Speed in km/h of the last fix (0 when unknown).
    var speedKmh: Double { max(location?.speed ?? 0, 0) * 3.6 }

    var geoPoint: GeoPoint? {
        location.map { GeoPoint(lat: $0.coordinate.latitude, lon: $0.coordinate.longitude) }
    }

    // MARK: Voice, vibration, alarm

    func speak(_ text: String) {
        synthesizer.stopSpeaking(at: .immediate)
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: L10n.languageTag ?? AVSpeechSynthesisVoice.currentLanguageCode())
        synthesizer.speak(utterance)
    }

    func vibrateLong() {
        UINotificationFeedbackGenerator().notificationOccurred(.warning)
        Task {
            for _ in 0..<3 {
                AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
                try? await Task.sleep(for: .milliseconds(700))
            }
        }
    }

    func startAlarm() {
        guard alarmTask == nil else { return }
        alarmTask = Task {
            while !Task.isCancelled {
                AudioServicesPlaySystemSound(1005)
                AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
                try? await Task.sleep(for: .milliseconds(1_200))
            }
        }
    }

    func stopAlarm() {
        alarmTask?.cancel()
        alarmTask = nil
    }

    func keepScreenOn(_ on: Bool) {
        UIApplication.shared.isIdleTimerDisabled = on
    }

    // MARK: Maps and notifications

    /// Hands the trip to Apple Maps; `walking` asks for a path on foot.
    func openNavigation(to point: GeoPoint, walking: Bool) {
        let flag = walking ? "w" : "d"
        guard let url = URL(string: "http://maps.apple.com/?daddr=\(point.lat),\(point.lon)&dirflg=\(flag)") else { return }
        UIApplication.shared.open(url)
    }

    /// Opens WhatsApp with the report written for dispatch (`phone` empty: WhatsApp asks for the chat).
    func openWhatsApp(phone: String, text: String) {
        guard let url = URL(string: DeviceServicesKt.whatsAppUrl(phone: phone, text: text)) else { return }
        UIApplication.shared.open(url)
    }

    /// Posted only while RutaCree is in the background: in the foreground the screen shows and speaks it.
    func notify(title: String, body: String) {
        guard UIApplication.shared.applicationState != .active, notificationStatus == .granted else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}
