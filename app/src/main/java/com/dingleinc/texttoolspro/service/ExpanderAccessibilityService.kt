package com.dingleinc.texttoolspro.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import com.dingleinc.texttoolspro.R
import com.dingleinc.texttoolspro.data.AppSettings
import com.dingleinc.texttoolspro.data.Match
import com.dingleinc.texttoolspro.data.ServiceCommandBus
import com.dingleinc.texttoolspro.data.Params
import com.dingleinc.texttoolspro.data.Var
import com.dingleinc.texttoolspro.data.SerializationHelper
import com.dingleinc.texttoolspro.extension.ContentExtension
import com.dingleinc.texttoolspro.extension.DependencyResolver
import com.dingleinc.texttoolspro.extension.ExtensionOutput
import com.dingleinc.texttoolspro.extension.ExtensionRegistry
import com.dingleinc.texttoolspro.extension.ExtensionResult
import com.dingleinc.texttoolspro.extension.HttpExtension
import com.dingleinc.texttoolspro.extension.InjectVariables
import com.dingleinc.texttoolspro.extension.IntentExtension
import com.dingleinc.texttoolspro.extension.JavaScriptExtension
import com.dingleinc.texttoolspro.extension.MatchExtension
import com.dingleinc.texttoolspro.extension.TemplateRenderer
import com.dingleinc.texttoolspro.extension.shell.ScriptExtension
import com.dingleinc.texttoolspro.extension.shell.ShellBackendDetector
import com.dingleinc.texttoolspro.extension.shell.ShellExecutor
import com.dingleinc.texttoolspro.extension.shell.ShellExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

class ExpanderAccessibilityService : AccessibilityService(), View.OnTouchListener, TemplateRenderer {

    companion object {
        private const val TAG = "A11Y"
        private const val CURSOR_STR = "\$|\$"
        private val SEPARATORS = arrayOf(" ", "\n", "\r\n", " ,")
        private val FORM_SEPARATORS = arrayOf(" ", "|", "\r\n", "\n")
        private val LINE_SEPARATORS = arrayOf("\r\n", "\n")
    }

    private var dict: MutableMap<String, Match> = mutableMapOf()
    private var regexDict: MutableMap<Pattern, Match> = mutableMapOf()
    private var globals: List<Var>? = null
    private val cursorArgs = Bundle()
    private var layoutParams: WindowManager.LayoutParams? = null
    private var floatView: View? = null
    private var windowManager: WindowManager? = null
    private var xDown = 0f
    private var yDown = 0f
    private var rowContainer: LinearLayout? = null
    private var previousOriginal = ""
    private var previousExpansion = ""
    private var formExpansion = ""
    private var formKey = ""
    private var skipNextFormEvent = false
    private var skipCount = 0

    private val extensionRegistry = ExtensionRegistry()
    @Volatile private var isExpansionInProgress = false
    private var pendingRenderContext: RenderContext? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var shellExecutor: ShellExecutor? = null

    private val packageWatchers = ConcurrentHashMap<String, Job>()
    private val lastKnownText = ConcurrentHashMap<String, String>()
    private val lastFocusTime = ConcurrentHashMap<String, Long>()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var commandCollectorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        dict = mutableMapOf()

        serviceScope.launch {
            ServiceCommandBus.commands.collect { command ->
                when (command) {
                    is ServiceCommandBus.Command.Add -> {
                        val item = command.match
                        if (!item.form.isNullOrEmpty() ||
                            (!item.trigger.isNullOrEmpty() && !item.replace.isNullOrEmpty())
                        ) {
                            dict[item.trigger!!] = item
                        }
                        if (!item.regex.isNullOrEmpty() && !item.replace.isNullOrEmpty()) {
                            try {
                                regexDict[Pattern.compile(item.regex!!)] = item
                            } catch (e: Exception) {
                                Log.e(TAG, "Invalid regex: ${item.regex}")
                            }
                        }
                        extensionRegistry.register(
                            MatchExtension(dict, globals ?: emptyList(), this@ExpanderAccessibilityService)
                        )
                    }
                    is ServiceCommandBus.Command.Quit -> {
                        disableSelf()
                    }
                    is ServiceCommandBus.Command.Reset -> {
                        loadDictFromFiles()
                        rebuildRegexDict()
                        extensionRegistry.register(
                            MatchExtension(dict, globals ?: emptyList(), this@ExpanderAccessibilityService)
                        )
                    }
                    is ServiceCommandBus.Command.Remove -> {
                        dict.remove(command.match.trigger)
                        if (!command.match.regex.isNullOrEmpty()) {
                            regexDict.entries.removeIf { it.value === command.match }
                        }
                        extensionRegistry.register(
                            MatchExtension(dict, globals ?: emptyList(), this@ExpanderAccessibilityService)
                        )
                    }
                    is ServiceCommandBus.Command.UpdateGlobals -> {
                        globals = command.globals
                        extensionRegistry.register(
                            MatchExtension(dict, globals ?: emptyList(), this@ExpanderAccessibilityService)
                        )
                    }
                }
            }
        }

        loadDictFromFiles()
        registerExtensions()
    }

    private fun registerExtensions() {
        val configPath = AppSettings.dictPath.substringBeforeLast("/")
        val homePath = filesDir.absolutePath
        val packagesPath = filesDir.absolutePath

        shellExecutor = ShellBackendDetector.detectBestBackend(this)

        extensionRegistry.register(HttpExtension())
        extensionRegistry.register(JavaScriptExtension())
        extensionRegistry.register(ShellExtension { shellExecutor })
        extensionRegistry.register(
            ScriptExtension({ shellExecutor }, configPath, homePath, packagesPath)
        )
        extensionRegistry.register(IntentExtension(this))
        extensionRegistry.register(ContentExtension(this))
        extensionRegistry.register(
            MatchExtension(dict, globals ?: emptyList(), this)
        )
    }

    private fun loadDictFromFiles() {
        try {
            if (File(AppSettings.dictPath).exists()) {
                val content = File(AppSettings.dictPath).readText()
                dict = SerializationHelper.jsonMapper.readValue(
                    content,
                    object : com.fasterxml.jackson.core.type.TypeReference<MutableMap<String, Match>>() {}
                )
            }
            if (File(AppSettings.globalVarsPath).exists()) {
                val content = File(AppSettings.globalVarsPath).readText()
                globals = SerializationHelper.jsonMapper.readValue(
                    content,
                    object : com.fasterxml.jackson.core.type.TypeReference<List<Var>>() {}
                )
            }
            rebuildRegexDict()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading files: ${e.message}")
        }
    }

    private fun rebuildRegexDict() {
        regexDict.clear()
        dict.values.forEach { match ->
            if (!match.regex.isNullOrEmpty()) {
                try {
                    regexDict[Pattern.compile(match.regex!!)] = match
                } catch (e: Exception) {
                    Log.e(TAG, "Invalid regex pattern: ${match.regex}")
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            if (isExpansionInProgress) return
            val packageName = event.packageName?.toString() ?: return

            val node = event.source

            if (node != null) {
                val className = node.className?.toString() ?: ""
                val isEditText = className.contains("EditText") && node.isEditable

                if (isEditText && event.text.isNotEmpty()) {
                    val expansionStr = node.text?.toString() ?: ""
                    if (expansionStr.isNotBlank()) {
                        val changed = lastKnownText[packageName] != expansionStr
                        if (changed) {
                            lastKnownText[packageName] = expansionStr
                            handleTextExpansion(event, expansionStr)
                        }
                    }
                    return
                }
            }

            startPackageWatcher(packageName, event)
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e}")
        }
    }

    private fun startPackageWatcher(packageName: String, triggerEvent: AccessibilityEvent) {
        if (packageWatchers.containsKey(packageName)) return

        val job = serviceScope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val root = rootInActiveWindow
                    if (root == null) {
                        kotlinx.coroutines.delay(1000)
                        continue
                    }

                    val focused = findFocusedEditText(root)

                    if (focused != null) {
                        lastFocusTime[packageName] = System.currentTimeMillis()

                        val text = focused.text?.toString() ?: ""
                        if (text.isNotBlank()) {
                            val changed = lastKnownText[packageName] != text
                            if (changed) {
                                lastKnownText[packageName] = text
                                withContext(Dispatchers.Main) {
                                    handleTextExpansion(triggerEvent, text)
                                }
                            }
                        }
                        kotlinx.coroutines.delay(1000)
                        continue
                    }

                    lastFocusTime[packageName]?.let { lastFocus ->
                        if (System.currentTimeMillis() - lastFocus >= 60_000) {
                            Log.d(TAG, "[$packageName] watcher stopped (no focus for 1 min)")
                            return@launch
                        }
                    }

                    kotlinx.coroutines.delay(1000)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Watcher error ($packageName): $e")
            } finally {
                packageWatchers.remove(packageName)
                lastFocusTime.remove(packageName)
            }
        }

        packageWatchers[packageName] = job
    }

    private fun findFocusedEditText(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        try {
            val className = node.className?.toString() ?: ""
            val isEditText = className.contains("EditText") && node.isEditable
            val isFocused = node.isFocused || node.isAccessibilityFocused

            if (isEditText && isFocused) return node

            for (i in 0 until node.childCount) {
                val result = findFocusedEditText(node.getChild(i))
                if (result != null) return result
            }
        } catch (e: Exception) {
            // ignore broken nodes
        }
        return null
    }

    private fun handleTextExpansion(event: AccessibilityEvent, expansionStr: String) {
        try {
            if (expansionStr.isEmpty()) return
            val original = expansionStr
            checkAndUpdateCursorArgs(expansionStr, sendIfCursorFound = true, event)

            val arr = expansionStr.split(*SEPARATORS).filter { it.isNotEmpty() }
            var send = false
            var storeOriginal = true
            val text = arr.last()

            if (previousOriginal == original) {
                return
            } else if (formExpansion != "") {
                val modified = original.replace(formKey, formExpansion)
                doExpansion(event, modified)
                storeOriginal = true
                send = true
                formExpansion = ""
            } else if (previousExpansion != "" && previousExpansion.dropLast(1) == original) {
                doExpansion(event, previousOriginal)
                storeOriginal = false
                send = true
            } else if (skipNextFormEvent) {
                if (skipCount > 1) {
                    skipNextFormEvent = false
                    skipCount = 0
                }
                skipCount++
                return
            } else {
                val match = dict[text]
                if (match != null) {
                    val triggerIndex = expansionStr.indexOf(text)

                    // Word boundary check: word = leftWord && rightWord
                    val checkLeft = match.word || match.leftWord
                    val checkRight = match.word || match.rightWord
                    if (checkLeft || checkRight) {
                        val beforeOk = triggerIndex == 0 ||
                            SEPARATORS.contains(expansionStr[triggerIndex - 1].toString())
                        val afterOk = triggerIndex + text.length >= expansionStr.length ||
                            SEPARATORS.contains(expansionStr[triggerIndex + text.length].toString())
                        if (checkLeft && !beforeOk) return
                        if (checkRight && !afterOk) return
                    }

                    if (!match.form.isNullOrEmpty()) {
                        startRender(match, text, event, expansionStr, triggerIndex, storeOriginal, original)
                        return
                    } else {
                        val hasChoiceOrFormVar = match.vars?.any { v ->
                            v.type in listOf("choice", "form", "shell", "script", "http", "javascript", "match", "intent", "content")
                        } == true || globals?.any { v ->
                            v.type in listOf("choice", "form", "shell", "script", "http", "javascript", "match", "intent", "content")
                        } == true
                        if (hasChoiceOrFormVar) {
                            startRender(match, text, event, expansionStr, triggerIndex, storeOriginal, original)
                            return
                        }
                        var replace = match.replace ?: ""
                        if (match.propagateCase) {
                            replace = applyPropagateCase(text, replace, match.uppercaseStyle)
                        }
                        globals?.forEach { item ->
                            replace = parseItem(item, replace)
                        }
                        match.vars?.forEach { item ->
                            replace = parseItem(item, replace)
                        }
                        if (replace.isNotEmpty()) {
                            val end = expansionStr.substring(triggerIndex).replace(text, replace)
                            val newStr = expansionStr.substring(0, triggerIndex) + end
                            doExpansion(event, newStr)
                            if (storeOriginal) {
                                previousOriginal = original
                                previousExpansion = newStr
                            } else {
                                previousOriginal = ""
                                previousExpansion = ""
                            }
                            return
                        }
                    }
                } else if (regexDict.isNotEmpty()) {
                    // Try regex triggers
                    for ((pattern, regexMatch) in regexDict) {
                        val matcher = pattern.matcher(expansionStr)
                        if (matcher.find()) {
                            val matchedText = matcher.group()
                            var replace = regexMatch.replace ?: ""
                            val triggerIndex = matcher.start()

                            if (regexMatch.propagateCase) {
                                replace = applyPropagateCase(matchedText, replace, regexMatch.uppercaseStyle)
                            }

                            if (!regexMatch.form.isNullOrEmpty()) {
                                startRender(regexMatch, matchedText, event, expansionStr, triggerIndex, storeOriginal, original)
                                return
                            } else {
                                val hasChoiceOrFormVar = regexMatch.vars?.any { v ->
                                    v.type in listOf("choice", "form", "shell", "script", "http", "javascript", "match", "intent", "content")
                                } == true || globals?.any { v ->
                                    v.type in listOf("choice", "form", "shell", "script", "http", "javascript", "match", "intent", "content")
                                } == true
                                if (hasChoiceOrFormVar) {
                                    startRender(regexMatch, matchedText, event, expansionStr, triggerIndex, storeOriginal, original)
                                    return
                                }
                                globals?.forEach { item ->
                                    replace = parseItem(item, replace)
                                }
                                regexMatch.vars?.forEach { item ->
                                    replace = parseItem(item, replace)
                                }
                                if (replace.isNotEmpty()) {
                                    val end = expansionStr.substring(triggerIndex).replace(matchedText, replace)
                                    val newStr = expansionStr.substring(0, triggerIndex) + end
                                    doExpansion(event, newStr)
                                    if (storeOriginal) {
                                        previousOriginal = original
                                        previousExpansion = newStr
                                    } else {
                                        previousOriginal = ""
                                        previousExpansion = ""
                                    }
                                    return
                                }
                            }
                        }
                    }
                }
            }

            if (send) {
                if (storeOriginal) {
                    previousOriginal = original
                } else {
                    previousOriginal = ""
                    previousExpansion = ""
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "HandleTextExpansion error: ${e}")
        }
    }

    private fun showForm(match: Match, text: String, event: AccessibilityEvent) {
        val formLines = match.form!!.split(*LINE_SEPARATORS).filter { it.isNotEmpty() }
        val replaceDict = mutableMapOf<String, String>()
        val context = this

        formLines.forEach { line ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            if (line.contains("[[")) {
                val words = line.split(*FORM_SEPARATORS).filter { it.isNotEmpty() }
                words.forEach { word ->
                    if (word.startsWith("[[")) {
                        val endIndex = word.indexOf(']')
                        val placeholderStr = word.substring(2, endIndex)
                        val formOption = match.formFields?.get(placeholderStr)

                        when (formOption?.type) {
                            "choice" -> {
                                val spinner = Spinner(context)
                                val adapter = ArrayAdapter(
                                    context,
                                    android.R.layout.simple_spinner_dropdown_item,
                                    formOption.values ?: emptyList()
                                )
                                spinner.adapter = adapter
                                spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                                        replaceDict[placeholderStr] = formOption.values!![position]
                                    }
                                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                                }
                                row.post { row.addView(spinner) }
                            }
                            "list" -> {
                                val listView = ListView(context)
                                val adapter = ArrayAdapter(
                                    context,
                                    android.R.layout.simple_list_item_1,
                                    formOption.values ?: emptyList()
                                )
                                listView.adapter = adapter
                                listView.setOnItemClickListener { _, _, position, _ ->
                                    replaceDict[placeholderStr] = formOption.values!![position]
                                }
                                row.post { row.addView(listView) }
                            }
                            else -> {
                                row.post {
                                    val et = EditText(context)
                                    et.setHint(placeholderStr)
                                    et.addTextChangedListener(object : android.text.TextWatcher {
                                        override fun afterTextChanged(s: android.text.Editable?) {
                                            replaceDict[placeholderStr] = s.toString()
                                        }
                                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                                    })
                                    row.addView(et)
                                }
                            }
                        }
                    } else {
                        addTextView(row, word)
                    }
                }
            } else {
                addTextView(row, line)
            }
            rowContainer?.post { rowContainer?.addView(row) }
        }

        val submitButton = Button(context)
        submitButton.setText("Submit")
        submitButton.setOnClickListener {
            var formText = match.form!!
            replaceDict.forEach { (key, value) ->
                formText = formText.replace("[[${key}]]", value)
            }
            windowManager?.removeView(floatView)
            rowContainer?.removeAllViewsInLayout()
            formExpansion = formText
            formKey = text
        }
        rowContainer?.post { rowContainer?.addView(submitButton) }
        windowManager?.addView(floatView, layoutParams)
    }

    private fun doExpansion(event: AccessibilityEvent, og: String) {
        var node = event.source

        if (node == null) {
            val root = rootInActiveWindow ?: return
            node = findFocusedEditText(root)
        }

        node ?: return

        try {
            textArgs.remove(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
            textArgs.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                og
            )
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, textArgs)

            if (node.refresh()) {
                checkAndUpdateCursorArgs(og, sendIfCursorFound = false, event)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "DoExpansion error: $e")
        }
    }

    private val textArgs = Bundle()

    private fun addTextView(row: LinearLayout, word: String) {
        row.post {
            val tv = TextView(this)
            tv.setText(word)
            row.addView(tv)
        }
    }

    private fun checkAndUpdateCursorArgs(og: String, sendIfCursorFound: Boolean, event: AccessibilityEvent) {
        val startIndex = og.indexOf(CURSOR_STR)
        cursorArgs.remove(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT)
        cursorArgs.remove(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT)
        if (startIndex != -1) {
            cursorArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, startIndex)
            cursorArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, startIndex + CURSOR_STR.length)
            if (sendIfCursorFound) {
                event.source?.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs)
            }
        } else {
            cursorArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, og.length)
            cursorArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, og.length)
        }
    }

    private fun parseItem(item: Var, replace: String): String {
        try {
            if (item.type != null) {
                return when (item.type) {
                    "echo" -> replace.replace(wrapName(item.name ?: ""), item.params.string("echo") ?: "")
                    "random" -> {
                        val choices = item.params.stringList("choices") ?: return replace
                        if (choices.isNotEmpty()) {
                            val random = SecureRandom()
                            replace.replace(wrapName(item.name ?: ""), choices[random.nextInt(choices.size)])
                        } else replace
                    }
                    "clipboard" -> {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                        replace.replace(wrapName(item.name ?: ""), clip)
                    }
                    "date" -> {
                        val param = item.params
                        val now = LocalDateTime.now()
                        val dateTime = now.plus(param.long("offset"), ChronoUnit.SECONDS)
                        val formatter = DateTimeFormatter.ofPattern(param.string("format") ?: "")
                        replace.replace(wrapName(item.name ?: ""), dateTime.format(formatter))
                    }
                    "choice" -> {
                        // Handled by showChoiceForMatch before parseItem is called
                        replace
                    }
                    else -> replace
                }
            }
            return replace
        } catch (e: Exception) {
            return replace
        }
    }

    private fun wrapName(name: String): String = "{{$name}}"

    data class RenderContext(
        val match: Match,
        val triggerText: String,
        val event: AccessibilityEvent,
        val expansionStr: String,
        val triggerIndex: Int,
        val storeOriginal: Boolean,
        val original: String,
        var workingReplace: String,
        val evalOrder: List<Var>,
        var currentIndex: Int,
        val scope: MutableMap<String, ExtensionOutput>,
        val isFormMatch: Boolean = false
    )

    private fun startRender(
        match: Match,
        triggerText: String,
        event: AccessibilityEvent,
        expansionStr: String,
        triggerIndex: Int,
        storeOriginal: Boolean,
        original: String
    ) {
        val isFormMatch = !match.form.isNullOrEmpty()
        var workingReplace = if (isFormMatch) match.form!! else match.replace ?: ""
        if (match.propagateCase) {
            workingReplace = applyPropagateCase(triggerText, workingReplace, match.uppercaseStyle)
        }

        val localVars = match.vars ?: emptyList()
        val globalVarsList = globals ?: emptyList()
        val evalOrder = DependencyResolver.resolveEvaluationOrder(
            workingReplace, localVars, globalVarsList
        ).getOrElse {
            Log.w(TAG, "Dependency resolution failed: ${it.message}, falling back to original order")
            globalVarsList + localVars
        }

        val hasAsyncVar = evalOrder.any { v ->
            v.type in listOf("shell", "script", "http", "javascript", "match", "choice", "form", "intent", "content")
        } || isFormMatch

        if (!hasAsyncVar) {
            evalOrder.forEach { v -> workingReplace = parseItemSync(v, workingReplace) }
            val end = expansionStr.substring(triggerIndex).replace(triggerText, workingReplace)
            val newStr = expansionStr.substring(0, triggerIndex) + end
            doExpansion(event, newStr)
            if (storeOriginal) {
                previousOriginal = original
                previousExpansion = newStr
            } else {
                previousOriginal = ""
                previousExpansion = ""
            }
            return
        }

        isExpansionInProgress = true
        pendingRenderContext = RenderContext(
            match, triggerText, AccessibilityEvent.obtain(event),
            expansionStr, triggerIndex, storeOriginal, original,
            workingReplace, evalOrder, 0, mutableMapOf(),
            isFormMatch
        )
        executeNextVariable()
    }

    private fun executeNextVariable() {
        val ctx = pendingRenderContext ?: return
        if (ctx.currentIndex >= ctx.evalOrder.size) {
            if (ctx.isFormMatch) {
                showFormMatchUI(ctx)
                return
            }
            isExpansionInProgress = false
            val end = ctx.expansionStr.substring(ctx.triggerIndex).replace(ctx.triggerText, ctx.workingReplace)
            val newStr = ctx.expansionStr.substring(0, ctx.triggerIndex) + end
            doExpansion(ctx.event, newStr)
            if (ctx.storeOriginal) {
                previousOriginal = ctx.original
                previousExpansion = newStr
            } else {
                previousOriginal = ""
                previousExpansion = ""
            }
            pendingRenderContext = null
            return
        }

        val variable = ctx.evalOrder[ctx.currentIndex]

        val effectiveParams = if (variable.injectVars) {
            InjectVariables.injectVariablesIntoParams(variable.params, ctx.scope.toMap())
        } else {
            variable.params
        }

        when (variable.type) {
            "choice" -> {
                showChoiceInPipeline(ctx, variable)
                return
            }
            "form" -> {
                showFormInPipeline(ctx, variable)
                return
            }
        }

        val extension = extensionRegistry.get(variable.type ?: "")
        if (extension == null) {
            val syncResult = parseItemSync(variable, ctx.workingReplace)
            ctx.workingReplace = syncResult
            ctx.scope[variable.name ?: ""] = ExtensionOutput.Single(syncResult)
            ctx.currentIndex++
            executeNextVariable()
            return
        }

        extension.calculate(effectiveParams, ctx.scope.toMap()) { result ->
            mainHandler.post {
                when (result) {
                    is ExtensionResult.Success -> {
                        val output = result.output
                        ctx.scope[variable.name ?: ""] = output
                        when (output) {
                            is ExtensionOutput.Single -> {
                                ctx.workingReplace = ctx.workingReplace.replace(
                                    wrapName(variable.name ?: ""), output.value
                                )
                            }
                            is ExtensionOutput.Multiple -> {
                                output.values.forEach { (field, value) ->
                                    ctx.workingReplace = ctx.workingReplace.replace(
                                        "{{${variable.name}.${field}}}", value
                                    )
                                }
                            }
                        }
                    }
                    is ExtensionResult.Aborted -> {
                        isExpansionInProgress = false
                        pendingRenderContext = null
                        return@post
                    }
                    is ExtensionResult.Error -> {
                        ctx.workingReplace = ctx.workingReplace.replace(
                            wrapName(variable.name ?: ""), "[${result.message}]"
                        )
                    }
                }
                ctx.currentIndex++
                executeNextVariable()
            }
        }
    }

    private fun parseItemSync(item: Var, replace: String): String {
        try {
            if (item.type != null) {
                return when (item.type) {
                    "echo" -> replace.replace(wrapName(item.name ?: ""), item.params.string("echo") ?: "")
                    "random" -> {
                        val choices = item.params.stringList("choices") ?: return replace
                        if (choices.isNotEmpty()) {
                            val random = SecureRandom()
                            replace.replace(wrapName(item.name ?: ""), choices[random.nextInt(choices.size)])
                        } else replace
                    }
                    "clipboard" -> {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                        replace.replace(wrapName(item.name ?: ""), clip)
                    }
                    "date" -> {
                        val param = item.params
                        val now = LocalDateTime.now()
                        val dateTime = now.plus(param.long("offset"), ChronoUnit.SECONDS)
                        val formatter = DateTimeFormatter.ofPattern(param.string("format") ?: "")
                        replace.replace(wrapName(item.name ?: ""), dateTime.format(formatter))
                    }
                    else -> replace
                }
            }
            return replace
        } catch (e: Exception) {
            return replace
        }
    }

    override fun render(
        template: Match,
        parentScope: Map<String, ExtensionOutput>,
        visitedTriggers: Set<String>,
        callback: (ExtensionResult) -> Unit
    ) {
        val trigger = template.trigger ?: ""
        if (trigger in visitedTriggers) {
            callback(ExtensionResult.Error("Circular match reference: $trigger"))
            return
        }

        var workingReplace = template.replace ?: ""
        if (template.propagateCase) {
            workingReplace = applyPropagateCase(trigger, workingReplace, template.uppercaseStyle)
        }

        val localVars = template.vars ?: emptyList()
        val globalVarsList = globals ?: emptyList()
        val evalOrder = DependencyResolver.resolveEvaluationOrder(
            workingReplace, localVars, globalVarsList
        ).getOrElse {
            globalVarsList + localVars
        }

        val scope = mutableMapOf<String, ExtensionOutput>()
        scope.putAll(parentScope)

        renderVariableList(template, workingReplace, evalOrder, scope, visitedTriggers + trigger, 0, callback)
    }

    private fun renderVariableList(
        template: Match,
        workingReplace: String,
        evalOrder: List<Var>,
        scope: MutableMap<String, ExtensionOutput>,
        visitedTriggers: Set<String>,
        index: Int,
        callback: (ExtensionResult) -> Unit
    ) {
        if (index >= evalOrder.size) {
            callback(ExtensionResult.Success(ExtensionOutput.Single(workingReplace)))
            return
        }

        val variable = evalOrder[index]
        var currentReplace = workingReplace

        val effectiveParams = if (variable.injectVars) {
            InjectVariables.injectVariablesIntoParams(variable.params, scope.toMap())
        } else {
            variable.params
        }

        when (variable.type) {
            "choice" -> {
                showChoiceInRenderList(template, variable, currentReplace, evalOrder, scope, visitedTriggers, index, callback)
                return
            }
            "form" -> {
                showFormInRenderList(template, variable, currentReplace, evalOrder, scope, visitedTriggers, index, callback)
                return
            }
        }

        val extension = extensionRegistry.get(variable.type ?: "")
        if (extension == null) {
            val syncResult = parseItemSync(variable, currentReplace)
            currentReplace = syncResult
            scope[variable.name ?: ""] = ExtensionOutput.Single(syncResult)
            renderVariableList(template, currentReplace, evalOrder, scope, visitedTriggers, index + 1, callback)
            return
        }

        extension.calculate(effectiveParams, scope.toMap()) { result ->
            mainHandler.post {
                when (result) {
                    is ExtensionResult.Success -> {
                        val output = result.output
                        scope[variable.name ?: ""] = output
                        when (output) {
                            is ExtensionOutput.Single -> {
                                currentReplace = currentReplace.replace(wrapName(variable.name ?: ""), output.value)
                            }
                            is ExtensionOutput.Multiple -> {
                                output.values.forEach { (field, value) ->
                                    currentReplace = currentReplace.replace(
                                        "{{${variable.name}.${field}}}", value
                                    )
                                }
                            }
                        }
                    }
                    is ExtensionResult.Aborted -> {
                        callback(ExtensionResult.Aborted)
                        return@post
                    }
                    is ExtensionResult.Error -> {
                        currentReplace = currentReplace.replace(wrapName(variable.name ?: ""), "[${result.message}]")
                    }
                }
                renderVariableList(template, currentReplace, evalOrder, scope, visitedTriggers, index + 1, callback)
            }
        }
    }

    private fun showFormMatchUI(ctx: RenderContext) {
        val context = this
        val formLines = ctx.workingReplace.split(*LINE_SEPARATORS).filter { it.isNotEmpty() }
        val replaceDict = mutableMapOf<String, String>()

        formLines.forEach { line ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            if (line.contains("[[")) {
                val words = line.split(*FORM_SEPARATORS).filter { it.isNotEmpty() }
                words.forEach { word ->
                    if (word.startsWith("[[")) {
                        val endIndex = word.indexOf(']')
                        val placeholderStr = word.substring(2, endIndex)
                        val formOption = ctx.match.formFields?.get(placeholderStr)

                        when (formOption?.type) {
                            "choice" -> {
                                val spinner = Spinner(context)
                                val adapter = ArrayAdapter(
                                    context,
                                    android.R.layout.simple_spinner_dropdown_item,
                                    formOption.values ?: emptyList()
                                )
                                spinner.adapter = adapter
                                spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                                        replaceDict[placeholderStr] = formOption.values!![position]
                                    }
                                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                                }
                                row.post { row.addView(spinner) }
                            }
                            "list" -> {
                                val listView = ListView(context)
                                val adapter = ArrayAdapter(
                                    context,
                                    android.R.layout.simple_list_item_1,
                                    formOption.values ?: emptyList()
                                )
                                listView.adapter = adapter
                                listView.setOnItemClickListener { _, _, position, _ ->
                                    replaceDict[placeholderStr] = formOption.values!![position]
                                }
                                row.post { row.addView(listView) }
                            }
                            else -> {
                                row.post {
                                    val et = EditText(context)
                                    et.setHint(placeholderStr)
                                    if (formOption?.multiline == true) {
                                        et.minLines = 3
                                    }
                                    et.addTextChangedListener(object : android.text.TextWatcher {
                                        override fun afterTextChanged(s: android.text.Editable?) {
                                            replaceDict[placeholderStr] = s.toString()
                                        }
                                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                                    })
                                    row.addView(et)
                                }
                            }
                        }
                    } else {
                        addTextView(row, word)
                    }
                }
            } else {
                addTextView(row, line)
            }
            rowContainer?.post { rowContainer?.addView(row) }
        }

        val submitButton = Button(context)
        submitButton.text = "Submit"
        submitButton.setOnClickListener {
            var formText = ctx.workingReplace
            replaceDict.forEach { (key, value) ->
                formText = formText.replace("[[${key}]]", value)
            }
            windowManager?.removeView(floatView)
            rowContainer?.removeAllViewsInLayout()

            isExpansionInProgress = false
            val end = ctx.expansionStr.substring(ctx.triggerIndex).replace(ctx.triggerText, formText)
            val newStr = ctx.expansionStr.substring(0, ctx.triggerIndex) + end
            doExpansion(ctx.event, newStr)
            if (ctx.storeOriginal) {
                previousOriginal = ctx.original
                previousExpansion = newStr
            } else {
                previousOriginal = ""
                previousExpansion = ""
            }
            pendingRenderContext = null
        }
        rowContainer?.post { rowContainer?.addView(submitButton) }
        windowManager?.addView(floatView, layoutParams)
    }

    private fun showChoiceInPipeline(ctx: RenderContext, choiceVar: Var) {
        val values = choiceVar.params.stringList("values")
            ?: choiceVar.params.stringList("choices")
        if (values.isNullOrEmpty()) {
            ctx.currentIndex++
            executeNextVariable()
            return
        }

        val context = this
        val choiceContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val title = TextView(context).apply {
            text = "Select: ${choiceVar.name ?: "choice"}"
            setPadding(0, 0, 0, 8)
        }
        choiceContainer.addView(title)

        val listView = ListView(context)
        val adapter = ArrayAdapter(
            context,
            android.R.layout.simple_list_item_1,
            values
        )
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val selected = values[position]
            ctx.scope[choiceVar.name ?: ""] = ExtensionOutput.Single(selected)
            ctx.workingReplace = ctx.workingReplace.replace(wrapName(choiceVar.name ?: ""), selected)

            windowManager?.removeView(floatView)
            rowContainer?.removeAllViewsInLayout()

            ctx.currentIndex++
            executeNextVariable()
        }
        choiceContainer.addView(listView)

        val cancelButton = Button(context)
        cancelButton.text = "Cancel"
        cancelButton.setOnClickListener {
            windowManager?.removeView(floatView)
            rowContainer?.removeAllViewsInLayout()
            isExpansionInProgress = false
            pendingRenderContext = null
        }
        choiceContainer.addView(cancelButton)

        rowContainer?.post {
            rowContainer?.addView(choiceContainer)
        }
        windowManager?.addView(floatView, layoutParams)
    }

    private fun showFormInPipeline(ctx: RenderContext, formVar: Var) {
        val formLayout = formVar.params.string("layout")
        if (formLayout == null) {
            ctx.currentIndex++
            executeNextVariable()
            return
        }

        val context = this
        val replaceDict = mutableMapOf<String, String>()

        renderFormFields(formLayout, formVar.params, replaceDict) { formText ->
            windowManager?.removeView(floatView)
            rowContainer?.removeAllViewsInLayout()

            ctx.scope[formVar.name ?: ""] = ExtensionOutput.Single(formText)
            ctx.workingReplace = ctx.workingReplace.replace(wrapName(formVar.name ?: ""), formText)

            ctx.currentIndex++
            executeNextVariable()
        }
    }

    private fun showChoiceInRenderList(
        template: Match,
        choiceVar: Var,
        currentReplace: String,
        evalOrder: List<Var>,
        scope: MutableMap<String, ExtensionOutput>,
        visitedTriggers: Set<String>,
        index: Int,
        callback: (ExtensionResult) -> Unit
    ) {
        val values = choiceVar.params.stringList("values")
            ?: choiceVar.params.stringList("choices")
        if (values.isNullOrEmpty()) {
            renderVariableList(template, currentReplace, evalOrder, scope, visitedTriggers, index + 1, callback)
            return
        }

        val context = this
        val choiceContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val title = TextView(context).apply {
            text = "Select: ${choiceVar.name ?: "choice"}"
            setPadding(0, 0, 0, 8)
        }
        choiceContainer.addView(title)

        val listView = ListView(context)
        val adapter = ArrayAdapter(
            context,
            android.R.layout.simple_list_item_1,
            values
        )
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val selected = values[position]
            scope[choiceVar.name ?: ""] = ExtensionOutput.Single(selected)
            val updated = currentReplace.replace(wrapName(choiceVar.name ?: ""), selected)

            windowManager?.removeView(floatView)
            rowContainer?.removeAllViewsInLayout()

            renderVariableList(template, updated, evalOrder, scope, visitedTriggers, index + 1, callback)
        }
        choiceContainer.addView(listView)

        val cancelButton = Button(context)
        cancelButton.text = "Cancel"
        cancelButton.setOnClickListener {
            windowManager?.removeView(floatView)
            rowContainer?.removeAllViewsInLayout()
            callback(ExtensionResult.Aborted)
        }
        choiceContainer.addView(cancelButton)

        rowContainer?.post {
            rowContainer?.addView(choiceContainer)
        }
        windowManager?.addView(floatView, layoutParams)
    }

    private fun showFormInRenderList(
        template: Match,
        formVar: Var,
        currentReplace: String,
        evalOrder: List<Var>,
        scope: MutableMap<String, ExtensionOutput>,
        visitedTriggers: Set<String>,
        index: Int,
        callback: (ExtensionResult) -> Unit
    ) {
        val formLayout = formVar.params.string("layout")
        if (formLayout == null) {
            renderVariableList(template, currentReplace, evalOrder, scope, visitedTriggers, index + 1, callback)
            return
        }

        val replaceDict = mutableMapOf<String, String>()

        renderFormFields(formLayout, formVar.params, replaceDict) { formText ->
            windowManager?.removeView(floatView)
            rowContainer?.removeAllViewsInLayout()

            scope[formVar.name ?: ""] = ExtensionOutput.Single(formText)
            val updated = currentReplace.replace(wrapName(formVar.name ?: ""), formText)

            renderVariableList(template, updated, evalOrder, scope, visitedTriggers, index + 1, callback)
        }
    }

    private fun renderFormFields(
        formLayout: String,
        fieldParams: Params,
        replaceDict: MutableMap<String, String>,
        onSubmit: (String) -> Unit
    ) {
        val context = this
        val formLines = formLayout.split(*LINE_SEPARATORS).filter { it.isNotEmpty() }

        formLines.forEach { line ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            if (line.contains("[[")) {
                val words = line.split(*FORM_SEPARATORS).filter { it.isNotEmpty() }
                words.forEach { word ->
                    if (word.startsWith("[[")) {
                        val endIndex = word.indexOf(']')
                        val placeholderStr = word.substring(2, endIndex)

                        val fieldType = fieldParams.string("${placeholderStr}_type") ?: "text"
                        val fieldValues = fieldParams.stringList("${placeholderStr}_values")
                        val fieldMultiline = (fieldParams.data["${placeholderStr}_multiline"] as? Boolean) ?: false

                        when (fieldType) {
                            "choice" -> {
                                val spinner = Spinner(context)
                                val adapter = ArrayAdapter(
                                    context,
                                    android.R.layout.simple_spinner_dropdown_item,
                                    fieldValues ?: emptyList()
                                )
                                spinner.adapter = adapter
                                spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                                        replaceDict[placeholderStr] = (fieldValues ?: emptyList())[position]
                                    }
                                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                                }
                                row.post { row.addView(spinner) }
                            }
                            "list" -> {
                                val listView = ListView(context)
                                val adapter = ArrayAdapter(
                                    context,
                                    android.R.layout.simple_list_item_1,
                                    fieldValues ?: emptyList()
                                )
                                listView.adapter = adapter
                                listView.setOnItemClickListener { _, _, position, _ ->
                                    replaceDict[placeholderStr] = (fieldValues ?: emptyList())[position]
                                }
                                row.post { row.addView(listView) }
                            }
                            else -> {
                                row.post {
                                    val et = EditText(context)
                                    et.setHint(placeholderStr)
                                    if (fieldMultiline) {
                                        et.minLines = 3
                                    }
                                    et.addTextChangedListener(object : android.text.TextWatcher {
                                        override fun afterTextChanged(s: android.text.Editable?) {
                                            replaceDict[placeholderStr] = s.toString()
                                        }
                                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                                    })
                                    row.addView(et)
                                }
                            }
                        }
                    } else {
                        addTextView(row, word)
                    }
                }
            } else {
                addTextView(row, line)
            }
            rowContainer?.post { rowContainer?.addView(row) }
        }

        val submitButton = Button(context)
        submitButton.text = "Submit"
        submitButton.setOnClickListener {
            var formText = formLayout
            replaceDict.forEach { (key, value) ->
                formText = formText.replace("[[${key}]]", value)
            }
            onSubmit(formText)
        }
        rowContainer?.post { rowContainer?.addView(submitButton) }
        windowManager?.addView(floatView, layoutParams)
    }

    private fun showChoiceForMatch(
        match: Match,
        triggerText: String,
        event: AccessibilityEvent,
        expansionStr: String,
        triggerIndex: Int,
        storeOriginal: Boolean,
        original: String
    ) {
        val context = this
        var workingReplace = match.replace ?: ""

        if (match.propagateCase) {
            workingReplace = applyPropagateCase(triggerText, workingReplace, match.uppercaseStyle)
        }

        // Process non-choice vars first
        globals?.forEach { item ->
            workingReplace = parseItemSync(item, workingReplace)
        }
        match.vars?.forEach { item ->
            if (item.type != "choice") {
                workingReplace = parseItemSync(item, workingReplace)
            }
        }

        // Find the first choice var
        val choiceVar = match.vars?.find { it.type == "choice" } ?: return
        val values = choiceVar.params.stringList("values") ?: choiceVar.params.stringList("choices") ?: return
        if (values.isEmpty()) return

        val choiceContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val title = TextView(context).apply {
            text = "Select: ${choiceVar.name ?: "choice"}"
            setPadding(0, 0, 0, 8)
        }
        choiceContainer.addView(title)

        val listView = ListView(context)
        val adapter = ArrayAdapter(
            context,
            android.R.layout.simple_list_item_1,
            values
        )
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val selected = values[position]
            var finalReplace = workingReplace.replace(wrapName(choiceVar.name ?: ""), selected)

            // Process remaining choice vars (if multiple, only first is interactive, rest use first value)
            match.vars?.forEach { item ->
                if (item.type == "choice" && item != choiceVar) {
                    val vals = item.params.stringList("values") ?: item.params.stringList("choices")
                    if (!vals.isNullOrEmpty()) {
                        finalReplace = finalReplace.replace(wrapName(item.name ?: ""), vals[0])
                    }
                }
            }

            windowManager?.removeView(floatView)
            rowContainer?.removeAllViewsInLayout()

            val end = expansionStr.substring(triggerIndex).replace(triggerText, finalReplace)
            val newStr = expansionStr.substring(0, triggerIndex) + end
            doExpansion(event, newStr)
            if (storeOriginal) {
                previousOriginal = original
                previousExpansion = newStr
            } else {
                previousOriginal = ""
                previousExpansion = ""
            }
        }
        choiceContainer.addView(listView)

        val cancelButton = Button(context)
        cancelButton.text = "Cancel"
        cancelButton.setOnClickListener {
            windowManager?.removeView(floatView)
            rowContainer?.removeAllViewsInLayout()
        }
        choiceContainer.addView(cancelButton)

        rowContainer?.post {
            rowContainer?.addView(choiceContainer)
        }
        windowManager?.addView(floatView, layoutParams)
    }

    private fun applyPropagateCase(trigger: String, replace: String, style: String?): String {
        val isAllUpper = trigger.all { !it.isLetter() || it.isUpperCase() }
        val isFirstUpper = trigger.isNotEmpty() && trigger[0].isUpperCase()
        return when {
            isAllUpper -> when (style ?: "uppercase") {
                "uppercase" -> replace.uppercase()
                "capitalize" -> replace.split(" ").joinToString(" ") { w ->
                    if (w.isNotEmpty()) w[0].uppercase() + w.drop(1).lowercase() else w
                }
                "capitalize_words" -> replace.split(" ").joinToString(" ") { w ->
                    if (w.isNotEmpty()) w[0].uppercase() + w.drop(1).lowercase() else w
                }
                else -> replace.uppercase()
            }
            isFirstUpper -> replace.split(" ").joinToString(" ") { w ->
                if (w.isNotEmpty()) w[0].uppercase() + w.drop(1) else w
            }
            else -> replace
        }
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        ServiceCommandBus.trySend(ServiceCommandBus.Command.Reset)

        val linearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        layoutParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP
        }
        val inflater = LayoutInflater.from(this)
        floatView = inflater.inflate(R.layout.floatview, linearLayout)

        val closeBtn = floatView?.findViewById<ImageButton>(R.id.close_button)
        closeBtn?.setOnClickListener {
            windowManager?.removeView(floatView)
            rowContainer?.removeAllViewsInLayout()
            skipNextFormEvent = true
        }

        floatView?.setOnTouchListener(this)
        rowContainer = floatView?.findViewById(R.id.rowContainer)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onUnbind(intent: Intent?): Boolean {
        floatView?.let {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(it)
        }
        return super.onUnbind(intent)
    }

    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        event ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                xDown = event.rawX
                yDown = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - xDown
                val deltaY = event.rawY - yDown
                layoutParams?.x = (layoutParams?.x ?: 0) + deltaX.toInt()
                layoutParams?.y = (layoutParams?.y ?: 0) + deltaY.toInt()
                windowManager?.updateViewLayout(floatView, layoutParams)
                xDown = event.rawX
                yDown = event.rawY
                return true
            }
            else -> return false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
