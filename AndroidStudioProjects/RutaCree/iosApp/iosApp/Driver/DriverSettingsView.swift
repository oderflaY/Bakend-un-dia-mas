import Shared
import SwiftUI

/// The signed-in driver, sign out (blocked mid-shift) and app settings.
struct DriverSettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.neu) private var neu
    let session: DriverConsole

    var body: some View {
        let driver = session.info
        NeuScroll {
            ScreenHeader(title: L10n.t("tab_settings"))
            SectionTitle(L10n.t("account_section"))
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 14) {
                    Image(systemName: "person.fill")
                        .font(.system(size: 24))
                        .foregroundStyle(neu.blueOnBg)
                        .frame(width: 52, height: 52)
                        .neuInset(Circle(), distance: 3)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(driver.name).font(.system(size: 18, weight: .bold)).foregroundStyle(neu.text)
                        Text(L10n.t("driver_role_label", driver.id)).font(.system(size: 14)).foregroundStyle(neu.textMuted)
                        if !driver.email.isEmpty {
                            Text(driver.email).font(.system(size: 14)).foregroundStyle(neu.textMuted)
                        }
                    }
                }
                .accessibilityElement(children: .combine)
                // Signing out mid-shift would leave the bus broadcasting with nobody to receive ramp requests.
                if session.onShift {
                    Text(L10n.t("driver_signout_blocked"))
                        .font(.system(size: 15))
                        .foregroundStyle(neu.text)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Button {
                    session.tab = .shift
                    model.signOut()
                } label: {
                    Text(L10n.t("account_signout")).font(.system(size: 16, weight: .bold)).foregroundStyle(neu.blueOnBg)
                }
                .buttonStyle(.neuPill)
                .disabled(session.onShift)
                .accessibilityHint(L10n.t("action_signout"))
            }
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .neuCard()
            SettingsSection()
        }
    }
}
