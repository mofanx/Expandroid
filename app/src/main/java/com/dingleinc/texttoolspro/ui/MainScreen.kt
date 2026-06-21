package com.dingleinc.texttoolspro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dingleinc.texttoolspro.R
import com.dingleinc.texttoolspro.data.Match
import com.dingleinc.texttoolspro.data.Var
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val canTextExpand by viewModel.canTextExpand.collectAsState()
    val dict by viewModel.dict.collectAsState()
    val currentMatch by viewModel.currentMatch.collectAsState()
    val currentVar by viewModel.currentVar.collectAsState()
    val showWelcome by viewModel.showWelcome.collectAsState()
    val lazyLoadIndex by viewModel.lazyLoadIndex.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val themeMode by viewModel.themeModeFlow.collectAsState()
    val language by viewModel.language.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshAccessibilityStatus()
    }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    ThemeSwitcher(themeMode) { viewModel.setThemeMode(it) }
                    LanguageSwitcher(language) { viewModel.setLanguage(it) }
                }
            )
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
                TextExpanderContent(
                    viewModel = viewModel,
                    dict = dict,
                    currentMatch = currentMatch,
                    currentVar = currentVar,
                    lazyLoadIndex = lazyLoadIndex
                )
            }
        }
    }
}

@Composable
private fun TextExpanderContent(
    viewModel: MainViewModel,
    dict: Map<String, Match>,
    currentMatch: Match,
    currentVar: Var,
    lazyLoadIndex: Int
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(onClick = { viewModel.saveDict() }) {
            Icon(Icons.Default.Save, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.make_sure_to_save))
        }
        OutlinedButton(onClick = { /* TODO: import */ }) {
            Icon(Icons.Default.Upload, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.import_text))
        }
        OutlinedButton(onClick = { /* TODO: export */ }) {
            Icon(Icons.Default.Download, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.export_text))
        }
    }

    OutlinedButton(
        onClick = { viewModel.forceQuit() },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) {
        Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.force_quit_app))
    }

    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.keywords), style = MaterialTheme.typography.titleMedium)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = currentMatch.trigger ?: "",
            onValueChange = { currentMatch.trigger = it; viewModel.updateCurrentMatch(currentMatch) },
            label = { Text(stringResource(R.string.key)) },
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = currentMatch.replace ?: "",
            onValueChange = { currentMatch.replace = it; viewModel.updateCurrentMatch(currentMatch) },
            label = { Text(stringResource(R.string.value)) },
            modifier = Modifier.weight(1f)
        )
        Button(onClick = { viewModel.addMatch() }) {
            Text(stringResource(R.string.add))
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.variables))
                Text("${currentMatch.vars?.size ?: 0}")
            }
            currentMatch.vars?.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(item.name ?: "")
                    IconButton(onClick = { viewModel.removeVar(item) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Checkbox(
            checked = currentMatch.word,
            onCheckedChange = { currentMatch.word = it; viewModel.updateCurrentMatch(currentMatch) }
        )
        Text(stringResource(R.string.word))
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    LazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {
        val displayCount = minOf(dict.size, lazyLoadIndex)
        items(dict.entries.take(displayCount)) { entry ->
            MatchCard(
                trigger = entry.key,
                replace = entry.value.replace ?: "",
                onEdit = { viewModel.editItem(entry.value) },
                onDelete = { viewModel.removeMatch(entry.value) }
            )
        }
        if (dict.size > lazyLoadIndex) {
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
