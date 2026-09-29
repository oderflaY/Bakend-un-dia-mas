package com.eter.rutacree.driver

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.eter.rutacree.map.MapBus
import com.eter.rutacree.map.MapContent
import com.eter.rutacree.map.PlatformMap
import com.eter.rutacree.platform.LocalDeviceServices
import com.eter.rutacree.transit.AppConfig
import com.eter.rutacree.ui.InsetShape
import com.eter.rutacree.ui.neuInsetShadow
import rutacree.shared.generated.resources.driver_gps_waiting
import rutacree.shared.generated.resources.driver_on_route
import rutacree.shared.generated.resources.approach_title
import rutacree.shared.generated.resources.approach_join_stop
import rutacree.shared.generated.resources.approach_join_route
import rutacree.shared.generated.resources.approach_eta
import rutacree.shared.generated.resources.approach_straight
import rutacree.shared.generated.resources.approach_calculating
import rutacree.shared.generated.resources.approach_in
import rutacree.shared.generated.resources.approach_follow_line
import rutacree.shared.generated.resources.action_navigate
import rutacree.shared.generated.resources.action_navigate_label
import rutacree.shared.generated.resources.cd_driver_map
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eter.rutacree.api.TrackerStatus
import com.eter.rutacree.transit.RampRequest
import com.eter.rutacree.transit.Stop
import com.eter.rutacree.ui.AppIcons
import com.eter.rutacree.ui.formatDistance
import com.eter.rutacree.ui.formatMinutes
import com.eter.rutacree.ui.icon
import com.eter.rutacree.ui.stepInstruction
import com.eter.rutacree.ui.CardShape
import com.eter.rutacree.ui.FieldLabel
import com.eter.rutacree.ui.MintChip
import com.eter.rutacree.ui.Neu
import com.eter.rutacree.ui.NeuButton
import com.eter.rutacree.ui.NeuSegmented
import com.eter.rutacree.ui.NeuTextField
import com.eter.rutacree.ui.PillShape
import com.eter.rutacree.ui.ScreenHeader
import com.eter.rutacree.ui.SectionTitle
import com.eter.rutacree.ui.StatusBanner
import com.eter.rutacree.ui.neuCard
import com.eter.rutacree.ui.neuInset
import org.jetbrains.compose.resources.stringResource
import rutacree.shared.generated.resources.Res
import rutacree.shared.generated.resources.action_ack_ramp
import rutacree.shared.generated.resources.action_start_route
import rutacree.shared.generated.resources.cd_ramp_alert
import rutacree.shared.generated.resources.driver_accessible
import rutacree.shared.generated.resources.driver_assign_subtitle
import rutacree.shared.generated.resources.driver_assign_title
import rutacree.shared.generated.resources.driver_greeting
import rutacree.shared.generated.resources.driver_bus_number
import rutacree.shared.generated.resources.driver_bus_number_hint
import rutacree.shared.generated.resources.driver_console_title
import rutacree.shared.generated.resources.driver_last_end_completed
import rutacree.shared.generated.resources.driver_last_end_emergency
import rutacree.shared.generated.resources.driver_error_network
import rutacree.shared.generated.resources.driver_error_rejected
import rutacree.shared.generated.resources.driver_last_end_server
import rutacree.shared.generated.resources.driver_starting
import rutacree.shared.generated.resources.driver_missing_fields
import rutacree.shared.generated.resources.driver_next_stops
import rutacree.shared.generated.resources.driver_no_stops
import rutacree.shared.generated.resources.driver_reporting_ok
import rutacree.shared.generated.resources.driver_reporting_off
import rutacree.shared.generated.resources.driver_route
import rutacree.shared.generated.resources.driver_simulated
import rutacree.shared.generated.resources.driver_start
import rutacree.shared.generated.resources.ramp_alert_ack
import rutacree.shared.generated.resources.ramp_alert_title
import rutacree.shared.generated.resources.tracker_ok
import rutacree.shared.generated.resources.tracker_setup
import rutacree.shared.generated.resources.tracker_setup_frequency
import rutacree.shared.generated.resources.tracker_setup_id
import rutacree.shared.generated.resources.tracker_setup_url
import rutacree.shared.generated.resources.tracker_setup_optional
import rutacree.shared.generated.resources.tracker_no_position
import rutacree.shared.generated.resources.tracker_waiting_any
import rutacree.shared.generated.resources.tracker_source_app
import rutacree.shared.generated.resources.tracker_source_traccar
import rutacree.shared.generated.resources.tracker_title

@Composable
fun DriverShiftScreen(
    state: DriverUiState,
    config: AppConfig,
    reportsToServer: Boolean,
    onBusNumber: (String) -> Unit,
    onSelectRoute: (String) -> Unit,
    onStart: () -> Unit,
) {
    val shift = state.shift
    if (shift == null) {
        AssignmentForm(state, onBusNumber, onSelectRoute, onStart)
    } else {
        Console(shift, state, reportsToServer, config)
    }
}

/** The driver is already identified by the login: only today's bus and route are asked. */
@Composable
private fun AssignmentForm(
    state: DriverUiState,
    onBusNumber: (String) -> Unit,
    onSelectRoute: (String) -> Unit,
    onStart: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        ScreenHeader(
            stringResource(Res.string.driver_greeting, state.driverName),
            stringResource(Res.string.driver_assign_subtitle),
        )
        state.lastShiftEnd?.let { end ->
            val text = stringResource(
                when (end) {
                    ShiftEnd.Completed -> Res.string.driver_last_end_completed
                    ShiftEnd.Emergency -> Res.string.driver_last_end_emergency
                    ShiftEnd.ClosedByServer -> Res.string.driver_last_end_server
                }
            )
            if (end == ShiftEnd.Completed) MintChip(text, Modifier.padding(top = 14.dp))
            else StatusBanner(text, Modifier.padding(top = 14.dp))
        }

        SectionTitle(stringResource(Res.string.driver_assign_title))
        NeuTextField(
            value = state.busNumber,
            onValueChange = onBusNumber,
            label = stringResource(Res.string.driver_bus_number),
            placeholder = stringResource(Res.string.driver_bus_number_hint),
            keyboardType = KeyboardType.Number,
        )

        Spacer(Modifier.height(24.dp))
        FieldLabel(stringResource(Res.string.driver_route))
        NeuSegmented(
            options = state.routes.map { it.id },
            selected = state.selectedRouteId,
            label = { id -> state.routes.first { it.id == id }.name.substringBefore(" - ") },
            onSelect = onSelectRoute,
        )

        Spacer(Modifier.height(32.dp))
        NeuButton(
            onClick = onStart,
            actionLabel = stringResource(Res.string.action_start_route),
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            color = Neu.Blue,
            enabled = state.canStart,
        ) {
            if (state.starting) {
                CircularProgressIndicator(Modifier.size(26.dp), color = Neu.palette.onBlue, strokeWidth = 3.dp)
            } else {
                Icon(AppIcons.Bus, null, tint = Neu.palette.onBlue, modifier = Modifier.size(28.dp))
            }
            Text(
                stringResource(if (state.starting) Res.string.driver_starting else Res.string.driver_start),
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Neu.palette.onBlue,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        state.startError?.let { error ->
            StatusBanner(
                when (error) {
                    StartError.Network -> stringResource(Res.string.driver_error_network)
                    StartError.Rejected -> stringResource(Res.string.driver_error_rejected, state.startErrorMessage.orEmpty())
                },
                Modifier.padding(top = 14.dp),
            )
        }
        if (!state.canStart && !state.starting) {
            Text(
                stringResource(Res.string.driver_missing_fields),
                fontSize = 15.sp,
                color = Neu.TextMuted,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Glanceable while driving: the map on top shows where the bus is; the panel below says what to do
 * next — how to reach the route while away from it, then the upcoming stops.
 */
@Composable
private fun Console(shift: DriverShift, state: DriverUiState, reportsToServer: Boolean, config: AppConfig) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelMax = maxHeight * 0.52f
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
                DriverMap(shift, state, config, Modifier.fillMaxSize().clip(InsetShape))
                Box(Modifier.matchParentSize().neuInsetShadow(InsetShape, 6.dp))
                ConsoleHeader(shift, state, reportsToServer, Modifier.align(Alignment.TopStart).padding(12.dp))
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = panelMax)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val approach = state.approach
                when {
                    state.position == null -> StatusBanner(stringResource(Res.string.driver_gps_waiting))
                    approach != null -> ApproachCard(approach)
                    else -> NextStops(state.nextStops)
                }
                if (approach != null && state.nextStops.isNotEmpty()) NextStops(state.nextStops)
                state.tracker?.let { TrackerCard(it) }
            }
        }
    }
}

@Composable
private fun DriverMap(shift: DriverShift, state: DriverUiState, config: AppConfig, modifier: Modifier) {
    val position = state.position
    val approach = state.approach
    val description = stringResource(Res.string.cd_driver_map, shift.route.name)
    PlatformMap(
        config = config,
        content = MapContent(
            route = shift.route,
            stops = shift.route.stops,
            selectedStopId = approach?.targetStop?.id ?: state.nextStops.firstOrNull()?.id,
            buses = listOfNotNull(position?.let { MapBus(DRIVER_BUS_ID, it, highlighted = true) }),
            focus = position,
            zoom = FOLLOW_ZOOM,
            guidance = approach?.path.orEmpty(),
            // While approaching, keep both the bus and where it joins the route in view.
            frame = approach?.path.orEmpty(),
        ),
        // Visual only: the panel below says the same in words.
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
    )
}

private const val DRIVER_BUS_ID = -1L
private const val FOLLOW_ZOOM = 16.0

@Composable
private fun ConsoleHeader(shift: DriverShift, state: DriverUiState, reportsToServer: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier
                .neuCard(CardShape, 4.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                stringResource(Res.string.driver_console_title, shift.busNumber),
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Neu.Text,
                modifier = Modifier.semantics { heading() },
            )
            Text(shift.route.name, fontSize = 14.sp, color = Neu.TextMuted, maxLines = 1)
        }
        when {
            !reportsToServer -> Text(
                stringResource(Res.string.driver_simulated),
                fontSize = 13.sp,
                color = Neu.TextMuted,
                modifier = Modifier.background(Neu.Bg, PillShape).padding(horizontal = 12.dp, vertical = 6.dp),
            )
            !state.reporting -> StatusBanner(stringResource(Res.string.driver_reporting_off))
            state.approach == null && state.position != null -> MintChip(stringResource(Res.string.driver_on_route))
            else -> MintChip(stringResource(Res.string.driver_reporting_ok))
        }
    }
}

/** Turn-by-turn to the route: the next maneuver in large type, then where and how far. */
@Composable
private fun ApproachCard(approach: Approach) {
    val services = LocalDeviceServices.current
    val step = approach.nextStep
    Column(
        Modifier
            .fillMaxWidth()
            .neuCard()
            .padding(18.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(Res.string.approach_title).uppercase(),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = Neu.BlueStrong,
            modifier = Modifier.semantics { heading() },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).background(Neu.Blue, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(step?.maneuver.icon(approach.byStreet), null, tint = Neu.palette.onBlue, modifier = Modifier.size(38.dp))
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                val distance = approach.nextStepMeters
                if (step != null && distance != null) {
                    Text(
                        stringResource(Res.string.approach_in, formatDistance(distance)),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Neu.TextMuted,
                    )
                }
                Text(
                    instruction(approach),
                    fontSize = 24.sp,
                    lineHeight = 29.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Neu.Text,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Neu.palette.mapGrid))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    approach.targetStop?.let { stringResource(Res.string.approach_join_stop, it.name) }
                        ?: stringResource(Res.string.approach_join_route),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Neu.Text,
                )
                val distance = formatDistance(approach.remainingMeters)
                val seconds = approach.remainingSeconds
                Text(
                    when {
                        seconds != null -> stringResource(Res.string.approach_eta, distance, formatMinutes(seconds))
                        else -> stringResource(Res.string.approach_straight, distance)
                    },
                    fontSize = 15.sp,
                    color = Neu.TextMuted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            NeuButton(
                onClick = { services.openNavigation(approach.target) },
                actionLabel = stringResource(Res.string.action_navigate_label),
                color = Neu.Blue,
                shape = PillShape,
                modifier = Modifier.padding(start = 12.dp),
            ) {
                Icon(AppIcons.Navigate, null, tint = Neu.palette.onBlue, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(Res.string.action_navigate),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Neu.palette.onBlue,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun instruction(approach: Approach): String {
    val step = approach.nextStep
    if (!approach.byStreet) return stringResource(Res.string.approach_calculating)
    if (step == null) return stringResource(Res.string.approach_follow_line)
    return stepInstruction(step)
}

@Composable
private fun NextStops(stops: List<Stop>) {
    Column(
        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(Res.string.driver_next_stops).uppercase(),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = Neu.TextMuted,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp).semantics { heading() },
        )
        if (stops.isEmpty()) {
            Text(stringResource(Res.string.driver_no_stops), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Neu.Text)
        }
        stops.forEachIndexed { index, stop -> NextStopRow(stop, first = index == 0) }
    }
}

private const val TRACKER_STALE_S = 60

/**
 * Tells the driver whether the bus position is reaching the server and from where. Traccar Client
 * keeps reporting with the screen off, so it's recommended; without it this app's GPS covers, and
 * that is not an error: riders still see the bus.
 */
@Composable
private fun TrackerCard(tracker: TrackerStatus) {
    val traccarAge = tracker.lastFixAgeSeconds
    val busAge = tracker.busFixAgeSeconds
    val viaTraccar = traccarAge != null && traccarAge < TRACKER_STALE_S
    val viaApp = !viaTraccar && busAge != null && busAge < TRACKER_STALE_S
    Column(
        Modifier
            .fillMaxWidth()
            .neuInset()
            .padding(16.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(Res.string.tracker_title), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Neu.Text)
        when {
            viaTraccar -> {
                Text(stringResource(Res.string.tracker_source_traccar), fontSize = 14.sp, color = Neu.TextMuted)
                MintChip(stringResource(Res.string.tracker_ok, traccarAge ?: 0))
            }
            viaApp -> {
                Text(stringResource(Res.string.tracker_source_app), fontSize = 14.sp, color = Neu.TextMuted)
                MintChip(stringResource(Res.string.tracker_ok, busAge ?: 0))
            }
            busAge != null -> StatusBanner(stringResource(Res.string.tracker_no_position, busAge))
            else -> StatusBanner(stringResource(Res.string.tracker_waiting_any))
        }
        if (!viaTraccar) {
            Text(
                stringResource(if (viaApp) Res.string.tracker_setup_optional else Res.string.tracker_setup),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Neu.Text,
                modifier = Modifier.padding(top = 6.dp),
            )
            tracker.trackerDeviceId?.let {
                Text(stringResource(Res.string.tracker_setup_id, it), fontSize = 15.sp, color = Neu.Text)
            }
            tracker.trackerUrl?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(Res.string.tracker_setup_url, it), fontSize = 15.sp, color = Neu.Text)
            }
            Text(stringResource(Res.string.tracker_setup_frequency), fontSize = 15.sp, color = Neu.Text)
        }
    }
}

@Composable
private fun NextStopRow(stop: Stop, first: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (first) Modifier.neuCard() else Modifier.neuInset())
            .padding(horizontal = 18.dp, vertical = if (first) 18.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stop.name,
            fontSize = if (first) 28.sp else 21.sp,
            lineHeight = if (first) 34.sp else 26.sp,
            fontWeight = if (first) FontWeight.ExtraBold else FontWeight.Bold,
            color = if (first) Neu.Text else Neu.TextMuted,
            modifier = Modifier.weight(1f),
        )
        if (stop.accessible) {
            Row(
                Modifier.background(Neu.palette.chipBg, PillShape).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Accessibility, null, tint = Neu.palette.chipText, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(Res.string.driver_accessible),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Neu.palette.chipText,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

/**
 * Full-screen ramp alert: the frame pulses and a dialog covering ~70% of the screen shows the stop.
 * Sound and vibration are started by the caller while this is visible.
 */
@Composable
fun RampAlertOverlay(request: RampRequest, onAcknowledge: () -> Unit) {
    val pulse by rememberInfiniteTransition().animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
    )
    val description = stringResource(Res.string.cd_ramp_alert, request.stopName)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .border(10.dp, Neu.Mint.copy(alpha = pulse))
            // Swallows touches so nothing behind the alert is activated by accident.
            .clickable(enabled = false) {}
            .semantics { paneTitle = description },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Neu.Bg)
                .border(6.dp, Neu.Mint.copy(alpha = 0.5f + pulse / 2), RoundedCornerShape(28.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            Box(Modifier.size(96.dp).background(Neu.Mint, CircleShape), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Accessibility, null, tint = Neu.palette.onBlue, modifier = Modifier.size(60.dp))
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = description
                    liveRegion = LiveRegionMode.Assertive
                },
            ) {
                Text(
                    stringResource(Res.string.ramp_alert_title),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Neu.Text,
                    textAlign = TextAlign.Center,
                )
                Text(
                    request.stopName,
                    fontSize = 36.sp,
                    lineHeight = 42.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Neu.MintText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            NeuButton(
                onClick = onAcknowledge,
                actionLabel = stringResource(Res.string.action_ack_ramp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                color = Neu.Mint,
            ) {
                Text(
                    stringResource(Res.string.ramp_alert_ack),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Neu.palette.onBlue,
                )
            }
        }
    }
}
