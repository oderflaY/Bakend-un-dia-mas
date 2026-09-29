package com.eter.rutacree

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.eter.rutacree.transit.AppConfig

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val config = AppConfig(
            mapboxAccessToken = BuildConfig.MAPBOX_ACCESS_TOKEN,
            mapboxStyleUrl = BuildConfig.MAPBOX_STYLE_URL,
            mapboxDarkStyleUrl = BuildConfig.MAPBOX_DARK_STYLE_URL,
            apiBaseUrl = BuildConfig.API_BASE_URL,
        )
        setContent {
            App(config)
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}