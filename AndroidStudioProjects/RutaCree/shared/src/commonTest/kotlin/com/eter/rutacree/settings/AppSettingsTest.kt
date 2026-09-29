package com.eter.rutacree.settings

import com.eter.rutacree.FakeKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsTest {
    @Test
    fun defaultsFollowThePhone() {
        val settings = AppSettings(FakeKeyValueStore())
        assertEquals(ThemeMode.System, settings.theme)
        assertEquals(AppLanguage.System, settings.language)
        assertEquals(null, settings.language.tag)
    }

    @Test
    fun choicesSurviveARestart() {
        val store = FakeKeyValueStore()
        AppSettings(store).apply {
            updateTheme(ThemeMode.Dark)
            updateLanguage(AppLanguage.English)
        }
        val reopened = AppSettings(store)
        assertEquals(ThemeMode.Dark, reopened.theme)
        assertEquals(AppLanguage.English, reopened.language)
        assertEquals("en", reopened.language.tag)
    }

    @Test
    fun unknownStoredValuesFallBackToDefaults() {
        val store = FakeKeyValueStore(mapOf("settings.theme" to "Neon", "settings.language" to "Klingon"))
        val settings = AppSettings(store)
        assertEquals(ThemeMode.System, settings.theme)
        assertEquals(AppLanguage.System, settings.language)
    }
}
