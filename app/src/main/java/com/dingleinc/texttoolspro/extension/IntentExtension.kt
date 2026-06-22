package com.dingleinc.texttoolspro.extension

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.app.PendingIntent
import com.dingleinc.texttoolspro.data.Params
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class IntentExtension(private val context: Context) : VariableExtension {

    override val typeName = "intent"

    companion object {
        private val requestCodes = AtomicInteger(1)
        private val pendingResults = ConcurrentHashMap<Int, (ExtensionResult) -> Unit>()
        private val mainHandler = Handler(Looper.getMainLooper())
    }

    override fun calculate(
        params: Params,
        scope: Map<String, ExtensionOutput>,
        callback: (ExtensionResult) -> Unit
    ) {
        val action = params.string("action")
        if (action == null) {
            callback(ExtensionResult.Error("missing 'action' param"))
            return
        }
        val resultKey = params.string("result_key")
        if (resultKey == null) {
            callback(ExtensionResult.Error("missing 'result_key' param"))
            return
        }
        val timeout = params.long("timeout").takeIf { it > 0 } ?: 5000L

        val requestCode = requestCodes.getAndIncrement()
        pendingResults[requestCode] = callback

        val resultReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null) return
                val resultValue = intent.getStringExtra(resultKey)
                val cb = pendingResults.remove(requestCode)
                context.unregisterReceiver(this)
                if (cb != null) {
                    if (resultValue != null) {
                        cb(ExtensionResult.Success(ExtensionOutput.Single(resultValue)))
                    } else {
                        cb(ExtensionResult.Error("no result for key: $resultKey"))
                    }
                }
            }
        }

        val resultAction = "com.dingleinc.texttoolspro.INTENT_RESULT_$requestCode"
        val filter = IntentFilter(resultAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(resultReceiver, filter)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, Intent(resultAction),
            PendingIntent.FLAG_ONE_SHOT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        )

        val broadcastIntent = Intent(action).apply {
            putExtra("com.dingleinc.texttoolspro.RESULT_PENDING_INTENT", pendingIntent)
            params.data.forEach { (key, value) ->
                if (key !in listOf("action", "result_key", "timeout") && value is String) {
                    putExtra(key, value)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.sendBroadcast(broadcastIntent, null)
        } else {
            context.sendBroadcast(broadcastIntent)
        }

        mainHandler.postDelayed({
            val cb = pendingResults.remove(requestCode)
            if (cb != null) {
                try { context.unregisterReceiver(resultReceiver) } catch (e: Exception) {}
                cb(ExtensionResult.Error("intent timeout"))
            }
        }, timeout)
    }
}
