import Shared
import SwiftUI
import UIKit

/// Ramp failure (the route goes on), bus failure or crash (slide to confirm) and end of the route.
/// Each one is saved on the server and opens WhatsApp for dispatch.
struct DriverReportsView: View {
    @Environment(\.neu) private var neu
    let session: DriverConsole
    @State private var accident = false
    @State private var confirmingEnd = false
    @State private var finalLocation = "terminal"

    private let finalLocations = ["terminal", "workshop", "depot"]

    var body: some View {
        NeuScroll {
            ScreenHeader(title: L10n.t("driver_reports_title"))
            if session.state.shift == nil {
                Text(L10n.t("driver_no_shift"))
                    .font(.system(size: 18))
                    .foregroundStyle(neu.text)
                    .padding(.top, 20)
            } else if let shift = session.state.shift {
                SectionTitle(L10n.t("ramp_failure_section"))
                if shift.rampFailureReported {
                    MintChip(L10n.t("ramp_failure_done"))
                } else {
                    Text(L10n.t("ramp_failure_desc"))
                        .font(.system(size: 16))
                        .foregroundStyle(neu.textMuted)
                    Button {
                        session.viewModel.reportFailure(type: IncidentType.rampFailure)
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: "figure.roll")
                                .font(.system(size: 22, weight: .bold))
                                .foregroundStyle(AlertColors.danger)
                                .accessibilityHidden(true)
                            Text(L10n.t("ramp_failure_button"))
                                .font(.system(size: 19, weight: .bold))
                                .foregroundStyle(neu.text)
                        }
                    }
                    .buttonStyle(.neu(fill: nil, minHeight: 64))
                    .accessibilityHint(L10n.t("action_ramp_failure"))
                    .padding(.top, 12)
                }

                SectionTitle(L10n.t("panic_section"))
                NeuSegmented(
                    options: [true, false],
                    selected: accident,
                    label: { L10n.t($0 ? "panic_type_accident" : "panic_type_breakdown") },
                    onSelect: { accident = $0 }
                )
                let typeLabel = L10n.t(accident ? "panic_type_accident" : "panic_type_breakdown")
                SlideToConfirm(
                    text: L10n.t("panic_slider"),
                    accessibilityText: L10n.t("cd_panic_slider", typeLabel),
                    actionLabel: L10n.t("action_panic")
                ) {
                    session.viewModel.reportFailure(type: accident ? IncidentType.accident : IncidentType.breakdown)
                    session.tab = .shift
                }
                .padding(.top, 20)

                SectionTitle(L10n.t("end_section"))
                if !confirmingEnd {
                    Button {
                        confirmingEnd = true
                    } label: {
                        Text(L10n.t("end_shift")).font(.system(size: 20, weight: .bold)).foregroundStyle(neu.blueOnBg)
                    }
                    .buttonStyle(.neu(fill: nil, minHeight: 64))
                    .accessibilityHint(L10n.t("action_end_shift"))
                } else {
                    FieldLabel(L10n.t("end_confirm_title")).padding(.bottom, 10)
                    VStack(spacing: 10) {
                        ForEach(finalLocations, id: \.self) { location in
                            let selected = location == finalLocation
                            Button {
                                finalLocation = location
                            } label: {
                                Text(L10n.t("final_" + location))
                                    .font(.system(size: 18, weight: selected ? .bold : .medium))
                                    .foregroundStyle(selected ? neu.blueOnBg : neu.text)
                                    .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                                    .padding(.horizontal, 18)
                                    .modifier(RowSurface(selected: selected))
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(selected ? [.isSelected] : [])
                        }
                    }
                    HStack(spacing: 14) {
                        Button {
                            confirmingEnd = false
                        } label: {
                            Text(L10n.t("end_cancel")).font(.system(size: 17, weight: .bold)).foregroundStyle(neu.textMuted)
                        }
                        .buttonStyle(.neu)
                        .accessibilityHint(L10n.t("action_cancel_end"))
                        Button {
                            session.viewModel.endShift(finalLocation: location(finalLocation))
                            confirmingEnd = false
                            session.tab = .shift
                        } label: {
                            Text(L10n.t("end_confirm"))
                                .font(.system(size: 17, weight: .bold))
                                .foregroundStyle(neu.onBlue)
                                .multilineTextAlignment(.center)
                        }
                        .buttonStyle(.neu(fill: neu.blue))
                        .accessibilityHint(L10n.t("action_confirm_end"))
                    }
                    .padding(.top, 10)
                }
            }
        }
    }

    private func location(_ wire: String) -> FinalLocation {
        switch wire {
        case "workshop": return .workshop
        case "depot": return .depot
        default: return .terminal
        }
    }
}

/// Red "slide to confirm": the deliberate drag prevents accidental emergency reports.
/// VoiceOver users get the same action with a labelled double tap.
private struct SlideToConfirm: View {
    @Environment(\.neu) private var neu
    let text: String
    let accessibilityText: String
    let actionLabel: String
    let onConfirm: () -> Void
    @State private var offset: CGFloat = 0

    private let thumb: CGFloat = 64

    var body: some View {
        GeometryReader { proxy in
            let maxOffset = max(proxy.size.width - thumb - 12, 1)
            ZStack(alignment: .leading) {
                Capsule().fill(AlertColors.danger.opacity(0.18))
                Text(text)
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(neu.text)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(.leading, thumb + 12)
                    .padding(.trailing, 12)
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 28, weight: .bold))
                    .foregroundStyle(AlertColors.onDanger)
                    .frame(width: thumb, height: thumb)
                    .background(Circle().fill(AlertColors.danger))
                    .offset(x: 6 + offset)
                    .gesture(
                        DragGesture()
                            .onChanged { offset = min(max(0, $0.translation.width), maxOffset) }
                            .onEnded { _ in
                                if offset >= maxOffset * 0.9 {
                                    offset = 0
                                    UINotificationFeedbackGenerator().notificationOccurred(.error)
                                    onConfirm()
                                } else {
                                    withAnimation(.spring) { offset = 0 }
                                }
                            }
                    )
            }
        }
        .frame(height: 76)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
        .accessibilityAddTraits(.isButton)
        .accessibilityAction(named: actionLabel) { onConfirm() }
        .accessibilityAction { onConfirm() }
    }
}
