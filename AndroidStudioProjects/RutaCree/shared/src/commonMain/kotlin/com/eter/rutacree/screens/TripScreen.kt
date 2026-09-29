package com.eter.rutacree.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eter.rutacree.transit.Stop
import com.eter.rutacree.transit.TransitUiState
import com.eter.rutacree.transit.TripProgress
import com.eter.rutacree.transit.TripStage
import com.eter.rutacree.ui.AppIcons
import com.eter.rutacree.ui.CardShape
import com.eter.rutacree.ui.FieldLabel
import com.eter.rutacree.ui.Neu
import com.eter.rutacree.ui.NeuButton
import com.eter.rutacree.ui.PillShape
import com.eter.rutacree.ui.ScreenHeader
import com.eter.rutacree.ui.StatusBanner
import com.eter.rutacree.ui.distanceText
import com.eter.rutacree.ui.neuCard
import com.eter.rutacree.ui.neuInset
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import rutacree.shared.generated.resources.Res
import rutacree.shared.generated.resources.action_choose_destination
import rutacree.shared.generated.resources.action_end_trip
import rutacree.shared.generated.resources.action_start_trip
import rutacree.shared.generated.resources.offline_banner
import rutacree.shared.generated.resources.trip_choose_destination
import rutacree.shared.generated.resources.trip_destination
import rutacree.shared.generated.resources.trip_distance
import rutacree.shared.generated.resources.trip_end
import rutacree.shared.generated.resources.trip_from
import rutacree.shared.generated.resources.trip_no_destinations
import rutacree.shared.generated.resources.trip_remaining_stops
import rutacree.shared.generated.resources.trip_stage_arrived
import rutacree.shared.generated.resources.trip_stage_prepare
import rutacree.shared.generated.resources.trip_stage_riding
import rutacree.shared.generated.resources.trip_start
import rutacree.shared.generated.resources.trip_stop_destination
import rutacree.shared.generated.resources.trip_stop_next
import rutacree.shared.generated.resources.trip_stop_passed
import rutacree.shared.generated.resources.trip_subtitle
import rutacree.shared.generated.resources.trip_title
import rutacree.shared.generated.resources.trip_waiting

@Composable
fun TripScreen(state: TransitUiState, onStart: (String) -> Unit, onEnd: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        ScreenHeader(stringResource(Res.string.trip_title), stringResource(Res.string.trip_subtitle))
        if (!state.online) StatusBanner(stringResource(Res.string.offline_banner), Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(20.dp))
        val trip = state.trip
        if (trip == null) {
            DestinationPicker(state, onStart)
        } else {
            val progress = trip.progress
            if (progress == null) {
                Text(stringResource(Res.string.trip_waiting), fontSize = 18.sp, color = Neu.Text)
            } else {
                TripHeader(progress)
                Spacer(Modifier.height(24.dp))
                Timeline(progress)
            }
            Spacer(Modifier.height(28.dp))
            NeuButton(
                onClick = onEnd,
                actionLabel = stringResource(Res.string.action_end_trip),
                modifier = Modifier.fillMaxWidth(),
                shape = PillShape,
            ) {
                Icon(AppIcons.Close, null, tint = Neu.TextMuted, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(Res.string.trip_end),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Neu.TextMuted,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DestinationPicker(state: TransitUiState, onStart: (String) -> Unit) {
    val route = state.route ?: return
    val boarding = state.stop
    val destinations = route.stops.filter { it.accessible && it.alongMeters > (boarding?.alongMeters ?: -1.0) }
    var chosen by remember(route.id, boarding?.id) { mutableStateOf(destinations.lastOrNull()?.id) }
    val chooseLabel = stringResource(Res.string.action_choose_destination)

    boarding?.let {
        Text(stringResource(Res.string.trip_from, it.name), fontSize = 16.sp, color = Neu.TextMuted)
        Spacer(Modifier.height(16.dp))
    }
    FieldLabel(stringResource(Res.string.trip_choose_destination))
    if (destinations.isEmpty()) {
        Text(stringResource(Res.string.trip_no_destinations), fontSize = 16.sp, color = Neu.Text)
        return
    }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        destinations.forEach { stop ->
            val selected = stop.id == chosen
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .then(if (selected) Modifier.neuInset() else Modifier.neuCard(CardShape, 4.dp))
                    .clip(CardShape)
                    .selectable(selected, role = Role.RadioButton) { chosen = stop.id }
                    .semantics { onClick(label = chooseLabel) { chosen = stop.id; true } }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.MapPin, null, tint = if (selected) Neu.BlueStrong else Neu.TextMuted, modifier = Modifier.size(22.dp))
                Text(
                    stop.name,
                    fontSize = 17.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) Neu.BlueStrong else Neu.Text,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
    Spacer(Modifier.height(24.dp))
    NeuButton(
        onClick = { chosen?.let(onStart) },
        actionLabel = stringResource(Res.string.action_start_trip),
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
        color = Neu.Blue,
        enabled = chosen != null,
    ) {
        Text(
            stringResource(Res.string.trip_start),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Neu.palette.onBlue,
        )
    }
}

@Composable
private fun TripHeader(progress: TripProgress) {
    val stage = stringResource(
        when (progress.stage) {
            TripStage.Riding -> Res.string.trip_stage_riding
            TripStage.PrepareToExit -> Res.string.trip_stage_prepare
            TripStage.Arrived -> Res.string.trip_stage_arrived
        }
    )
    val highlight = progress.stage != TripStage.Riding
    Column(
        Modifier
            .fillMaxWidth()
            .neuCard()
            .then(if (highlight) Modifier.border(3.dp, Neu.Mint, CardShape) else Modifier)
            .padding(20.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
    ) {
        Text(stage, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = if (highlight) Neu.MintText else Neu.Text)
        Text(
            stringResource(Res.string.trip_destination, progress.destination.name),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = Neu.Text,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            stringResource(Res.string.trip_distance, distanceText(progress.distanceToDestinationMeters)) + " · " +
                pluralStringResource(Res.plurals.trip_remaining_stops, progress.remainingStops.size, progress.remainingStops.size),
            fontSize = 16.sp,
            color = Neu.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Vertical timeline: passed stops dimmed, next stop and destination emphasized. */
@Composable
private fun Timeline(progress: TripProgress) {
    val passedLabel = stringResource(Res.string.trip_stop_passed)
    val nextLabel = stringResource(Res.string.trip_stop_next)
    val destinationLabel = stringResource(Res.string.trip_stop_destination)
    val stops = progress.passedStops + progress.remainingStops
    Column {
        stops.forEachIndexed { index, stop ->
            val passed = stop in progress.passedStops
            val isNext = stop == progress.nextStop
            val isDestination = stop.id == progress.destination.id
            val status = when {
                isDestination -> destinationLabel
                passed -> passedLabel
                isNext -> nextLabel
                else -> null
            }
            TimelineRow(
                stop = stop,
                passed = passed,
                emphasized = isNext || isDestination,
                first = index == 0,
                last = index == stops.lastIndex,
                description = listOfNotNull(stop.name, status).joinToString(", "),
            )
        }
    }
}

@Composable
private fun TimelineRow(
    stop: Stop,
    passed: Boolean,
    emphasized: Boolean,
    first: Boolean,
    last: Boolean,
    description: String,
) {
    val lineColor = if (passed) Neu.TextMuted.copy(alpha = 0.4f) else Neu.Blue
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Box(Modifier.width(36.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(4.dp).weight(1f).background(if (first) Neu.Bg else lineColor))
                Box(Modifier.width(4.dp).weight(1f).background(if (last) Neu.Bg else lineColor))
            }
            Box(
                Modifier
                    .size(if (emphasized) 24.dp else 16.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            passed -> Neu.TextMuted.copy(alpha = 0.5f)
                            emphasized -> Neu.Mint
                            else -> Neu.Blue
                        }
                    )
            )
        }
        Text(
            stop.name,
            fontSize = if (emphasized) 20.sp else 17.sp,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium,
            color = if (passed) Neu.TextMuted else Neu.Text,
            modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 14.dp),
        )
    }
}
