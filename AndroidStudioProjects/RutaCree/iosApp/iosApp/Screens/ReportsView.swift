import Shared
import SwiftUI
import UIKit

/// Quick report to the transit authority (Movilidad) about the bus or the stop.
struct ReportsView: View {
    @Environment(\.neu) private var neu
    let session: PassengerSession
    @State private var selected: ReportOption?
    @State private var comment = ""

    enum ReportOption: CaseIterable {
        case rampBroken, didNotStop, busFull, stopBlocked

        var label: String {
            switch self {
            case .rampBroken: return L10n.t("report_ramp_broken")
            case .didNotStop: return L10n.t("report_no_stop")
            case .busFull: return L10n.t("report_full")
            case .stopBlocked: return L10n.t("report_stop_blocked")
            }
        }

        var systemImage: String {
            switch self {
            case .rampBroken: return "figure.roll"
            case .didNotStop: return "bus.fill"
            case .busFull: return "person.3.fill"
            case .stopBlocked: return "nosign"
            }
        }

        var kind: ReportKind {
            switch self {
            case .rampBroken: return .rampBroken
            case .didNotStop: return .didNotStop
            case .busFull: return .busFull
            case .stopBlocked: return .stopBlocked
            }
        }
    }

    var body: some View {
        let state = session.state
        let report = state.report
        NeuScroll {
            ScreenHeader(title: L10n.t("reports_title"))
            if let route = state.route {
                Label {
                    if let bus = state.arrival?.bus.position {
                        Text(L10n.t("reports_detected", route.name, Format.busName(bus)))
                    } else {
                        Text(L10n.t("reports_detected_route", route.name))
                    }
                } icon: {
                    Image(systemName: "bus.fill")
                }
                .font(.system(size: 13))
                .foregroundStyle(neu.textMuted)
                .padding(.top, 6)
            }

            SectionTitle(L10n.t("section_quick_report"))
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 16), GridItem(.flexible(), spacing: 16)], spacing: 16) {
                ForEach(ReportOption.allCases, id: \.self) { option in
                    let isSelected = option == selected
                    Button {
                        selected = option
                        session.transit.resetReport()
                    } label: {
                        VStack(spacing: 10) {
                            Image(systemName: option.systemImage).font(.system(size: 28))
                            Text(option.label)
                                .font(.system(size: 15, weight: isSelected ? .bold : .semibold))
                                .multilineTextAlignment(.center)
                        }
                        .foregroundStyle(isSelected ? neu.blueOnBg : neu.text)
                        .padding(14)
                        .frame(maxWidth: .infinity, minHeight: 124)
                        .modifier(RowSurface(selected: isSelected))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(isSelected ? [.isSelected] : [])
                }
            }

            NeuTextField(
                label: L10n.t("report_comment"),
                placeholder: L10n.t("report_comment_hint"),
                text: Binding(
                    get: { comment },
                    set: {
                        comment = String($0.prefix(500))
                        session.transit.resetReport()
                    }
                ),
                multiline: true
            )
            .padding(.top, 28)

            Button {
                if let selected { session.transit.sendReport(kind: selected.kind, comment: comment) }
            } label: {
                Label(L10n.t("send_report"), systemImage: "paperplane.fill")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(neu.onBlue)
            }
            .buttonStyle(.neu(fill: neu.blue, minHeight: 60))
            .disabled(selected == nil || report == .sending)
            .accessibilityHint(L10n.t("action_send_report"))
            .padding(.top, 32)

            Group {
                if report == .sent {
                    MintChip(L10n.t("report_sent"))
                } else if report == .failed {
                    StatusBanner(L10n.t("report_failed"))
                } else if report == .sending {
                    ProgressView().tint(neu.blueOnBg)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 16)
        }
        // Clear the form only once the server accepted it, so nothing typed is lost on failure.
        .onChange(of: report.name) {
            if session.state.report == .sent {
                selected = nil
                comment = ""
                UIAccessibility.post(notification: .announcement, argument: L10n.t("report_sent"))
            }
        }
    }
}
