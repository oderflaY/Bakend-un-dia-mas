import Shared
import SwiftUI

/// Login, then the passenger tabs or the driver console, in the chosen theme and language.
struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var systemScheme
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        let dark = isDark
        let palette = dark ? NeuPalette.dark : NeuPalette.light
        content
            // Rebuild every screen when the language changes so each text is read again.
            .id(model.language.name)
            .environment(\.neu, palette)
            .environment(\.locale, L10n.locale)
            .preferredColorScheme(preferredScheme)
            .tint(palette.blueOnBg)
            .background(palette.bg.ignoresSafeArea())
            .permissionPrompt(model.services)
            .onChange(of: scenePhase) {
                // Permissions can change in Settings while the app is in the background.
                if scenePhase == .active { model.services.refreshPermissions() }
            }
    }

    @ViewBuilder private var content: some View {
        if model.role == nil {
            LoginView()
        } else if model.reviewingPermissions {
            PermissionsView()
        } else if let driver = model.driver {
            DriverRootView(session: driver)
        } else if let passenger = model.passenger {
            PassengerRootView(session: passenger)
        }
    }

    private var preferredScheme: ColorScheme? {
        switch model.theme.name {
        case "Light": return .light
        case "Dark": return .dark
        default: return nil
        }
    }

    private var isDark: Bool {
        switch model.theme.name {
        case "Light": return false
        case "Dark": return true
        default: return systemScheme == .dark
        }
    }
}

extension View {
    /// Shows PhoneServices' explanation before each system permission prompt.
    func permissionPrompt(_ services: PhoneServices) -> some View {
        modifier(PermissionPromptModifier(services: services))
    }
}

private struct PermissionPromptModifier: ViewModifier {
    let services: PhoneServices

    func body(content: Content) -> some View {
        let prompt = services.prompt
        let texts = prompt.map { PermissionTexts($0.permission, driver: $0.need == .driverShift) }
        content.alert(
            texts?.title ?? "",
            isPresented: Binding(
                get: { services.prompt != nil },
                set: { shown in
                    if !shown, let prompt = services.prompt { services.answer(prompt, allow: false) }
                }
            ),
            presenting: prompt
        ) { prompt in
            Button(L10n.t(prompt.opensSettings ? "perm_open_settings" : "perm_allow")) {
                services.answer(prompt, allow: true)
            }
            .keyboardShortcut(.defaultAction)
            Button(L10n.t("perm_not_now"), role: .cancel) {
                services.answer(prompt, allow: false)
            }
        } message: { prompt in
            let description = texts?.description ?? ""
            Text(prompt.opensSettings ? description + "\n\n" + L10n.t("perm_blocked_hint") : description)
        }
    }
}

/// Name, SF Symbol and reason for each permission, worded for riders or drivers.
struct PermissionTexts {
    let title: String
    let description: String
    let systemImage: String

    init(_ permission: AppPermission, driver: Bool) {
        switch permission.name {
        case "Location":
            title = L10n.t("perm_location")
            description = L10n.t(driver ? "perm_location_desc_driver" : "perm_location_desc_passenger")
            systemImage = "location.fill"
        case "Notifications":
            title = L10n.t("perm_notifications")
            description = L10n.t(driver ? "perm_notifications_desc_driver" : "perm_notifications_desc")
            systemImage = "bell.badge.fill"
        default:
            title = L10n.t("perm_overlay")
            description = L10n.t("perm_overlay_desc")
            systemImage = "rectangle.on.rectangle"
        }
    }
}

/// Review screen opened from Settings: the state of each permission and a way to change it.
struct PermissionsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.neu) private var neu

    var body: some View {
        let services = model.services
        let driver = model.role == UserRole.driver
        NeuScroll(spacing: 20) {
            ScreenHeader(title: L10n.t("permissions_title"), subtitle: L10n.t("permissions_subtitle"))
            ForEach([AppPermission.location, AppPermission.notifications], id: \.self) { permission in
                PermissionCard(
                    texts: PermissionTexts(permission, driver: driver),
                    status: services.statuses[permission] ?? .notRequested,
                    onRequest: { services.request(permission) }
                )
            }
            Button {
                model.reviewingPermissions = false
            } label: {
                Text(L10n.t("perm_continue"))
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(neu.blueOnBg)
            }
            .buttonStyle(.neu)
            .accessibilityHint(L10n.t("action_continue"))
            .padding(.top, 8)
        }
    }
}

private struct PermissionCard: View {
    @Environment(\.neu) private var neu
    let texts: PermissionTexts
    let status: PermissionStatus
    let onRequest: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 14) {
                Image(systemName: texts.systemImage)
                    .font(.system(size: 22))
                    .foregroundStyle(neu.blueOnBg)
                    .frame(width: 48, height: 48)
                    .neuInset(Circle(), distance: 3)
                VStack(alignment: .leading, spacing: 2) {
                    Text(texts.title).font(.system(size: 18, weight: .bold)).foregroundStyle(neu.text)
                    Text(texts.description).font(.system(size: 15)).foregroundStyle(neu.textMuted)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .accessibilityElement(children: .combine)

            if status == .granted {
                MintChip(L10n.t("perm_granted"))
            } else {
                let opensSettings = PermissionFlowKt.permissionAction(status: status).name == "OpenSettings"
                if opensSettings {
                    Text(L10n.t("perm_denied"))
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(neu.text)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Button(action: onRequest) {
                    Text(L10n.t(opensSettings ? "perm_open_settings" : "perm_allow"))
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(neu.blueOnBg)
                }
                .buttonStyle(.neuPill)
                .accessibilityHint(opensSettings ? L10n.t("action_open_settings") : L10n.t("action_allow_permission", texts.title))
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .neuCard()
    }
}
