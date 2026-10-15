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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.eter.rutacree.api.Passengers
import com.eter.rutacree.transit.RampRequest
import com.eter.rutacree.ui.AppIcons
import com.eter.rutacree.ui.CardShape
import com.eter.rutacree.ui.FieldLabel
import com.eter.rutacree.transit.municipalitiesOf
import com.eter.rutacree.ui.MintChip
import com.eter.rutacree.ui.MunicipalityPicker
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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import rutacree.shared.generated.resources.Res
import rutacree.shared.generated.resources.action_ack_ramp
import rutacree.shared.generated.resources.action_passengers_add
import rutacree.shared.generated.resources.action_passengers_remove
import rutacree.shared.generated.resources.action_start_route
import rutacree.shared.generated.resources.action_whatsapp_resend
import rutacree.shared.generated.resources.cd_passengers_count
import rutacree.shared.generated.resources.cd_ramp_alert
import rutacree.shared.generated.resources.driver_assign_subtitle
import rutacree.shared.generated.resources.driver_assign_title
import rutacree.shared.generated.resources.driver_bus_number
import rutacree.shared.generated.resources.driver_bus_number_hint
import rutacree.shared.generated.resources.driver_console_gps
import rutacree.shared.generated.resources.driver_console_passengers
import rutacree.shared.generated.resources.driver_console_ramp_reported
import rutacree.shared.generated.resources.driver_console_reports_hint
import rutacree.shared.generated.resources.driver_console_started
import rutacree.shared.generated.resources.driver_console_title
import rutacree.shared.generated.resources.driver_error_network
import rutacree.shared.generated.resources.driver_error_rejected
import rutacree.shared.generated.resources.driver_greeting
import rutacree.shared.generated.resources.driver_last_end_completed
import rutacree.shared.generated.resources.driver_last_end_emergency
import rutacree.shared.generated.resources.driver_last_end_server
import rutacree.shared.generated.resources.driver_missing_fields
import rutacree.shared.generated.resources.driver_passengers_title
import rutacree.shared.generated.resources.driver_route
import rutacree.shared.generated.resources.driver_simulated
import rutacree.shared.generated.resources.driver_start
import rutacree.shared.generated.resources.driver_starting
import rutacree.shared.generated.resources.driver_whatsapp_hint
import rutacree.shared.generated.resources.passengers_companions
import rutacree.shared.generated.resources.passengers_disability
import rutacree.shared.generated.resources.passengers_elderly
import rutacree.shared.generated.resources.passengers_men
import rutacree.shared.generated.resources.passengers_pregnant
import rutacree.shared.generated.resources.passengers_women
import rutacree.shared.generated.resources.driver_passengers_of_them
import rutacree.shared.generated.resources.driver_console_categories
import rutacree.shared.generated.resources.gps_phone_no_connection
import rutacree.shared.generated.resources.gps_phone_no_connection_hint
import rutacree.shared.generated.resources.gps_phone_no_signal
import rutacree.shared.generated.resources.gps_phone_no_signal_hint
import rutacree.shared.generated.resources.gps_phone_searching
import rutacree.shared.generated.resources.gps_phone_searching_hint
import rutacree.shared.generated.resources.gps_phone_sending
import rutacree.shared.generated.resources.gps_phone_sending_hint
import rutacree.shared.generated.resources.ramp_alert_ack
import rutacree.shared.generated.resources.ramp_alert_title
import rutacree.shared.generated.resources.whatsapp_resend

@Composable
fun DriverShiftScreen(
    state: DriverUiState,
    reportsToServer: Boolean,
    onBusNumber: (String) -> Unit,
    onSelectRoute: (String) -> Unit,
    onPassengers: (PassengerKind, Int) -> Unit,
    onStart: () -> Unit,
    onResendWhatsApp: () -> Unit,
    municipality: String? = null,
    onSelectMunicipality: (String?) -> Unit = {},
) {
    val shift = state.shift
    if (shift == null) {
        AssignmentForm(state, municipality, onSelectMunicipality, onBusNumber, onSelectRoute, onPassengers, onStart, onResendWhatsApp)
    } else {
        Console(shift, state, reportsToServer, onResendWhatsApp)
    }
}

internal val PassengerKind.label: StringResource
    get() = when (this) {
        PassengerKind.Men -> Res.string.passengers_men
        PassengerKind.Women -> Res.string.passengers_women
        PassengerKind.Disability -> Res.string.passengers_disability
        PassengerKind.Elderly -> Res.string.passengers_elderly
        PassengerKind.Pregnant -> Res.string.passengers_pregnant
        PassengerKind.Companions -> Res.string.passengers_companions
    }

internal fun Passengers.count(kind: PassengerKind): Int = when (kind) {
    PassengerKind.Men -> men
    PassengerKind.Women -> women
    PassengerKind.Disability -> disability
    PassengerKind.Elderly -> elderly
    PassengerKind.Pregnant -> pregnant
    PassengerKind.Companions -> companions
}

/** Men and women make the total; the other kinds say who among them rode. */
internal val PassengerKind.isCategory: Boolean get() = this != PassengerKind.Men && this != PassengerKind.Women

/** The driver is already identified by the login: only today's bus, route and passengers are asked. */
@Composable
private fun AssignmentForm(
    state: DriverUiState,
    municipality: String?,
    onSelectMunicipality: (String?) -> Unit,
    onBusNumber: (String) -> Unit,
    onSelectRoute: (String) -> Unit,
    onPassengers: (PassengerKind, Int) -> Unit,
    onStart: () -> Unit,
    onResendWhatsApp: () -> Unit,
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
            if (end != ShiftEnd.ClosedByServer && state.lastWhatsApp != null) {
                ResendButton(onResendWhatsApp, Modifier.padding(top = 12.dp))
            }
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
        // Only the routes of the driver's municipio; the choice is kept in Ajustes for next time.
        val routes = state.routes.filter { it.servesMunicipality(municipality) }.ifEmpty { state.routes }
        LaunchedEffect(routes) {
            if (routes.none { it.id == state.selectedRouteId }) routes.firstOrNull()?.let { onSelectRoute(it.id) }
        }
        MunicipalityPicker(
            municipalitiesOf(state.routes),
            municipality,
            onSelectMunicipality,
            Modifier.padding(bottom = 14.dp),
        )
        NeuSegmented(
            options = routes.map { it.id },
            selected = state.selectedRouteId,
            label = { id -> state.routes.first { it.id == id }.name.substringBefore(" - ") },
            onSelect = onSelectRoute,
        )

        Spacer(Modifier.height(24.dp))
        FieldLabel(stringResource(Res.string.driver_passengers_title))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PassengerKind.entries.filter { !it.isCategory }.forEach { kind ->
                PassengerCounter(kind, state.passengers.count(kind), onPassengers)
            }
            Text(
                stringResource(Res.string.driver_passengers_of_them, state.passengers.total),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Neu.Text,
                modifier = Modifier.padding(top = 8.dp),
            )
            PassengerKind.entries.filter { it.isCategory }.forEach { kind ->
                PassengerCounter(kind, state.passengers.count(kind), onPassengers)
            }
        }

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
        Text(
            stringResource(Res.string.driver_whatsapp_hint),
            fontSize = 15.sp,
            color = Neu.TextMuted,
            modifier = Modifier.padding(top = 12.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** Big −/+ buttons: usable with gloves and without typing. */
@Composable
private fun PassengerCounter(kind: PassengerKind, value: Int, onChange: (PassengerKind, Int) -> Unit) {
    val name = stringResource(kind.label)
    val spoken = stringResource(Res.string.cd_passengers_count, name, value)
    Row(
        Modifier.fillMaxWidth().neuInset().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            fontSize = 17.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Bold,
            color = Neu.Text,
            modifier = Modifier.weight(1f).padding(start = 6.dp, end = 8.dp),
        )
        NeuButton(
            onClick = { onChange(kind, -1) },
            actionLabel = stringResource(Res.string.action_passengers_remove, name),
            shape = CircleShape,
            enabled = value > 0,
            modifier = Modifier.size(52.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(AppIcons.Minus, null, tint = Neu.BlueStrong, modifier = Modifier.size(26.dp))
        }
        Text(
            value.toString(),
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Neu.Text,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .width(48.dp)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    liveRegion = LiveRegionMode.Polite
                },
        )
        NeuButton(
            onClick = { onChange(kind, 1) },
            actionLabel = stringResource(Res.string.action_passengers_add, name),
            shape = CircleShape,
            enabled = value < DriverViewModel.MAX_PASSENGERS,
            modifier = Modifier.size(52.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(AppIcons.Plus, null, tint = Neu.BlueStrong, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
internal fun ResendButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    NeuButton(
        onClick = onClick,
        actionLabel = stringResource(Res.string.action_whatsapp_resend),
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Text(
            stringResource(Res.string.whatsapp_resend),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Neu.BlueStrong,
        )
    }
}

/**
 * On route the driver only needs to glance at which bus and route are open and since when; the bus
 * position comes from its own GPS. Failures and the end of the route are in Reportes.
 */
@Composable
private fun Console(shift: DriverShift, state: DriverUiState, reportsToServer: Boolean, onResendWhatsApp: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().neuCard().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).background(Neu.Blue, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.Bus, null, tint = Neu.palette.onBlue, modifier = Modifier.size(30.dp))
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(
                        stringResource(Res.string.driver_console_title, shift.busNumber),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Neu.Text,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(shift.route.name, fontSize = 15.sp, color = Neu.TextMuted, maxLines = 2)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(Res.string.driver_console_started, shift.startTime),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Neu.Text,
            )
            val p = shift.passengers
            Text(
                stringResource(Res.string.driver_console_passengers, p.total, p.men, p.women),
                fontSize = 18.sp,
                color = Neu.Text,
            )
            Text(
                stringResource(Res.string.driver_console_categories, p.disability, p.elderly, p.pregnant, p.companions),
                fontSize = 16.sp,
                color = Neu.TextMuted,
            )
        }
        if (!reportsToServer) {
            Text(
                stringResource(Res.string.driver_simulated),
                fontSize = 13.sp,
                color = Neu.TextMuted,
                modifier = Modifier.background(Neu.Bg, PillShape).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        } else if (state.phoneGps != PhoneGps.Off) {
            PhoneGpsCard(state.phoneGps)
        } else {
            MintChip(stringResource(Res.string.driver_console_gps))
        }
        if (shift.rampFailureReported) StatusBanner(stringResource(Res.string.driver_console_ramp_reported))
        if (state.lastWhatsApp != null) ResendButton(onResendWhatsApp)
        Text(
            stringResource(Res.string.driver_console_reports_hint),
            fontSize = 16.sp,
            color = Neu.TextMuted,
        )
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

/** Demo mode: whether this phone is putting the bus on the map, in one glance (color, title, what to do). */
@Composable
private fun PhoneGpsCard(status: PhoneGps) {
    val (color, title, hint) = when (status) {
        PhoneGps.Sending -> Triple(Neu.MintText, Res.string.gps_phone_sending, Res.string.gps_phone_sending_hint)
        PhoneGps.Searching -> Triple(Neu.BlueStrong, Res.string.gps_phone_searching, Res.string.gps_phone_searching_hint)
        PhoneGps.NoSignal -> Triple(Color(0xFFD08A1E), Res.string.gps_phone_no_signal, Res.string.gps_phone_no_signal_hint)
        PhoneGps.NoConnection, PhoneGps.Off -> Triple(Color(0xFFD9534F), Res.string.gps_phone_no_connection, Res.string.gps_phone_no_connection_hint)
    }
    val titleText = stringResource(title)
    val hintText = stringResource(hint)
    Row(
        Modifier
            .fillMaxWidth()
            .neuCard()
            .padding(16.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp).background(color, CircleShape))
        Column(Modifier.padding(start = 14.dp)) {
            Text(titleText, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
            Text(hintText, fontSize = 15.sp, color = Neu.TextMuted)
        }
    }
}
