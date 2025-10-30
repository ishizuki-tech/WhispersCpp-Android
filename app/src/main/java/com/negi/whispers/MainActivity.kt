package com.negi.whispers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.negi.whispers.ui.main.MainScreenEntryPoint
import com.negi.whispers.ui.main.MainScreenViewModel
import kotlinx.serialization.InternalSerializationApi

class MainActivity : ComponentActivity() {
    @OptIn(InternalSerializationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainScreenViewModel =
                viewModel(factory = MainScreenViewModel.factory(application))
            MainScreenEntryPoint(viewModel)
        }
    }
}

