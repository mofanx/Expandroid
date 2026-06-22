package com.dingleinc.texttoolspro.extension.shell

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.app.PendingIntent
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TermuxShellExecutor(private val context: Context) : ShellExecutor {

    companion object {
        private const val TAG = "TermuxShell"
        private const val TERMUX_PACKAGE = "com.termux"
        private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"

        private val executionId = AtomicInteger(1000)
        private val pendingCallbacks = ConcurrentHashMap<Int, (ShellResult) -> Unit>()
        private val mainHandler = Handler(Looper.getMainLooper())

        fun handleResult(execId: Int, stdout: String, stderr: String, exitCode: Int) {
            val callback = pendingCallbacks.remove(execId) ?: return
            callback(ShellResult(stdout, stderr, exitCode, exitCode == 0))
        }
    }

    override fun isAvailable(): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            context.checkSelfPermission("com.termux.permission.RUN_COMMAND") ==
                PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    override fun execute(
        command: List<String>,
        env: Map<String, String>,
        timeoutMs: Long,
        callback: (ShellResult) -> Unit
    ) {
        val execId = executionId.getAndIncrement()

        val envExport = buildString {
            env.forEach { (key, value) ->
                val escaped = value.replace("'", "'\\''")
                append("export $key='$escaped'; ")
            }
        }
        val fullCmd = if (command.size >= 3 && command[1] == "-c") {
            "$envExport${command[2]}"
        } else {
            "$envExport${command.joinToString(" ")}"
        }

        val shellPath = if (command.isNotEmpty()) command[0] else "bash"
        val termuxShellPath = when (shellPath) {
            "bash" -> "/data/data/com.termux/files/usr/bin/bash"
            "sh" -> "/data/data/com.termux/files/usr/bin/sh"
            "zsh" -> "/data/data/com.termux/files/usr/bin/zsh"
            else -> "/data/data/com.termux/files/usr/bin/$shellPath"
        }

        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            action = RUN_COMMAND_ACTION
            putExtra(EXTRA_PATH, termuxShellPath)
            putExtra(EXTRA_ARGUMENTS, arrayOf("-c", fullCmd))
            putExtra(EXTRA_WORKDIR, "/data/data/com.termux/files/home")
            putExtra(EXTRA_BACKGROUND, true)
        }

        val resultIntent = Intent(context, TermuxResultService::class.java)
        resultIntent.putExtra(TermuxResultService.EXTRA_EXECUTION_ID, execId)

        val pendingIntent = PendingIntent.getService(
            context, execId, resultIntent,
            PendingIntent.FLAG_ONE_SHOT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        )
        intent.putExtra(EXTRA_PENDING_INTENT, pendingIntent)

        pendingCallbacks[execId] = callback

        mainHandler.postDelayed({
            val cb = pendingCallbacks.remove(execId)
            cb?.invoke(ShellResult("", "timeout", -1, false))
        }, timeoutMs)

        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Termux service: ${e.message}")
            pendingCallbacks.remove(execId)
            callback(ShellResult("", e.message ?: "termux error", -1, false))
        }
    }
}
