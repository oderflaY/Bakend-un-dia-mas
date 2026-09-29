package com.eter.rutacree.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eter.rutacree.platform.AppPermission
import com.eter.rutacree.platform.DeviceServices
import com.eter.rutacree.platform.PermissionAction
import com.eter.rutacree.platform.PermissionNeed
import com.eter.rutacree.platform.missingPermissions
import com.eter.rutacree.platform.permissionAction
import com.eter.rutacree.ui.AppIcons
import com.eter.rutacree.ui.Neu
import com.eter.rutacree.ui.NeuButton
import com.eter.rutacree.ui.PillShape
import com.eter.rutacree.ui.neuInset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.compose.resources.stringResource
import rutacree.shared.generated.resources.Res
import rutacree.shared.generated.resources.action_allow_permission
import rutacree.shared.generated.resources.action_not_now
import rutacree.shared.generated.resources.action_open_settings
import rutacree.shared.generated.resources.perm_allow
import rutacree.shared.generated.resources.perm_blocked_hint
import rutacree.shared.generated.resources.perm_location
import rutacree.shared.generated.resources.perm_location_desc_driver
import rutacree.shared.generated.resources.perm_location_desc_passenger
import rutacree.shared.generated.resources.perm_not_now
import rutacree.shared.generated.resources.perm_notifications
import rutacree.shared.generated.resources.perm_notifications_desc
import rutacree.shared.generated.resources.perm_notifications_desc_driver
import rutacree.shared.generated.resources.perm_open_settings
import rutacree.shared.generated.resources.perm_overlay
import rutacree.shared.generated.resources.perm_overlay_desc
import kotlin.coroutines.resume

/**
 * Asks for permissions at the moment a feature needs them: one explanation per missing permission,
 * then the system prompt (or the settings page when the system no longer asks). Whatever the answer,
 * the feature goes on afterwards with what it was given.
 */
@Stable
class PermissionAsker(private val services: DeviceServices, private val scope: CoroutineScope) {

    class Prompt(
        val permission: AppPermission,
        val need: PermissionNeed,
        val action: PermissionAction,
        internal val answer: CompletableDeferred<Boolean>,
    )

    var prompt by mutableStateOf<Prompt?>(null)
        private set

    private var job: Job? = null

    /** Runs [then] once every missing permission of [need] was asked for (at once when none is missing). */
    fun ask(need: PermissionNeed, then: () -> Unit = {}) {
        if (job?.isActive == true) return
        job = scope.launch {
            for (permission in missingPermissions(need, services.permissions.value)) {
                // Re-read: answering the previous prompt may have changed this one too.
                val action = permissionAction(services.permissions.value[permission])
                if (action == PermissionAction.None) continue
                val answer = CompletableDeferred<Boolean>()
                prompt = Prompt(permission, need, action, answer)
                val allow = try {
                    answer.await()
                } finally {
                    prompt = null
                }
                if (allow) awaitSystem(permission, action)
            }
            then()
        }
    }

    fun answer(allow: Boolean) {
        prompt?.answer?.complete(allow)
    }

    private suspend fun awaitSystem(permission: AppPermission, action: PermissionAction) =
        suspendCancellableCoroutine { continuation ->
            val done = { if (continuation.isActive) continuation.resume(Unit) }
            if (action == PermissionAction.OpenSettings) services.openAppSettings(done)
            else services.requestPermission(permission, done)
        }
}

/** The explanation shown before the system prompt; cannot be dismissed by tapping outside. */
@Composable
fun PermissionPromptDialog(prompt: PermissionAsker.Prompt, onAnswer: (Boolean) -> Unit) {
    val (icon, title, description) = permissionTexts(prompt.permission, driver = prompt.need == PermissionNeed.DriverShift)
    val openSettings = prompt.action == PermissionAction.OpenSettings
    Dialog(onDismissRequest = { onAnswer(false) }, properties = DialogProperties(dismissOnClickOutside = false)) {
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Neu.Bg)
                .padding(24.dp)
                .semantics { paneTitle = title },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(72.dp).neuInset(CircleShape, 4.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Neu.BlueStrong, modifier = Modifier.size(36.dp))
            }
            Column(
                Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Neu.Text, textAlign = TextAlign.Center)
                Text(description, fontSize = 16.sp, color = Neu.Text, textAlign = TextAlign.Center, lineHeight = 22.sp)
                if (openSettings) {
                    Text(
                        stringResource(Res.string.perm_blocked_hint),
                        fontSize = 15.sp,
                        color = Neu.TextMuted,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp,
                    )
                }
            }
            NeuButton(
                onClick = { onAnswer(true) },
                actionLabel = if (openSettings) stringResource(Res.string.action_open_settings)
                else stringResource(Res.string.action_allow_permission, title),
                modifier = Modifier.fillMaxWidth(),
                color = Neu.Blue,
                shape = PillShape,
            ) {
                Text(
                    stringResource(if (openSettings) Res.string.perm_open_settings else Res.string.perm_allow),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Neu.palette.onBlue,
                )
            }
            NeuButton(
                onClick = { onAnswer(false) },
                actionLabel = stringResource(Res.string.action_not_now),
                modifier = Modifier.fillMaxWidth(),
                shape = PillShape,
            ) {
                Text(stringResource(Res.string.perm_not_now), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Neu.TextMuted)
            }
        }
    }
}

@Composable
internal fun permissionTexts(permission: AppPermission, driver: Boolean) = when (permission) {
    AppPermission.Location -> Triple(
        AppIcons.MapPin,
        stringResource(Res.string.perm_location),
        stringResource(if (driver) Res.string.perm_location_desc_driver else Res.string.perm_location_desc_passenger),
    )
    AppPermission.Notifications -> Triple(
        AppIcons.Alert,
        stringResource(Res.string.perm_notifications),
        stringResource(if (driver) Res.string.perm_notifications_desc_driver else Res.string.perm_notifications_desc),
    )
    AppPermission.Overlay -> Triple(
        AppIcons.Bus,
        stringResource(Res.string.perm_overlay),
        stringResource(Res.string.perm_overlay_desc),
    )
}
