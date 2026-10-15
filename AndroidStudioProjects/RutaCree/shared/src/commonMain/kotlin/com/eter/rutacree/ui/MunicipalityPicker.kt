package com.eter.rutacree.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import rutacree.shared.generated.resources.Res
import rutacree.shared.generated.resources.action_change_municipality
import rutacree.shared.generated.resources.cd_municipality
import rutacree.shared.generated.resources.municipality_all

/**
 * Compact pill with the chosen municipio that opens the list, so only that municipio's routes are
 * shown. Nothing is drawn while the routes serve a single municipio.
 */
@Composable
fun MunicipalityPicker(
    municipalities: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (municipalities.size < 2) return
    var open by remember { mutableStateOf(false) }
    val all = stringResource(Res.string.municipality_all)
    val current = selected ?: all
    val description = stringResource(Res.string.cd_municipality, current)
    val action = stringResource(Res.string.action_change_municipality)
    Box(modifier) {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .neuCard(PillShape, 4.dp)
                .clip(PillShape)
                .clickable(role = Role.Button, onClickLabel = action) { open = true }
                .semantics { contentDescription = description }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.MapPin, null, tint = Neu.BlueStrong, modifier = Modifier.size(18.dp))
            Text(
                current,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Neu.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Icon(AppIcons.ChevronDown, null, tint = Neu.TextMuted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Neu.Bg) {
            (listOf<String?>(null) + municipalities).forEach { option ->
                val chosen = option == selected
                DropdownMenuItem(
                    text = {
                        Text(
                            option ?: all,
                            fontSize = 16.sp,
                            fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium,
                            color = if (chosen) Neu.BlueStrong else Neu.Text,
                        )
                    },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}
