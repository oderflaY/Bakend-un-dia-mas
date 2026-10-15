package com.eter.rutacree.driver

import com.eter.rutacree.platform.whatsAppUrl
import kotlin.test.Test
import kotlin.test.assertEquals

class WhatsAppReportsTest {
    @Test
    fun clockPadsHoursAndMinutes() {
        assertEquals("07:05", WhatsAppReports.clock(7 * 60 + 5))
        assertEquals("00:00", WhatsAppReports.clock(24 * 60))
    }

    @Test
    fun durationCountsForwardAcrossMidnight() {
        assertEquals("45 min", WhatsAppReports.duration(60, 105))
        assertEquals("1 h 05 min", WhatsAppReports.duration(23 * 60 + 30, 35))
    }

    @Test
    fun linkKeepsOnlyDigitsAndEncodesTheText() {
        assertEquals("https://wa.me/5216181234567?text=Hola%20%2A1%2A%0AUnidad", whatsAppUrl("+52 1 618 123 4567", "Hola *1*\nUnidad"))
        assertEquals("https://wa.me/?text=ok", whatsAppUrl("", "ok"))
    }
}
