package com.eter.rutacree.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eter.rutacree.settings.AppSettings
import com.eter.rutacree.account.AccountUiState
import com.eter.rutacree.account.SessionKind
import com.eter.rutacree.account.accountErrorText
import com.eter.rutacree.api.RiderProfile
import com.eter.rutacree.transit.SendState
import com.eter.rutacree.ui.AlertColors
import com.eter.rutacree.ui.PillShape
import com.eter.rutacree.ui.StatusBanner
import rutacree.shared.generated.resources.account_anonymous
import rutacree.shared.generated.resources.account_delete
import rutacree.shared.generated.resources.account_delete_confirm
import rutacree.shared.generated.resources.account_delete_no
import rutacree.shared.generated.resources.account_delete_yes
import rutacree.shared.generated.resources.account_section
import rutacree.shared.generated.resources.account_signed_in
import rutacree.shared.generated.resources.account_signin_button
import rutacree.shared.generated.resources.account_signout
import rutacree.shared.generated.resources.action_delete
import rutacree.shared.generated.resources.action_delete_no
import rutacree.shared.generated.resources.action_delete_yes
import rutacree.shared.generated.resources.action_open_login
import rutacree.shared.generated.resources.action_signout
import rutacree.shared.generated.resources.profile_save_failed
import com.eter.rutacree.ui.AppIcons
import com.eter.rutacree.ui.FieldLabel
import com.eter.rutacree.ui.NeuSegmented
import com.eter.rutacree.ui.CardShape
import com.eter.rutacree.ui.MintChip
import com.eter.rutacree.ui.Neu
import com.eter.rutacree.ui.NeuButton
import com.eter.rutacree.ui.NeuSwitch
import com.eter.rutacree.ui.NeuTextField
import com.eter.rutacree.ui.ScreenHeader
import com.eter.rutacree.ui.SectionTitle
import com.eter.rutacree.ui.neuCard
import com.eter.rutacree.ui.neuInset
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import rutacree.shared.generated.resources.Res
import rutacree.shared.generated.resources.action_save_profile
import rutacree.shared.generated.resources.assist_low_vision
import rutacree.shared.generated.resources.assist_low_vision_desc
import rutacree.shared.generated.resources.assist_mobility
import rutacree.shared.generated.resources.assist_mobility_desc
import rutacree.shared.generated.resources.assist_wheelchair
import rutacree.shared.generated.resources.assist_wheelchair_desc
import rutacree.shared.generated.resources.favorite_center
import rutacree.shared.generated.resources.favorite_hint
import rutacree.shared.generated.resources.favorite_home
import rutacree.shared.generated.resources.favorite_therapy
import rutacree.shared.generated.resources.field_name
import rutacree.shared.generated.resources.field_name_hint
import rutacree.shared.generated.resources.profile_saved
import rutacree.shared.generated.resources.profile_subtitle
import rutacree.shared.generated.resources.profile_title
import rutacree.shared.generated.resources.save_profile
import rutacree.shared.generated.resources.section_assistance
import rutacree.shared.generated.resources.section_basic
import rutacree.shared.generated.resources.section_favorites
import rutacree.shared.generated.resources.user_type
import rutacree.shared.generated.resources.user_type_companion
import rutacree.shared.generated.resources.user_type_rider

private enum class UserType(val wire: String, val label: StringResource) {
    Rider("rider", Res.string.user_type_rider),
    Companion("companion", Res.string.user_type_companion),
}

@Composable
fun ProfileScreen(
    settings: AppSettings,
    account: AccountUiState,
    onEdit: ((RiderProfile) -> RiderProfile) -> Unit,
    onSave: () -> Unit,
    onOpenLogin: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onReviewPermissions: () -> Unit,
) {
    val profile = account.profile
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        ScreenHeader(stringResource(Res.string.profile_title), stringResource(Res.string.profile_subtitle))

        AccountSection(account, onOpenLogin, onSignOut, onDeleteAccount)

        SectionTitle(stringResource(Res.string.section_basic))
        NeuTextField(
            profile.name,
            { value -> onEdit { it.copy(name = value.take(120)) } },
            stringResource(Res.string.field_name),
            stringResource(Res.string.field_name_hint),
        )
        Spacer(Modifier.height(20.dp))
        FieldLabel(stringResource(Res.string.user_type))
        NeuSegmented(
            options = UserType.entries,
            selected = UserType.entries.firstOrNull { it.wire == profile.userType } ?: UserType.Rider,
            label = { stringResource(it.label) },
            onSelect = { type -> onEdit { it.copy(userType = type.wire) } },
        )

        SectionTitle(stringResource(Res.string.section_favorites))
        val hint = stringResource(Res.string.favorite_hint)
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            NeuTextField(
                profile.favorites.home,
                { v -> onEdit { it.copy(favorites = it.favorites.copy(home = v.take(120))) } },
                stringResource(Res.string.favorite_home), hint,
            )
            NeuTextField(
                profile.favorites.center,
                { v -> onEdit { it.copy(favorites = it.favorites.copy(center = v.take(120))) } },
                stringResource(Res.string.favorite_center), hint,
            )
            NeuTextField(
                profile.favorites.therapy,
                { v -> onEdit { it.copy(favorites = it.favorites.copy(therapy = v.take(120))) } },
                stringResource(Res.string.favorite_therapy), hint,
            )
        }

        SectionTitle(stringResource(Res.string.section_assistance))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AssistanceSwitch(
                AppIcons.Accessibility,
                stringResource(Res.string.assist_wheelchair),
                stringResource(Res.string.assist_wheelchair_desc),
                profile.wheelchair,
            ) { v -> onEdit { it.copy(wheelchair = v) } }
            AssistanceSwitch(
                AppIcons.Eye,
                stringResource(Res.string.assist_low_vision),
                stringResource(Res.string.assist_low_vision_desc),
                profile.lowVision,
            ) { v -> onEdit { it.copy(lowVision = v) } }
            AssistanceSwitch(
                AppIcons.Walking,
                stringResource(Res.string.assist_mobility),
                stringResource(Res.string.assist_mobility_desc),
                profile.reducedMobility,
            ) { v -> onEdit { it.copy(reducedMobility = v) } }
        }

        Spacer(Modifier.height(32.dp))
        NeuButton(
            onClick = onSave,
            actionLabel = stringResource(Res.string.action_save_profile),
            modifier = Modifier.fillMaxWidth(),
            enabled = account.save != SendState.Sending,
        ) {
            Text(
                stringResource(Res.string.save_profile),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Neu.BlueStrong,
            )
        }
        when (account.save) {
            SendState.Sent -> MintChip(
                stringResource(Res.string.profile_saved),
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            SendState.Failed -> StatusBanner(
                stringResource(Res.string.profile_save_failed),
                Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp),
            )
            else -> Unit
        }

        SettingsSection(settings, onReviewPermissions)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AccountSection(
    account: AccountUiState,
    onOpenLogin: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    SectionTitle(stringResource(Res.string.account_section))
    Column(Modifier.fillMaxWidth().neuCard().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (account.kind != SessionKind.Rider) {
            Text(stringResource(Res.string.account_anonymous), fontSize = 15.sp, color = Neu.Text, lineHeight = 20.sp)
            if (account.available) {
                NeuButton(
                    onClick = onOpenLogin,
                    actionLabel = stringResource(Res.string.action_open_login),
                    modifier = Modifier.fillMaxWidth(),
                    shape = PillShape,
                ) {
                    Text(stringResource(Res.string.account_signin_button), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Neu.BlueStrong)
                }
            }
            return@Column
        }
        Text(
            stringResource(Res.string.account_signed_in, account.email),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Neu.Text,
        )
        account.error?.let { StatusBanner(accountErrorText(it)) }
        if (!confirmingDelete) {
            NeuButton(
                onClick = onSignOut,
                actionLabel = stringResource(Res.string.action_signout),
                modifier = Modifier.fillMaxWidth(),
                shape = PillShape,
            ) {
                Text(stringResource(Res.string.account_signout), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Neu.BlueStrong)
            }
            NeuButton(
                onClick = { confirmingDelete = true },
                actionLabel = stringResource(Res.string.action_delete),
                modifier = Modifier.fillMaxWidth(),
                shape = PillShape,
            ) {
                Text(stringResource(Res.string.account_delete), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AlertColors.Danger)
            }
        } else {
            Text(
                stringResource(Res.string.account_delete_confirm),
                fontSize = 15.sp,
                color = Neu.Text,
                lineHeight = 20.sp,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NeuButton(
                    onClick = { confirmingDelete = false },
                    actionLabel = stringResource(Res.string.action_delete_no),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(Res.string.account_delete_no), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Neu.TextMuted)
                }
                NeuButton(
                    onClick = { confirmingDelete = false; onDeleteAccount() },
                    actionLabel = stringResource(Res.string.action_delete_yes),
                    modifier = Modifier.weight(1f),
                    color = AlertColors.Danger,
                    enabled = !account.busy,
                ) {
                    Text(stringResource(Res.string.account_delete_yes), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AlertColors.OnDanger)
                }
            }
        }
    }
}

/** Whole row is the switch, so TalkBack reads title, description and "on/off" as one control. */
@Composable
private fun AssistanceSwitch(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .neuCard(distance = 6.dp)
            .clip(CardShape)
            .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).neuInset(CircleShape, 3.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (checked) Neu.BlueStrong else Neu.TextMuted, modifier = Modifier.size(26.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Neu.Text)
            Text(description, fontSize = 14.sp, color = Neu.TextMuted, lineHeight = 19.sp)
        }
        Spacer(Modifier.width(4.dp))
        NeuSwitch(checked)
    }
}
