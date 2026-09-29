package com.eter.rutacree.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eter.rutacree.settings.AppLanguage
import com.eter.rutacree.settings.AppSettings
import com.eter.rutacree.settings.ThemeMode
import com.eter.rutacree.ui.FieldLabel
import com.eter.rutacree.ui.Neu
import com.eter.rutacree.ui.NeuButton
import com.eter.rutacree.ui.NeuSegmented
import com.eter.rutacree.ui.SectionTitle
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import rutacree.shared.generated.resources.Res
import rutacree.shared.generated.resources.action_review_permissions
import rutacree.shared.generated.resources.language_en
import rutacree.shared.generated.resources.language_es
import rutacree.shared.generated.resources.language_system
import rutacree.shared.generated.resources.section_app_settings
import rutacree.shared.generated.resources.setting_language
import rutacree.shared.generated.resources.setting_theme
import rutacree.shared.generated.resources.settings_permissions
import rutacree.shared.generated.resources.theme_dark
import rutacree.shared.generated.resources.theme_light
import rutacree.shared.generated.resources.theme_system

val ThemeMode.label: StringResource
    get() = when (this) {
        ThemeMode.System -> Res.string.theme_system
        ThemeMode.Light -> Res.string.theme_light
        ThemeMode.Dark -> Res.string.theme_dark
    }

val AppLanguage.label: StringResource
    get() = when (this) {
        AppLanguage.System -> Res.string.language_system
        AppLanguage.Spanish -> Res.string.language_es
        AppLanguage.English -> Res.string.language_en
    }

/** Theme, language and permissions; shared by the rider profile and the driver settings tab. */
@Composable
fun SettingsSection(settings: AppSettings, onReviewPermissions: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(Res.string.section_app_settings))
        FieldLabel(stringResource(Res.string.setting_theme))
        NeuSegmented(ThemeMode.entries, settings.theme, { stringResource(it.label) }, settings::updateTheme)
        Spacer(Modifier.height(20.dp))
        FieldLabel(stringResource(Res.string.setting_language))
        NeuSegmented(AppLanguage.entries, settings.language, { stringResource(it.label) }, settings::updateLanguage)
        Spacer(Modifier.height(20.dp))
        NeuButton(
            onClick = onReviewPermissions,
            actionLabel = stringResource(Res.string.action_review_permissions),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(Res.string.settings_permissions),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Neu.BlueStrong,
            )
        }
    }
}
