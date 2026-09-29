import Shared
import SwiftUI
import UIKit

/// Trip mode: the rider picks where they get off; the app warns by voice and vibration before it.
struct TripView: View {
    @Environment(\.neu) private var neu
    let session: PassengerSession

    var body: some View {
        let state = session.state
        NeuScroll {
            ScreenHeader(title: L10n.t("trip_title"), subtitle: L10n.t("trip_subtitle"))
            if !state.online { StatusBanner(L10n.t("offline_banner")).padding(.top, 12) }
            Spacer().frame(height: 20)
            if let trip = state.trip {
                if let progress = trip.progress {
                    TripHeader(progress: progress)
                    Timeline(progress: progress).padding(.top, 24)
                } else {
                    Text(L10n.t("trip_waiting")).font(.system(size: 18)).foregroundStyle(neu.text)
                }
                Button {
                    session.transit.endTrip()
                } label: {
                    Label(L10n.t("trip_end"), systemImage: "xmark")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(neu.textMuted)
                }
                .buttonStyle(.neuPill)
                .accessibilityHint(L10n.t("action_end_trip"))
                .padding(.top, 28)
            } else {
                DestinationPicker(session: session)
            }
        }
    }
}

private struct DestinationPicker: View {
    @Environment(\.neu) private var neu
    let session: PassengerSession
    @State private var chosen: String?

    var body: some View {
        let state = session.state
        if let route = state.route {
            let boarding = state.stop
            let destinations = route.stops.filter { $0.accessible && $0.alongMeters > (boarding?.alongMeters ?? -1) }
            let selectedId = chosen.flatMap { id in destinations.contains { $0.id == id } ? id : nil } ?? destinations.last?.id
            VStack(alignment: .leading, spacing: 0) {
                if let boarding {
                    Text(L10n.t("trip_from", boarding.name))
                        .font(.system(size: 16))
                        .foregroundStyle(neu.textMuted)
                        .padding(.bottom, 16)
                }
                FieldLabel(L10n.t("trip_choose_destination")).padding(.bottom, 10)
                if destinations.isEmpty {
                    Text(L10n.t("trip_no_destinations")).font(.system(size: 16)).foregroundStyle(neu.text)
                } else {
                    VStack(spacing: 10) {
                        ForEach(destinations, id: \.id) { stop in
                            let selected = stop.id == selectedId
                            Button {
                                chosen = stop.id
                            } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: "mappin").foregroundStyle(selected ? neu.blueOnBg : neu.textMuted)
                                    Text(stop.name)
                                        .font(.system(size: 17, weight: selected ? .bold : .medium))
                                        .foregroundStyle(selected ? neu.blueOnBg : neu.text)
                                    Spacer(minLength: 0)
                                }
                                .padding(.horizontal, 16)
                                .frame(minHeight: 56)
                                .modifier(RowSurface(selected: selected))
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityHint(L10n.t("action_choose_destination"))
                            .accessibilityAddTraits(selected ? [.isSelected] : [])
                        }
                    }
                    Button {
                        if let selectedId { session.startTrip(destinationStopId: selectedId) }
                    } label: {
                        Text(L10n.t("trip_start"))
                            .font(.system(size: 18, weight: .bold))
                            .foregroundStyle(neu.onBlue)
                    }
                    .buttonStyle(.neu(fill: neu.blue, minHeight: 60))
                    .disabled(selectedId == nil)
                    .accessibilityHint(L10n.t("action_start_trip"))
                    .padding(.top, 24)
                }
            }
        }
    }
}

private struct TripHeader: View {
    @Environment(\.neu) private var neu
    let progress: TripProgress

    var body: some View {
        let stage = progress.stage
        let highlight = stage != .riding
        let title: String = {
            switch stage {
            case .prepareToExit: return L10n.t("trip_stage_prepare")
            case .arrived: return L10n.t("trip_stage_arrived")
            default: return L10n.t("trip_stage_riding")
            }
        }()
        let remaining = progress.remainingStops.count
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.system(size: 24, weight: .heavy))
                .foregroundStyle(highlight ? neu.mintOnBg : neu.text)
            Text(L10n.t("trip_destination", progress.destination.name))
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(neu.text)
            Text(
                L10n.t("trip_distance", Format.distance(progress.distanceToDestinationMeters)) + " · " +
                    L10n.plural("trip_remaining_stops", count: remaining, remaining)
            )
            .font(.system(size: 16))
            .foregroundStyle(neu.textMuted)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .neuCard()
        .overlay {
            if highlight { Shapes.card.stroke(neu.mint, lineWidth: 3) }
        }
        .accessibilityElement(children: .combine)
        .onChange(of: stage.name) { UIAccessibility.post(notification: .announcement, argument: title) }
    }
}

/// Vertical timeline: passed stops dimmed, next stop and destination emphasized.
private struct Timeline: View {
    @Environment(\.neu) private var neu
    let progress: TripProgress

    var body: some View {
        let stops = progress.passedStops + progress.remainingStops
        let passedIds = Set(progress.passedStops.map(\.id))
        VStack(spacing: 0) {
            ForEach(Array(stops.enumerated()), id: \.element.id) { index, stop in
                let passed = passedIds.contains(stop.id)
                let isNext = stop.id == progress.nextStop?.id
                let isDestination = stop.id == progress.destination.id
                let status: String? = isDestination ? L10n.t("trip_stop_destination")
                    : passed ? L10n.t("trip_stop_passed")
                    : isNext ? L10n.t("trip_stop_next") : nil
                TimelineRow(
                    name: stop.name,
                    passed: passed,
                    emphasized: isNext || isDestination,
                    first: index == 0,
                    last: index == stops.count - 1
                )
                .accessibilityElement(children: .ignore)
                .accessibilityLabel([stop.name, status].compactMap { $0 }.joined(separator: ", "))
            }
        }
    }
}

private struct TimelineRow: View {
    @Environment(\.neu) private var neu
    let name: String
    let passed: Bool
    let emphasized: Bool
    let first: Bool
    let last: Bool

    var body: some View {
        let line = passed ? neu.textMuted.opacity(0.4) : neu.blue
        HStack(spacing: 12) {
            ZStack {
                VStack(spacing: 0) {
                    Rectangle().fill(first ? Color.clear : line).frame(width: 4)
                    Rectangle().fill(last ? Color.clear : line).frame(width: 4)
                }
                Circle()
                    .fill(passed ? neu.textMuted.opacity(0.5) : emphasized ? neu.mint : neu.blue)
                    .frame(width: emphasized ? 24 : 16, height: emphasized ? 24 : 16)
            }
            .frame(width: 36)
            Text(name)
                .font(.system(size: emphasized ? 20 : 17, weight: emphasized ? .bold : .medium))
                .foregroundStyle(passed ? neu.textMuted : neu.text)
                .padding(.vertical, 14)
            Spacer(minLength: 0)
        }
        .fixedSize(horizontal: false, vertical: true)
    }
}
