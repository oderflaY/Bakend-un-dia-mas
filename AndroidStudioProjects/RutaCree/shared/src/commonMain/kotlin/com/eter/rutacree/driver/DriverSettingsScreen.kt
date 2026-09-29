package com.eter.rutacree.driver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eter.rutacree.api.DriverInfo
import com.eter.rutacree.screens.SettingsSection
import com.eter.rutacree.settings.AppSettings
import com.eter.rutacree.ui.AppIcons
import com.eter.rutacree.ui.Neu
import com.eter.rutacree.ui.NeuButton
import com.eter.rutacree.ui.PillShape
import com.eter.rutacree.ui.ScreenHeader
import com.eter.rutacree.ui.SectionTitle
import com.eter.rutacree.ui.neuCard
import com.eter.rutacree.ui.neuInset
import org.jetbrains.compose.resources.stringResource
import rutacree.shared.generated.resources.Res
import rutacree.shared.generated.resources.account_section
import rutacree.shared.generated.resources.account_signout
import rutacree.shared.generated.resources.action_signout
import rutacree.shared.generated.resources.driver_role_label
import rutacree.shared.generated.resources.driver_signout_blocked
import rutacree.shared.generated.resources.tab_settings

@Composable
fun DriverSettingsScreen(
    driver: DriverInfo,
    onShift: Boolean,
    settings: AppSettings,
    onSignOut: () -> Unit,
    onReviewPermissions: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        ScreenHeader(stringResource(Res.string.tab_settings))
        SectionTitle(stringResource(Res.string.account_section))
        Column(Modifier.fillMaxWidth().neuCard().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Box(Modifier.size(52.dp).neuInset(CircleShape, 3.dp), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.User, null, tint = Neu.BlueStrong, modifier = Modifier.size(26.dp))
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(driver.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Neu.Text)
                    Text(stringResource(Res.string.driver_role_label, driver.id), fontSize = 14.sp, color = Neu.TextMuted)
                    if (driver.email.isNotBlank()) Text(driver.email, fontSize = 14.sp, color = Neu.TextMuted)
                }
            }
            // Signing out mid-shift would leave the bus broadcasting with nobody to receive ramp requests.
            if (onShift) {
                Text(stringResource(Res.string.driver_signout_blocked), fontSize = 15.sp, color = Neu.Text, lineHeight = 20.sp)
            }
            NeuButton(
                onClick = onSignOut,
                actionLabel = stringResource(Res.string.action_signout),
                modifier = Modifier.fillMaxWidth(),
                shape = PillShape,
                enabled = !onShift,
            ) {
                Text(stringResource(Res.string.account_signout), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Neu.BlueStrong)
            }
        }
        SettingsSection(settings, onReviewPermissions)
    }
}
