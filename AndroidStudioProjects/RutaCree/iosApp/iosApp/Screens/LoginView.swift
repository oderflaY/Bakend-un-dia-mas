import Shared
import SwiftUI

/// The single entrance: drivers and riders use the same form; the server answers with the role.
/// Riders can also create an account or go on without one.
struct LoginView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.neu) private var neu
    @State private var registering = false
    @State private var name = ""
    @State private var email = ""
    @State private var password = ""
    @FocusState private var focus: Field?

    private enum Field { case name, email, password }

    var body: some View {
        let state = model.session
        let account = model.account
        let canSubmit = !state.busy && !email.isEmpty && !password.isEmpty && (!registering || !name.isEmpty)
        let submit = {
            guard canSubmit else { return }
            if registering {
                account.register(name: name, email: email, password: password)
            } else {
                account.login(email: email, password: password)
            }
        }
        ScrollView {
            VStack(spacing: 0) {
                Image(systemName: "bus.fill")
                    .font(.system(size: 40))
                    .foregroundStyle(neu.mint)
                    .frame(width: 88, height: 88)
                    .neuCard(Circle(), distance: 8)
                    .padding(.top, 16)
                    .accessibilityHidden(true)
                Text(L10n.t(registering ? "login_register_title" : "app_name"))
                    .font(.system(size: 28, weight: .heavy))
                    .foregroundStyle(neu.text)
                    .accessibilityAddTraits(.isHeader)
                    .padding(.top, 16)
                Text(L10n.t(registering ? "login_register_subtitle" : "login_signin_subtitle"))
                    .font(.system(size: 16))
                    .foregroundStyle(neu.textMuted)
                    .multilineTextAlignment(.center)
                    .padding(.top, 6)

                if !state.available {
                    DemoEntries().padding(.top, 28)
                } else {
                    VStack(alignment: .leading, spacing: 20) {
                        if registering {
                            NeuTextField(
                                label: L10n.t("field_name"),
                                placeholder: L10n.t("field_name_hint"),
                                text: cleared($name),
                                contentType: .name,
                                submitLabel: .next,
                                onSubmit: { focus = .email }
                            )
                            .focused($focus, equals: .name)
                        }
                        NeuTextField(
                            label: L10n.t("field_email"),
                            placeholder: L10n.t("field_email_hint"),
                            text: cleared(Binding(get: { email }, set: { email = $0.trimmingCharacters(in: .whitespaces) })),
                            keyboard: .emailAddress,
                            contentType: .username,
                            submitLabel: .next,
                            onSubmit: { focus = .password }
                        )
                        .focused($focus, equals: .email)
                        NeuTextField(
                            label: L10n.t("field_password"),
                            placeholder: L10n.t(registering ? "field_password_new_hint" : "field_password_hint"),
                            text: cleared($password),
                            contentType: registering ? .newPassword : .password,
                            secret: true,
                            submitLabel: .go,
                            onSubmit: submit
                        )
                        .focused($focus, equals: .password)

                        if let error = state.error { StatusBanner(Format.accountError(error)) }

                        Button(action: submit) {
                            HStack(spacing: 10) {
                                if state.busy { ProgressView().tint(neu.onBlue) }
                                Text(L10n.t(state.busy ? "login_busy" : registering ? "login_submit_register" : "login_submit_signin"))
                                    .font(.system(size: 18, weight: .bold))
                                    .foregroundStyle(neu.onBlue)
                            }
                        }
                        .buttonStyle(.neu(fill: neu.blue, minHeight: 60))
                        .disabled(!canSubmit)
                        .accessibilityHint(L10n.t(registering ? "action_register" : "action_signin"))
                        .padding(.top, 4)

                        // Switching modes is a secondary action so it never competes with "Sign in".
                        Button {
                            registering.toggle()
                            account.clearError()
                        } label: {
                            Text(L10n.t(registering ? "login_switch_to_signin" : "login_switch_to_register"))
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(neu.blueOnBg)
                                .multilineTextAlignment(.center)
                        }
                        .buttonStyle(.neuPill)
                        .accessibilityHint(L10n.t(registering ? "action_switch_to_signin" : "action_switch_to_register"))
                    }
                    .padding(.top, 28)

                    Text(L10n.t("login_skip_hint"))
                        .font(.system(size: 14))
                        .foregroundStyle(neu.textMuted)
                        .multilineTextAlignment(.center)
                        .padding(.top, 28)
                    Button {
                        account.continueAsGuest()
                    } label: {
                        Text(L10n.t("login_skip"))
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(neu.text)
                    }
                    .buttonStyle(.neuPill)
                    .accessibilityHint(L10n.t("action_skip"))
                    .padding(.top, 10)
                }
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(neu.bg.ignoresSafeArea())
    }

    /// Typing again hides the previous error.
    private func cleared(_ binding: Binding<String>) -> Binding<String> {
        Binding(
            get: { binding.wrappedValue },
            set: {
                binding.wrappedValue = $0
                if model.session.error != nil { model.account.clearError() }
            }
        )
    }
}

/// Simulation mode (no server configured): both sides of the app can be explored directly.
private struct DemoEntries: View {
    @Environment(AppModel.self) private var model
    @Environment(\.neu) private var neu

    var body: some View {
        VStack(spacing: 14) {
            StatusBanner(L10n.t("login_demo_title"))
            HStack(spacing: 14) {
                Button {
                    model.account.continueAsGuest()
                } label: {
                    Text(L10n.t("login_demo_rider"))
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(neu.blueOnBg)
                        .multilineTextAlignment(.center)
                }
                .buttonStyle(.neu(fill: nil, minHeight: 64))
                .accessibilityHint(L10n.t("action_demo_rider"))
                Button {
                    model.account.continueAsDemoDriver()
                } label: {
                    Text(L10n.t("login_demo_driver"))
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(neu.blueOnBg)
                        .multilineTextAlignment(.center)
                }
                .buttonStyle(.neu(fill: nil, minHeight: 64))
                .accessibilityHint(L10n.t("action_demo_driver"))
            }
        }
    }
}
