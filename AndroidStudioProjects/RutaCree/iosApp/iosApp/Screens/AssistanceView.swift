import Shared
import SwiftUI
import UIKit

/// "Solicitar Rampa": one big button that tells the next bus's driver to lower the ramp.
struct AssistanceView: View {
    @Environment(\.neu) private var neu
    let session: PassengerSession

    var body: some View {
        let state = session.state
        let flow = state.ramp.flow
        // A failed request leaves the button raised so the rider can try again.
        let active = flow != .idle && flow != .failed
        ScrollView {
            VStack(spacing: 0) {
                ScreenHeader(title: L10n.t("assistance_title"))
                GeofenceBadge(state: state).padding(.top, 20)
                RampButton(active: active) { session.requestRamp() }
                    .padding(.top, 48)
                if flow != .idle {
                    VStack(spacing: 28) {
                        RampStatusBar(ramp: state.ramp)
                        Button {
                            session.transit.cancelRamp()
                        } label: {
                            Label(L10n.t("cancel_request"), systemImage: "xmark")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(neu.textMuted)
                                .padding(.horizontal, 8)
                        }
                        .buttonStyle(.neuPill)
                        .fixedSize()
                        .accessibilityHint(L10n.t("action_cancel_request"))
                    }
                    .padding(.top, 40)
                    .transition(.opacity)
                }
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 8)
            .animation(.easeInOut, value: flow.name)
        }
        .background(neu.bg.ignoresSafeArea())
    }
}

/// "Estás en: …" inside a stop's 150 m geofence; otherwise the nearest or the chosen stop.
private struct GeofenceBadge: View {
    @Environment(\.neu) private var neu
    let state: TransitUiState

    var body: some View {
        Label {
            Text(text).font(.system(size: 16, weight: .semibold)).foregroundStyle(neu.text)
        } icon: {
            Image(systemName: "mappin").foregroundStyle(neu.blueOnBg)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
        .neuCard(Shapes.pill, distance: 5)
        .accessibilityElement(children: .combine)
    }

    private var text: String {
        if let geofence = state.geofence {
            return geofence.inside
                ? L10n.t("geofence_inside", geofence.stop.name)
                : L10n.t("geofence_nearest", geofence.stop.name, Format.distance(geofence.distanceMeters))
        }
        if let stop = state.stop { return L10n.t("geofence_no_gps", stop.name) }
        return L10n.t("geofence_none")
    }
}

private struct RampButton: View {
    @Environment(\.neu) private var neu
    let active: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                ZStack {
                    RadialGradient(colors: [neu.mint.opacity(0.6), .clear], center: .center, startRadius: 0, endRadius: 60)
                        .frame(width: 120, height: 120)
                        .opacity(active ? 1 : 0)
                    Image(systemName: "figure.roll")
                        .font(.system(size: 76, weight: .semibold))
                        .foregroundStyle(active ? (neu.isDark ? neu.mint : neu.blueOnBg) : neu.mint)
                }
                Text(L10n.t(active ? "ramp_requested" : "ramp_request"))
                    .font(.system(size: 19, weight: .bold))
                    .foregroundStyle(neu.text)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 130)
            }
            .frame(width: 220, height: 220)
            .modifier(RampSurface(sunk: active))
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(active)
        .animation(.easeInOut, value: active)
        .accessibilityLabel(L10n.t(active ? "cd_ramp_button_sent" : "cd_ramp_button"))
        .accessibilityHint(active ? "" : L10n.t("action_request_ramp"))
    }

    private struct RampSurface: ViewModifier {
        let sunk: Bool
        func body(content: Content) -> some View {
            if sunk { content.neuInset(Circle(), distance: 8) } else { content.neuCard(Circle(), distance: 12) }
        }
    }
}

private struct RampStatusBar: View {
    @Environment(\.neu) private var neu
    let ramp: RampUiState

    var body: some View {
        let flow = ramp.flow
        HStack(spacing: 14) {
            if flow == .sending || flow == .waitingDriver {
                ProgressView().tint(neu.mintOnBg)
            } else if flow == .acknowledged {
                Image(systemName: "checkmark")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(neu.chipText)
                    .frame(width: 28, height: 28)
                    .background(Circle().fill(neu.chipBg))
            } else {
                Image(systemName: "exclamationmark")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(AlertColors.onWarning)
                    .frame(width: 28, height: 28)
                    .background(Circle().fill(AlertColors.warning))
            }
            Text(text)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(flow == .acknowledged ? neu.mintOnBg : neu.text)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, minHeight: 60)
        .neuInset()
        .accessibilityElement(children: .combine)
        .onChange(of: text) { UIAccessibility.post(notification: .announcement, argument: text) }
    }

    private var text: String {
        switch ramp.flow {
        case .sending: return L10n.t("status_sending")
        case .waitingDriver:
            let drivers = Int(ramp.driversNotified)
            return drivers > 0 ? L10n.plural("status_waiting", count: drivers, drivers) : L10n.t("status_waiting_no_drivers")
        case .acknowledged: return L10n.t("status_notified")
        default: return L10n.t("status_failed")
        }
    }
}
