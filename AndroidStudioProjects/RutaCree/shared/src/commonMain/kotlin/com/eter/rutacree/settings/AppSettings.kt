package com.eter.rutacree.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode { System, Light, Dark }

/** Which side of the app is open; decided by the server at sign-in (guests are passengers). */
enum class UserRole { Passenger, Driver }

/** [tag] is the BCP-47 language tag; null follows the device language. */
enum class AppLanguage(val tag: String?) { System(null), Spanish("es"), English("en") }

/** Small persistent key-value storage (SharedPreferences / NSUserDefaults). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

@Composable
expect fun rememberKeyValueStore(): KeyValueStore

/**
 * Overrides the locale Compose resources read, so strings switch language at runtime.
 * Pass null to go back to the device language.
 */
expect object LocalAppLocale {
    val current: String @Composable get

    @Composable
    infix fun provides(value: String?): ProvidedValue<*>
}

/** Keeps system bars (status bar icons, native views) in sync with the in-app theme. */
@Composable
expect fun ApplySystemTheme(mode: ThemeMode, dark: Boolean)

class AppSettings(private val store: KeyValueStore) {
    var theme by mutableStateOf(read(THEME_KEY, ThemeMode.System))
        private set
    var language by mutableStateOf(read(LANGUAGE_KEY, AppLanguage.System))
        private set

    /**
     * Municipio whose routes are shown: null until the user picks one (the app then guesses it from
     * the GPS), "" for all of them.
     */
    var municipality by mutableStateOf(store.get(MUNICIPALITY_KEY))
        private set

    fun updateMunicipality(value: String?) {
        municipality = value.orEmpty()
        store.put(MUNICIPALITY_KEY, municipality)
    }
    fun updateTheme(value: ThemeMode) {
        theme = value
        store.put(THEME_KEY, value.name)
    }

    fun updateLanguage(value: AppLanguage) {
        language = value
        store.put(LANGUAGE_KEY, value.name)
    }

    private inline fun <reified T : Enum<T>> read(key: String, default: T): T =
        store.get(key)?.let { saved -> enumValues<T>().firstOrNull { it.name == saved } } ?: default

    private companion object {
        const val THEME_KEY = "settings.theme"
        const val LANGUAGE_KEY = "settings.language"
        const val MUNICIPALITY_KEY = "settings.municipality"
    }
}
