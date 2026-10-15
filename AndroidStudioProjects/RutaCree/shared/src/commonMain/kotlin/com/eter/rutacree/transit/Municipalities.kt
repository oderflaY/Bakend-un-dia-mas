package com.eter.rutacree.transit

/**
 * Cabeceras municipales of Durango, the same list the backend uses to tag each route
 * (internal/transit/municipios.go). Used to guess the rider's municipio from the GPS.
 */
private val CABECERAS = listOf(
    "Durango" to GeoPoint(24.0277, -104.6532),
    "Gómez Palacio" to GeoPoint(25.5611, -103.4983),
    "Lerdo" to GeoPoint(25.5383, -103.5245),
    "Santiago Papasquiaro" to GeoPoint(25.0439, -105.4192),
    "Pueblo Nuevo (El Salto)" to GeoPoint(23.7783, -105.3606),
    "Canatlán" to GeoPoint(24.5250, -104.7740),
    "Guadalupe Victoria" to GeoPoint(24.4469, -104.1211),
    "Nombre de Dios" to GeoPoint(23.8492, -104.2461),
    "Vicente Guerrero" to GeoPoint(23.7353, -103.9842),
    "Poanas (Villa Unión)" to GeoPoint(23.9744, -104.0469),
    "Mezquital" to GeoPoint(23.4736, -104.3961),
    "Súchil" to GeoPoint(23.6211, -103.9233),
    "Cuencamé" to GeoPoint(24.8711, -103.6975),
    "Peñón Blanco" to GeoPoint(24.7881, -104.0306),
    "Pánuco de Coronado" to GeoPoint(24.5392, -104.3289),
    "San Juan del Río" to GeoPoint(24.7769, -104.4614),
    "Rodeo" to GeoPoint(25.1778, -104.5594),
    "Nazas" to GeoPoint(25.2250, -104.1131),
    "Mapimí" to GeoPoint(25.8333, -103.8500),
    "Tlahualilo" to GeoPoint(26.1053, -103.4436),
    "Santa María del Oro" to GeoPoint(25.9500, -105.3667),
    "Tepehuanes" to GeoPoint(25.3394, -105.7258),
    "Guanaceví" to GeoPoint(25.9333, -105.9500),
    "Topia" to GeoPoint(25.2119, -106.5711),
    "Tamazula" to GeoPoint(24.9686, -106.9672),
)

/** Municipios served by at least one of [routes], in the order they first appear. */
fun municipalitiesOf(routes: List<TransitRoute>): List<String> = routes.flatMap { it.municipalities }.distinct()

/** The municipio of [location] (nearest cabecera), if any of [routes] serves it. */
fun municipalityAt(location: GeoPoint, routes: List<TransitRoute>): String? {
    val served = municipalitiesOf(routes)
    return CABECERAS
        .filter { it.first in served }
        .minByOrNull { distanceMeters(location, it.second) }
        ?.first
}
