import Shared
import SwiftUI
import UIKit

/// The driver console: shift, reports and settings, plus the full-screen ramp alert.
struct DriverRootView: View {
    @Environment(AppModel.self) private var model
    @Bindable var session: DriverConsole

    var body: some View {
        TabView(selection: $session.tab) {
            DriverShiftView(session: session)
                .tabItem { Label(L10n.t("driver_tab_shift"), systemImage: "bus.fill") }
                .tag(DriverConsole.DriverTab.shift)
            DriverReportsView(session: session)
                .tabItem { Label(L10n.t("driver_tab_reports"), systemImage: "exclamationmark.triangle.fill") }
                .tag(DriverConsole.DriverTab.reports)
            DriverSettingsView(session: session)
                .tabItem { Label(L10n.t("tab_settings"), systemImage: "gearshape.fill") }
                .tag(DriverConsole.DriverTab.settings)
        }
        .overlay {
            if let alert = session.state.rampAlert {
                RampAlertView(request: alert) { session.viewModel.acknowledgeRamp() }
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: session.state.rampAlert?.id)
        .onChange(of: model.services.location) {
            guard let point = model.services.geoPoint else { return }
            session.viewModel.onPhoneLocation(fix: DeviceLocation(point: point, speedKmh: model.services.speedKmh))
        }
    }
}

/// Full-screen ramp alert with a pulsing frame; sound and vibration run while it is shown.
struct RampAlertView: View {
    @Environment(\.neu) private var neu
    let request: RampRequest
    let onAcknowledge: () -> Void
    @State private var pulse = false

    var body: some View {
        let description = L10n.t("cd_ramp_alert", request.stopName)
        ZStack {
            Color.black.opacity(0.55)
                .ignoresSafeArea()
                .overlay(Rectangle().stroke(neu.mint.opacity(pulse ? 1 : 0.25), lineWidth: 10).ignoresSafeArea())
            GeometryReader { proxy in
                VStack {
                    Spacer(minLength: 0)
                    Image(systemName: "figure.roll")
                        .font(.system(size: 56, weight: .bold))
                        .foregroundStyle(neu.onBlue)
                        .frame(width: 96, height: 96)
                        .background(Circle().fill(neu.mint))
                        .accessibilityHidden(true)
                    Spacer(minLength: 0)
                    VStack(spacing: 8) {
                        Text(L10n.t("ramp_alert_title"))
                            .font(.system(size: 28, weight: .heavy))
                            .foregroundStyle(neu.text)
                        Text(request.stopName)
                            .font(.system(size: 36, weight: .heavy))
                            .foregroundStyle(neu.mintOnBg)
                    }
                    .multilineTextAlignment(.center)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(description)
                    Spacer(minLength: 0)
                    Button(action: onAcknowledge) {
                        Text(L10n.t("ramp_alert_ack"))
                            .font(.system(size: 24, weight: .heavy))
                            .foregroundStyle(neu.onBlue)
                    }
                    .buttonStyle(.neu(fill: neu.mint, minHeight: 72))
                    .accessibilityHint(L10n.t("action_ack_ramp"))
                    Spacer(minLength: 0)
                }
                .padding(24)
                .frame(maxWidth: .infinity)
                .frame(height: proxy.size.height * 0.7)
                .background(neu.bg)
                .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 28, style: .continuous)
                        .stroke(neu.mint.opacity(pulse ? 1 : 0.6), lineWidth: 6)
                )
                .padding(.horizontal, 16)
                .frame(maxHeight: .infinity)
            }
        }
        .accessibilityAddTraits(.isModal)
        .onAppear {
            UIAccessibility.post(notification: .screenChanged, argument: description)
            withAnimation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true)) { pulse = true }
        }
    }
}
