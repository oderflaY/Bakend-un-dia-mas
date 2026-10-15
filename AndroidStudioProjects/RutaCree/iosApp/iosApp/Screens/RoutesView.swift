import Shared
import SwiftUI

/// Every route on a map: its whole path, its stops in order, and a shortcut to use it on the Radar.
struct RoutesView: View {
    @Environment(\.neu) private var neu
    let session: PassengerSession
    @State private var viewedId: String?
    @State private var highlightedId: String?

    var body: some View {
        let state = session.state
        let route = state.routes.first { $0.id == viewedId } ?? state.route ?? state.routes.first
        let highlighted = route?.stops.first { $0.id == highlightedId }
        VStack(alignment: .leading, spacing: 0) {
            ScreenHeader(title: L10n.t("routes_title"), subtitle: L10n.t("routes_subtitle"))
            RouteChips(
                routes: state.routes,
                selectedId: route?.id,
                showColor: true,
                municipalities: state.municipalities,
                municipality: state.municipality,
                onMunicipality: session.selectMunicipality
            ) { id in
                viewedId = id
                // A stop from the previous route never stays highlighted.
                highlightedId = nil
            }
            if let route {
                // Live buses are only followed for the Radar's route.
                let buses = route.id == state.route?.id ? state.buses : []
                TransitMapView(
                    model: MapModel(
                        route: route,
                        stops: route.stops,
                        selectedStopId: highlighted?.id,
                        buses: buses.map { MapBusPin(id: $0.position.deviceId, point: $0.position.point, highlighted: false) },
                        user: state.userLocation,
                        focus: highlighted?.point,
                        zoom: highlighted == nil ? nil : 15.5,
                        // The whole path in view, unless the rider is looking at one stop.
                        frame: highlighted == nil ? route.geometry.points : []
                    ),
                    accessibilityDescription: L10n.t(
                        "cd_route_map",
                        route.name,
                        route.stops.first?.name ?? "",
                        route.stops.last?.name ?? ""
                    )
                )
                .clipShape(Shapes.inset)
                .frame(maxHeight: .infinity)
                .padding(.vertical, 8)

                RouteDetails(
                    route: route,
                    riderStopId: state.stop?.id,
                    highlightedId: highlightedId,
                    onHighlight: { id in highlightedId = highlightedId == id ? nil : id },
                    onShowOnRadar: {
                        session.transit.selectRoute(routeId: route.id)
                        if let stop = highlighted, stop.accessible {
                            session.transit.selectStop(stopId: stop.id)
                        }
                        session.tab = .radar
                    }
                )
                .frame(maxHeight: .infinity)
                .padding(.bottom, 8)
            } else {
                Spacer()
            }
        }
        .padding(.horizontal, 20)
        .background(neu.bg.ignoresSafeArea())
    }
}

private struct RouteDetails: View {
    @Environment(\.neu) private var neu
    let route: TransitRoute
    let riderStopId: String?
    let highlightedId: String?
    let onHighlight: (String) -> Void
    let onShowOnRadar: () -> Void

    var body: some View {
        let accessible = route.stops.filter(\.accessible).count
        VStack(alignment: .leading, spacing: 0) {
            Text(route.name)
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(neu.text)
                .lineLimit(2)
                .accessibilityAddTraits(.isHeader)
            Text(
                L10n.t("route_length", Format.guidanceDistance(route.geometry.lengthMeters)) + " · " +
                    L10n.plural("route_stops", count: route.stops.count, route.stops.count, accessible)
            )
            .font(.system(size: 14))
            .foregroundStyle(neu.textMuted)
            .padding(.top, 2)

            Button(action: onShowOnRadar) {
                Label(L10n.t("action_use_route"), systemImage: "mappin.and.ellipse")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(neu.onBlue)
            }
            .buttonStyle(.neuPill(fill: neu.blue, minHeight: 48))
            .accessibilityHint(L10n.t("action_use_route_label"))
            .padding(.top, 12)

            Text(L10n.t("route_stops_title"))
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(neu.text)
                .accessibilityAddTraits(.isHeader)
                .padding(.top, 14)
                .padding(.bottom, 6)

            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(Array(route.stops.enumerated()), id: \.element.id) { index, stop in
                        StopRow(
                            number: index + 1,
                            stop: stop,
                            ring: Color(hexString: route.colorHex),
                            mine: stop.id == riderStopId,
                            highlighted: stop.id == highlightedId,
                            onTap: { onHighlight(stop.id) }
                        )
                    }
                }
            }
        }
        .padding(16)
        .neuCard()
    }
}

private struct StopRow: View {
    @Environment(\.neu) private var neu
    let number: Int
    let stop: Stop
    let ring: Color
    let mine: Bool
    let highlighted: Bool
    let onTap: () -> Void

    var body: some View {
        let status = [
            L10n.t(stop.accessible ? "route_stop_accessible" : "route_stop_not_accessible"),
            mine ? L10n.t("route_stop_mine") : nil,
        ].compactMap { $0 }.joined(separator: " · ")
        Button(action: onTap) {
            HStack(spacing: 12) {
                Text(String(number))
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(neu.text)
                    .frame(width: 30, height: 30)
                    .background(Circle().fill(highlighted ? neu.mint : neu.stopFill))
                    .overlay(Circle().stroke(ring, lineWidth: 3))
                VStack(alignment: .leading, spacing: 1) {
                    Text(stop.name)
                        .font(.system(size: 16, weight: highlighted || mine ? .bold : .medium))
                        .foregroundStyle(mine ? neu.blueOnBg : neu.text)
                    Text(status)
                        .font(.system(size: 13))
                        .foregroundStyle(stop.accessible ? neu.mintOnBg : neu.textMuted)
                }
                Spacer(minLength: 0)
                Image(systemName: stop.accessible ? "figure.roll" : "nosign")
                    .foregroundStyle(stop.accessible ? neu.mintOnBg : neu.textMuted)
            }
            .padding(8)
            .frame(minHeight: 52)
            .background {
                if highlighted { Color.clear.neuInset(Shapes.card, distance: 3) }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityHint(L10n.t("action_choose_route_stop"))
        .accessibilityAddTraits(highlighted ? [.isSelected] : [])
    }
}
