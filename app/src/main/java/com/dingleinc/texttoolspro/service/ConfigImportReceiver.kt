package com.dingleinc.texttoolspro.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.dingleinc.texttoolspro.data.AppSettings
import com.dingleinc.texttoolspro.data.Match
import com.dingleinc.texttoolspro.data.SerializationHelper
import com.dingleinc.texttoolspro.data.ServiceCommandBus
import com.dingleinc.texttoolspro.data.Var
import com.dingleinc.texttoolspro.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class ConfigImportReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ConfigImport"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) return

        val configStr = intent.getStringExtra("config_string") ?: return
        if (configStr.isEmpty()) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val localDict = SerializationHelper.parseDictWrapperFromYaml(configStr)

                localDict.matches?.forEach { item ->
                    item.vars?.forEach { x ->
                        if (x.type != null) {
                            if (!AppSettings.supportedList.contains(x.type)) {
                                return@forEach
                            } else if (x.type == "date") {
                                try {
                                    val fmt = x.params.string("format")
                                    if (!fmt.isNullOrEmpty()) {
                                        x.params["format"] = Utils.getTheRealFormat(fmt)
                                    }
                                } catch (e: Exception) {
                                    throw Exception("Date extension parameter formats error")
                                }
                            }
                        }
                    }
                }

                localDict.globalVars?.let { gvars ->
                    val str = SerializationHelper.toJson(gvars)
                    File(AppSettings.globalVarsPath).writeText(str)
                    ServiceCommandBus.trySend(
                        ServiceCommandBus.Command.UpdateGlobals(gvars)
                    )
                }

                val dict = mutableMapOf<String, Match>()
                var skippedCount = 0
                localDict.matches?.forEach { match ->
                    // Check if any vars have unsupported types
                    var hasUnsupported = false
                    match.vars?.forEach { v ->
                        if (v.type != null && !AppSettings.supportedList.contains(v.type)) {
                            hasUnsupported = true
                        }
                    }
                    if (hasUnsupported) {
                        skippedCount++
                        Log.w(TAG, "Skipped match with unsupported var type: trigger=${match.trigger} regex=${match.regex}")
                        return@forEach
                    }

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
                        // Regex-only match: use regex string as dict key for storage
                        dict["__regex__${match.regex}"] = match
                    }
                }
                val jsonStr = SerializationHelper.toJson(dict)
                File(AppSettings.dictPath).writeText(jsonStr)
                ServiceCommandBus.trySend(ServiceCommandBus.Command.Reset)

                val resultIntent = Intent("com.dingleinc.texttoolspro.CONFIG_RESULT")
                resultIntent.putExtra("status", 0)
                resultIntent.putExtra("imported", dict.size)
                resultIntent.putExtra("skipped", skippedCount)
                context.sendBroadcast(resultIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Import error: ${e}")
                val resultIntent = Intent("com.dingleinc.texttoolspro.CONFIG_RESULT")
                resultIntent.putExtra("status", e.message ?: "error")
                context.sendBroadcast(resultIntent)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
