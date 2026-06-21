package com.dingleinc.texttoolspro.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dingleinc.texttoolspro.data.AppSettings
import com.dingleinc.texttoolspro.data.DictWrapper
import com.dingleinc.texttoolspro.data.Match
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

    private val _currentVar = MutableStateFlow(Var(params = com.dingleinc.texttoolspro.data.Params()))
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

    fun removeMatch(item: Match) {
        viewModelScope.launch {
            try {
                val newDict = _dict.value.toMutableMap()
                if (newDict.remove(item.trigger) != null) {
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
                match.vars?.add(Var(name = "datenow", type = "date", params = com.dingleinc.texttoolspro.data.Params(format = "dd/MM/yyyy")))
                match.replace = (match.replace ?: "") + " {{datenow}}"
            }
            1 -> {
                match.vars?.add(Var(name = "yesterday", type = "date", params = com.dingleinc.texttoolspro.data.Params(format = "dd/MM/yyyy", offset = -86400)))
                match.replace = (match.replace ?: "") + " {{yesterday}}"
            }
            2 -> {
                match.vars?.add(Var(name = "tommorow", type = "date", params = com.dingleinc.texttoolspro.data.Params(format = "dd/MM/yyyy", offset = 86400)))
                match.replace = (match.replace ?: "") + " {{tommorow}}"
            }
            3 -> {
                match.vars?.add(Var(name = "time", type = "date", params = com.dingleinc.texttoolspro.data.Params(format = "HH:mm")))
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
        if (!copy.params.format.isNullOrEmpty()) {
            copy.params.format = Utils.getTheRealFormat(copy.params.format!!)
        }
        _currentMatch.value.vars?.add(copy)
        _currentMatch.value = Match(_currentMatch.value)
    }

    fun removeVar(item: Var) {
        _currentMatch.value.vars?.remove(item)
        _currentMatch.value = Match(_currentMatch.value)
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
}
