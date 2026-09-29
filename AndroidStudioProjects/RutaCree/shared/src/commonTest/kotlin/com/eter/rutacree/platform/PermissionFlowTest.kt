package com.eter.rutacree.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class PermissionFlowTest {
    private fun statuses(
        location: PermissionStatus,
        notifications: PermissionStatus = PermissionStatus.Granted,
        overlay: PermissionStatus = PermissionStatus.NotApplicable,
    ) = mapOf(
        AppPermission.Location to location,
        AppPermission.Notifications to notifications,
        AppPermission.Overlay to overlay,
    )

    @Test
    fun allowAsksTheSystemUntilOnlySettingsCanHelp() {
        assertEquals(PermissionAction.AskSystem, permissionAction(PermissionStatus.NotRequested))
        assertEquals(PermissionAction.AskSystem, permissionAction(PermissionStatus.Denied))
        assertEquals(PermissionAction.OpenSettings, permissionAction(PermissionStatus.Blocked))
        assertEquals(PermissionAction.None, permissionAction(PermissionStatus.Granted))
        assertEquals(PermissionAction.None, permissionAction(PermissionStatus.NotApplicable))
        assertEquals(PermissionAction.None, permissionAction(null))
    }

    @Test
    fun nothingIsAskedOnceEverythingIsGranted() {
        val granted = statuses(PermissionStatus.Granted, overlay = PermissionStatus.Granted)
        PermissionNeed.entries.forEach { assertEquals(emptyList(), missingPermissions(it, granted)) }
    }

    @Test
    fun onlyWhatTheFeatureUsesIsAsked() {
        val none = statuses(PermissionStatus.NotRequested, PermissionStatus.NotRequested, PermissionStatus.NotRequested)
        assertEquals(listOf(AppPermission.Location), missingPermissions(PermissionNeed.RiderLocation, none))
        assertEquals(
            listOf(AppPermission.Location, AppPermission.Notifications),
            missingPermissions(PermissionNeed.TripAlerts, none),
        )
        assertEquals(
            listOf(AppPermission.Location, AppPermission.Notifications),
            missingPermissions(PermissionNeed.RampRequest, none),
        )
        assertEquals(
            listOf(AppPermission.Location, AppPermission.Notifications, AppPermission.Overlay),
            missingPermissions(PermissionNeed.DriverShift, none),
        )
    }

    @Test
    fun deniedOrBlockedPermissionsAreAskedAgainEveryTime() {
        val refused = statuses(PermissionStatus.Blocked, PermissionStatus.Denied)
        assertEquals(
            listOf(AppPermission.Location, AppPermission.Notifications),
            missingPermissions(PermissionNeed.TripAlerts, refused),
        )
    }

    @Test
    fun iosHasNoOverlayToAskFor() {
        val ios = statuses(PermissionStatus.Granted, PermissionStatus.Granted, PermissionStatus.NotApplicable)
        assertEquals(emptyList(), missingPermissions(PermissionNeed.DriverShift, ios))
    }
}
