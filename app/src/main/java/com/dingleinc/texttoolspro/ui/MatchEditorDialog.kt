package com.dingleinc.texttoolspro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.dingleinc.texttoolspro.data.Match
import com.dingleinc.texttoolspro.data.Var

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MatchEditorDialog(
    match: Match,
    isEditing: Boolean,
    existingKeys: Set<String>,
    onSave: (Match) -> Unit,
    onDismiss: () -> Unit,
    onAddVariable: () -> Unit,
    onEditVariable: (Var) -> Unit,
    onRemoveVariable: (Var) -> Unit
) {
    var triggers by remember { mutableStateOf(match.triggers ?: listOfNotNull(match.trigger).toMutableList()) }
    var triggerInput by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf(match.replace ?: "") }
    var regex by remember { mutableStateOf(match.regex ?: "") }
    var showAdvanced by remember { mutableStateOf(false) }
    var wordMode by remember { mutableStateOf(
        when {
            match.leftWord && match.rightWord -> 1
            match.leftWord -> 2
            match.rightWord -> 3
            else -> 0
        }
    ) }
    var propagateCase by remember { mutableStateOf(match.propagateCase) }
    var uppercaseStyle by remember { mutableStateOf(match.uppercaseStyle ?: "") }
    val vars = remember { mutableStateListOf<Var>().apply { match.vars?.let { addAll(it) } } }
    var showDeleteVarConfirm by remember { mutableStateOf<Var?>(null) }
    var showConflictDialog by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(if (isEditing) "Edit: ${triggers.firstOrNull() ?: ""}" else "New Match")
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            if (triggers.isEmpty() && regex.isBlank()) {
                                return@TextButton
                            }
                            if (replace.isBlank() && regex.isBlank()) {
                                return@TextButton
                            }
                            val keysToCheck = if (regex.isNotBlank()) {
                                listOf("__regex__$regex")
                            } else {
                                triggers.map { it }
                            }
                            val conflictKey = keysToCheck.firstOrNull { it in existingKeys }
                            if (conflictKey != null && !isEditing) {
                                showConflictDialog = conflictKey
                            } else if (conflictKey != null && isEditing && conflictKey != (match.trigger ?: "")) {
                                showConflictDialog = conflictKey
                            } else {
                                val result = match.copy(
                                    trigger = triggers.firstOrNull(),
                                    triggers = if (triggers.size > 1) triggers.toMutableList() else null,
                                    replace = replace,
                                    regex = regex.ifBlank { null },
                                    vars = vars.toList().ifEmpty { null }?.toMutableList(),
                                    word = wordMode == 1,
                                    leftWord = wordMode == 2 || wordMode == 1,
                                    rightWord = wordMode == 3 || wordMode == 1,
                                    propagateCase = propagateCase,
                                    uppercaseStyle = uppercaseStyle.ifBlank { null }
                                )
                                onSave(result)
                            }
                        }) {
                            Text(if (isEditing) "Update" else "Add")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(8.dp))

                if (regex.isBlank()) {
                    Text("Triggers", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        triggers.forEach { trigger ->
                            AssistChip(
                                onClick = { triggers = triggers.filterNot { it == trigger }.toMutableList() },
                                label = { Text(trigger) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Remove",
                                        modifier = Modifier.padding(start = 4.dp)
                                    )
                                }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = triggerInput,
                        onValueChange = { triggerInput = it },
                        label = { Text("Type trigger and press Enter") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                        keyboardActions = KeyboardActions(onDone = {
                            if (triggerInput.isNotBlank()) {
                                triggers = (triggers + triggerInput.trim()).toMutableList()
                                triggerInput = ""
                            }
                        })
                    )

                    Spacer(Modifier.height(16.dp))
                }

                OutlinedTextField(
                    value = regex,
                    onValueChange = { regex = it },
                    label = { Text("Regex (optional, replaces triggers)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (regex.isBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text("Replace Text", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = replace,
                        onValueChange = { replace = it },
                        label = { Text("Replacement text") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6
                    )
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Variables (${vars.size})", style = MaterialTheme.typography.titleSmall)
                    FilledTonalButton(onClick = onAddVariable) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                        Text("Add")
                    }
                }

                vars.forEach { v ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(v.name ?: "", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    v.type ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            IconButton(onClick = { onEditVariable(v) }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { showDeleteVarConfirm = v }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(if (showAdvanced) "▼ Advanced Options" else "▶ Advanced Options")
                }

                if (showAdvanced && regex.isBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Word Boundary", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Off" to 0, "Full" to 1, "Left" to 2, "Right" to 3).forEach { (label, mode) ->
                            FilledTonalButton(
                                onClick = { wordMode = if (wordMode == mode) 0 else mode },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    label,
                                    color = if (wordMode == mode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Propagate Case")
                        Switch(checked = propagateCase, onCheckedChange = { propagateCase = it })
                    }

                    if (propagateCase) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = uppercaseStyle,
                            onValueChange = { uppercaseStyle = it },
                            label = { Text("Uppercase Style (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    showDeleteVarConfirm?.let { v ->
        AlertDialog(
            onDismissRequest = { showDeleteVarConfirm = null },
            title = { Text("Delete Variable") },
            text = { Text("Delete variable '${v.name}'?") },
            confirmButton = {
                TextButton(onClick = {
                    vars.remove(v)
                    onRemoveVariable(v)
                    showDeleteVarConfirm = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteVarConfirm = null }) { Text("Cancel") }
            }
        )
    }

    showConflictDialog?.let { conflictKey ->
        AlertDialog(
            onDismissRequest = { showConflictDialog = null },
            title = { Text("Trigger Conflict") },
            text = { Text("Trigger '$conflictKey' already exists. Overwrite?") },
            confirmButton = {
                TextButton(onClick = {
                    showConflictDialog = null
                    val result = match.copy(
                        trigger = triggers.firstOrNull(),
                        triggers = if (triggers.size > 1) triggers.toMutableList() else null,
                        replace = replace,
                        regex = regex.ifBlank { null },
                        vars = vars.toList().ifEmpty { null }?.toMutableList(),
                        word = wordMode == 1,
                        leftWord = wordMode == 2 || wordMode == 1,
                        rightWord = wordMode == 3 || wordMode == 1,
                        propagateCase = propagateCase,
                        uppercaseStyle = uppercaseStyle.ifBlank { null }
                    )
                    onSave(result)
                }) { Text("Overwrite") }
            },
            dismissButton = {
                TextButton(onClick = { showConflictDialog = null }) { Text("Cancel") }
            }
        )
    }
}
