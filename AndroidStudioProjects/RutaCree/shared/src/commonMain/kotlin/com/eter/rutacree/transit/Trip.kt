package com.eter.rutacree.transit

/** Distance (straight line, Haversine) at which the rider is told to get ready to exit. */
const val PREPARE_TO_EXIT_M = 500.0
const val ARRIVED_M = 80.0

enum class TripStage { Riding, PrepareToExit, Arrived }

data class Trip(
    val routeId: String,
    val boardingStopId: String?,
    val destinationStopId: String,
    val boardedBusId: Long?,
)

data class TripProgress(
    val destination: Stop,
    /** Stops still ahead of the rider, ending with the destination. */
    val remainingStops: List<Stop>,
    val passedStops: List<Stop>,
    val distanceToDestinationMeters: Double,
    val stage: TripStage,
) {
    val nextStop: Stop? get() = remainingStops.firstOrNull()
}

fun TransitRoute.tripProgress(boarding: Stop?, destination: Stop, rider: GeoPoint): TripProgress {
    val along = geometry.project(rider).alongMeters
    val from = boarding?.alongMeters ?: 0.0
    val upToDestination = stops.filter { it.alongMeters >= from - 1 && it.alongMeters <= destination.alongMeters + 1 }
    val (passed, remaining) = upToDestination.partition { it.alongMeters < along - 20 }
    val distance = distanceMeters(rider, destination.point)
    val stage = when {
        distance <= ARRIVED_M || along >= destination.alongMeters -> TripStage.Arrived
        distance <= PREPARE_TO_EXIT_M -> TripStage.PrepareToExit
        else -> TripStage.Riding
    }
    return TripProgress(destination, remaining.ifEmpty { listOf(destination) }, passed, distance, stage)
}
