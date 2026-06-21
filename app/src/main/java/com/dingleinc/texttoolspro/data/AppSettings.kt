package com.dingleinc.texttoolspro.data

import android.content.Context
import java.io.File

object AppSettings {
    val supportedList = listOf("echo", "date", "clipboard", "random")

    lateinit var dictPath: String
        private set
    lateinit var oldDictPath: String
        private set
    lateinit var globalVarsPath: String
        private set

    fun init(context: Context) {
        dictPath = File(context.filesDir, "keywords.json").absolutePath
        oldDictPath = File(context.cacheDir, "keywords.json").absolutePath
        globalVarsPath = File(context.filesDir, "global.json").absolutePath
    }
}
