package com.eter.rutacree.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.eter.rutacree.transit.DirectionStep
import com.eter.rutacree.transit.Maneuver
import org.jetbrains.compose.resources.stringResource
import rutacree.shared.generated.resources.Res
import rutacree.shared.generated.resources.approach_street
import rutacree.shared.generated.resources.distance_km
import rutacree.shared.generated.resources.distance_m
import rutacree.shared.generated.resources.duration_min
import rutacree.shared.generated.resources.maneuver_arrive
import rutacree.shared.generated.resources.maneuver_left
import rutacree.shared.generated.resources.maneuver_right
import rutacree.shared.generated.resources.maneuver_roundabout
import rutacree.shared.generated.resources.maneuver_sharp_left
import rutacree.shared.generated.resources.maneuver_sharp_right
import rutacree.shared.generated.resources.maneuver_slight_left
import rutacree.shared.generated.resources.maneuver_slight_right
import rutacree.shared.generated.resources.maneuver_straight
import rutacree.shared.generated.resources.maneuver_uturn
import kotlin.math.ceil
import kotlin.math.roundToInt

/** "Gira a la derecha en Av. Juárez": the maneuver, plus the street when it has a name. */
@Composable
fun stepInstruction(step: DirectionStep): String {
    val action = stringResource(
        when (step.maneuver) {
            Maneuver.Straight -> Res.string.maneuver_straight
            Maneuver.SlightLeft -> Res.string.maneuver_slight_left
            Maneuver.Left -> Res.string.maneuver_left
            Maneuver.SharpLeft -> Res.string.maneuver_sharp_left
            Maneuver.SlightRight -> Res.string.maneuver_slight_right
            Maneuver.Right -> Res.string.maneuver_right
            Maneuver.SharpRight -> Res.string.maneuver_sharp_right
            Maneuver.UTurn -> Res.string.maneuver_uturn
            Maneuver.Roundabout -> Res.string.maneuver_roundabout
            Maneuver.Arrive -> Res.string.maneuver_arrive
        }
    )
    return if (step.street.isBlank() || step.maneuver == Maneuver.Arrive) action
    else stringResource(Res.string.approach_street, action, step.street)
}

fun Maneuver?.icon(byStreet: Boolean): ImageVector = when (this) {
    null -> if (byStreet) AppIcons.ArrowStraight else AppIcons.Navigate
    Maneuver.Straight -> AppIcons.ArrowStraight
    Maneuver.SlightLeft -> AppIcons.SlightLeft
    Maneuver.Left, Maneuver.SharpLeft -> AppIcons.TurnLeft
    Maneuver.SlightRight -> AppIcons.SlightRight
    Maneuver.Right, Maneuver.SharpRight -> AppIcons.TurnRight
    Maneuver.UTurn -> AppIcons.UTurn
    Maneuver.Roundabout -> AppIcons.Roundabout
    Maneuver.Arrive -> AppIcons.Flag
}

/** "80 m", "450 m", "1.2 km": rounded like navigation apps so it doesn't flicker every fix. */
@Composable
fun formatDistance(meters: Double): String {
    if (meters < 1_000) {
        val step = if (meters < 100) 10 else 50
        return stringResource(Res.string.distance_m, ((meters / step).roundToInt() * step).coerceAtLeast(step))
    }
    val tenths = (meters / 100).roundToInt()
    return stringResource(Res.string.distance_km, "${tenths / 10}.${tenths % 10}")
}

@Composable
fun formatMinutes(seconds: Double): String =
    stringResource(Res.string.duration_min, ceil(seconds / 60).toInt().coerceAtLeast(1))
