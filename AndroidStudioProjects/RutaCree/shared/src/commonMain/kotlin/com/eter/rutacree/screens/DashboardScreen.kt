package com.eter.rutacree.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eter.rutacree.map.CITY_CENTER
import com.eter.rutacree.map.CITY_ZOOM
import com.eter.rutacree.map.DirectionLegend
import com.eter.rutacree.map.MapContent
import com.eter.rutacree.map.PlatformMap
import com.eter.rutacree.map.rememberAnimatedBuses
import com.eter.rutacree.platform.LocalDeviceServices
import com.eter.rutacree.transit.AppConfig
import com.eter.rutacree.transit.Arrival
import com.eter.rutacree.transit.Compass
import com.eter.rutacree.transit.Maneuver
import com.eter.rutacree.transit.Stop
import com.eter.rutacree.transit.TransitRoute
import com.eter.rutacree.transit.TransitUiState
import com.eter.rutacree.transit.WalkGuide
import com.eter.rutacree.transit.formatClock
import com.eter.rutacree.transit.minutesUntil
import com.eter.rutacree.transit.nextDepartures
import com.eter.rutacree.ui.AppIcons
import com.eter.rutacree.ui.CardShape
import com.eter.rutacree.ui.InsetShape
import com.eter.rutacree.ui.MintChip
import com.eter.rutacree.ui.Neu
import com.eter.rutacree.ui.NeuButton
import com.eter.rutacree.ui.NeuIconButton
import com.eter.rutacree.ui.MunicipalityPicker
import com.eter.rutacree.ui.PillShape
import com.eter.rutacree.ui.StatusBanner
import com.eter.rutacree.ui.busName
import com.eter.rutacree.ui.distanceText
import com.eter.rutacree.ui.formatDistance
import com.eter.rutacree.ui.formatMinutes
import com.eter.rutacree.ui.neuCard
import com.eter.rutacree.ui.neuInset
import com.eter.rutacree.ui.neuInsetShadow
import com.eter.rutacree.ui.stepInstruction
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import rutacree.shared.generated.resources.Res
import rutacree.shared.generated.resources.action_change_stop
import rutacree.shared.generated.resources.action_choose_stop
import rutacree.shared.generated.resources.action_collapse_details
import rutacree.shared.generated.resources.action_expand_details
import rutacree.shared.generated.resources.action_open_profile
import rutacree.shared.generated.resources.action_show_route
import rutacree.shared.generated.resources.action_walk
import rutacree.shared.generated.resources.action_walk_label
import rutacree.shared.generated.resources.app_name
import rutacree.shared.generated.resources.approach_in
import rutacree.shared.generated.resources.arrival_distance
import rutacree.shared.generated.resources.arrival_headline
import rutacree.shared.generated.resources.badge_live
import rutacree.shared.generated.resources.badge_simulated
import rutacree.shared.generated.resources.cd_badge_live
import rutacree.shared.generated.resources.cd_badge_simulated
import rutacree.shared.generated.resources.cd_map
import rutacree.shared.generated.resources.cd_open_profile
import rutacree.shared.generated.resources.compass_east
import rutacree.shared.generated.resources.compass_north
import rutacree.shared.generated.resources.compass_north_east
import rutacree.shared.generated.resources.compass_north_west
import rutacree.shared.generated.resources.compass_south
import rutacree.shared.generated.resources.compass_south_east
import rutacree.shared.generated.resources.compass_south_west
import rutacree.shared.generated.resources.compass_west
import rutacree.shared.generated.resources.countdown_next_bus
import rutacree.shared.generated.resources.countdown_none
import rutacree.shared.generated.resources.error_routes
import rutacree.shared.generated.resources.geofence_inside
import rutacree.shared.generated.resources.geofence_nearest
import rutacree.shared.generated.resources.geofence_no_gps
import rutacree.shared.generated.resources.loading_routes
import rutacree.shared.generated.resources.no_routes
import rutacree.shared.generated.resources.no_arrival
import rutacree.shared.generated.resources.offline_banner
import rutacree.shared.generated.resources.ramp_operational
import rutacree.shared.generated.resources.schedule_in_hours
import rutacree.shared.generated.resources.schedule_in_hours_exact
import rutacree.shared.generated.resources.schedule_in_minutes
import rutacree.shared.generated.resources.schedule_later
import rutacree.shared.generated.resources.schedule_next
import rutacree.shared.generated.resources.schedule_none
import rutacree.shared.generated.resources.schedule_now
import rutacree.shared.generated.resources.schedule_title
import rutacree.shared.generated.resources.stop_change
import rutacree.shared.generated.resources.stop_close
import rutacree.shared.generated.resources.stop_label
import rutacree.shared.generated.resources.stop_none
import rutacree.shared.generated.resources.walk_heading
import rutacree.shared.generated.resources.walk_summary

@Composable
fun DashboardScreen(
    config: AppConfig,
    state: TransitUiState,
    onSelectRoute: (String) -> Unit,
    onSelectStop: (String) -> Unit,
    onOpenProfile: () -> Unit,
    onSelectMunicipality: (String?) -> Unit = {},
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.app_name),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Neu.Text,
                    modifier = Modifier.semantics { heading() },
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Icon(AppIcons.MapPin, null, tint = Neu.BlueStrong, modifier = Modifier.size(16.dp))
                    Text(
                        locationLine(state),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 15.sp,
                        color = Neu.TextMuted,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            NeuIconButton(
                icon = AppIcons.User,
                contentDescription = stringResource(Res.string.cd_open_profile),
                actionLabel = stringResource(Res.string.action_open_profile),
                onClick = onOpenProfile,
            )
        }

        RouteSelector(state, onSelectRoute, onSelectMunicipality)

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
            val walk = state.walk?.takeUnless { it.arrived }
            val buses = rememberAnimatedBuses(state.route, state.buses, state.arrival?.bus?.position?.deviceId)
            val mapDescription = state.route?.let {
                pluralStringResource(Res.plurals.cd_map, buses.size, it.name, buses.size)
            }.orEmpty()
            PlatformMap(
                config = config,
                content = MapContent(
                    route = state.route,
                    stops = state.route?.stops?.filter { it.accessible }.orEmpty(),
                    selectedStopId = state.stop?.id,
                    buses = buses,
                    userLocation = state.userLocation,
                    focus = state.stop?.point ?: CITY_CENTER.takeIf { state.noRoutes },
                    zoom = CITY_ZOOM.takeIf { state.noRoutes },
                    // Walking to the stop: draw the way there and keep the rider and the stop in view.
                    guidance = walk?.path.orEmpty(),
                    frame = walk?.path.orEmpty(),
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .clip(InsetShape)
                    // The map is visual only; its content is summarized for screen readers.
                    .clearAndSetSemantics { contentDescription = mapDescription },
            )
            // Drawn over the map so it reads as sunk into the screen.
            Box(Modifier.matchParentSize().neuInsetShadow(InsetShape, 6.dp))
            Column(
                Modifier.align(Alignment.TopStart).fillMaxWidth().padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceBadge(state.live, Modifier.padding(start = 4.dp, top = 4.dp))
                    Spacer(Modifier.weight(1f))
                    DirectionLegend(state.route, Modifier.padding(end = 4.dp, top = 4.dp))
                }
                if (!state.online) StatusBanner(stringResource(Res.string.offline_banner))
                if (walk != null) WalkCard(walk)
            }
            ArrivalCard(
                state = state,
                onSelectStop = onSelectStop,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(10.dp)
                    // Never cover more than about half the map, whatever the screen size.
                    .heightIn(max = maxHeight * 0.55f),
            )
        }
    }
}

/** Which way to walk to the stop, how far it is and how long it takes, over the map. */
@Composable
private fun WalkCard(walk: WalkGuide) {
    val services = LocalDeviceServices.current
    val heading = stringResource(Res.string.walk_heading, compassName(walk.heading))
    val step = walk.nextStep?.takeIf { walk.byStreet && it.maneuver != Maneuver.Arrive }
    val stepDistance = walk.nextStepMeters
    Row(
        Modifier
            .fillMaxWidth()
            .neuCard()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The map is always north-up, so the arrow points the same way as the dotted line.
            Box(Modifier.size(48.dp).background(Neu.Blue, CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    AppIcons.ArrowStraight,
                    null,
                    tint = Neu.palette.onBlue,
                    modifier = Modifier.size(28.dp).rotate(walk.headingDegrees.toFloat()),
                )
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(heading, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Neu.Text)
                if (step != null && stepDistance != null) {
                    Text(
                        stringResource(Res.string.approach_in, formatDistance(stepDistance)) + " · " + stepInstruction(step),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Neu.BlueStrong,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    stringResource(
                        Res.string.walk_summary,
                        formatDistance(walk.remainingMeters),
                        formatMinutes(walk.remainingSeconds),
                        walk.stop.name,
                    ),
                    fontSize = 14.sp,
                    color = Neu.TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        NeuButton(
            onClick = { services.openNavigation(walk.stop.point, walking = true) },
            actionLabel = stringResource(Res.string.action_walk_label),
            color = Neu.Blue,
            shape = PillShape,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Icon(AppIcons.Walking, null, tint = Neu.palette.onBlue, modifier = Modifier.size(20.dp))
            Text(
                stringResource(Res.string.action_walk),
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Neu.palette.onBlue,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun compassName(compass: Compass): String = stringResource(
    when (compass) {
        Compass.North -> Res.string.compass_north
        Compass.NorthEast -> Res.string.compass_north_east
        Compass.East -> Res.string.compass_east
        Compass.SouthEast -> Res.string.compass_south_east
        Compass.South -> Res.string.compass_south
        Compass.SouthWest -> Res.string.compass_south_west
        Compass.West -> Res.string.compass_west
        Compass.NorthWest -> Res.string.compass_north_west
    }
)

/** Where the rider is right now: inside a stop's geofence, near one, or the stop they picked. */
@Composable
private fun locationLine(state: TransitUiState): String {
    val geofence = state.geofence
    return when {
        geofence != null && geofence.inside -> stringResource(Res.string.geofence_inside, geofence.stop.name)
        geofence != null -> stringResource(Res.string.geofence_nearest, geofence.stop.name, distanceText(geofence.distanceMeters))
        state.stop != null -> stringResource(Res.string.geofence_no_gps, state.stop.name)
        state.noRoutes -> stringResource(Res.string.no_routes)
        else -> stringResource(Res.string.loading_routes)
    }
}

@Composable
private fun RouteSelector(state: TransitUiState, onSelectRoute: (String) -> Unit, onSelectMunicipality: (String?) -> Unit) {
    val showRoute = stringResource(Res.string.action_show_route)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MunicipalityPicker(state.municipalities, state.municipality, onSelectMunicipality)
        state.routes.forEach { route ->
            val selected = route.id == state.route?.id
            Text(
                route.shortName(),
                fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Neu.BlueStrong else Neu.Text,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .then(if (selected) Modifier.neuInset(PillShape, 3.dp) else Modifier.neuCard(PillShape, 5.dp))
                    .clip(PillShape)
                    .selectable(selected, role = Role.Tab) { onSelectRoute(route.id) }
                    .semantics {
                        contentDescription = route.name
                        onClick(label = showRoute) { onSelectRoute(route.id); true }
                    }
                    .padding(horizontal = 22.dp, vertical = 13.dp),
            )
        }
    }
}

private fun TransitRoute.shortName() = name.substringBefore(" - ")

@Composable
private fun SourceBadge(live: Boolean, modifier: Modifier) {
    val description = stringResource(if (live) Res.string.cd_badge_live else Res.string.cd_badge_simulated)
    Row(
        modifier
            .neuCard(PillShape, 4.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(if (live) Neu.MintText else Neu.TextMuted, CircleShape))
        Text(
            stringResource(if (live) Res.string.badge_live else Res.string.badge_simulated),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Neu.Text,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun ArrivalCard(state: TransitUiState, onSelectStop: (String) -> Unit, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var choosingStop by remember { mutableStateOf(false) }
    Column(modifier.neuCard().padding(horizontal = 16.dp, vertical = 14.dp)) {
        when {
            state.loadFailed -> Text(stringResource(Res.string.error_routes), fontSize = 16.sp, color = Neu.Text)
            state.noRoutes -> Text(stringResource(Res.string.no_routes), fontSize = 16.sp, color = Neu.Text)
            state.loading || state.route == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Neu.BlueStrong, strokeWidth = 3.dp)
                Text(
                    stringResource(Res.string.loading_routes),
                    fontSize = 16.sp,
                    color = Neu.Text,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            else -> {
                val toggleLabel = stringResource(
                    if (expanded) Res.string.action_collapse_details else Res.string.action_expand_details
                )
                // Collapsed: one compact row with the ETA, so the map stays visible.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(InsetShape)
                        .clickable(onClickLabel = toggleLabel, role = Role.Button) {
                            expanded = !expanded
                            if (!expanded) choosingStop = false
                        }
                        .semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp).neuCard(CircleShape, 3.dp), contentAlignment = Alignment.Center) {
                        Icon(AppIcons.Bus, null, tint = Neu.Blue, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.route.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Neu.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        ArrivalHeadline(state.arrival, state.stop)
                    }
                    Icon(
                        if (expanded) AppIcons.ChevronDown else AppIcons.ChevronUp,
                        null,
                        tint = Neu.BlueStrong,
                        modifier = Modifier.padding(start = 8.dp).size(28.dp),
                    )
                }
                AnimatedVisibility(expanded) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 12.dp)) {
                        Countdown(state.arrival)
                        state.arrival?.let { arrival ->
                            Text(
                                "${busName(arrival.bus.position)} " +
                                    stringResource(Res.string.arrival_distance, distanceText(arrival.distanceMeters)),
                                fontSize = 15.sp,
                                color = Neu.TextMuted,
                            )
                            MintChip(stringResource(Res.string.ramp_operational), Modifier.padding(vertical = 12.dp))
                        }
                        state.stop?.let { Schedule(it, state.nowMinuteOfDay) }
                        Spacer(Modifier.height(12.dp))
                        StopPicker(
                            stops = state.route.stops.filter { it.accessible },
                            selected = state.stop,
                            expanded = choosingStop,
                            onToggle = { choosingStop = !choosingStop },
                            onSelect = { onSelectStop(it); choosingStop = false; expanded = true },
                        )
                    }
                }
            }
        }
    }
}

/** Live countdown driven by the along-route ETA, in large type. */
@Composable
private fun Countdown(arrival: Arrival?) {
    Text(
        if (arrival == null) stringResource(Res.string.countdown_none)
        else pluralStringResource(Res.plurals.countdown_next_bus, arrival.minutes, arrival.minutes),
        fontSize = 24.sp,
        fontWeight = FontWeight.ExtraBold,
        color = if (arrival == null) Neu.Text else Neu.BlueStrong,
        modifier = Modifier.padding(bottom = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun Schedule(stop: Stop, nowMinuteOfDay: Int) {
    val departures = stop.nextDepartures(nowMinuteOfDay)
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(
            stringResource(Res.string.schedule_title),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Neu.Text,
            modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
        )
        if (departures.isEmpty()) {
            Text(stringResource(Res.string.schedule_none), fontSize = 15.sp, color = Neu.TextMuted)
            return@Column
        }
        NextDeparture(departures.first(), nowMinuteOfDay)
        val later = departures.drop(1)
        if (later.isNotEmpty()) {
            Text(
                stringResource(Res.string.schedule_later),
                fontSize = 13.sp,
                color = Neu.TextMuted,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
            )
            // Even columns that wrap to a new line instead of squeezing when the text is large.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = later.size,
            ) {
                later.forEach { minute ->
                    Text(
                        formatClock(minute),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Neu.Text,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(min = 72.dp)
                            .neuInset(PillShape, 3.dp)
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NextDeparture(minute: Int, nowMinuteOfDay: Int) {
    val wait = minutesUntil(minute, nowMinuteOfDay)
    val waitText = when {
        wait == 0 -> stringResource(Res.string.schedule_now)
        wait < 60 -> stringResource(Res.string.schedule_in_minutes, wait)
        wait % 60 == 0 -> stringResource(Res.string.schedule_in_hours_exact, wait / 60)
        else -> stringResource(Res.string.schedule_in_hours, wait / 60, wait % 60)
    }
    Row(
        Modifier.fillMaxWidth().neuInset(InsetShape, 3.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.schedule_next), fontSize = 13.sp, color = Neu.TextMuted)
            Text(waitText, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Neu.BlueStrong)
        }
        Text(
            formatClock(minute),
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Neu.Text,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun ArrivalHeadline(arrival: Arrival?, stop: Stop?) {
    val stopName = stop?.name ?: stringResource(Res.string.stop_none)
    val modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    if (arrival == null) {
        Text(
            stringResource(Res.string.no_arrival, stopName),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Neu.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        return
    }
    val headline = stringResource(Res.string.arrival_headline, stopName, arrival.minutes)
    val minutes = arrival.minutes.toString()
    val accent = Neu.BlueStrong
    Text(
        buildAnnotatedString {
            val start = headline.lastIndexOf(minutes)
            if (start < 0) {
                append(headline)
            } else {
                append(headline.substring(0, start))
                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)) {
                    append(headline.substring(start))
                }
            }
        },
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Bold,
        color = Neu.Text,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun StopPicker(
    stops: List<Stop>,
    selected: Stop?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val changeLabel = stringResource(Res.string.action_change_stop)
    val chooseLabel = stringResource(Res.string.action_choose_stop)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .neuInset()
            .clip(InsetShape)
            .selectable(expanded, role = Role.DropdownList, onClick = onToggle)
            .semantics { onClick(label = changeLabel) { onToggle(); true } }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.MapPin, null, tint = Neu.BlueStrong, modifier = Modifier.size(20.dp))
        Text(
            stringResource(Res.string.stop_label, selected?.name ?: stringResource(Res.string.stop_none)),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Neu.Text,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        Text(
            stringResource(if (expanded) Res.string.stop_close else Res.string.stop_change),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Neu.BlueStrong,
        )
    }
    AnimatedVisibility(expanded) {
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            stops.forEach { stop ->
                val isSelected = stop.id == selected?.id
                Text(
                    stop.name,
                    fontSize = 16.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) Neu.BlueStrong else Neu.Text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .then(if (isSelected) Modifier.neuInset() else Modifier.neuCard(CardShape, 3.dp))
                        .clip(CardShape)
                        .selectable(isSelected, role = Role.RadioButton) { onSelect(stop.id) }
                        .semantics { onClick(label = chooseLabel) { onSelect(stop.id); true } }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                )
            }
        }
    }
}
