import Shared
import SwiftUI

/// Before the shift: today's bus and route. During it: the map, directions to the route and next stops.
struct DriverShiftView: View {
    @Environment(\.neu) private var neu
    let session: DriverConsole

    var body: some View {
        if let shift = session.state.shift {
            Console(session: session, shift: shift)
        } else {
            AssignmentForm(session: session)
        }
    }
}

/// The driver is already identified by the login: only today's bus and route are asked.
private struct AssignmentForm: View {
    @Environment(\.neu) private var neu
    let session: DriverConsole

    var body: some View {
        let state = session.state
        let viewModel = session.viewModel
        NeuScroll {
            ScreenHeader(title: L10n.t("driver_greeting", state.driverName), subtitle: L10n.t("driver_assign_subtitle"))
            if let end = state.lastShiftEnd {
                Group {
                    switch end.name {
                    case "Completed": MintChip(L10n.t("driver_last_end_completed"))
                    case "Emergency": StatusBanner(L10n.t("driver_last_end_emergency"))
                    default: StatusBanner(L10n.t("driver_last_end_server"))
                    }
                }
                .padding(.top, 14)
            }

            SectionTitle(L10n.t("driver_assign_title"))
            NeuTextField(
                label: L10n.t("driver_bus_number"),
                placeholder: L10n.t("driver_bus_number_hint"),
                text: Binding(get: { state.busNumber }, set: { viewModel.updateBusNumber(value: $0) }),
                keyboard: .numberPad
            )

            FieldLabel(L10n.t("driver_route")).padding(.top, 24).padding(.bottom, 10)
            NeuSegmented(
                options: state.routes.map(\.id),
                selected: state.selectedRouteId,
                label: { id in state.routes.first { $0.id == id }.map(Format.shortName) ?? id },
                onSelect: { viewModel.selectRoute(id: $0) }
            )

            Button {
                session.startShift()
            } label: {
                HStack(spacing: 12) {
                    if state.starting {
                        ProgressView().tint(neu.onBlue)
                    } else {
                        Image(systemName: "bus.fill").font(.system(size: 24))
                    }
                    Text(L10n.t(state.starting ? "driver_starting" : "driver_start"))
                        .font(.system(size: 22, weight: .heavy))
                }
                .foregroundStyle(neu.onBlue)
            }
            .buttonStyle(.neu(fill: neu.blue, minHeight: 72))
            .disabled(!state.canStart)
            .accessibilityHint(L10n.t("action_start_route"))
            .padding(.top, 32)

            if let error = state.startError {
                StatusBanner(
                    error.name == "Network"
                        ? L10n.t("driver_error_network")
                        : L10n.t("driver_error_rejected", state.startErrorMessage ?? "")
                )
                .padding(.top, 14)
            }
            if !state.canStart && !state.starting {
                Text(L10n.t("driver_missing_fields"))
                    .font(.system(size: 15))
                    .foregroundStyle(neu.textMuted)
                    .padding(.top, 12)
            }
        }
    }
}

/// Glanceable while driving: the map shows where the bus is; the panel says what to do next.
private struct Console: View {
    @Environment(\.neu) private var neu
    let session: DriverConsole
    let shift: DriverShift

    var body: some View {
        let state = session.state
        let approach = state.approach
        GeometryReader { proxy in
            VStack(spacing: 0) {
                ZStack(alignment: .topLeading) {
                    TransitMapView(
                        model: MapModel(
                            route: shift.route,
                            stops: shift.route.stops,
                            selectedStopId: approach?.targetStop?.id ?? state.nextStops.first?.id,
                            buses: state.position.map { [MapBusPin(id: -1, point: $0, highlighted: true)] } ?? [],
                            focus: state.position,
                            zoom: 16,
                            guidance: approach?.path ?? [],
                            // While approaching, keep both the bus and where it joins the route in view.
                            frame: approach?.path ?? []
                        ),
                        accessibilityDescription: L10n.t("cd_driver_map", shift.route.name)
                    )
                    .clipShape(Shapes.inset)
                    ConsoleHeader(session: session, shift: shift).padding(12)
                }
                .padding(.horizontal, 12)
                .padding(.top, 8)

                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        if state.position == nil {
                            StatusBanner(L10n.t("driver_gps_waiting"))
                        } else if let approach {
                            ApproachCard(approach: approach)
                        } else {
                            NextStops(stops: state.nextStops)
                        }
                        if approach != nil && !state.nextStops.isEmpty {
                            NextStops(stops: state.nextStops)
                        }
                        if let tracker = state.tracker { TrackerCard(tracker: tracker) }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
                // Never more than about half the screen, so the map stays readable.
                .frame(height: proxy.size.height * 0.48)
            }
        }
        .background(neu.bg.ignoresSafeArea())
    }
}

private struct ConsoleHeader: View {
    @Environment(AppModel.self) private var model
    @Environment(\.neu) private var neu
    let session: DriverConsole
    let shift: DriverShift

    var body: some View {
        let state = session.state
        VStack(alignment: .leading, spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(L10n.t("driver_console_title", shift.busNumber))
                    .font(.system(size: 20, weight: .heavy))
                    .foregroundStyle(neu.text)
                    .accessibilityAddTraits(.isHeader)
                Text(shift.route.name)
                    .font(.system(size: 14))
                    .foregroundStyle(neu.textMuted)
                    .lineLimit(1)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .neuCard(distance: 4)

            if !model.config.isLive {
                Text(L10n.t("driver_simulated"))
                    .font(.system(size: 13))
                    .foregroundStyle(neu.textMuted)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(neu.bg))
            } else if !state.reporting {
                StatusBanner(L10n.t("driver_reporting_off"))
            } else if state.approach == nil && state.position != nil {
                MintChip(L10n.t("driver_on_route"))
            } else {
                MintChip(L10n.t("driver_reporting_ok"))
            }
        }
    }
}

/// Turn-by-turn to the route: the next maneuver in large type, then where and how far.
private struct ApproachCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.neu) private var neu
    let approach: Approach

    var body: some View {
        let step = approach.nextStep
        VStack(alignment: .leading, spacing: 14) {
            Text(L10n.t("approach_title").uppercased(with: L10n.locale))
                .font(.system(size: 13, weight: .bold))
                .tracking(1)
                .foregroundStyle(neu.blueOnBg)
                .accessibilityAddTraits(.isHeader)
            HStack(spacing: 14) {
                Image(systemName: Format.maneuverSymbol(step?.maneuver, byStreet: approach.byStreet))
                    .font(.system(size: 34, weight: .bold))
                    .foregroundStyle(neu.onBlue)
                    .frame(width: 64, height: 64)
                    .background(RoundedRectangle(cornerRadius: 18).fill(neu.blue))
                VStack(alignment: .leading, spacing: 2) {
                    if step != nil, let meters = approach.nextStepMeters?.doubleValue {
                        Text(L10n.t("approach_in", Format.guidanceDistance(meters)))
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(neu.textMuted)
                    }
                    Text(instruction)
                        .font(.system(size: 24, weight: .heavy))
                        .foregroundStyle(neu.text)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Rectangle().fill(neu.mapGrid).frame(height: 1)
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(approach.targetStop.map { L10n.t("approach_join_stop", $0.name) } ?? L10n.t("approach_join_route"))
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(neu.text)
                    Text(summary)
                        .font(.system(size: 15))
                        .foregroundStyle(neu.textMuted)
                }
                Spacer(minLength: 0)
                Button {
                    model.services.openNavigation(to: approach.target, walking: false)
                } label: {
                    Label(L10n.t("action_navigate"), systemImage: "location.north.line.fill")
                        .font(.system(size: 17, weight: .heavy))
                        .foregroundStyle(neu.onBlue)
                        .fixedSize()
                }
                .buttonStyle(.neuPill(fill: neu.blue, minHeight: 52))
                .fixedSize()
                .accessibilityHint(L10n.t("action_navigate_label"))
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .neuCard()
        .accessibilityElement(children: .contain)
    }

    private var instruction: String {
        if !approach.byStreet { return L10n.t("approach_calculating") }
        guard let step = approach.nextStep else { return L10n.t("approach_follow_line") }
        return Format.instruction(step)
    }

    private var summary: String {
        let distance = Format.guidanceDistance(approach.remainingMeters)
        if let seconds = approach.remainingSeconds?.doubleValue {
            return L10n.t("approach_eta", distance, Format.minutes(seconds))
        }
        return L10n.t("approach_straight", distance)
    }
}

private struct NextStops: View {
    @Environment(\.neu) private var neu
    let stops: [Stop]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(L10n.t("driver_next_stops").uppercased(with: L10n.locale))
                .font(.system(size: 13, weight: .bold))
                .tracking(1)
                .foregroundStyle(neu.textMuted)
                .accessibilityAddTraits(.isHeader)
                .padding(.leading, 4)
                .padding(.top, 4)
            if stops.isEmpty {
                Text(L10n.t("driver_no_stops"))
                    .font(.system(size: 26, weight: .bold))
                    .foregroundStyle(neu.text)
            }
            ForEach(Array(stops.enumerated()), id: \.element.id) { index, stop in
                NextStopRow(stop: stop, first: index == 0)
            }
        }
    }
}

private struct NextStopRow: View {
    @Environment(\.neu) private var neu
    let stop: Stop
    let first: Bool

    var body: some View {
        HStack(spacing: 8) {
            Text(stop.name)
                .font(.system(size: first ? 28 : 21, weight: first ? .heavy : .bold))
                .foregroundStyle(first ? neu.text : neu.textMuted)
                .frame(maxWidth: .infinity, alignment: .leading)
            if stop.accessible {
                Label(L10n.t("driver_accessible"), systemImage: "figure.roll")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(neu.chipText)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(neu.chipBg))
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, first ? 18 : 14)
        .modifier(StopSurface(first: first))
        .accessibilityElement(children: .combine)
    }

    private struct StopSurface: ViewModifier {
        let first: Bool
        func body(content: Content) -> some View {
            if first { content.neuCard() } else { content.neuInset() }
        }
    }
}

/// Whether the bus position reaches the server, and from where (Traccar Client or this app).
private struct TrackerCard: View {
    @Environment(\.neu) private var neu
    let tracker: TrackerStatus
    private let staleSeconds = 60

    var body: some View {
        let traccarAge = tracker.lastFixAgeSeconds.map { Int($0.int32Value) }
        let busAge = tracker.busFixAgeSeconds.map { Int($0.int32Value) }
        let viaTraccar = (traccarAge ?? .max) < staleSeconds
        let viaApp = !viaTraccar && (busAge ?? .max) < staleSeconds
        VStack(alignment: .leading, spacing: 6) {
            Text(L10n.t("tracker_title")).font(.system(size: 17, weight: .bold)).foregroundStyle(neu.text)
            if viaTraccar {
                Text(L10n.t("tracker_source_traccar")).font(.system(size: 14)).foregroundStyle(neu.textMuted)
                MintChip(L10n.t("tracker_ok", traccarAge ?? 0))
            } else if viaApp {
                Text(L10n.t("tracker_source_app")).font(.system(size: 14)).foregroundStyle(neu.textMuted)
                MintChip(L10n.t("tracker_ok", busAge ?? 0))
            } else if let busAge {
                StatusBanner(L10n.t("tracker_no_position", busAge))
            } else {
                StatusBanner(L10n.t("tracker_waiting_any"))
            }
            if !viaTraccar {
                Text(L10n.t(viaApp ? "tracker_setup_optional" : "tracker_setup"))
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(neu.text)
                    .padding(.top, 6)
                if let id = tracker.trackerDeviceId {
                    Text(L10n.t("tracker_setup_id", id)).font(.system(size: 15)).foregroundStyle(neu.text)
                }
                if let url = tracker.trackerUrl, !url.isEmpty {
                    Text(L10n.t("tracker_setup_url", url)).font(.system(size: 15)).foregroundStyle(neu.text)
                }
                Text(L10n.t("tracker_setup_frequency")).font(.system(size: 15)).foregroundStyle(neu.text)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .neuInset()
        .accessibilityElement(children: .combine)
    }
}
