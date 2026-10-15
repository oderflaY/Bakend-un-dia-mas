import Shared
import SwiftUI
import UIKit

/// The rider's tabs, GPS tracking and the contingency alert.
struct PassengerRootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.neu) private var neu
    @Bindable var session: PassengerSession

    var body: some View {
        let services = model.services
        let state = session.state
        TabView(selection: $session.tab) {
            RadarView(session: session)
                .tabItem { Label(L10n.t("tab_radar"), systemImage: "mappin.and.ellipse") }
                .tag(PassengerTab.radar)
            RoutesView(session: session)
                .tabItem { Label(L10n.t("tab_routes"), systemImage: "point.topleft.down.to.point.bottomright.curvepath") }
                .tag(PassengerTab.routes)
            AssistanceView(session: session)
                .tabItem { Label(L10n.t("tab_ramp"), systemImage: "figure.roll") }
                .tag(PassengerTab.ramp)
            TripView(session: session)
                .tabItem { Label(L10n.t("tab_trip"), systemImage: "bus.fill") }
                .tag(PassengerTab.trip)
            ReportsView(session: session)
                .tabItem { Label(L10n.t("tab_reports"), systemImage: "exclamationmark.bubble.fill") }
                .tag(PassengerTab.reports)
            ProfileView(session: session)
                .tabItem { Label(L10n.t("tab_profile"), systemImage: "person.crop.circle") }
                .tag(PassengerTab.profile)
        }
        .overlay {
            if let incident = state.incident {
                IncidentAlert(
                    incident: incident,
                    onAlternatives: {
                        session.transit.showAlternativeRoutes()
                        session.tab = .radar
                    },
                    onDismiss: { session.transit.dismissIncident() }
                )
                .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: state.incident?.id)
        .onAppear {
            // Screens that locate the rider ask for location every time they open without it.
            askLocationIfNeeded(session.tab)
            trackLocation(tripActive: state.trip != nil)
        }
        .onChange(of: session.tab) { askLocationIfNeeded(session.tab) }
        .onChange(of: state.trip != nil) { trackLocation(tripActive: session.state.trip != nil) }
        .onChange(of: services.location) {
            session.transit.updateUserLocation(location: services.geoPoint)
        }
        .onDisappear { services.stopLocationUpdates(for: "rider") }
    }

    private func askLocationIfNeeded(_ tab: PassengerTab) {
        if tab == .radar || tab == .ramp || tab == .trip {
            model.services.ask(.riderLocation)
        }
    }

    /// Trip mode keeps following the ride with the screen locked, to warn before the stop.
    private func trackLocation(tripActive: Bool) {
        model.services.startLocationUpdates(for: "rider", background: tripActive)
    }
}

/// High-contrast contingency alert: covers the screen and is only closed with its buttons.
struct IncidentAlert: View {
    @Environment(\.neu) private var neu
    let incident: Incident
    let onAlternatives: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        let title = Format.incidentTitle(incident)
        ZStack {
            Color.black.opacity(0.55).ignoresSafeArea()
            VStack(spacing: 16) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 36, weight: .bold))
                    .foregroundStyle(AlertColors.onWarning)
                    .frame(width: 72, height: 72)
                    .background(Circle().fill(AlertColors.warning))
                    .accessibilityHidden(true)
                VStack(spacing: 8) {
                    Text(title)
                        .font(.system(size: 24, weight: .heavy))
                        .foregroundStyle(neu.text)
                    Text(Format.incidentBody(incident))
                        .font(.system(size: 17))
                        .foregroundStyle(neu.text)
                    Text(L10n.t("incident_hint"))
                        .font(.system(size: 16))
                        .foregroundStyle(neu.textMuted)
                }
                .multilineTextAlignment(.center)
                .accessibilityElement(children: .combine)

                Button(action: onAlternatives) {
                    Text(L10n.t("incident_alternatives"))
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(AlertColors.onWarning)
                        .frame(maxWidth: .infinity, minHeight: 56)
                        .background(Capsule().fill(AlertColors.warning))
                }
                .accessibilityHint(L10n.t("action_alternatives"))
                Button(action: onDismiss) {
                    Text(L10n.t("incident_dismiss"))
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(neu.text)
                        .frame(maxWidth: .infinity, minHeight: 56)
                        .overlay(Capsule().stroke(neu.textMuted, lineWidth: 2))
                }
                .accessibilityHint(L10n.t("action_dismiss_incident"))
            }
            .padding(24)
            .background(neu.bg)
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(AlertColors.warning, lineWidth: 4).padding(6))
            .clipShape(RoundedRectangle(cornerRadius: 24))
            .overlay(RoundedRectangle(cornerRadius: 24).stroke(AlertColors.danger, lineWidth: 6))
            .padding(.horizontal, 24)
            .accessibilityElement(children: .contain)
            .accessibilityAddTraits(.isModal)
            .accessibilityLabel(title)
        }
        .onAppear { UIAccessibility.post(notification: .screenChanged, argument: title) }
    }
}
