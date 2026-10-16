package com.eter.rutacree.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Alert colors kept high-contrast in both themes (WCAG AA against their own fills). */
object AlertColors {
    val Danger = Color(0xFFE31E31) // rojo DIF
    val Warning = Color(0xFFF8C817) // amarillo DIF
    val OnWarning = Color(0xFF1C1C1C)
    val OnDanger = Color(0xFFFFFFFF)
}

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = Neu.Text,
        modifier = modifier.padding(start = 4.dp, bottom = 10.dp),
    )
}

/**
 * Mutually exclusive options: raised when available, sunk when chosen; read as a radio group.
 * Each option is as wide as its label; they wrap onto more rows instead of squeezing or cutting it.
 */
@Composable
fun <T> NeuSegmented(
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .widthIn(min = 96.dp)
                    .heightIn(min = 56.dp)
                    .then(if (isSelected) Modifier.neuInset() else Modifier.neuCard(distance = 5.dp))
                    .clip(CardShape)
                    .selectable(isSelected, role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    fontSize = 15.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) Neu.BlueStrong else Neu.Text,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Persistent strip for connection problems; announced politely when it appears. */
@Composable
fun StatusBanner(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(AlertColors.Warning, PillShape)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Alert, null, tint = AlertColors.OnWarning, modifier = Modifier.size(18.dp))
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = AlertColors.OnWarning,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
