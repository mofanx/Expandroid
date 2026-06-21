package com.dingleinc.texttoolspro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dingleinc.texttoolspro.ui.theme.ExpandroidTheme
import com.dingleinc.texttoolspro.ui.MainScreen
import com.dingleinc.texttoolspro.ui.MainViewModel
import com.dingleinc.texttoolspro.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = viewModel()
            val themeMode by viewModel.themeModeFlow.collectAsState()
            ExpandroidTheme(themeMode = themeMode) {
                MainScreen(viewModel = viewModel)
            }
        }
    }
}
