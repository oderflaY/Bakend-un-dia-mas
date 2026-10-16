package com.eter.rutacree.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.painterResource
import rutacree.shared.generated.resources.Res
import rutacree.shared.generated.resources.dif_logo

// Identidad DIF Durango, la misma del panel web: cada sección de la app toma un color de la paleta del
// logotipo DIF / imagen «Ruta Azul». fill = rellenos (botones, chips), ink = texto e íconos sobre el fondo
// (contraste AA), onFill = texto encima del relleno. Cada tono tiene su versión para el modo oscuro.

data class Accent(val fill: Color, val ink: Color, val onFill: Color)

enum class DifColor(private val light: Accent, private val dark: Accent) {
    Azul(Accent(Color(0xFF2F5DA8), Color(0xFF2A55A0), Color.White), Accent(Color(0xFF7FA6EE), Color(0xFF7FA6EE), Color(0xFF0D1B33))),
    Magenta(Accent(Color(0xFFD91178), Color(0xFFB80E66), Color.White), Accent(Color(0xFFF56AAE), Color(0xFFF56AAE), Color(0xFF2A0716))),
    Morado(Accent(Color(0xFF9A6CB1), Color(0xFF7A4E96), Color.White), Accent(Color(0xFFC39BDB), Color(0xFFC39BDB), Color(0xFF1F0F2A))),
    Naranja(Accent(Color(0xFFF19025), Color(0xFF9C4F08), Color(0xFF3A1D00)), Accent(Color(0xFFF7AE5C), Color(0xFFF7AE5C), Color(0xFF3A1D00))),
    Turquesa(Accent(Color(0xFF34B8BB), Color(0xFF0F7477), Color(0xFF06393B)), Accent(Color(0xFF5FD3D5), Color(0xFF5FD3D5), Color(0xFF06393B))),
    Rojo(Accent(Color(0xFFE31E31), Color(0xFFBF1520), Color.White), Accent(Color(0xFFFF7A82), Color(0xFFFF7A82), Color(0xFF3D0A10))),
    Amarillo(Accent(Color(0xFFF8C817), Color(0xFF806000), Color(0xFF3D2E00)), Accent(Color(0xFFF8CF3E), Color(0xFFF8CF3E), Color(0xFF3D2E00))),
    Gris(Accent(Color(0xFF6B7486), Color(0xFF4E5668), Color.White), Accent(Color(0xFFAEB6C6), Color(0xFFAEB6C6), Color(0xFF161C27)));

    fun accent(dark: Boolean): Accent = if (dark) this.dark else light
}

/** Pinta el contenido con el color de su sección: botones, selección y títulos toman ese tono. */
@Composable
fun SectionColor(color: DifColor, content: @Composable () -> Unit) {
    val p = Neu.palette
    val a = color.accent(p.isDark)
    CompositionLocalProvider(LocalNeuPalette provides p.copy(blue = a.fill, blueOnBg = a.ink, onBlue = a.onFill), content = content)
}

private val FRANJA = listOf(
    Color(0xFFD91178), Color(0xFFF19025), Color(0xFFF8C817), Color(0xFF34B8BB), Color(0xFF2F5DA8), Color(0xFF9A6CB1),
)

/** Franja multicolor del DIF (papel picado de la imagen Ruta Azul). */
@Composable
fun DifStripe(modifier: Modifier = Modifier, height: Dp = 5.dp) {
    Row(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50))) {
        FRANJA.forEach { Box(Modifier.weight(1f).height(height).background(it)) }
    }
}

@Composable
fun DifLogo(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Image(painterResource(Res.drawable.dif_logo), contentDescription = "DIF Durango", modifier = modifier.size(size * 1.12f, size))
}

/** Encabezado de la app, como el del menú del panel: franja, logo DIF y la sección actual. */
@Composable
fun AppHeader(section: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 4.dp)) {
        DifStripe(height = 4.dp)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            DifLogo(size = 34.dp)
            Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(30.dp).background(Neu.TextMuted.copy(alpha = 0.25f)))
            Column(Modifier.weight(1f)) {
                Text("RutaCree", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Neu.Text)
                Text("Ruta Azul · DIF Durango", fontSize = 11.sp, color = Neu.TextMuted)
            }
            // La sección actual, en su color, como el elemento activo del menú del panel.
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(tint(Neu.Blue, 0.16f)).padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).background(Neu.Blue), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Neu.palette.onBlue, modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(section, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Neu.BlueStrong, maxLines = 1)
            }
        }
    }
}

/** Mezcla un color con el fondo (como color-mix del panel), para chips y selección suaves. */
@Composable
fun tint(color: Color, amount: Float): Color = lerp(Neu.Bg, color, amount)

/** Título de tarjeta con la barrita del color de la sección, como `Section` del panel. */
@Composable
fun AccentTitle(text: String, modifier: Modifier = Modifier) {
    Row(modifier.semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(width = 5.dp, height = 18.dp).clip(RoundedCornerShape(50)).background(Neu.Blue))
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Neu.Text)
    }
}
