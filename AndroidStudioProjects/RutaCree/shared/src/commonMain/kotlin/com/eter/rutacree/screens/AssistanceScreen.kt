package com.eter.rutacree.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eter.rutacree.transit.RampFlow
import com.eter.rutacree.transit.RampUiState
import com.eter.rutacree.transit.TransitUiState
import com.eter.rutacree.ui.AlertColors
import org.jetbrains.compose.resources.pluralStringResource
import rutacree.shared.generated.resources.status_failed
import rutacree.shared.generated.resources.status_waiting
import rutacree.shared.generated.resources.status_waiting_no_drivers
import com.eter.rutacree.ui.AppIcons
import com.eter.rutacree.ui.Neu
import com.eter.rutacree.ui.NeuButton
import com.eter.rutacree.ui.PillShape
import com.eter.rutacree.ui.ScreenHeader
import com.eter.rutacree.ui.distanceText
import com.eter.rutacree.ui.neuCard
import com.eter.rutacree.ui.neuInset
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import rutacree.shared.generated.resources.Res
import rutacree.shared.generated.resources.action_cancel_request
import rutacree.shared.generated.resources.action_request_ramp
import rutacree.shared.generated.resources.assistance_title
import rutacree.shared.generated.resources.cancel_request
import rutacree.shared.generated.resources.cd_ramp_button
import rutacree.shared.generated.resources.cd_ramp_button_sent
import rutacree.shared.generated.resources.geofence_inside
import rutacree.shared.generated.resources.geofence_nearest
import rutacree.shared.generated.resources.geofence_no_gps
import rutacree.shared.generated.resources.geofence_none
import rutacree.shared.generated.resources.ramp_request
import rutacree.shared.generated.resources.ramp_requested
import rutacree.shared.generated.resources.status_notified
import rutacree.shared.generated.resources.status_sending

@Composable
fun AssistanceScreen(state: TransitUiState, onRequestRamp: () -> Unit, onCancel: () -> Unit) {
    val flow = state.ramp.flow
    // A failed request leaves the button raised so the rider can try again.
    val active = flow != RampFlow.Idle && flow != RampFlow.Failed

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScreenHeader(stringResource(Res.string.assistance_title))

        GeofenceBadge(state)

        Spacer(Modifier.height(48.dp))

        RampButton(
            active = active,
            onClick = onRequestRamp,
        )

        Spacer(Modifier.height(40.dp))

        AnimatedVisibility(flow != RampFlow.Idle, enter = fadeIn(), exit = fadeOut()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                StatusBar(state.ramp)
                Spacer(Modifier.height(28.dp))
                NeuButton(
                    onClick = onCancel,
                    actionLabel = stringResource(Res.string.action_cancel_request),
                    shape = PillShape,
                ) {
                    Icon(AppIcons.Close, null, tint = Neu.TextMuted, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(Res.string.cancel_request),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Neu.TextMuted,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/** "Estás en: …" when the rider is inside a stop's 150 m geofence; otherwise the nearest or chosen stop. */
@Composable
private fun GeofenceBadge(state: TransitUiState) {
    val geofence = state.geofence
    val text = when {
        geofence != null && geofence.inside -> stringResource(Res.string.geofence_inside, geofence.stop.name)
        geofence != null -> stringResource(
            Res.string.geofence_nearest,
            geofence.stop.name,
            distanceText(geofence.distanceMeters),
        )
        state.stop != null -> stringResource(Res.string.geofence_no_gps, state.stop.name)
        else -> stringResource(Res.string.geofence_none)
    }
    Row(
        Modifier
            .padding(top = 20.dp)
            .neuCard(PillShape, 5.dp)
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.MapPin, null, tint = Neu.BlueStrong, modifier = Modifier.size(20.dp))
        Text(
            text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Neu.Text,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun RampButton(active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val palette = Neu.palette
    val sunk = pressed || active
    val glowColor = palette.mint
    val activeIcon = if (palette.isDark) palette.mint else palette.blueOnBg
    val glow by animateFloatAsState(if (active) 1f else 0f)
    val iconColor by animateColorAsState(if (active) activeIcon else palette.mint)

    val description = stringResource(if (active) Res.string.cd_ramp_button_sent else Res.string.cd_ramp_button)
    val actionLabel = stringResource(Res.string.action_request_ramp)

    Box(
        Modifier
            .size(220.dp)
            .then(if (sunk) Modifier.neuInset(CircleShape, 8.dp) else Modifier.neuCard(CircleShape, 12.dp))
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !active,
                onClickLabel = actionLabel,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                contentDescription = description
                if (active) stateDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(120.dp)
                        .alpha(glow)
                        .background(
                            Brush.radialGradient(listOf(glowColor.copy(alpha = 0.6f), Color.Transparent)),
                            CircleShape,
                        )
                )
                Icon(AppIcons.Accessibility, null, tint = iconColor, modifier = Modifier.size(88.dp))
            }
            Text(
                stringResource(if (active) Res.string.ramp_requested else Res.string.ramp_request),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = Neu.Text,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
                // Already covered by the button's contentDescription.
                modifier = Modifier.widthIn(max = 130.dp).clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun StatusBar(ramp: RampUiState) {
    val waiting = ramp.flow == RampFlow.Sending || ramp.flow == RampFlow.WaitingDriver
    val text = when (ramp.flow) {
        RampFlow.Sending -> stringResource(Res.string.status_sending)
        RampFlow.WaitingDriver -> if (ramp.driversNotified > 0) {
            pluralStringResource(Res.plurals.status_waiting, ramp.driversNotified, ramp.driversNotified)
        } else {
            stringResource(Res.string.status_waiting_no_drivers)
        }
        RampFlow.Acknowledged -> stringResource(Res.string.status_notified)
        RampFlow.Failed, RampFlow.Idle -> stringResource(Res.string.status_failed)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .neuInset()
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            waiting -> CircularProgressIndicator(Modifier.size(24.dp), color = Neu.MintText, strokeWidth = 3.dp)
            ramp.flow == RampFlow.Acknowledged ->
                Box(Modifier.size(28.dp).background(Neu.palette.chipBg, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.Check, null, tint = Neu.palette.chipText, modifier = Modifier.size(18.dp))
                }
            else -> Box(Modifier.size(28.dp).background(AlertColors.Warning, CircleShape), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Alert, null, tint = AlertColors.OnWarning, modifier = Modifier.size(18.dp))
            }
        }
        Text(
            text,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (ramp.flow == RampFlow.Acknowledged) Neu.MintText else Neu.Text,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}
