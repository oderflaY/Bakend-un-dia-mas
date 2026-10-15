package com.eter.rutacree.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eter.rutacree.map.DirectionLegend
import com.eter.rutacree.map.MapContent
import com.eter.rutacree.map.PlatformMap
import com.eter.rutacree.map.parseHexColor
import com.eter.rutacree.map.rememberAnimatedBuses
import com.eter.rutacree.transit.AppConfig
import com.eter.rutacree.transit.Stop
import com.eter.rutacree.transit.TransitRoute
import com.eter.rutacree.transit.TransitUiState
import com.eter.rutacree.ui.AppIcons
import com.eter.rutacree.ui.CardShape
import com.eter.rutacree.ui.InsetShape
import com.eter.rutacree.ui.Neu
import com.eter.rutacree.ui.NeuButton
import com.eter.rutacree.ui.MunicipalityPicker
import com.eter.rutacree.ui.PillShape
import com.eter.rutacree.ui.ScreenHeader
import com.eter.rutacree.ui.formatDistance
import com.eter.rutacree.ui.neuCard
import com.eter.rutacree.ui.neuInset
import com.eter.rutacree.ui.neuInsetShadow
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import rutacree.shared.generated.resources.Res
import rutacree.shared.generated.resources.action_choose_route_stop
import rutacree.shared.generated.resources.action_show_route
import rutacree.shared.generated.resources.action_use_route
import rutacree.shared.generated.resources.action_use_route_label
import rutacree.shared.generated.resources.cd_route_map
import rutacree.shared.generated.resources.route_length
import rutacree.shared.generated.resources.route_stop_accessible
import rutacree.shared.generated.resources.route_stop_mine
import rutacree.shared.generated.resources.route_stop_not_accessible
import rutacree.shared.generated.resources.route_stops
import rutacree.shared.generated.resources.route_stops_title
import rutacree.shared.generated.resources.no_routes
import rutacree.shared.generated.resources.routes_subtitle
import rutacree.shared.generated.resources.routes_title

private const val STOP_ZOOM = 15.5

/** Every route on a map: its whole path, its stops in order, and a shortcut to use it on the Radar. */
@Composable
fun RoutesScreen(
    config: AppConfig,
    state: TransitUiState,
    onShowOnRadar: (routeId: String, stopId: String?) -> Unit,
    onSelectMunicipality: (String?) -> Unit = {},
) {
    var viewedId by remember { mutableStateOf(state.route?.id) }
    val route = state.routes.firstOrNull { it.id == viewedId } ?: state.route ?: state.routes.firstOrNull()
    // Reset whenever another route is shown, so a stop from the previous one never stays highlighted.
    var highlightedId by remember(route?.id) { mutableStateOf<String?>(null) }
    val highlighted = route?.stops?.firstOrNull { it.id == highlightedId }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeader(stringResource(Res.string.routes_title), stringResource(Res.string.routes_subtitle))
        RouteChips(state, route, onSelect = { viewedId = it }, onSelectMunicipality = onSelectMunicipality)
        if (route == null) {
            if (state.noRoutes) {
                Text(stringResource(Res.string.no_routes), fontSize = 16.sp, color = Neu.Text, modifier = Modifier.padding(top = 16.dp))
            }
            return@Column
        }

        Box(Modifier.weight(1.1f).fillMaxWidth().padding(vertical = 8.dp)) {
            // Live buses are only followed for the Radar's route.
            val buses = rememberAnimatedBuses(
                route,
                if (route.id == state.route?.id) state.buses else emptyList(),
                highlightedId = null,
            )
            val description = stringResource(
                Res.string.cd_route_map,
                route.name,
                route.stops.firstOrNull()?.name.orEmpty(),
                route.stops.lastOrNull()?.name.orEmpty(),
            )
            PlatformMap(
                config = config,
                content = MapContent(
                    route = route,
                    stops = route.stops,
                    selectedStopId = highlighted?.id,
                    buses = buses,
                    userLocation = state.userLocation,
                    focus = highlighted?.point,
                    zoom = highlighted?.let { STOP_ZOOM },
                    // The whole path in view, unless the rider is looking at one stop.
                    frame = if (highlighted == null) route.geometry.points else emptyList(),
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .clip(InsetShape)
                    .clearAndSetSemantics { contentDescription = description },
            )
            Box(Modifier.matchParentSize().neuInsetShadow(InsetShape, 6.dp))
            DirectionLegend(route, Modifier.align(Alignment.TopEnd).padding(10.dp))
        }

        RouteDetails(
            route = route,
            riderStopId = state.stop?.id,
            highlightedId = highlightedId,
            onHighlight = { highlightedId = if (highlightedId == it) null else it },
            onShowOnRadar = {
                onShowOnRadar(route.id, highlighted?.takeIf { it.accessible }?.id)
            },
            modifier = Modifier.weight(1f).padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun RouteChips(
    state: TransitUiState,
    current: TransitRoute?,
    onSelect: (String) -> Unit,
    onSelectMunicipality: (String?) -> Unit,
) {
    val showRoute = stringResource(Res.string.action_show_route)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MunicipalityPicker(state.municipalities, state.municipality, onSelectMunicipality)
        state.routes.forEach { route ->
            val selected = route.id == current?.id
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .then(if (selected) Modifier.neuInset(PillShape, 3.dp) else Modifier.neuCard(PillShape, 5.dp))
                    .clip(PillShape)
                    .selectable(selected, role = Role.Tab) { onSelect(route.id) }
                    .semantics {
                        contentDescription = route.name
                        onClick(label = showRoute) { onSelect(route.id); true }
                    }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(12.dp).background(parseHexColor(route.colorHex), CircleShape))
                Text(
                    route.name.substringBefore(" - "),
                    fontSize = 16.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) Neu.BlueStrong else Neu.Text,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun RouteDetails(
    route: TransitRoute,
    riderStopId: String?,
    highlightedId: String?,
    onHighlight: (String) -> Unit,
    onShowOnRadar: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth().neuCard().padding(16.dp)) {
        Text(
            route.name,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Neu.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(Res.string.route_length, formatDistance(route.geometry.lengthMeters)) + " · " +
                pluralStringResource(
                    Res.plurals.route_stops,
                    route.stops.size,
                    route.stops.size,
                    route.stops.count { it.accessible },
                ),
            fontSize = 14.sp,
            color = Neu.TextMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
        NeuButton(
            onClick = onShowOnRadar,
            actionLabel = stringResource(Res.string.action_use_route_label),
            color = Neu.Blue,
            shape = PillShape,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp),
        ) {
            Icon(AppIcons.MapPin, null, tint = Neu.palette.onBlue, modifier = Modifier.size(20.dp))
            Text(
                stringResource(Res.string.action_use_route),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Neu.palette.onBlue,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            stringResource(Res.string.route_stops_title),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Neu.Text,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp).semantics { heading() },
        )
        Column(Modifier.verticalScroll(rememberScrollState())) {
            route.stops.forEachIndexed { i, stop ->
                StopRow(
                    number = i + 1,
                    stop = stop,
                    color = parseHexColor(route.colorHex),
                    mine = stop.id == riderStopId,
                    highlighted = stop.id == highlightedId,
                    onClick = { onHighlight(stop.id) },
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun StopRow(
    number: Int,
    stop: Stop,
    color: Color,
    mine: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val showLabel = stringResource(Res.string.action_choose_route_stop)
    val status = listOfNotNull(
        stringResource(if (stop.accessible) Res.string.route_stop_accessible else Res.string.route_stop_not_accessible),
        if (mine) stringResource(Res.string.route_stop_mine) else null,
    ).joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(CardShape)
            .then(if (highlighted) Modifier.neuInset(CardShape, 3.dp) else Modifier)
            .selectable(highlighted, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { onClick(label = showLabel) { onClick(); true } }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            // Same look as the stop dots on the map: light fill, ringed in the route color.
            Modifier
                .size(30.dp)
                .background(if (highlighted) Neu.Mint else Neu.palette.stopFill, CircleShape)
                .border(3.dp, color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Neu.Text)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                stop.name,
                fontSize = 16.sp,
                fontWeight = if (highlighted || mine) FontWeight.Bold else FontWeight.Medium,
                color = if (mine) Neu.BlueStrong else Neu.Text,
            )
            Text(status, fontSize = 13.sp, color = if (stop.accessible) Neu.MintText else Neu.TextMuted)
        }
        Icon(
            if (stop.accessible) AppIcons.Accessibility else AppIcons.Ban,
            null,
            tint = if (stop.accessible) Neu.MintText else Neu.TextMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}
