package com.eter.rutacree.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import com.eter.rutacree.api.ReportKind
import com.eter.rutacree.transit.SendState
import com.eter.rutacree.transit.TransitUiState
import com.eter.rutacree.ui.StatusBanner
import rutacree.shared.generated.resources.report_failed
import com.eter.rutacree.ui.AppIcons
import com.eter.rutacree.ui.CardShape
import com.eter.rutacree.ui.MintChip
import com.eter.rutacree.ui.Neu
import com.eter.rutacree.ui.NeuButton
import com.eter.rutacree.ui.NeuTextField
import com.eter.rutacree.ui.ScreenHeader
import com.eter.rutacree.ui.SectionTitle
import com.eter.rutacree.ui.busName
import com.eter.rutacree.ui.neuCard
import com.eter.rutacree.ui.neuInset
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import rutacree.shared.generated.resources.Res
import rutacree.shared.generated.resources.action_send_report
import rutacree.shared.generated.resources.report_comment
import rutacree.shared.generated.resources.report_comment_hint
import rutacree.shared.generated.resources.report_full
import rutacree.shared.generated.resources.report_no_stop
import rutacree.shared.generated.resources.report_ramp_broken
import rutacree.shared.generated.resources.report_sent
import rutacree.shared.generated.resources.report_stop_blocked
import rutacree.shared.generated.resources.reports_detected
import rutacree.shared.generated.resources.reports_detected_route
import rutacree.shared.generated.resources.reports_title
import rutacree.shared.generated.resources.section_quick_report
import rutacree.shared.generated.resources.send_report

private enum class ReportType(val label: StringResource, val icon: ImageVector, val kind: ReportKind) {
    RampBroken(Res.string.report_ramp_broken, AppIcons.Accessibility, ReportKind.RampBroken),
    DidNotStop(Res.string.report_no_stop, AppIcons.Bus, ReportKind.DidNotStop),
    BusFull(Res.string.report_full, AppIcons.Users, ReportKind.BusFull),
    StopBlocked(Res.string.report_stop_blocked, AppIcons.Ban, ReportKind.StopBlocked),
}

@Composable
fun ReportsScreen(state: TransitUiState, onSend: (ReportKind, String) -> Unit, onEdit: () -> Unit) {
    var selected by remember { mutableStateOf<ReportType?>(null) }
    var comment by remember { mutableStateOf("") }
    // Clear the form only once the server accepted the report, so nothing typed is lost on failure.
    LaunchedEffect(state.report) {
        if (state.report == SendState.Sent) {
            selected = null
            comment = ""
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        ScreenHeader(stringResource(Res.string.reports_title))
        state.route?.let { route ->
            val bus = state.arrival?.bus?.position
            Row(Modifier.padding(top = 6.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Bus, null, tint = Neu.TextMuted, modifier = Modifier.size(16.dp))
                Text(
                    if (bus != null) stringResource(Res.string.reports_detected, route.name, busName(bus))
                    else stringResource(Res.string.reports_detected_route, route.name),
                    fontSize = 13.sp,
                    color = Neu.TextMuted,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }

        SectionTitle(stringResource(Res.string.section_quick_report))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ReportType.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { type ->
                        ReportOption(
                            type = type,
                            selected = type == selected,
                            onClick = { selected = type; onEdit() },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        NeuTextField(
            comment,
            { comment = it.take(500); onEdit() },
            stringResource(Res.string.report_comment),
            stringResource(Res.string.report_comment_hint),
            singleLine = false,
            minLines = 3,
        )

        Spacer(Modifier.height(32.dp))
        NeuButton(
            onClick = { selected?.let { onSend(it.kind, comment) } },
            actionLabel = stringResource(Res.string.action_send_report),
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
            color = Neu.Blue,
            enabled = selected != null && state.report != SendState.Sending,
        ) {
            val onBlue = Neu.palette.onBlue
            Icon(AppIcons.Send, null, tint = onBlue, modifier = Modifier.size(22.dp))
            Text(
                stringResource(Res.string.send_report),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = onBlue,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        when (state.report) {
            SendState.Sent -> MintChip(
                stringResource(Res.string.report_sent),
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            SendState.Failed -> StatusBanner(
                stringResource(Res.string.report_failed),
                Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp),
            )
            SendState.Sending -> CircularProgressIndicator(
                Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp).size(28.dp),
                color = Neu.BlueStrong,
                strokeWidth = 3.dp,
            )
            SendState.Idle -> Unit
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Quick-report tile: raised by default, sunk once chosen. Announced as a radio option with its label. */
@Composable
private fun ReportOption(type: ReportType, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .heightIn(min = 124.dp)
            .then(if (selected) Modifier.neuInset(CardShape) else Modifier.neuCard(distance = 6.dp))
            .clip(CardShape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            type.icon,
            null,
            tint = if (selected) Neu.BlueStrong else Neu.TextMuted,
            modifier = Modifier.size(30.dp),
        )
        Text(
            stringResource(type.label),
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (selected) Neu.BlueStrong else Neu.Text,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
