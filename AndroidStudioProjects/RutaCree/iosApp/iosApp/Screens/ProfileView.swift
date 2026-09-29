import Shared
import SwiftUI

/// The rider's accessibility profile, account and app settings.
struct ProfileView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.neu) private var neu
    let session: PassengerSession

    var body: some View {
        let account = model.session
        let profile = account.profile
        let edit: (@escaping (RiderProfile) -> RiderProfile) -> Void = { transform in model.account.editProfile(transform: transform) }
        NeuScroll {
            ScreenHeader(title: L10n.t("profile_title"), subtitle: L10n.t("profile_subtitle"))

            AccountSection(account: account, onSignOut: {
                session.tab = .radar
                model.signOut()
            })

            SectionTitle(L10n.t("section_basic"))
            NeuTextField(
                label: L10n.t("field_name"),
                placeholder: L10n.t("field_name_hint"),
                text: Binding(get: { profile.name }, set: { value in edit { ProfileEdits.shared.name(profile: $0, value: value) } }),
                contentType: .name
            )
            FieldLabel(L10n.t("user_type")).padding(.top, 20).padding(.bottom, 10)
            NeuSegmented(
                options: ["rider", "companion"],
                selected: profile.userType == "companion" ? "companion" : "rider",
                label: { L10n.t($0 == "companion" ? "user_type_companion" : "user_type_rider") },
                onSelect: { value in edit { ProfileEdits.shared.userType(profile: $0, value: value) } }
            )

            SectionTitle(L10n.t("section_favorites"))
            VStack(spacing: 20) {
                NeuTextField(
                    label: L10n.t("favorite_home"),
                    placeholder: L10n.t("favorite_hint"),
                    text: Binding(get: { profile.favorites.home }, set: { value in edit { ProfileEdits.shared.home(profile: $0, value: value) } })
                )
                NeuTextField(
                    label: L10n.t("favorite_center"),
                    placeholder: L10n.t("favorite_hint"),
                    text: Binding(get: { profile.favorites.center }, set: { value in edit { ProfileEdits.shared.center(profile: $0, value: value) } })
                )
                NeuTextField(
                    label: L10n.t("favorite_therapy"),
                    placeholder: L10n.t("favorite_hint"),
                    text: Binding(get: { profile.favorites.therapy }, set: { value in edit { ProfileEdits.shared.therapy(profile: $0, value: value) } })
                )
            }

            SectionTitle(L10n.t("section_assistance"))
            VStack(spacing: 16) {
                AssistanceToggle(
                    systemImage: "figure.roll",
                    title: L10n.t("assist_wheelchair"),
                    description: L10n.t("assist_wheelchair_desc"),
                    isOn: profile.wheelchair,
                    onChange: { value in edit { ProfileEdits.shared.wheelchair(profile: $0, value: value) } }
                )
                AssistanceToggle(
                    systemImage: "eye",
                    title: L10n.t("assist_low_vision"),
                    description: L10n.t("assist_low_vision_desc"),
                    isOn: profile.lowVision,
                    onChange: { value in edit { ProfileEdits.shared.lowVision(profile: $0, value: value) } }
                )
                AssistanceToggle(
                    systemImage: "figure.walk",
                    title: L10n.t("assist_mobility"),
                    description: L10n.t("assist_mobility_desc"),
                    isOn: profile.reducedMobility,
                    onChange: { value in edit { ProfileEdits.shared.reducedMobility(profile: $0, value: value) } }
                )
            }

            Button {
                model.account.saveProfile()
            } label: {
                Text(L10n.t("save_profile"))
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(neu.blueOnBg)
            }
            .buttonStyle(.neu)
            .disabled(account.save == .sending)
            .accessibilityHint(L10n.t("action_save_profile"))
            .padding(.top, 32)

            Group {
                if account.save == .sent {
                    MintChip(L10n.t("profile_saved"))
                } else if account.save == .failed {
                    StatusBanner(L10n.t("profile_save_failed"))
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 16)

            SettingsSection()
        }
    }
}

private struct AccountSection: View {
    @Environment(AppModel.self) private var model
    @Environment(\.neu) private var neu
    let account: AccountUiState
    let onSignOut: () -> Void
    @State private var confirmingDelete = false

    var body: some View {
        SectionTitle(L10n.t("account_section"))
        VStack(alignment: .leading, spacing: 14) {
            if account.kind.name != "Rider" {
                Text(L10n.t("account_anonymous"))
                    .font(.system(size: 15))
                    .foregroundStyle(neu.text)
                    .fixedSize(horizontal: false, vertical: true)
                if account.available {
                    Button {
                        model.account.openLogin()
                    } label: {
                        Text(L10n.t("account_signin_button"))
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(neu.blueOnBg)
                    }
                    .buttonStyle(.neuPill)
                    .accessibilityHint(L10n.t("action_open_login"))
                }
            } else {
                Text(L10n.t("account_signed_in", account.email))
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(neu.text)
                if let error = account.error { StatusBanner(Format.accountError(error)) }
                Button(action: onSignOut) {
                    Text(L10n.t("account_signout"))
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(neu.blueOnBg)
                }
                .buttonStyle(.neuPill)
                .accessibilityHint(L10n.t("action_signout"))
                Button {
                    confirmingDelete = true
                } label: {
                    Text(L10n.t("account_delete"))
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(AlertColors.danger)
                }
                .buttonStyle(.neuPill)
                .disabled(account.busy)
                .accessibilityHint(L10n.t("action_delete"))
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .neuCard()
        .confirmationDialog(L10n.t("account_delete_confirm"), isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button(L10n.t("account_delete_yes"), role: .destructive) { model.account.deleteAccount() }
            Button(L10n.t("account_delete_no"), role: .cancel) {}
        }
    }
}

/// Theme, language and permissions; shared by the rider profile and the driver settings tab.
struct SettingsSection: View {
    @Environment(AppModel.self) private var model
    @Environment(\.neu) private var neu

    var body: some View {
        SectionTitle(L10n.t("section_app_settings"))
        FieldLabel(L10n.t("setting_theme")).padding(.bottom, 10)
        NeuSegmented(
            options: [ThemeMode.system, ThemeMode.light, ThemeMode.dark],
            selected: model.theme,
            label: { mode in
                switch mode.name {
                case "Light": return L10n.t("theme_light")
                case "Dark": return L10n.t("theme_dark")
                default: return L10n.t("theme_system")
                }
            },
            onSelect: { model.updateTheme($0) }
        )
        FieldLabel(L10n.t("setting_language")).padding(.top, 20).padding(.bottom, 10)
        NeuSegmented(
            options: [AppLanguage.system, AppLanguage.spanish, AppLanguage.english],
            selected: model.language,
            label: { language in
                switch language.name {
                case "Spanish": return L10n.t("language_es")
                case "English": return L10n.t("language_en")
                default: return L10n.t("language_system")
                }
            },
            onSelect: { model.updateLanguage($0) }
        )
        Button {
            model.reviewingPermissions = true
        } label: {
            Text(L10n.t("settings_permissions"))
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(neu.blueOnBg)
        }
        .buttonStyle(.neu)
        .accessibilityHint(L10n.t("action_review_permissions"))
        .padding(.top, 20)
    }
}
