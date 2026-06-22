package com.dingleinc.texttoolspro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import com.dingleinc.texttoolspro.data.Params
import com.dingleinc.texttoolspro.data.Var

@Composable
fun VariableEditorDialog(
    varType: String,
    existingVar: Var?,
    onSave: (Var) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(existingVar?.name ?: "") }
    val params = remember { mutableStateOf(Params(existingVar?.params ?: Params())) }
    var values by remember {
        mutableStateOf(
            params.value.stringList("values") ?: params.value.stringList("choices") ?: mutableListOf()
        )
    }
    var newValue by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Edit $varType variable")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Variable Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(Modifier.height(12.dp))

                when (varType) {
                    "echo" -> EchoFields(params)
                    "date" -> DateFields(params)
                    "clipboard" -> {}
                    "random" -> ListFields(
                        title = "Choices",
                        items = values,
                        newItem = newValue,
                        onNewItemChange = { newValue = it },
                        onAdd = {
                            if (newValue.isNotBlank()) {
                                values = (values + newValue.trim()).toMutableList()
                                newValue = ""
                                params.value["choices"] = values.toMutableList()
                            }
                        },
                        onRemove = { idx ->
                            values = values.filterIndexed { i, _ -> i != idx }.toMutableList()
                            params.value["choices"] = values.toMutableList()
                        }
                    )
                    "choice" -> ChoiceFields(params, values, newValue)
                    "form" -> FormFields(params)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    val result = Var(
                        name = name,
                        type = varType,
                        params = Params(params.value)
                    )
                    onSave(result)
                }
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun EchoFields(params: MutableState<Params>) {
    var echo by remember { mutableStateOf(params.value.string("echo") ?: "") }
    OutlinedTextField(
        value = echo,
        onValueChange = {
            echo = it
            params.value["echo"] = it
        },
        label = { Text("Value") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 1,
        maxLines = 4
    )
}

@Composable
private fun DateFields(params: MutableState<Params>) {
    var format by remember { mutableStateOf(params.value.string("format") ?: "") }
    var offset by remember { mutableStateOf(params.value.long("offset").toString()) }
    var locale by remember { mutableStateOf(params.value.string("locale") ?: "") }
    var tz by remember { mutableStateOf(params.value.string("tz") ?: "") }

    OutlinedTextField(
        value = format,
        onValueChange = {
            format = it
            params.value["format"] = it
        },
        label = { Text("Format (e.g. dd/MM/yyyy)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledTonalButton(onClick = {
            format = "dd/MM/yyyy"
            params.value["format"] = format
        }) { Text("Date") }
        FilledTonalButton(onClick = {
            format = "HH:mm"
            params.value["format"] = format
        }) { Text("Time") }
        FilledTonalButton(onClick = {
            format = "dd/MM/yyyy HH:mm"
            params.value["format"] = format
        }) { Text("DateTime") }
    }

    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = offset,
        onValueChange = {
            offset = it
            it.toLongOrNull()?.let { v -> params.value["offset"] = v }
        },
        label = { Text("Offset (seconds)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledTonalButton(onClick = {
            offset = "-86400"
            params.value["offset"] = -86400L
        }) { Text("Yesterday") }
        FilledTonalButton(onClick = {
            offset = "86400"
            params.value["offset"] = 86400L
        }) { Text("Tomorrow") }
    }

    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = locale,
        onValueChange = {
            locale = it
            params.value["locale"] = it.ifBlank { null } ?: ""
        },
        label = { Text("Locale (optional)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )

    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = tz,
        onValueChange = {
            tz = it
            params.value["tz"] = it.ifBlank { null } ?: ""
        },
        label = { Text("Timezone (optional)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun ListFields(
    title: String,
    items: List<String>,
    newItem: String,
    onNewItemChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit
) {
    Text(title, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(4.dp))

    items.forEachIndexed { idx, item ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(item, modifier = Modifier.weight(1f))
                IconButton(onClick = { onRemove(idx) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = newItem,
            onValueChange = onNewItemChange,
            label = { Text("Add item...") },
            modifier = Modifier.weight(1f),
            singleLine = true
        )
        IconButton(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = "Add")
        }
    }
}

@Composable
private fun ChoiceFields(
    params: MutableState<Params>,
    initialValues: MutableList<String>,
    initialNewItem: String
) {
    var advancedMode by remember { mutableStateOf(false) }
    var values by remember { mutableStateOf(initialValues) }
    var newValue by remember { mutableStateOf(initialNewItem) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Values", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { advancedMode = !advancedMode }) {
            Text(if (advancedMode) "Simple" else "Advanced")
        }
    }

    if (!advancedMode) {
        ListFields(
            title = "",
            items = values,
            newItem = newValue,
            onNewItemChange = { newValue = it },
            onAdd = {
                if (newValue.isNotBlank()) {
                    values = (values + newValue.trim()).toMutableList()
                    newValue = ""
                    params.value["values"] = values.toMutableList()
                }
            },
            onRemove = { idx ->
                values = values.filterIndexed { i, _ -> i != idx }.toMutableList()
                params.value["values"] = values.toMutableList()
            }
        )
    } else {
        Text("Enter id|label pairs (one per line):", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        var advancedText by remember {
            mutableStateOf(
                values.joinToString("\n") { it }
            )
        }
        OutlinedTextField(
            value = advancedText,
            onValueChange = {
                advancedText = it
                val pairs = it.split("\n").filter { l -> l.isNotBlank() }.map { l -> l.trim() }
                values = pairs.toMutableList()
                params.value["values"] = pairs.toMutableList()
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 8
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Each line: id|label (e.g. yes|Yes, I agree)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun FormFields(params: MutableState<Params>) {
    var layout by remember { mutableStateOf(params.value.string("layout") ?: "") }

    OutlinedTextField(
        value = layout,
        onValueChange = {
            layout = it
            params.value["layout"] = it
        },
        label = { Text("Form Layout") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 4,
        maxLines = 10
    )

    Spacer(Modifier.height(4.dp))
    Text(
        "Use [[field_name]] syntax for fields",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary
    )
}
