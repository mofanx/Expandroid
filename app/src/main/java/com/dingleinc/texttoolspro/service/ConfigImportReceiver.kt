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
                                    x.params.format = Utils.getTheRealFormat(x.params.format ?: "")
                                } catch (e: Exception) {
                                    throw Exception("Date extension parameter formats error")
                                }
                            }
                        }
                    }
                }

                if (localDict.globalVars != null) {
                    val str = SerializationHelper.toJson(localDict.globalVars)
                    File(AppSettings.globalVarsPath).writeText(str)
                    ServiceCommandBus.trySend(
                        ServiceCommandBus.Command.UpdateGlobals(localDict.globalVars!!)
                    )
                }

                val dict = mutableMapOf<String, Match>()
                localDict.matches?.forEach { match ->
                    match.trigger?.let { dict[it] = match }
                }
                val jsonStr = SerializationHelper.toJson(dict)
                File(AppSettings.dictPath).writeText(jsonStr)
                ServiceCommandBus.trySend(ServiceCommandBus.Command.Reset)

                val resultIntent = Intent("com.dingleinc.texttoolspro.CONFIG_RESULT")
                resultIntent.putExtra("status", 0)
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
