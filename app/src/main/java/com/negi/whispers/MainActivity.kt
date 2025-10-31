// file: app/src/main/java/com/negi/whispers/MainActivity.kt
@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.negi.whispers

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.negi.whispers.ui.main.MainScreenEntryPoint
import com.negi.whispers.ui.main.MainScreenViewModel
import com.negi.whispers.ui.theme.WhispersTheme
import kotlinx.serialization.InternalSerializationApi

/**
 * Application entry activity for the Whisper App.
 *
 * Responsibilities:
 * - Initializes an immersive edge-to-edge layout.
 * - Instantiates [MainScreenViewModel] with an [Application] context factory.
 * - Sets up Compose UI rooted at [MainScreenEntryPoint].
 *
 * Design rationale:
 * - Uses [enableEdgeToEdge] to draw content under system bars.
 * - Keeps dependency setup lightweight (no DI framework).
 * - Opts into [InternalSerializationApi] for custom `MyRecord` serializer support.
 */
class MainActivity : ComponentActivity() {

    @OptIn(InternalSerializationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enables transparent system bars (edge-to-edge layout).
        enableEdgeToEdge()

        setContent {
            // ViewModel scoped to Application lifecycle via custom factory.
            val viewModel: MainScreenViewModel =
                viewModel(factory = MainScreenViewModel.factory(application))

            // Wraps UI in custom Material3 theme.
            WhispersTheme {
                MainScreenEntryPoint(viewModel)
            }
        }
    }
}
