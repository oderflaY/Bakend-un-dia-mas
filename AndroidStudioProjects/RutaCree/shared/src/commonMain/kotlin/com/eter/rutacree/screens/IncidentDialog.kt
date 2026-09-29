package com.eter.rutacree.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eter.rutacree.transit.Incident
import com.eter.rutacree.transit.IncidentType
import com.eter.rutacree.ui.AlertColors
import com.eter.rutacree.ui.AppIcons
import com.eter.rutacree.ui.Neu
import com.eter.rutacree.ui.PillShape
import org.jetbrains.compose.resources.stringResource
import rutacree.shared.generated.resources.Res
import rutacree.shared.generated.resources.action_alternatives
import rutacree.shared.generated.resources.action_dismiss_incident
import rutacree.shared.generated.resources.incident_alternatives
import rutacree.shared.generated.resources.incident_body
import rutacree.shared.generated.resources.incident_body_bus
import rutacree.shared.generated.resources.incident_dismiss
import rutacree.shared.generated.resources.incident_hint
import rutacree.shared.generated.resources.incident_title_accident
import rutacree.shared.generated.resources.incident_title_breakdown

@Composable
fun incidentTitle(incident: Incident): String = stringResource(
    if (incident.type == IncidentType.Accident) Res.string.incident_title_accident else Res.string.incident_title_breakdown
)

/**
 * High-contrast contingency alert. It interrupts navigation (not dismissable by tapping outside)
 * and uses a solid red/yellow frame that stays legible in both themes.
 */
@Composable
fun IncidentDialog(incident: Incident, onAlternatives: () -> Unit, onDismiss: () -> Unit) {
    val title = incidentTitle(incident)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Neu.Bg)
                .border(6.dp, AlertColors.Danger, shape)
                .padding(6.dp)
                .border(4.dp, AlertColors.Warning, RoundedCornerShape(18.dp))
                .padding(24.dp)
                .semantics { paneTitle = title },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(72.dp).background(AlertColors.Warning, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Alert, null, tint = AlertColors.OnWarning, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Neu.Text, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(
                    incident.busName?.let { stringResource(Res.string.incident_body_bus, it) }
                        ?: stringResource(Res.string.incident_body),
                    fontSize = 17.sp,
                    color = Neu.Text,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(Res.string.incident_hint),
                    fontSize = 16.sp,
                    color = Neu.TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            AlertButton(
                text = stringResource(Res.string.incident_alternatives),
                actionLabel = stringResource(Res.string.action_alternatives),
                primary = true,
                onClick = onAlternatives,
            )
            Spacer(Modifier.height(12.dp))
            AlertButton(
                text = stringResource(Res.string.incident_dismiss),
                actionLabel = stringResource(Res.string.action_dismiss_incident),
                primary = false,
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun AlertButton(text: String, actionLabel: String, primary: Boolean, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = if (primary) AlertColors.OnWarning else Neu.Text,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(PillShape)
            .then(
                if (primary) Modifier.background(AlertColors.Warning)
                else Modifier.border(2.dp, Neu.TextMuted, PillShape)
            )
            .clickable(onClickLabel = actionLabel, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    )
}
