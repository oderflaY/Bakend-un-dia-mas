package com.eter.rutacree.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import rutacree.shared.generated.resources.show_password
import rutacree.shared.generated.resources.hide_password
import rutacree.shared.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class NeuPalette(
    val isDark: Boolean,
    val bg: Color,
    val shadowDark: Color,
    val shadowLight: Color,
    val text: Color,
    val textMuted: Color,
    /** Accents used as fills (chips, knobs, pins, glow). */
    val mint: Color,
    val blue: Color,
    /** Accents used for text and small icons, tuned for contrast on [bg]. */
    val mintOnBg: Color,
    val blueOnBg: Color,
    val chipBg: Color,
    val chipText: Color,
    /** Text on a filled [blue] surface. */
    val onBlue: Color,
    val mapGrid: Color,
    val stopFill: Color,
)

val LightNeuPalette = NeuPalette(
    isDark = false,
    bg = Color(0xFFE6ECF5),
    shadowDark = Color(0xFFC5CBD5),
    shadowLight = Color(0xFFFFFFFF),
    text = Color(0xFF2C3E50),
    textMuted = Color(0xFF5A6C7D),
    // Colores DIF Durango (logo DIF e imagen Ruta Azul): azul como acento principal ("blue") y
    // magenta como secundario ("mint", el de la rampa y lo destacado). Texto blanco encima: >4.8:1.
    mint = Color(0xFFD91178),
    blue = Color(0xFF2F5DA8),
    mintOnBg = Color(0xFFB80E66),
    blueOnBg = Color(0xFF2A55A0),
    chipBg = Color(0xFFF7D3E5),
    chipText = Color(0xFF9C1866),
    onBlue = Color(0xFFFFFFFF),
    mapGrid = Color(0xFFF3F6FA),
    stopFill = Color(0xFFFFFFFF),
)

// Modo oscuro «noche DIF» (igual que el panel): azul carbón profundo en vez de gris plano, sombras más
// suaves y los colores DIF aclarados para leerse sobre el fondo (>5:1); el texto encima va oscuro.
val DarkNeuPalette = NeuPalette(
    isDark = true,
    bg = Color(0xFF161C27),
    shadowDark = Color(0xFF0A0D13),
    shadowLight = Color(0xFF222A38),
    text = Color(0xFFE8EDF5),
    textMuted = Color(0xFF98A4B8),
    mint = Color(0xFFF56AAE),
    blue = Color(0xFF7FA6EE),
    mintOnBg = Color(0xFFF56AAE),
    blueOnBg = Color(0xFF7FA6EE),
    chipBg = Color(0x33D91178),
    chipText = Color(0xFFF56AAE),
    onBlue = Color(0xFF0D1B33),
    mapGrid = Color(0xFF1E2532),
    stopFill = Color(0xFF161C27),
)

val LocalNeuPalette = staticCompositionLocalOf { LightNeuPalette }

/** Shorthand for the current palette. */
object Neu {
    val palette: NeuPalette @Composable @ReadOnlyComposable get() = LocalNeuPalette.current
    val Bg: Color @Composable @ReadOnlyComposable get() = palette.bg
    val Text: Color @Composable @ReadOnlyComposable get() = palette.text
    val TextMuted: Color @Composable @ReadOnlyComposable get() = palette.textMuted
    val Mint: Color @Composable @ReadOnlyComposable get() = palette.mint
    val Blue: Color @Composable @ReadOnlyComposable get() = palette.blue
    val MintText: Color @Composable @ReadOnlyComposable get() = palette.mintOnBg
    val BlueStrong: Color @Composable @ReadOnlyComposable get() = palette.blueOnBg
}

val CardShape = RoundedCornerShape(16.dp)
val InsetShape = RoundedCornerShape(12.dp)
val PillShape = RoundedCornerShape(50)

/** Extruded surface (`.neumorphic-card` / `.neumorphic-card-dark`). */
@Composable
fun Modifier.neuCard(
    shape: Shape = CardShape,
    distance: Dp = 8.dp,
    color: Color = Neu.Bg,
): Modifier {
    val p = Neu.palette
    return this
        .dropShadow(shape, Shadow(radius = distance * 2, color = p.shadowDark, offset = DpOffset(distance, distance)))
        .dropShadow(shape, Shadow(radius = distance * 2, color = p.shadowLight, offset = DpOffset(-distance, -distance)))
        .background(color, shape)
        // En oscuro, un filo de luz en el borde (como las tarjetas del panel) para que no se pierdan en el fondo.
        .then(if (p.isDark) Modifier.border(1.dp, Color.White.copy(alpha = 0.05f), shape) else Modifier)
}

@Composable
fun Modifier.neuInsetShadow(shape: Shape = InsetShape, distance: Dp = 4.dp): Modifier {
    val p = Neu.palette
    return this
        .innerShadow(shape, Shadow(radius = distance * 2, color = p.shadowDark, offset = DpOffset(distance, distance)))
        .innerShadow(shape, Shadow(radius = distance * 2, color = p.shadowLight, offset = DpOffset(-distance, -distance)))
}

/** Pressed-in surface (`.neumorphic-inset` / `.neumorphic-inset-dark`). */
@Composable
fun Modifier.neuInset(shape: Shape = InsetShape, distance: Dp = 4.dp): Modifier =
    this.background(Neu.Bg, shape).neuInsetShadow(shape, distance)

/**
 * Raised button that sinks while pressed.
 * [actionLabel] completes TalkBack's "double tap to …" hint; the role already announces "button".
 */
@Composable
fun NeuButton(
    onClick: () -> Unit,
    actionLabel: String,
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    color: Color = Neu.Bg,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 56.dp)
            // Fades each draw call instead of an offscreen layer, which would clip the shadows to a square.
            .graphicsLayer {
                alpha = if (enabled) 1f else 0.5f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .then(
                if (pressed) Modifier.background(color, shape).neuInsetShadow(shape)
                else Modifier.neuCard(shape, 6.dp, color)
            )
            .clip(shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClickLabel = actionLabel,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(contentPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun NeuIconButton(icon: ImageVector, contentDescription: String, actionLabel: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(52.dp)
            .then(if (pressed) Modifier.neuInset(CircleShape) else Modifier.neuCard(CircleShape, 5.dp))
            .clip(CircleShape)
            .clickable(interaction, indication = null, onClickLabel = actionLabel, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Neu.BlueStrong, modifier = Modifier.size(24.dp))
    }
}

/** Pill switch: raised when off, sunken with a mint knob when on. Visual only; the parent row is the toggle. */
@Composable
fun NeuSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val knobOffset by animateDpAsState(if (checked) 28.dp else 0.dp)
    Box(
        modifier = modifier
            .size(width = 64.dp, height = 36.dp)
            .then(if (checked) Modifier.neuInset(PillShape, 3.dp) else Modifier.neuCard(PillShape, 4.dp))
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .offset(x = knobOffset)
                .size(28.dp)
                .then(
                    if (checked) Modifier.background(Neu.Blue, CircleShape)
                    else Modifier.neuCard(CircleShape, 2.dp)
                )
        )
    }
}

@Composable
fun NeuTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
) {
    // Seeing what you typed matters for low vision or unsteady hands: passwords can be revealed.
    var revealed by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Neu.Text,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        )
        val textColor = Neu.Text
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = TextStyle(fontSize = 17.sp, color = textColor),
            cursorBrush = SolidColor(Neu.BlueStrong),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = !secret, imeAction = imeAction),
            // Only override the keyboard action when asked: otherwise "Next" must keep moving focus.
            keyboardActions = onImeAction?.let { action -> KeyboardActions(onAny = { action() }) } ?: KeyboardActions.Default,
            visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            // The visible label is a separate Text, so name the field explicitly for screen readers.
            modifier = Modifier.semantics { contentDescription = label },
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 56.dp)
                        .neuInset()
                        .padding(start = 18.dp, end = if (secret) 4.dp else 18.dp),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                ) {
                    Box(Modifier.weight(1f).padding(vertical = 16.dp)) {
                        if (value.isEmpty()) Text(placeholder, fontSize = 17.sp, color = Neu.TextMuted)
                        inner()
                    }
                    if (secret) {
                        val description = stringResource(if (revealed) Res.string.hide_password else Res.string.show_password)
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable(role = Role.Button, onClickLabel = description) { revealed = !revealed }
                                .semantics { contentDescription = description },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (revealed) AppIcons.EyeOff else AppIcons.Eye,
                                null,
                                tint = Neu.TextMuted,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            },
        )
    }
}

/** Título de sección con la barrita del color de la pantalla, como en el panel web. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    AccentTitle(text, modifier.padding(start = 2.dp, top = 28.dp, bottom = 14.dp))
}

@Composable
fun MintChip(text: String, modifier: Modifier = Modifier) {
    val p = Neu.palette
    Row(
        modifier = modifier
            .background(p.chipBg, PillShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Check, null, tint = p.chipText, modifier = Modifier.size(18.dp))
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = p.chipText,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            title,
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            color = Neu.Text,
            modifier = Modifier.semantics { heading() },
        )
        if (subtitle != null) {
            Text(subtitle, fontSize = 15.sp, color = Neu.TextMuted, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
