package com.eter.rutacree.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

private fun circle(cx: Float, cy: Float, r: Float) =
    "M${cx - r} $cy a$r $r 0 1 0 ${2 * r} 0 a$r $r 0 1 0 ${-2 * r} 0"

private fun lineIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach {
            addPath(
                pathData = addPathNodes(it),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

object AppIcons {
    val Bus by lazy {
        lineIcon(
            "Bus",
            "M8 6v6", "M15 6v6", "M2 12h19.6",
            "M18 18h3s.5-1.7.8-2.8c.1-.4.2-.8.2-1.2 0-.4-.1-.8-.2-1.2l-1.4-5C20.1 6.8 19.1 6 18 6H4a2 2 0 0 0-2 2v10h3",
            circle(7f, 18f, 2f), "M9 18h5", circle(16f, 18f, 2f),
        )
    }
    val Accessibility by lazy {
        lineIcon(
            "Accessibility",
            circle(16f, 4f, 1f),
            "M18 19l1-7-6 1",
            "M5 8l3-3 5.5 3-2.36 3.5",
            "M4.24 14.5a5 5 0 0 0 6.88 6",
            "M13.76 17.5a5 5 0 0 0-6.88-6",
        )
    }
    val User by lazy {
        lineIcon("User", "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2", circle(12f, 7f, 4f))
    }
    val Users by lazy {
        lineIcon(
            "Users",
            "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2", circle(9f, 7f, 4f),
            "M22 21v-2a4 4 0 0 0-3-3.87", "M16 3.13a4 4 0 0 1 0 7.75",
        )
    }
    val MapPin by lazy {
        lineIcon(
            "MapPin",
            "M20 10c0 5-5.5 10.2-7.4 11.8a1 1 0 0 1-1.2 0C9.5 20.2 4 15 4 10a8 8 0 0 1 16 0",
            circle(12f, 10f, 3f),
        )
    }
    val Alert by lazy {
        lineIcon(
            "Alert",
            "M21.73 18l-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3",
            "M12 9v4", "M12 17h.01",
        )
    }
    val Eye by lazy {
        lineIcon(
            "Eye",
            "M2.06 12.35a1 1 0 0 1 0-.7 10.75 10.75 0 0 1 19.88 0 1 1 0 0 1 0 .7 10.75 10.75 0 0 1-19.88 0",
            circle(12f, 12f, 3f),
        )
    }
    val EyeOff by lazy {
        lineIcon(
            "EyeOff",
            "M2.06 12.35a1 1 0 0 1 0-.7 10.75 10.75 0 0 1 19.88 0 1 1 0 0 1 0 .7 10.75 10.75 0 0 1-19.88 0",
            circle(12f, 12f, 3f),
            "M3 3l18 18",
        )
    }
    val Walking by lazy {
        lineIcon("Walking", circle(12f, 5f, 1f), "M9 20l3-6 3 6", "M6 8l6 2 6-2", "M12 10v4")
    }
    val Ban by lazy {
        lineIcon("Ban", circle(12f, 12f, 10f), "M4.9 4.9l14.2 14.2")
    }
    val ChevronUp by lazy { lineIcon("ChevronUp", "M18 15l-6-6-6 6") }
    val ChevronDown by lazy { lineIcon("ChevronDown", "M6 9l6 6 6-6") }
    val Check by lazy { lineIcon("Check", "M20 6L9 17l-5-5") }
    val Close by lazy { lineIcon("Close", "M18 6L6 18", "M6 6l12 12") }
    val Send by lazy {
        lineIcon(
            "Send",
            "M14.54 21.69a.5.5 0 0 0 .94-.02l6.5-19a.5.5 0 0 0-.64-.64l-19 6.5a.5.5 0 0 0-.02.94l7.93 3.18a2 2 0 0 1 1.11 1.11z",
            "M21.85 2.15l-10.94 10.94",
        )
    }

    // Driving maneuvers, as navigation apps draw them.
    val ArrowStraight by lazy { lineIcon("ArrowStraight", "M12 20V4", "M6 10l6-6 6 6") }
    val TurnRight by lazy { lineIcon("TurnRight", "M6 20v-7a4 4 0 0 1 4-4h9", "M15 5l4 4-4 4") }
    val TurnLeft by lazy { lineIcon("TurnLeft", "M18 20v-7a4 4 0 0 0-4-4H5", "M9 5L5 9l4 4") }
    val SlightRight by lazy { lineIcon("SlightRight", "M8 20v-7l9-9", "M11 4h6v6") }
    val SlightLeft by lazy { lineIcon("SlightLeft", "M16 20v-7L7 4", "M13 4H7v6") }
    val UTurn by lazy { lineIcon("UTurn", "M6 20V10a5 5 0 0 1 10 0v6", "M12 13l4 4 4-4") }
    val Roundabout by lazy { lineIcon("Roundabout", circle(12f, 9f, 4f), "M12 13v8", "M15 6l4-4", "M15 2h4v4") }
    val Flag by lazy { lineIcon("Flag", "M5 21V4", "M5 4h12l-2.5 4 2.5 4H5") }
    val Navigate by lazy { lineIcon("Navigate", "M3 11l18-8-8 18-2-8z") }
}
