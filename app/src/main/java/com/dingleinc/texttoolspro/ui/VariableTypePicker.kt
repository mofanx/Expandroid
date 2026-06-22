package com.dingleinc.texttoolspro.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DynamicForm
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VariableTypePicker(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                "Select Variable Type",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            val types = listOf(
                "echo" to ("Echo" to "Plain text replacement"),
                "date" to ("Date" to "Date and time formatting"),
                "clipboard" to ("Clipboard" to "Insert clipboard content"),
                "random" to ("Random" to "Random choice from list"),
                "choice" to ("Choice" to "Interactive list selection"),
                "form" to ("Form" to "Multi-field form input"),
                "shell" to ("Shell" to "Execute shell command"),
                "script" to ("Script" to "Execute script with args"),
                "javascript" to ("JavaScript" to "Run JavaScript code"),
                "http" to ("HTTP" to "Fetch data from URL"),
                "match" to ("Match" to "Reference another match"),
                "intent" to ("Intent" to "Broadcast intent plugin"),
                "content" to ("Content" to "Query content provider")
            )

            types.forEach { (type, info) ->
                VariableTypeRow(
                    icon = iconForType(type),
                    title = info.first,
                    description = info.second,
                    onClick = { onSelect(type) }
                )
                HorizontalDivider()
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun VariableTypeRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onClick) { Text("Select") }
    }
}

private fun iconForType(type: String): androidx.compose.ui.graphics.vector.ImageVector = when (type) {
    "echo" -> Icons.Default.FormatQuote
    "date" -> Icons.Default.CalendarMonth
    "clipboard" -> Icons.Default.ContentPaste
    "random" -> Icons.Default.Shuffle
    "choice" -> Icons.Default.ListAlt
    "form" -> Icons.Default.DynamicForm
    "shell" -> Icons.Default.Terminal
    "script" -> Icons.Default.PlayArrow
    "javascript" -> Icons.Default.Code
    "http" -> Icons.Default.Http
    "match" -> Icons.Default.Extension
    "intent" -> Icons.Default.Cloud
    "content" -> Icons.Default.ContentPaste
    else -> Icons.Default.FormatQuote
}
