package com.dingleinc.texttoolspro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import com.dingleinc.texttoolspro.ui.theme.ThemeMode

@Composable
fun ThemeSwitcher(currentMode: ThemeMode, onModeChange: (ThemeMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        IconButton(onClick = { onModeChange(ThemeMode.Light) }) {
            Icon(
                Icons.Default.WbSunny,
                contentDescription = "Light",
                tint = if (currentMode == ThemeMode.Light) androidx.compose.material3.MaterialTheme.colorScheme.primary else androidx.compose.material3.MaterialTheme.colorScheme.onSurface
            )
        }
        IconButton(onClick = { onModeChange(ThemeMode.Dark) }) {
            Icon(
                Icons.Default.NightsStay,
                contentDescription = "Dark",
                tint = if (currentMode == ThemeMode.Dark) androidx.compose.material3.MaterialTheme.colorScheme.primary else androidx.compose.material3.MaterialTheme.colorScheme.onSurface
            )
        }
        IconButton(onClick = { onModeChange(ThemeMode.Auto) }) {
            Icon(
                Icons.Default.BrightnessAuto,
                contentDescription = "Auto",
                tint = if (currentMode == ThemeMode.Auto) androidx.compose.material3.MaterialTheme.colorScheme.primary else androidx.compose.material3.MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
