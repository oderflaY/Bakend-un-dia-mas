package com.eter.rutacree.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.preferredLanguages
import platform.UIKit.UIApplication
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIWindow

@Composable
actual fun rememberKeyValueStore(): KeyValueStore = remember { UserDefaultsStore() }

class UserDefaultsStore : KeyValueStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    override fun get(key: String): String? = defaults.stringForKey(key)
    override fun put(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, key)
    }
}

actual object LocalAppLocale {
    private const val LANG_KEY = "AppleLanguages"
    private val deviceDefault = NSLocale.preferredLanguages.first() as String
    private val LocalLocale = staticCompositionLocalOf { deviceDefault }

    actual val current: String
        @Composable get() = LocalLocale.current

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        val defaults = NSUserDefaults.standardUserDefaults
        // Compose resources on iOS read the preferred-language list.
        if (value == null) defaults.removeObjectForKey(LANG_KEY) else defaults.setObject(listOf(value), LANG_KEY)
        return LocalLocale.provides(value ?: deviceDefault)
    }
}

@Composable
actual fun ApplySystemTheme(mode: ThemeMode, dark: Boolean) {
    SideEffect {
        // Also switches the status bar and the native Mapbox view. "System" must clear the override,
        // otherwise the window would stop reporting the device's real appearance.
        val style = when (mode) {
            ThemeMode.System -> UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
            ThemeMode.Light -> UIUserInterfaceStyle.UIUserInterfaceStyleLight
            ThemeMode.Dark -> UIUserInterfaceStyle.UIUserInterfaceStyleDark
        }
        UIApplication.sharedApplication.windows.forEach { (it as? UIWindow)?.overrideUserInterfaceStyle = style }
    }
}
