package com.dingleinc.texttoolspro.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dingleinc.texttoolspro.MainActivity
import com.dingleinc.texttoolspro.R
import com.dingleinc.texttoolspro.data.Match
import com.dingleinc.texttoolspro.data.Var

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importConfig(it) }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-yaml")
    ) { uri ->
        uri?.let { viewModel.exportConfig(it) }
    }

    val canTextExpand by viewModel.canTextExpand.collectAsState()
    val dict by viewModel.dict.collectAsState()
    val editingKey by viewModel.editingKey.collectAsState()
    val currentMatch by viewModel.currentMatch.collectAsState()
    val showWelcome by viewModel.showWelcome.collectAsState()
    val lazyLoadIndex by viewModel.lazyLoadIndex.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val recreateActivity by viewModel.recreateActivity.collectAsState()

    var showMatchEditor by remember { mutableStateOf(false) }
    var showVariableTypePicker by remember { mutableStateOf(false) }
    var editingVariable by remember { mutableStateOf<Var?>(null) }
    var editingVariableIndex by remember { mutableStateOf(-1) }
    var newVarType by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var deleteConfirmKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshAccessibilityStatus()
    }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    LaunchedEffect(recreateActivity) {
        if (recreateActivity) {
            (context as? MainActivity)?.recreate()
            viewModel.onRecreateHandled()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            if (canTextExpand) {
                ExtendedFloatingActionButton(
                    onClick = {
                        viewModel.startNewMatch()
                        showMatchEditor = true
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = "Add") },
                    text = { Text("Add") }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            if (showWelcome) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Text(
                        text = stringResource(R.string.welcome_message),
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            if (!canTextExpand) {
                Text(
                    text = stringResource(R.string.accessibility_permission_message),
                    modifier = Modifier.padding(vertical = 16.dp)
                )
                OutlinedButton(
                    onClick = {
                        com.dingleinc.texttoolspro.util.AccessibilityChecker.openSettings(context)
                        viewModel.forceQuit()
                    }
                ) {
                    Text(stringResource(R.string.consent_and_open_settings))
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = { viewModel.saveDict() }) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.padding(4.dp))
                        Text(stringResource(R.string.make_sure_to_save))
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search keywords...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    singleLine = true
                )

                val filteredDict = if (searchQuery.isBlank()) {
                    dict
                } else {
                    dict.filter { (key, match) ->
                        key.contains(searchQuery, ignoreCase = true) ||
                        (match.replace?.contains(searchQuery, ignoreCase = true) == true)
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val displayCount = minOf(filteredDict.size, lazyLoadIndex)
                    items(filteredDict.entries.take(displayCount)) { entry ->
                        MatchCard(
                            trigger = entry.key,
                            replace = entry.value.replace ?: entry.value.regex ?: "",
                            onEdit = {
                                viewModel.editMatchByKey(entry.key)
                                showMatchEditor = true
                            },
                            onDelete = { deleteConfirmKey = entry.key }
                        )
                    }
                    if (filteredDict.size > lazyLoadIndex) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                TextButton(onClick = { viewModel.loadMore() }) {
                                    Text(stringResource(R.string.load_more))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMatchEditor) {
        val isEditing = editingKey != null
        MatchEditorDialog(
            match = currentMatch,
            isEditing = isEditing,
            existingKeys = dict.keys,
            onSave = { match ->
                viewModel.saveMatchFromDialog(match, isEditing, editingKey)
                showMatchEditor = false
            },
            onDismiss = { showMatchEditor = false },
            onAddVariable = { showVariableTypePicker = true },
            onEditVariable = { v ->
                editingVariable = v
                editingVariableIndex = currentMatch.vars?.indexOf(v) ?: -1
                newVarType = v.type ?: ""
            },
            onRemoveVariable = { v ->
                viewModel.removeVar(v)
            }
        )
    }

    if (showVariableTypePicker) {
        VariableTypePicker(
            onDismiss = { showVariableTypePicker = false },
            onSelect = { type ->
                showVariableTypePicker = false
                newVarType = type
                editingVariable = null
                editingVariableIndex = -1
            }
        )
    }

    if (newVarType.isNotBlank() && editingVariable == null) {
        VariableEditorDialog(
            varType = newVarType,
            existingVar = null,
            onSave = { v ->
                viewModel.saveVariable(v)
                newVarType = ""
            },
            onDismiss = { newVarType = "" }
        )
    }

    if (editingVariable != null) {
        VariableEditorDialog(
            varType = editingVariable!!.type ?: newVarType,
            existingVar = editingVariable,
            onSave = { v ->
                if (editingVariableIndex >= 0) {
                    viewModel.updateVariable(editingVariableIndex, v)
                }
                editingVariable = null
                editingVariableIndex = -1
            },
            onDismiss = {
                editingVariable = null
                editingVariableIndex = -1
            }
        )
    }

    deleteConfirmKey?.let { key ->
        AlertDialog(
            onDismissRequest = { deleteConfirmKey = null },
            title = { Text("Delete Match") },
            text = { Text("Delete '$key'?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeMatchByKey(key)
                    deleteConfirmKey = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmKey = null }) { Text("Cancel") }
            }
        )
    }

    if (showSettings) {
        SettingsSheet(
            viewModel = viewModel,
            onDismiss = { showSettings = false },
            onImport = {
                importLauncher.launch(arrayOf("text/yaml", "text/yml", "application/x-yaml", "*/*"))
            },
            onExport = {
                exportLauncher.launch("expandroid_export.yml")
            }
        )
    }
}

@Composable
fun MatchCard(
    trigger: String,
    replace: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(trigger, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(replace, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
            Row {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
