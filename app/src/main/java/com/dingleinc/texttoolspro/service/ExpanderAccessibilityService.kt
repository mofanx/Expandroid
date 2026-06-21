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
import com.dingleinc.texttoolspro.data.Var
import com.dingleinc.texttoolspro.data.SerializationHelper
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

class ExpanderAccessibilityService : AccessibilityService(), View.OnTouchListener {

    companion object {
        private const val TAG = "A11Y"
        private const val CURSOR_STR = "\$|\$"
        private val SEPARATORS = arrayOf(" ", "\n", "\r\n", " ,")
        private val FORM_SEPARATORS = arrayOf(" ", "|", "\r\n", "\n")
        private val LINE_SEPARATORS = arrayOf("\r\n", "\n")
    }

    private var dict: MutableMap<String, Match> = mutableMapOf()
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
                    }
                    is ServiceCommandBus.Command.Quit -> {
                        disableSelf()
                    }
                    is ServiceCommandBus.Command.Reset -> {
                        loadDictFromFiles()
                    }
                    is ServiceCommandBus.Command.Remove -> {
                        dict.remove(command.match.trigger)
                    }
                    is ServiceCommandBus.Command.UpdateGlobals -> {
                        globals = command.globals
                    }
                }
            }
        }

        loadDictFromFiles()
    }

    private fun loadDictFromFiles() {
        try {
            if (File(AppSettings.dictPath).exists()) {
                val content = File(AppSettings.dictPath).readText()
                val type = kotlinx.serialization.builtins.MapSerializer(
                    kotlinx.serialization.builtins.serializer<String>(),
                    kotlinx.serialization.builtins.serializer<Match>()
                )
                dict = SerializationHelper.json.decodeFromString(content) as MutableMap<String, Match>
            }
            if (File(AppSettings.globalVarsPath).exists()) {
                val content = File(AppSettings.globalVarsPath).readText()
                globals = SerializationHelper.json.decodeFromString(content)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading files: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            val packageName = event.packageName?.toString() ?: return

            val node = event.source

            if (node != null) {
                val className = node.className?.toString() ?: ""
                val isEditText = className.contains("EditText") && node.isEditable

                if (isEditText && event.text != null && event.text.isNotEmpty()) {
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
                    val root = rootInActiveWindow ?: run {
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
                var modified = original.replace(formKey, formExpansion)
                expansionStrHandleTextExpansion(expansionStr, modified, event)
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
                    var replace = match.replace ?: ""
                    val triggerIndex = expansionStr.indexOf(text)

                    if (match.word) {
                        if (triggerIndex == 0) {
                            if (triggerIndex + text.length < expansionStr.length &&
                                !SEPARATORS.contains(expansionStr[triggerIndex + text.length].toString())
                            ) return
                        } else if (triggerIndex + text.length >= expansionStr.length ||
                            !SEPARATORS.contains(expansionStr[triggerIndex - 1].toString()) ||
                            !SEPARATORS.contains(expansionStr[triggerIndex + text.length].toString())
                        ) {
                            return
                        }
                    }

                    if (!match.form.isNullOrEmpty()) {
                        showForm(match, text, event)
                        return
                    } else {
                        globals?.forEach { item ->
                            replace = parseItem(item, replace)
                        }
                        match.vars?.forEach { item ->
                            replace = parseItem(item, replace)
                        }
                        if (replace != null) {
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

    private fun expansionStrHandleTextExpansion(original: String, modified: String, event: AccessibilityEvent) {
        doExpansion(event, modified)
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
                                    val et = EditText(context).apply {
                                        hint = placeholderStr
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

        val submitButton = Button(context).apply { text = "Submit" }
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
            row.addView(TextView(this).apply { text = word })
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
                    "echo" -> replace.replace(wrapName(item.name ?: ""), item.params.echo ?: "")
                    "random" -> {
                        val choices = item.params.choices ?: return replace
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
                        val dateTime = now.plus(param.offset, ChronoUnit.SECONDS)
                        val formatter = DateTimeFormatter.ofPattern(param.format ?: "")
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

    private fun wrapName(name: String): String = "{{$name}}"

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
