import Shared
import SwiftUI

/// Before the route: today's bus, route and passengers. During it: what is open and since when;
/// the bus position comes from its own GPS, and every report also goes out by WhatsApp.
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

/// Men and women make the trip's total; the categories of the DIF's weekly log say who among them rode.
private let totalKinds: [PassengerKind] = [.men, .women]
private let categoryKinds: [PassengerKind] = [.disability, .elderly, .pregnant, .companions]

private func passengerLabel(_ kind: PassengerKind) -> String {
    switch kind {
    case .men: return L10n.t("passengers_men")
    case .women: return L10n.t("passengers_women")
    case .disability: return L10n.t("passengers_disability")
    case .elderly: return L10n.t("passengers_elderly")
    case .pregnant: return L10n.t("passengers_pregnant")
    default: return L10n.t("passengers_companions")
    }
}

private func passengerCount(_ passengers: Passengers, _ kind: PassengerKind) -> Int {
    switch kind {
    case .men: return Int(passengers.men)
    case .women: return Int(passengers.women)
    case .disability: return Int(passengers.disability)
    case .elderly: return Int(passengers.elderly)
    case .pregnant: return Int(passengers.pregnant)
    default: return Int(passengers.companions)
    }
}

/// The driver is already identified by the login: only today's bus, route and passengers are asked.
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
                if end.name != "ClosedByServer" && state.lastWhatsApp != nil {
                    ResendButton(session: session).padding(.top, 12)
                }
            }

            SectionTitle(L10n.t("driver_assign_title"))
            NeuTextField(
                label: L10n.t("driver_bus_number"),
                placeholder: L10n.t("driver_bus_number_hint"),
                text: Binding(get: { state.busNumber }, set: { viewModel.updateBusNumber(value: $0) }),
                keyboard: .numberPad
            )

            FieldLabel(L10n.t("driver_route")).padding(.top, 24).padding(.bottom, 10)
            // Only the routes of the driver's municipio; the choice is kept for next time.
            let allRoutes = state.routes
            let served = allRoutes.filter { $0.servesMunicipality(municipality: session.municipality) }
            let routes = served.isEmpty ? allRoutes : served
            MunicipalityMenu(
                municipalities: allRoutes.flatMap(\.municipalities).reduce(into: [String]()) { list, name in
                    if !list.contains(name) { list.append(name) }
                },
                selected: session.municipality,
                onSelect: session.selectMunicipality
            )
            .padding(.bottom, 14)
            NeuSegmented(
                options: routes.map(\.id),
                selected: state.selectedRouteId,
                label: { id in state.routes.first { $0.id == id }.map(Format.shortName) ?? id },
                onSelect: { viewModel.selectRoute(id: $0) }
            )

            FieldLabel(L10n.t("driver_passengers_title")).padding(.top, 24).padding(.bottom, 10)
            VStack(spacing: 10) {
                ForEach(totalKinds, id: \.self) { kind in
                    PassengerCounter(
                        name: passengerLabel(kind),
                        value: passengerCount(state.passengers, kind),
                        onChange: { viewModel.changePassengers(kind: kind, delta: Int32($0)) }
                    )
                }
                Text(L10n.t("driver_passengers_of_them", Int(state.passengers.total)))
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(neu.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 8)
                ForEach(categoryKinds, id: \.self) { kind in
                    PassengerCounter(
                        name: passengerLabel(kind),
                        value: passengerCount(state.passengers, kind),
                        onChange: { viewModel.changePassengers(kind: kind, delta: Int32($0)) }
                    )
                }
            }

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
            Text(L10n.t("driver_whatsapp_hint"))
                .font(.system(size: 15))
                .foregroundStyle(neu.textMuted)
                .padding(.top, 12)
        }
    }
}

/// Big −/+ buttons: usable with gloves and without typing. VoiceOver adjusts it like a stepper.
private struct PassengerCounter: View {
    @Environment(\.neu) private var neu
    let name: String
    let value: Int
    let onChange: (Int) -> Void

    var body: some View {
        HStack(spacing: 0) {
            Text(name)
                .font(.system(size: 19, weight: .bold))
                .foregroundStyle(neu.text)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.leading, 6)
            NeuIconButton(systemImage: "minus", label: L10n.t("action_passengers_remove", name)) { onChange(-1) }
                .disabled(value == 0)
            Text("\(value)")
                .font(.system(size: 28, weight: .heavy))
                .foregroundStyle(neu.text)
                .frame(width: 64)
            NeuIconButton(systemImage: "plus", label: L10n.t("action_passengers_add", name)) { onChange(1) }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .neuInset()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(name)
        .accessibilityValue("\(value)")
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: onChange(1)
            case .decrement: onChange(-1)
            @unknown default: break
            }
        }
    }
}

/// Opens the last WhatsApp report again, for when WhatsApp was closed before sending it.
struct ResendButton: View {
    @Environment(\.neu) private var neu
    let session: DriverConsole

    var body: some View {
        Button {
            session.viewModel.resendWhatsApp()
        } label: {
            Text(L10n.t("whatsapp_resend"))
                .font(.system(size: 17, weight: .bold))
                .foregroundStyle(neu.blueOnBg)
        }
        .buttonStyle(.neu)
        .accessibilityHint(L10n.t("action_whatsapp_resend"))
    }
}

/// On route the driver only needs to glance at which bus and route are open and since when.
/// Failures and the end of the route are in Reports.
private struct Console: View {
    @Environment(AppModel.self) private var model
    @Environment(\.neu) private var neu
    let session: DriverConsole
    let shift: DriverShift

    var body: some View {
        let state = session.state
        let passengers = shift.passengers
        NeuScroll {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 14) {
                    Image(systemName: "bus.fill")
                        .font(.system(size: 26))
                        .foregroundStyle(neu.onBlue)
                        .frame(width: 52, height: 52)
                        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(neu.blue))
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L10n.t("driver_console_title", shift.busNumber))
                            .font(.system(size: 24, weight: .heavy))
                            .foregroundStyle(neu.text)
                            .accessibilityAddTraits(.isHeader)
                        Text(shift.route.name)
                            .font(.system(size: 15))
                            .foregroundStyle(neu.textMuted)
                            .lineLimit(2)
                    }
                }
                Text(L10n.t("driver_console_started", shift.startTime))
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(neu.text)
                    .padding(.top, 8)
                Text(L10n.t("driver_console_passengers", Int(passengers.total), Int(passengers.men), Int(passengers.women)))
                    .font(.system(size: 18))
                    .foregroundStyle(neu.text)
                Text(L10n.t("driver_console_categories", Int(passengers.disability), Int(passengers.elderly),
                            Int(passengers.pregnant), Int(passengers.companions)))
                    .font(.system(size: 16))
                    .foregroundStyle(neu.textMuted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
            .neuCard()
            .padding(.top, 16)

            VStack(alignment: .leading, spacing: 14) {
                if !model.config.isLive {
                    Text(L10n.t("driver_simulated"))
                        .font(.system(size: 13))
                        .foregroundStyle(neu.textMuted)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Capsule().fill(neu.bg))
                } else if state.phoneGps != .off {
                    PhoneGpsCard(status: state.phoneGps)
                } else {
                    MintChip(L10n.t("driver_console_gps"))
                }
                if shift.rampFailureReported {
                    StatusBanner(L10n.t("driver_console_ramp_reported"))
                }
                if state.lastWhatsApp != nil {
                    ResendButton(session: session)
                }
                Text(L10n.t("driver_console_reports_hint"))
                    .font(.system(size: 16))
                    .foregroundStyle(neu.textMuted)
            }
            .padding(.top, 14)
        }
    }
}

/// Demo mode: whether this phone is putting the bus on the map, in one glance (color, title, what to do).
private struct PhoneGpsCard: View {
    @Environment(\.neu) private var neu
    let status: PhoneGps

    var body: some View {
        let (color, title, hint): (Color, String, String) = switch status {
        case .sending: (neu.mintOnBg, "gps_phone_sending", "gps_phone_sending_hint")
        case .searching: (neu.blueOnBg, "gps_phone_searching", "gps_phone_searching_hint")
        case .noSignal: (Color(hex: 0xD08A1E), "gps_phone_no_signal", "gps_phone_no_signal_hint")
        default: (Color(hex: 0xD9534F), "gps_phone_no_connection", "gps_phone_no_connection_hint")
        }
        HStack(spacing: 14) {
            Circle().fill(color).frame(width: 18, height: 18)
            VStack(alignment: .leading, spacing: 2) {
                Text(L10n.t(title)).font(.system(size: 18, weight: .bold)).foregroundStyle(color)
                Text(L10n.t(hint)).font(.system(size: 15)).foregroundStyle(neu.textMuted)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .neuCard()
        .accessibilityElement(children: .combine)
    }
}
