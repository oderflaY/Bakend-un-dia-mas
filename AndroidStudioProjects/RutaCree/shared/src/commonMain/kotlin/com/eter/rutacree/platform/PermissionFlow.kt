package com.eter.rutacree.platform

/**
 * Features that need permissions. Each one asks, right when it is used, for whatever it still
 * lacks; the feature then goes on either way with what it was given.
 */
enum class PermissionNeed(val permissions: List<AppPermission>) {
    /** Radar and ramp screens: nearest stop, geofence and the walk to the stop. */
    RiderLocation(listOf(AppPermission.Location)),

    /** Trip mode: follow the ride and warn before the stop, also with the screen off. */
    TripAlerts(listOf(AppPermission.Location, AppPermission.Notifications)),

    /** Ramp request: which stop the rider is at, and the answer from the driver. */
    RampRequest(listOf(AppPermission.Location, AppPermission.Notifications)),

    /** Driver shift: broadcast the bus position and ring ramp alerts over other apps. */
    DriverShift(listOf(AppPermission.Location, AppPermission.Notifications, AppPermission.Overlay)),
}

/** What pressing "Allow" does for a permission in a given state. */
enum class PermissionAction { None, AskSystem, OpenSettings }

fun permissionAction(status: PermissionStatus?): PermissionAction = when (status) {
    PermissionStatus.NotRequested, PermissionStatus.Denied -> PermissionAction.AskSystem
    PermissionStatus.Blocked -> PermissionAction.OpenSettings
    PermissionStatus.Granted, PermissionStatus.NotApplicable, null -> PermissionAction.None
}

/** The permissions [need] still lacks on this phone, in the order they should be asked. */
fun missingPermissions(need: PermissionNeed, statuses: Map<AppPermission, PermissionStatus>): List<AppPermission> =
    need.permissions.filter { permissionAction(statuses[it]) != PermissionAction.None }
