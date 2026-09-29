package com.eter.rutacree.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eter.rutacree.platform.AppPermission
import com.eter.rutacree.platform.PermissionAction
import com.eter.rutacree.platform.PermissionStatus
import com.eter.rutacree.platform.permissionAction
import com.eter.rutacree.settings.UserRole
import com.eter.rutacree.ui.CardShape
import com.eter.rutacree.ui.MintChip
import com.eter.rutacree.ui.Neu
import com.eter.rutacree.ui.NeuButton
import com.eter.rutacree.ui.PillShape
import com.eter.rutacree.ui.ScreenHeader
import com.eter.rutacree.ui.neuCard
import com.eter.rutacree.ui.neuInset
import com.eter.rutacree.ui.neuInsetShadow
import org.jetbrains.compose.resources.stringResource
import rutacree.shared.generated.resources.Res
import rutacree.shared.generated.resources.action_allow_permission
import rutacree.shared.generated.resources.action_continue
import rutacree.shared.generated.resources.perm_allow
import rutacree.shared.generated.resources.perm_continue
import rutacree.shared.generated.resources.perm_denied
import rutacree.shared.generated.resources.perm_granted
import rutacree.shared.generated.resources.perm_denied_retry
import rutacree.shared.generated.resources.perm_open_settings
import rutacree.shared.generated.resources.action_open_settings
import rutacree.shared.generated.resources.permissions_subtitle
import rutacree.shared.generated.resources.permissions_title

@Composable
fun PermissionsScreen(
    role: UserRole,
    statuses: Map<AppPermission, PermissionStatus>,
    onRequest: (AppPermission) -> Unit,
    onContinue: () -> Unit,
) {
    val permissions = buildList {
        add(AppPermission.Location)
        add(AppPermission.Notifications)
        // Only drivers need their ramp alert to appear over other apps.
        if (role == UserRole.Driver) add(AppPermission.Overlay)
    }.filter { statuses[it] != PermissionStatus.NotApplicable }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader(stringResource(Res.string.permissions_title), stringResource(Res.string.permissions_subtitle))
        permissions.forEach { permission ->
            PermissionCard(permission, role, statuses[permission] ?: PermissionStatus.NotRequested, onRequest)
        }
        Spacer(Modifier.height(8.dp))
        NeuButton(
            onClick = onContinue,
            actionLabel = stringResource(Res.string.action_continue),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.perm_continue), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Neu.BlueStrong)
        }
    }
}

@Composable
private fun PermissionCard(
    permission: AppPermission,
    role: UserRole,
    status: PermissionStatus,
    onRequest: (AppPermission) -> Unit,
) {
    val (icon, title, description) = permissionTexts(permission, driver = role == UserRole.Driver)
    Column(Modifier.fillMaxWidth().neuCard().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Box(Modifier.size(48.dp).neuInset(CircleShape, 3.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Neu.BlueStrong, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Neu.Text)
                Text(description, fontSize = 15.sp, color = Neu.TextMuted, lineHeight = 20.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (status == PermissionStatus.Granted) {
            MintChip(stringResource(Res.string.perm_granted))
            return@Column
        }
        val hint = when (status) {
            PermissionStatus.Denied -> Res.string.perm_denied_retry
            PermissionStatus.Blocked -> Res.string.perm_denied
            else -> null
        }
        hint?.let {
            Text(
                stringResource(it),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Neu.Text,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PillShape)
                    .neuInsetShadow(PillShape, 3.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
        val openSettings = permissionAction(status) == PermissionAction.OpenSettings
        NeuButton(
            onClick = { onRequest(permission) },
            actionLabel = if (openSettings) stringResource(Res.string.action_open_settings)
            else stringResource(Res.string.action_allow_permission, title),
            modifier = Modifier.fillMaxWidth(),
            shape = PillShape,
        ) {
            Text(
                stringResource(if (openSettings) Res.string.perm_open_settings else Res.string.perm_allow),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Neu.BlueStrong,
            )
        }
    }
}
