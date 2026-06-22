package com.dingleinc.texttoolspro.extension.shell

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

class ShellServiceImpl : IShellService.Stub() {

    companion object {
        private const val TAG = "ShellServiceImpl"
    }

    override fun executeCommand(
        command: Array<out String>,
        envKeys: Array<out String>,
        envValues: Array<out String>,
        timeoutMs: Long
    ): String {
        try {
            if (command.isEmpty()) return "[error: empty command]"

            val processBuilder = ProcessBuilder(*command)
                .redirectErrorStream(false)

            for (i in envKeys.indices) {
                processBuilder.environment()[envKeys[i]] = envValues[i]
            }

            processBuilder.directory(File("/data"))

            val process = processBuilder.start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

            if (!completed) {
                process.destroyForcibly()
                return "[error: timeout]"
            }

            val exitCode = process.exitValue()
            val result = if (exitCode == 0) {
                stdout
            } else {
                "[error: exit code $exitCode, stderr: $stderr]"
            }
            Log.d(TAG, "Command: ${command.joinToString(" ")}, exit: $exitCode")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Execution error: ${e.message}")
            return "[error: ${e.message}]"
        }
    }
}
