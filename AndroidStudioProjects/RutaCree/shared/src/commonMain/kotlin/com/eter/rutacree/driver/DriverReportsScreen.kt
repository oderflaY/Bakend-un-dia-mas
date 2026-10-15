package com.eter.rutacree.driver

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eter.rutacree.transit.IncidentType
import com.eter.rutacree.ui.AlertColors
import com.eter.rutacree.ui.AppIcons
import com.eter.rutacree.ui.CardShape
import com.eter.rutacree.ui.FieldLabel
import com.eter.rutacree.ui.Neu
import com.eter.rutacree.ui.NeuButton
import com.eter.rutacree.ui.NeuSegmented
import com.eter.rutacree.ui.PillShape
import com.eter.rutacree.ui.ScreenHeader
import com.eter.rutacree.ui.SectionTitle
import com.eter.rutacree.ui.neuCard
import com.eter.rutacree.ui.neuInset
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import rutacree.shared.generated.resources.Res
import rutacree.shared.generated.resources.action_cancel_end
import rutacree.shared.generated.resources.action_confirm_end
import rutacree.shared.generated.resources.action_end_shift
import rutacree.shared.generated.resources.action_panic
import rutacree.shared.generated.resources.cd_panic_slider
import rutacree.shared.generated.resources.driver_no_shift
import rutacree.shared.generated.resources.driver_reports_title
import rutacree.shared.generated.resources.end_cancel
import rutacree.shared.generated.resources.end_confirm
import rutacree.shared.generated.resources.end_confirm_title
import rutacree.shared.generated.resources.end_section
import rutacree.shared.generated.resources.end_shift
import rutacree.shared.generated.resources.final_depot
import rutacree.shared.generated.resources.final_terminal
import rutacree.shared.generated.resources.final_workshop
import rutacree.shared.generated.resources.panic_section
import rutacree.shared.generated.resources.panic_slider
import rutacree.shared.generated.resources.panic_type_accident
import rutacree.shared.generated.resources.panic_type_breakdown
import rutacree.shared.generated.resources.action_ramp_failure
import rutacree.shared.generated.resources.ramp_failure_button
import rutacree.shared.generated.resources.ramp_failure_desc
import rutacree.shared.generated.resources.ramp_failure_done
import rutacree.shared.generated.resources.ramp_failure_section
import com.eter.rutacree.ui.MintChip

private val IncidentType.label: StringResource
    get() = when (this) {
        IncidentType.Accident -> Res.string.panic_type_accident
        IncidentType.Breakdown, IncidentType.RampFailure -> Res.string.panic_type_breakdown
    }

/** These end the route; a ramp failure has its own button because the bus keeps going. */
private val emergencyTypes = listOf(IncidentType.Breakdown, IncidentType.Accident)

private val FinalLocation.label: StringResource
    get() = when (this) {
        FinalLocation.Terminal -> Res.string.final_terminal
        FinalLocation.Workshop -> Res.string.final_workshop
        FinalLocation.Depot -> Res.string.final_depot
    }

@Composable
fun DriverReportsScreen(
    state: DriverUiState,
    onFailure: (IncidentType) -> Unit,
    onEndShift: (FinalLocation) -> Unit,
) {
    var emergencyType by remember { mutableStateOf(IncidentType.Breakdown) }
    var confirmingEnd by remember { mutableStateOf(false) }
    var finalLocation by remember { mutableStateOf(FinalLocation.Terminal) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        ScreenHeader(stringResource(Res.string.driver_reports_title))
        if (state.shift == null) {
            Text(
                stringResource(Res.string.driver_no_shift),
                fontSize = 18.sp,
                color = Neu.Text,
                modifier = Modifier.padding(top = 20.dp),
            )
            return@Column
        }

        SectionTitle(stringResource(Res.string.ramp_failure_section))
        if (state.shift.rampFailureReported) {
            MintChip(stringResource(Res.string.ramp_failure_done))
        } else {
            Text(stringResource(Res.string.ramp_failure_desc), fontSize = 16.sp, color = Neu.TextMuted)
            Spacer(Modifier.height(12.dp))
            NeuButton(
                onClick = { onFailure(IncidentType.RampFailure) },
                actionLabel = stringResource(Res.string.action_ramp_failure),
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            ) {
                Icon(AppIcons.Accessibility, null, tint = AlertColors.Danger, modifier = Modifier.size(26.dp))
                Text(
                    stringResource(Res.string.ramp_failure_button),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = Neu.Text,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }

        SectionTitle(stringResource(Res.string.panic_section))
        NeuSegmented(emergencyTypes, emergencyType, { stringResource(it.label) }, { emergencyType = it })
        Spacer(Modifier.height(20.dp))
        SlideToConfirm(
            text = stringResource(Res.string.panic_slider),
            description = stringResource(Res.string.cd_panic_slider, stringResource(emergencyType.label)),
            actionLabel = stringResource(Res.string.action_panic),
            onConfirm = { onFailure(emergencyType) },
        )

        SectionTitle(stringResource(Res.string.end_section))
        if (!confirmingEnd) {
            NeuButton(
                onClick = { confirmingEnd = true },
                actionLabel = stringResource(Res.string.action_end_shift),
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            ) {
                Text(stringResource(Res.string.end_shift), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Neu.BlueStrong)
            }
        } else {
            FieldLabel(stringResource(Res.string.end_confirm_title))
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FinalLocation.entries.forEach { location ->
                    val selected = location == finalLocation
                    Text(
                        stringResource(location.label),
                        fontSize = 18.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) Neu.BlueStrong else Neu.Text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .then(if (selected) Modifier.neuInset() else Modifier.neuCard(CardShape, 4.dp))
                            .clip(CardShape)
                            .selectable(selected, role = Role.RadioButton) { finalLocation = location }
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row {
                NeuButton(
                    onClick = { confirmingEnd = false },
                    actionLabel = stringResource(Res.string.action_cancel_end),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(Res.string.end_cancel), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Neu.TextMuted)
                }
                Spacer(Modifier.size(14.dp))
                NeuButton(
                    onClick = { onEndShift(finalLocation); confirmingEnd = false },
                    actionLabel = stringResource(Res.string.action_confirm_end),
                    modifier = Modifier.weight(1f),
                    color = Neu.Blue,
                ) {
                    Text(
                        stringResource(Res.string.end_confirm),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Neu.palette.onBlue,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private const val CONFIRM_FRACTION = 0.9f

/**
 * Red "slide to confirm" control: the deliberate drag prevents accidental emergency reports.
 * Screen-reader users get the same action through a labelled double-tap.
 */
@Composable
private fun SlideToConfirm(text: String, description: String, actionLabel: String, onConfirm: () -> Unit) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val thumb = 64.dp
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(PillShape)
            .background(AlertColors.Danger.copy(alpha = 0.18f))
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = actionLabel) { onConfirm(); true }
            },
    ) {
        val maxPx = with(LocalDensity.current) { (maxWidth - thumb - 12.dp).toPx() }
        Text(
            text,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Neu.Text,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(start = thumb + 12.dp, end = 12.dp),
        )
        Box(
            Modifier
                .padding(6.dp)
                .offset { IntOffset(offset.value.toInt(), 0) }
                .size(thumb)
                .background(AlertColors.Danger, CircleShape)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { offset.snapTo((offset.value + delta).coerceIn(0f, maxPx)) }
                    },
                    onDragStopped = {
                        if (offset.value >= maxPx * CONFIRM_FRACTION) {
                            offset.snapTo(0f)
                            onConfirm()
                        } else {
                            offset.animateTo(0f)
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Alert, null, tint = AlertColors.OnDanger, modifier = Modifier.size(30.dp))
        }
    }
}
