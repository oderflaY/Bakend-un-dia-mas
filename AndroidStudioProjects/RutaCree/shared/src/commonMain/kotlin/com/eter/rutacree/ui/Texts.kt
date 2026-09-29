package com.eter.rutacree.ui

import androidx.compose.runtime.Composable
import com.eter.rutacree.transit.BusPosition
import org.jetbrains.compose.resources.stringResource
import rutacree.shared.generated.resources.Res
import rutacree.shared.generated.resources.bus_unit
import rutacree.shared.generated.resources.distance_km
import rutacree.shared.generated.resources.distance_meters
import kotlin.math.roundToInt

@Composable
fun distanceText(meters: Double): String =
    if (meters < 1000) {
        stringResource(Res.string.distance_meters, (meters / 10).roundToInt() * 10)
    } else {
        val tenths = (meters / 100).roundToInt()
        stringResource(Res.string.distance_km, "${tenths / 10}.${tenths % 10}")
    }

@Composable
fun busName(bus: BusPosition): String = bus.name ?: stringResource(Res.string.bus_unit, bus.deviceId.toString())
