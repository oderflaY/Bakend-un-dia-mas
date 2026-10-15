package com.eter.rutacree.driver

import com.eter.rutacree.transit.IncidentType

/**
 * A report ready to send in WhatsApp. [phone] is dispatch's number (digits with country code); empty
 * lets WhatsApp ask for the chat or group. [id] changes on every report so the screen opens each once.
 */
data class WhatsAppMessage(val id: Long, val phone: String, val text: String)

/**
 * The texts the driver sends to dispatch. Always in Spanish: they are read by dispatch in Durango,
 * whatever language the driver's phone uses.
 */
internal object WhatsAppReports {
    fun start(shift: DriverShift): String = buildString {
        appendLine("🚌 *Inicio de ruta* — RutaCree")
        header(shift)
        appendLine("Hora de inicio: ${clock(shift.startMinute)}")
        append(users(shift.passengers))
    }

    /** The trip's users as the DIF's weekly log counts them. */
    private fun users(p: com.eter.rutacree.api.Passengers) =
        "Usuarios: ${p.total} (${p.men} hombres, ${p.women} mujeres)\n" +
            "Discapacidad: ${p.disability} · Adultos mayores: ${p.elderly} · Embarazadas: ${p.pregnant} · Acompañantes: ${p.companions}"

    fun end(shift: DriverShift, endMinute: Int, finalLocation: FinalLocation): String = buildString {
        appendLine("🏁 *Fin de ruta* — RutaCree")
        header(shift)
        appendLine("Inicio: ${clock(shift.startMinute)} · Fin: ${clock(endMinute)} (${duration(shift.startMinute, endMinute)})")
        appendLine(users(shift.passengers))
        append("La unidad quedó en: ${finalLocation.spanish}")
    }

    fun failure(shift: DriverShift, type: IncidentType, minute: Int): String = buildString {
        appendLine(
            when (type) {
                IncidentType.Breakdown -> "⚠️ *Falla del camión* — RutaCree"
                IncidentType.RampFailure -> "♿ *Falla de la rampa* — RutaCree"
                IncidentType.Accident -> "🚨 *Accidente* — RutaCree"
            }
        )
        header(shift)
        appendLine("Hora: ${clock(minute)}")
        append(
            when (type) {
                IncidentType.RampFailure -> "La unidad sigue su recorrido sin rampa. Se avisó a los pasajeros."
                else -> "La unidad no puede seguir el recorrido. Se avisó a los pasajeros."
            }
        )
    }

    private fun StringBuilder.header(shift: DriverShift) {
        appendLine("Chofer: ${shift.driverName}")
        appendLine("Unidad ${shift.busNumber} · ${shift.route.name}")
    }

    /** "07:05" from minutes since midnight. */
    fun clock(minute: Int): String {
        val m = ((minute % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
    }

    /** "45 min", "1 h 05 min"; a route that crosses midnight still counts forward. */
    fun duration(startMinute: Int, endMinute: Int): String {
        val total = ((endMinute - startMinute) % MINUTES_PER_DAY + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return if (total < 60) "$total min" else "${total / 60} h ${(total % 60).toString().padStart(2, '0')} min"
    }

    private val FinalLocation.spanish: String
        get() = when (this) {
            FinalLocation.Terminal -> "terminal"
            FinalLocation.Workshop -> "taller"
            FinalLocation.Depot -> "encierro"
        }

    private const val MINUTES_PER_DAY = 24 * 60
}
