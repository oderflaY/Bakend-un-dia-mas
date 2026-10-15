import Shared
import SwiftUI

/// Radar: the chosen route on the map, the next bus with a ramp and, with GPS, the walk to the stop.
struct RadarView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.neu) private var neu
    let session: PassengerSession

    var body: some View {
        let state = session.state
        VStack(spacing: 0) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L10n.t("app_name"))
                        .font(.system(size: 24, weight: .bold))
                        .foregroundStyle(neu.text)
                        .accessibilityAddTraits(.isHeader)
                    Label {
                        Text(locationLine(state)).lineLimit(1)
                    } icon: {
                        Image(systemName: "mappin").foregroundStyle(neu.blueOnBg)
                    }
                    .font(.system(size: 15))
                    .foregroundStyle(neu.textMuted)
                }
                Spacer()
                NeuIconButton(systemImage: "person.fill", label: L10n.t("cd_open_profile")) {
                    session.tab = .profile
                }
                .accessibilityHint(L10n.t("action_open_profile"))
            }
            .padding(.vertical, 8)

            RouteChips(
                routes: state.routes,
                selectedId: state.route?.id,
                showColor: false,
                municipalities: state.municipalities,
                municipality: state.municipality,
                onMunicipality: session.selectMunicipality
            ) {
                session.transit.selectRoute(routeId: $0)
            }

            GeometryReader { proxy in
                ZStack {
                    let walk = state.walk.flatMap { $0.arrived ? nil : $0 }
                    TransitMapView(
                        model: MapModel(
                            route: state.route,
                            stops: state.route?.stops.filter(\.accessible) ?? [],
                            selectedStopId: state.stop?.id,
                            buses: state.buses.map {
                                MapBusPin(
                                    id: $0.position.deviceId,
                                    point: $0.position.point,
                                    highlighted: $0.position.deviceId == state.arrival?.bus.position.deviceId
                                )
                            },
                            user: state.userLocation,
                            focus: state.stop?.point ?? (state.noRoutes ? GeoPoint(lat: 24.0277, lon: -104.6532) : nil),
                            zoom: state.noRoutes ? 12.5 : nil,
                            // Walking to the stop: draw the way there and keep rider and stop in view.
                            guidance: walk?.path ?? [],
                            frame: walk?.path ?? []
                        ),
                        accessibilityDescription: state.route.map {
                            L10n.plural("cd_map", count: state.buses.count, $0.name, state.buses.count)
                        } ?? ""
                    )
                    .clipShape(Shapes.inset)

                    VStack(alignment: .leading, spacing: 8) {
                        SourceBadge(live: state.live)
                        if !state.online { StatusBanner(L10n.t("offline_banner")) }
                        if let walk { WalkCard(walk: walk) }
                        Spacer(minLength: 0)
                        ArrivalCard(session: session)
                            .frame(maxHeight: proxy.size.height * 0.55, alignment: .bottom)
                    }
                    .padding(10)
                }
            }
            .padding(.vertical, 8)
        }
        .padding(.horizontal, 20)
        .background(neu.bg.ignoresSafeArea())
    }

    /// Inside a stop's geofence, near one, or the stop the rider picked.
    private func locationLine(_ state: TransitUiState) -> String {
        if let geofence = state.geofence {
            return geofence.inside
                ? L10n.t("geofence_inside", geofence.stop.name)
                : L10n.t("geofence_nearest", geofence.stop.name, Format.distance(geofence.distanceMeters))
        }
        if let stop = state.stop { return L10n.t("geofence_no_gps", stop.name) }
        if state.noRoutes { return L10n.t("no_routes") }
        return L10n.t("loading_routes")
    }
}

/// Horizontal route selector shared by the Radar and the Routes tab.
struct RouteChips: View {
    @Environment(\.neu) private var neu
    let routes: [TransitRoute]
    let selectedId: String?
    let showColor: Bool
    var municipalities: [String] = []
    var municipality: String? = nil
    var onMunicipality: (String?) -> Void = { _ in }
    let onSelect: (String) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 14) {
                MunicipalityMenu(municipalities: municipalities, selected: municipality, onSelect: onMunicipality)
                ForEach(routes, id: \.id) { route in
                    let selected = route.id == selectedId
                    Button {
                        onSelect(route.id)
                    } label: {
                        HStack(spacing: 8) {
                            if showColor {
                                Circle().fill(Color(hexString: route.colorHex)).frame(width: 12, height: 12)
                            }
                            Text(Format.shortName(route))
                                .font(.system(size: 16, weight: selected ? .bold : .medium))
                                .foregroundStyle(selected ? neu.blueOnBg : neu.text)
                        }
                        .padding(.horizontal, 20)
                        .frame(minHeight: 48)
                        .modifier(ChipSurface(selected: selected))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(route.name)
                    .accessibilityHint(L10n.t("action_show_route"))
                    .accessibilityAddTraits(selected ? [.isSelected] : [])
                }
            }
            .padding(.vertical, 10)
            .padding(.horizontal, 6)
        }
    }

    private struct ChipSurface: ViewModifier {
        let selected: Bool
        func body(content: Content) -> some View {
            if selected { content.neuInset(Shapes.pill, distance: 3) } else { content.neuCard(Shapes.pill, distance: 5) }
        }
    }
}

private struct SourceBadge: View {
    @Environment(\.neu) private var neu
    let live: Bool

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(live ? neu.mintOnBg : neu.textMuted).frame(width: 8, height: 8)
            Text(L10n.t(live ? "badge_live" : "badge_simulated"))
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(neu.text)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .neuCard(Shapes.pill, distance: 4)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L10n.t(live ? "cd_badge_live" : "cd_badge_simulated"))
    }
}

/// Which way to walk to the stop, how far and how long, over the map.
private struct WalkCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.neu) private var neu
    let walk: WalkGuide

    var body: some View {
        let step = walk.byStreet ? walk.nextStep.flatMap { $0.maneuver.name == "Arrive" ? nil : $0 } : nil
        HStack(spacing: 8) {
            HStack(spacing: 12) {
                // The map is always north-up, so the arrow points the same way as the dotted line.
                Image(systemName: "arrow.up")
                    .font(.system(size: 24, weight: .bold))
                    .foregroundStyle(neu.onBlue)
                    .rotationEffect(.degrees(walk.headingDegrees))
                    .frame(width: 48, height: 48)
                    .background(Circle().fill(neu.blue))
                VStack(alignment: .leading, spacing: 2) {
                    Text(L10n.t("walk_heading", Format.compass(walk.heading)))
                        .font(.system(size: 17, weight: .heavy))
                        .foregroundStyle(neu.text)
                    if let step, let meters = walk.nextStepMeters?.doubleValue {
                        Text(L10n.t("approach_in", Format.guidanceDistance(meters)) + " · " + Format.instruction(step))
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(neu.blueOnBg)
                            .lineLimit(2)
                    }
                    Text(L10n.t(
                        "walk_summary",
                        Format.guidanceDistance(walk.remainingMeters),
                        Format.minutes(walk.remainingSeconds),
                        walk.stop.name
                    ))
                    .font(.system(size: 14))
                    .foregroundStyle(neu.textMuted)
                    .lineLimit(2)
                }
                Spacer(minLength: 0)
            }
            .accessibilityElement(children: .combine)

            Button {
                model.services.openNavigation(to: walk.stop.point, walking: true)
            } label: {
                Label(L10n.t("action_walk"), systemImage: "figure.walk")
                    .font(.system(size: 16, weight: .heavy))
                    .foregroundStyle(neu.onBlue)
                    .fixedSize()
            }
            .buttonStyle(.neuPill(fill: neu.blue, minHeight: 48))
            .fixedSize()
            .accessibilityHint(L10n.t("action_walk_label"))
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .neuCard()
    }
}

/// The next bus: collapsed to one row so the map stays visible; expands to the countdown,
/// official timetable and the stop picker.
private struct ArrivalCard: View {
    @Environment(\.neu) private var neu
    let session: PassengerSession
    @State private var expanded = false
    @State private var choosingStop = false

    var body: some View {
        let state = session.state
        VStack(alignment: .leading, spacing: 0) {
            if state.loadFailed {
                Text(L10n.t("error_routes")).font(.system(size: 16)).foregroundStyle(neu.text)
            } else if state.noRoutes {
                Text(L10n.t("no_routes")).font(.system(size: 16)).foregroundStyle(neu.text)
            } else if let route = state.route, !state.loading {
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        expanded.toggle()
                        if !expanded { choosingStop = false }
                    }
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "bus.fill")
                            .font(.system(size: 20))
                            .foregroundStyle(neu.blue)
                            .frame(width: 44, height: 44)
                            .neuCard(Circle(), distance: 3)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(route.name)
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(neu.textMuted)
                                .lineLimit(1)
                            ArrivalHeadline(arrival: state.arrival, stop: state.stop)
                        }
                        Spacer(minLength: 0)
                        Image(systemName: expanded ? "chevron.down" : "chevron.up")
                            .font(.system(size: 20, weight: .bold))
                            .foregroundStyle(neu.blueOnBg)
                    }
                    .frame(minHeight: 56)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .combine)
                .accessibilityHint(L10n.t(expanded ? "action_collapse_details" : "action_expand_details"))

                if expanded {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 0) {
                            Text(countdown(state.arrival))
                                .font(.system(size: 24, weight: .heavy))
                                .foregroundStyle(state.arrival == nil ? neu.text : neu.blueOnBg)
                                .padding(.bottom, 6)
                            if let arrival = state.arrival {
                                Text(Format.busName(arrival.bus.position) + " " + L10n.t("arrival_distance", Format.distance(arrival.distanceMeters)))
                                    .font(.system(size: 15))
                                    .foregroundStyle(neu.textMuted)
                                MintChip(L10n.t("ramp_operational")).padding(.vertical, 12)
                            }
                            if let stop = state.stop {
                                ScheduleView(stop: stop, nowMinuteOfDay: state.nowMinuteOfDay)
                            }
                            StopPicker(
                                stops: route.stops.filter(\.accessible),
                                selected: state.stop,
                                expanded: $choosingStop,
                                onSelect: { id in
                                    session.transit.selectStop(stopId: id)
                                    choosingStop = false
                                }
                            )
                            .padding(.top, 12)
                        }
                        .padding(.top, 12)
                    }
                    .transition(.opacity)
                }
            } else {
                HStack(spacing: 12) {
                    ProgressView().tint(neu.blueOnBg)
                    Text(L10n.t("loading_routes")).font(.system(size: 16)).foregroundStyle(neu.text)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .fixedSize(horizontal: false, vertical: !expanded)
        .neuCard()
    }

    private func countdown(_ arrival: Arrival?) -> String {
        guard let arrival else { return L10n.t("countdown_none") }
        let minutes = Int(arrival.minutes)
        return L10n.plural("countdown_next_bus", count: minutes, minutes)
    }
}

/// "Llegando a 20 de Noviembre en 4 min", with the minutes emphasized.
private struct ArrivalHeadline: View {
    @Environment(\.neu) private var neu
    let arrival: Arrival?
    let stop: Stop?

    var body: some View {
        let stopName = stop?.name ?? L10n.t("stop_none")
        Group {
            if let arrival {
                let headline = L10n.t("arrival_headline", stopName, Int(arrival.minutes))
                let minutes = String(arrival.minutes)
                if let range = headline.range(of: minutes, options: .backwards) {
                    Text(headline[..<range.lowerBound])
                        + Text(headline[range.lowerBound...])
                        .font(.system(size: 22, weight: .heavy))
                        .foregroundStyle(neu.blueOnBg)
                } else {
                    Text(headline)
                }
            } else {
                Text(L10n.t("no_arrival", stopName))
            }
        }
        .font(.system(size: 17, weight: .bold))
        .foregroundStyle(neu.text)
        .lineLimit(2)
    }
}

private struct ScheduleView: View {
    @Environment(\.neu) private var neu
    let stop: Stop
    let nowMinuteOfDay: Int32

    var body: some View {
        let departures = stop.nextDepartures(nowMinuteOfDay: nowMinuteOfDay, count: 4)
        VStack(alignment: .leading, spacing: 8) {
            Text(L10n.t("schedule_title"))
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(neu.text)
                .accessibilityAddTraits(.isHeader)
            if let next = departures.first {
                NextDepartureView(minute: next.int32Value, nowMinuteOfDay: nowMinuteOfDay)
                let later = departures.dropFirst().map(\.int32Value)
                if !later.isEmpty {
                    Text(L10n.t("schedule_later"))
                        .font(.system(size: 13))
                        .foregroundStyle(neu.textMuted)
                        .padding(.top, 4)
                    // Even columns that wrap to a new line instead of squeezing when the text is large.
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 72), spacing: 8)], spacing: 8) {
                        ForEach(Array(later.enumerated()), id: \.offset) { _, minute in
                            Text(Format.clock(minute))
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(neu.text)
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 8)
                                .neuInset(Shapes.pill, distance: 3)
                        }
                    }
                }
            } else {
                Text(L10n.t("schedule_none")).font(.system(size: 15)).foregroundStyle(neu.textMuted)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

private struct NextDepartureView: View {
    @Environment(\.neu) private var neu
    let minute: Int32
    let nowMinuteOfDay: Int32

    private var waitText: String {
        let wait = Int(TransitKt.minutesUntil(departure: minute, nowMinuteOfDay: nowMinuteOfDay))
        if wait == 0 { return L10n.t("schedule_now") }
        if wait < 60 { return L10n.t("schedule_in_minutes", wait) }
        if wait % 60 == 0 { return L10n.t("schedule_in_hours_exact", wait / 60) }
        return L10n.t("schedule_in_hours", wait / 60, wait % 60)
    }

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(L10n.t("schedule_next")).font(.system(size: 13)).foregroundStyle(neu.textMuted)
                Text(waitText).font(.system(size: 15, weight: .bold)).foregroundStyle(neu.blueOnBg)
            }
            Spacer(minLength: 0)
            Text(Format.clock(minute))
                .font(.system(size: 26, weight: .heavy))
                .foregroundStyle(neu.text)
                .lineLimit(1)
                .fixedSize()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity)
        .neuInset(Shapes.inset, distance: 3)
    }
}

private struct StopPicker: View {
    @Environment(\.neu) private var neu
    let stops: [Stop]
    let selected: Stop?
    @Binding var expanded: Bool
    let onSelect: (String) -> Void

    var body: some View {
        VStack(spacing: 10) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) { expanded.toggle() }
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: "mappin").foregroundStyle(neu.blueOnBg)
                    Text(L10n.t("stop_label", selected?.name ?? L10n.t("stop_none")))
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(neu.text)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text(L10n.t(expanded ? "stop_close" : "stop_change"))
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(neu.blueOnBg)
                }
                .padding(.horizontal, 14)
                .frame(minHeight: 48)
                .neuInset()
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(L10n.t("action_change_stop"))

            if expanded {
                ForEach(stops, id: \.id) { stop in
                    let isSelected = stop.id == selected?.id
                    Button {
                        onSelect(stop.id)
                    } label: {
                        Text(stop.name)
                            .font(.system(size: 16, weight: isSelected ? .bold : .medium))
                            .foregroundStyle(isSelected ? neu.blueOnBg : neu.text)
                            .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                            .padding(.horizontal, 16)
                            .modifier(RowSurface(selected: isSelected))
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint(L10n.t("action_choose_stop"))
                    .accessibilityAddTraits(isSelected ? [.isSelected] : [])
                }
            }
        }
    }
}

/// Raised row, or sunk when it is the chosen one.
struct RowSurface: ViewModifier {
    let selected: Bool
    func body(content: Content) -> some View {
        if selected { content.neuInset() } else { content.neuCard(distance: 3) }
    }
}

/// Pill with the chosen municipio that opens the list; hidden while routes serve a single municipio.
struct MunicipalityMenu: View {
    @Environment(\.neu) private var neu
    let municipalities: [String]
    let selected: String?
    let onSelect: (String?) -> Void

    var body: some View {
        if municipalities.count > 1 {
            let all = L10n.t("municipality_all")
            Menu {
                Button(all) { onSelect(nil) }
                ForEach(municipalities, id: \.self) { name in
                    Button(name) { onSelect(name) }
                }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "mappin.and.ellipse").foregroundStyle(neu.blueOnBg)
                    Text(selected ?? all)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(neu.text)
                        .lineLimit(1)
                    Image(systemName: "chevron.down").font(.system(size: 12)).foregroundStyle(neu.textMuted)
                }
                .padding(.horizontal, 16)
                .frame(minHeight: 48)
                .neuCard(Capsule(), distance: 4)
            }
            .accessibilityLabel(L10n.t("cd_municipality", selected ?? all))
            .accessibilityHint(L10n.t("action_change_municipality"))
        }
    }
}
