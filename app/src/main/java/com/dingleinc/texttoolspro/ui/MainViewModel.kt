package com.dingleinc.texttoolspro.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dingleinc.texttoolspro.data.AppSettings
import com.dingleinc.texttoolspro.data.DictWrapper
import com.dingleinc.texttoolspro.data.Match
import com.dingleinc.texttoolspro.data.Params
import com.dingleinc.texttoolspro.data.SerializationHelper
import com.dingleinc.texttoolspro.data.ServiceCommandBus
import com.dingleinc.texttoolspro.data.Var
import com.dingleinc.texttoolspro.ui.theme.ThemeMode
import com.dingleinc.texttoolspro.util.AccessibilityChecker
import com.dingleinc.texttoolspro.util.Utils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

import com.fasterxml.jackson.core.type.TypeReference

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = getApplication<Application>().getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        ThemeMode.valueOf(prefs.getString("theme_mode", "Auto") ?: "Auto")
    )
    val themeModeFlow = _themeMode.asStateFlow()

    private val _canTextExpand = MutableStateFlow(false)
    val canTextExpand = _canTextExpand.asStateFlow()

    private val _dict = MutableStateFlow<MutableMap<String, Match>>(mutableMapOf())
    val dict = _dict.asStateFlow()

    private val _globalVars = MutableStateFlow<List<Var>>(emptyList())
    val globalVars = _globalVars.asStateFlow()

    private val _currentMatch = MutableStateFlow(Match(trigger = "t:omw", replace = "On my way", vars = mutableListOf()))
    val currentMatch = _currentMatch.asStateFlow()

    private val _currentVar = MutableStateFlow(Var(params = Params()))
    val currentVar = _currentVar.asStateFlow()

    private val _editingKey = MutableStateFlow<String?>(null)
    val editingKey = _editingKey.asStateFlow()

    private val _showWelcome = MutableStateFlow(!prefs.getBoolean("welcomed", false))
    val showWelcome = _showWelcome.asStateFlow()

    private val _lazyLoadIndex = MutableStateFlow(100)
    val lazyLoadIndex = _lazyLoadIndex.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage = _snackbarMessage.asStateFlow()

    private val _language = MutableStateFlow(prefs.getString("app_language", "en") ?: "en")
    val language = _language.asStateFlow()

    private val _recreateActivity = MutableStateFlow(false)
    val recreateActivity = _recreateActivity.asStateFlow()

    init {
        loadDict()
    }

    fun refreshAccessibilityStatus() {
        _canTextExpand.value = AccessibilityChecker.isActivated(getApplication())
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    fun setLanguage(code: String) {
        _language.value = code
        prefs.edit().putString("app_language", code).apply()
        _recreateActivity.value = true
    }

    fun dismissWelcome() {
        _showWelcome.value = false
        prefs.edit().putBoolean("welcomed", true).apply()
    }

    private fun loadDict() {
        viewModelScope.launch {
            refreshAccessibilityStatus()

            // Migrate old dict if exists
            if (File(AppSettings.oldDictPath).exists()) {
                try {
                    val content = File(AppSettings.oldDictPath).readText()
                    val migrated = SerializationHelper.jsonMapper.readValue(
                        content,
                        object : TypeReference<MutableMap<String, Match>>() {}
                    )
                    _dict.value = migrated
                    if (_canTextExpand.value) {
                        migrated.values.forEach { ServiceCommandBus.trySend(ServiceCommandBus.Command.Add(it)) }
                    }
                    saveDict()
                    File(AppSettings.oldDictPath).delete()
                } catch (e: Exception) {
                    // ignore migration errors
                }
            }

            // Load current dict
            if (File(AppSettings.dictPath).exists()) {
                try {
                    val content = File(AppSettings.dictPath).readText()
                    val loaded = SerializationHelper.jsonMapper.readValue(
                        content,
                        object : TypeReference<MutableMap<String, Match>>() {}
                    )
                    _dict.value = loaded
                    if (_canTextExpand.value) {
                        loaded.values.forEach { ServiceCommandBus.trySend(ServiceCommandBus.Command.Add(it)) }
                    }
                } catch (e: Exception) {
                    _dict.value = mutableMapOf()
                }
            }

            // Load global vars
            if (File(AppSettings.globalVarsPath).exists()) {
                try {
                    val content = File(AppSettings.globalVarsPath).readText()
                    val loaded = SerializationHelper.jsonMapper.readValue(
                        content,
                        object : TypeReference<List<Var>>() {}
                    )
                    _globalVars.value = loaded
                    if (_canTextExpand.value) {
                        ServiceCommandBus.trySend(ServiceCommandBus.Command.UpdateGlobals(loaded))
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    fun addMatch() {
        viewModelScope.launch {
            try {
                val copy = Match(_currentMatch.value)
                if (!copy.trigger.isNullOrEmpty() && !copy.replace.isNullOrEmpty()) {
                    val editingKeyVal = _editingKey.value
                    if (editingKeyVal != null && editingKeyVal != copy.trigger && _dict.value.containsKey(editingKeyVal)) {
                        val oldItem = _dict.value[editingKeyVal]!!
                        val newDict = _dict.value.toMutableMap()
                        newDict.remove(editingKeyVal)
                        ServiceCommandBus.trySend(ServiceCommandBus.Command.Remove(oldItem))
                        _dict.value = newDict
                    }
                    val newDict = _dict.value.toMutableMap()
                    newDict[copy.trigger!!] = Match(copy)
                    _dict.value = newDict
                    copy.replace = copy.replace!!.replace("\\n", System.lineSeparator())
                    ServiceCommandBus.trySend(ServiceCommandBus.Command.Add(copy))
                    _editingKey.value = null
                } else {
                    _snackbarMessage.value = "Fill out required parameters"
                }
            } catch (e: Exception) {
                _snackbarMessage.value = e.message
            }
        }
    }

    fun saveMatchFromDialog(match: Match, isEditing: Boolean, originalKey: String?) {
        viewModelScope.launch {
            try {
                val newDict = _dict.value.toMutableMap()
                val copy = Match(match)
                copy.replace = copy.replace?.replace("\\n", System.lineSeparator())

                if (isEditing && originalKey != null && newDict.containsKey(originalKey)) {
                    val oldItem = newDict[originalKey]!!
                    newDict.remove(originalKey)
                    ServiceCommandBus.trySend(ServiceCommandBus.Command.Remove(oldItem))
                }

                if (!copy.regex.isNullOrEmpty()) {
                    val key = "__regex__${copy.regex}"
                    newDict[key] = copy
                    ServiceCommandBus.trySend(ServiceCommandBus.Command.Add(copy))
                } else if (!copy.triggers.isNullOrEmpty()) {
                    copy.triggers!!.forEach { t ->
                        val cloned = Match(copy)
                        cloned.trigger = t
                        cloned.triggers = null
                        newDict[t] = cloned
                        ServiceCommandBus.trySend(ServiceCommandBus.Command.Add(cloned))
                    }
                } else if (!copy.trigger.isNullOrEmpty()) {
                    newDict[copy.trigger!!] = copy
                    ServiceCommandBus.trySend(ServiceCommandBus.Command.Add(copy))
                }

                _dict.value = newDict
                _editingKey.value = null
                _snackbarMessage.value = if (isEditing) "Updated" else "Added"
            } catch (e: Exception) {
                _snackbarMessage.value = e.message
            }
        }
    }

    fun startNewMatch() {
        _editingKey.value = null
        _currentMatch.value = Match(trigger = "", replace = "", vars = mutableListOf())
    }

    fun editMatchByKey(key: String) {
        val match = _dict.value[key] ?: return
        _editingKey.value = key
        _currentMatch.value = Match(match)
    }

    fun removeMatch(item: Match) {
        viewModelScope.launch {
            try {
                val newDict = _dict.value.toMutableMap()
                val key = if (!item.regex.isNullOrEmpty()) {
                    "__regex__${item.regex}"
                } else {
                    item.trigger
                }
                if (key != null && newDict.remove(key) != null) {
                    _dict.value = newDict
                    ServiceCommandBus.trySend(ServiceCommandBus.Command.Remove(item))
                    _snackbarMessage.value = "Deleted item"
                }
            } catch (e: Exception) {
                _snackbarMessage.value = e.message
            }
        }
    }

    fun removeMatchByKey(key: String) {
        viewModelScope.launch {
            try {
                val newDict = _dict.value.toMutableMap()
                val item = newDict.remove(key)
                if (item != null) {
                    _dict.value = newDict
                    ServiceCommandBus.trySend(ServiceCommandBus.Command.Remove(item))
                    _snackbarMessage.value = "Deleted item"
                }
            } catch (e: Exception) {
                _snackbarMessage.value = e.message
            }
        }
    }

    fun editItem(item: Match) {
        _editingKey.value = item.trigger
        _currentMatch.value = Match(item)
    }

    fun saveDict() {
        viewModelScope.launch {
            try {
                _dict.value.forEach { (_, match) ->
                    match.replace?.let { match.replace = it.replace("\\n", System.lineSeparator()) }
                }
                val str = SerializationHelper.toJson(_dict.value)
                File(AppSettings.dictPath).writeText(str)
                _snackbarMessage.value = "Save successful"
            } catch (e: Exception) {
                _snackbarMessage.value = e.message
            }
        }
    }

    fun addPreBuiltVar(index: Int) {
        val match = _currentMatch.value
        when (index) {
            0 -> {
                val p = Params(); p["format"] = "dd/MM/yyyy"
                match.vars?.add(Var(name = "datenow", type = "date", params = p))
                match.replace = (match.replace ?: "") + " {{datenow}}"
            }
            1 -> {
                val p = Params(); p["format"] = "dd/MM/yyyy"; p["offset"] = -86400L
                match.vars?.add(Var(name = "yesterday", type = "date", params = p))
                match.replace = (match.replace ?: "") + " {{yesterday}}"
            }
            2 -> {
                val p = Params(); p["format"] = "dd/MM/yyyy"; p["offset"] = 86400L
                match.vars?.add(Var(name = "tommorow", type = "date", params = p))
                match.replace = (match.replace ?: "") + " {{tommorow}}"
            }
            3 -> {
                val p = Params(); p["format"] = "HH:mm"
                match.vars?.add(Var(name = "time", type = "date", params = p))
                match.replace = (match.replace ?: "") + " {{time}}"
            }
            4 -> {
                match.replace = (match.replace ?: "") + " \$|\$"
            }
        }
        _currentMatch.value = Match(match)
    }

    fun addCurrentVar() {
        val copy = Var(_currentVar.value)
        val fmt = copy.params.string("format")
        if (!fmt.isNullOrEmpty()) {
            copy.params["format"] = Utils.getTheRealFormat(fmt)
        }
        _currentMatch.value.vars?.add(copy)
        _currentMatch.value = Match(_currentMatch.value)
    }

    fun removeVar(item: Var) {
        _currentMatch.value.vars?.remove(item)
        _currentMatch.value = Match(_currentMatch.value)
    }

    fun saveVariable(v: Var) {
        val copy = Var(v)
        val fmt = copy.params.string("format")
        if (!fmt.isNullOrEmpty()) {
            copy.params["format"] = Utils.getTheRealFormat(fmt)
        }
        val match = Match(_currentMatch.value)
        if (match.vars == null) match.vars = mutableListOf()
        match.vars!!.add(copy)
        _currentMatch.value = match
    }

    fun updateVariable(index: Int, v: Var) {
        val copy = Var(v)
        val fmt = copy.params.string("format")
        if (!fmt.isNullOrEmpty()) {
            copy.params["format"] = Utils.getTheRealFormat(fmt)
        }
        val match = Match(_currentMatch.value)
        if (match.vars != null && index < match.vars!!.size) {
            match.vars!![index] = copy
        }
        _currentMatch.value = match
    }

    fun updateCurrentMatch(match: Match) {
        _currentMatch.value = match
    }

    fun updateCurrentVar(v: Var) {
        _currentVar.value = v
    }

    fun loadMore() {
        val diff = _dict.value.size - _lazyLoadIndex.value
        if (diff > 100) {
            _lazyLoadIndex.value += 100
        } else {
            _lazyLoadIndex.value += diff
        }
    }

    fun forceQuit() {
        ServiceCommandBus.trySend(ServiceCommandBus.Command.Quit)
        (getApplication() as android.app.Application).let {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun onRecreateHandled() {
        _recreateActivity.value = false
    }

    fun importConfig(uri: Uri) {
        viewModelScope.launch {
            try {
                val content = getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw Exception("Cannot read file")

                val localDict = SerializationHelper.parseDictWrapperFromYaml(content)

                // Convert date formats and filter unsupported vars
                localDict.matches?.forEach { match ->
                    match.vars?.forEach { v ->
                        if (v.type == "date") {
                            val fmt = v.params.string("format")
                            if (!fmt.isNullOrEmpty()) {
                                v.params["format"] = Utils.getTheRealFormat(fmt)
                            }
                        }
                    }
                }
                localDict.globalVars?.forEach { v ->
                    if (v.type == "date") {
                        val fmt = v.params.string("format")
                        if (!fmt.isNullOrEmpty()) {
                            v.params["format"] = Utils.getTheRealFormat(fmt)
                        }
                    }
                }

                // Build dict from imported matches
                val dict = mutableMapOf<String, Match>()
                localDict.matches?.forEach { match ->
                    if (!match.triggers.isNullOrEmpty()) {
                        match.triggers!!.forEach { t ->
                            val cloned = Match(match)
                            cloned.trigger = t
                            cloned.triggers = null
                            dict[t] = cloned
                        }
                    } else if (!match.trigger.isNullOrEmpty()) {
                        dict[match.trigger!!] = match
                    } else if (!match.regex.isNullOrEmpty()) {
                        dict["__regex__${match.regex}"] = match
                    }
                }

                // Save
                _dict.value = dict
                File(AppSettings.dictPath).writeText(SerializationHelper.toJson(dict))

                if (!localDict.globalVars.isNullOrEmpty()) {
                    _globalVars.value = localDict.globalVars!!
                    File(AppSettings.globalVarsPath).writeText(SerializationHelper.toJson(localDict.globalVars!!))
                    ServiceCommandBus.trySend(ServiceCommandBus.Command.UpdateGlobals(localDict.globalVars!!))
                }

                ServiceCommandBus.trySend(ServiceCommandBus.Command.Reset)
                _snackbarMessage.value = "Imported ${dict.size} matches"
            } catch (e: Exception) {
                _snackbarMessage.value = "Import error: ${e.message}"
            }
        }
    }

    fun exportConfig(uri: Uri) {
        viewModelScope.launch {
            try {
                val matches = mutableListOf<Match>()
                _dict.value.values.forEach { match ->
                    val exportMatch = Match(match)
                    // Reverse date format conversion
                    exportMatch.vars?.forEach { v ->
                        if (v.type == "date") {
                            val fmt = v.params.string("format")
                            if (!fmt.isNullOrEmpty()) {
                                v.params["format"] = Utils.getOriginalFormat(fmt)
                            }
                        }
                    }
                    // Remove internal regex key prefix
                    if (exportMatch.trigger != null && exportMatch.trigger!!.startsWith("__regex__")) {
                        exportMatch.trigger = null
                    }
                    matches.add(exportMatch)
                }

                val exportGlobals = _globalVars.value.map { v ->
                    val copy = Var(v)
                    if (copy.type == "date") {
                        val fmt = copy.params.string("format")
                        if (!fmt.isNullOrEmpty()) {
                            copy.params["format"] = Utils.getOriginalFormat(fmt)
                        }
                    }
                    copy
                }.toMutableList()

                val wrapper = DictWrapper(
                    globalVars = exportGlobals,
                    matches = matches
                )

                val yaml = SerializationHelper.toYaml(wrapper)
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(yaml.toByteArray()) }
                    ?: throw Exception("Cannot write file")

                _snackbarMessage.value = "Exported ${matches.size} matches"
            } catch (e: Exception) {
                _snackbarMessage.value = "Export error: ${e.message}"
            }
        }
    }
}
