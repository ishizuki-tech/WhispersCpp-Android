// file: app/src/main/java/com/negi/whispers/MainActivity.kt
package com.negi.whispers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.negi.whispers.ui.main.MainScreenEntryPoint
import com.negi.whispers.ui.main.MainScreenViewModel
import kotlinx.serialization.InternalSerializationApi

/**
 * Application entry activity.
 *
 * Responsibilities:
 *  - Initializes edge-to-edge layout.
 *  - Instantiates [MainScreenViewModel] using a factory bound to the Application context.
 *  - Sets the Compose UI content tree rooted at [MainScreenEntryPoint].
 *
 * Design rationale:
 *  - Uses `enableEdgeToEdge()` for immersive layout (status/navigation bars transparent).
 *  - Uses `viewModel(factory = ...)` instead of Hilt to keep dependency setup lightweight.
 *  - Marks `@OptIn(InternalSerializationApi::class)` to allow `kotlinx.serialization` features
 *    (required by MyRecord’s custom serializer).
 */
class MainActivity : ComponentActivity() {

    @OptIn(InternalSerializationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enables immersive content layout under system bars.
        enableEdgeToEdge()

        setContent {
            // Obtain ViewModel bound to the application lifecycle.
            // Factory is used instead of default constructor because ViewModel needs Application context.
            val viewModel: MainScreenViewModel =
                viewModel(factory = MainScreenViewModel.factory(application))

            // Entry point composable handling permissions, UI, and logic orchestration.
            MainScreenEntryPoint(viewModel)
        }
    }
}
