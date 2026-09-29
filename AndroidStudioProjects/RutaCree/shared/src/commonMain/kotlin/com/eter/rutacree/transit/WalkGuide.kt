package com.eter.rutacree.transit

/** Average walking pace (4.3 km/h), a bit slow to leave room for people using a cane or wheelchair. */
const val WALK_SPEED_MS = 1.2
/** Beyond this the rider is not walking to the stop; the guide stays hidden. */
const val WALK_MAX_M = 5_000.0
/** Leaving the street path by more than this asks for a new one. */
const val WALK_OFF_PATH_M = 40.0
/** How far ahead on the path the "walk towards" heading looks, so it ignores GPS jitter. */
private const val HEADING_LOOKAHEAD_M = 25.0

/** How the rider gets from where they are to their stop on foot. */
data class WalkGuide(
    val stop: Stop,
    val remainingMeters: Double,
    val remainingSeconds: Double,
    /** Bearing to walk now, in degrees from north; drawn as an arrow on a north-up map. */
    val headingDegrees: Double,
    val nextStep: DirectionStep?,
    val nextStepMeters: Double?,
    /** From the rider to the stop: the street path, or a straight line until one arrives. */
    val path: List<GeoPoint>,
    val byStreet: Boolean,
    /** Inside the stop's geofence: nothing left to walk. */
    val arrived: Boolean,
) {
    val heading: Compass get() = compassOf(headingDegrees)
}

/**
 * Builds the guide from [rider] to [stop], following [directions] when it leads there.
 * Null when the stop is too far to walk.
 */
fun walkGuide(rider: GeoPoint, stop: Stop, directions: Directions?): WalkGuide? {
    val straight = distanceMeters(rider, stop.point)
    if (straight > WALK_MAX_M) return null
    val arrived = straight <= GEOFENCE_RADIUS_M
    val progress = directions?.path?.project(rider)
    if (arrived || directions == null || progress == null || progress.offsetMeters > WALK_OFF_PATH_M) {
        return WalkGuide(
            stop = stop,
            remainingMeters = straight,
            remainingSeconds = straight / WALK_SPEED_MS,
            headingDegrees = bearingDegrees(rider, stop.point),
            nextStep = null,
            nextStepMeters = null,
            path = if (arrived) emptyList() else listOf(rider, stop.point),
            byStreet = false,
            arrived = arrived,
        )
    }
    val along = progress.alongMeters
    val length = directions.path.lengthMeters
    val remaining = (length - along).coerceAtLeast(0.0)
    val next = directions.stepAlong.indexOfFirst { it > along + 5 }.takeIf { it >= 0 }
    val ahead = directions.path.pointAt(along + HEADING_LOOKAHEAD_M)
    return WalkGuide(
        stop = stop,
        remainingMeters = remaining,
        remainingSeconds = if (length > 0) directions.durationSeconds * remaining / length else 0.0,
        headingDegrees = bearingDegrees(rider, if (ahead == rider) stop.point else ahead),
        nextStep = next?.let { directions.steps[it] },
        nextStepMeters = next?.let { directions.stepAlong[it] - along },
        path = listOf(rider) + directions.path.pointsAfter(along),
        byStreet = true,
        arrived = false,
    )
}
