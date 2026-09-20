package com.example.networkrisktester

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.example.networkrisktester.ui.DetectionApp
import com.example.networkrisktester.ui.DetectionViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: DetectionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DetectionApp(viewModel)
        }
    }
}
