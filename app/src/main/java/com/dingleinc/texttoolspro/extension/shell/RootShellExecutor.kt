package com.dingleinc.texttoolspro.extension.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class RootShellExecutor : ShellExecutor {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun isAvailable(): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            completed && process.exitValue() == 0
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
        scope.launch {
            try {
                val fullCommand = buildString {
                    env.forEach { (key, value) ->
                        val escapedValue = value.replace("'", "'\\''")
                        append("export $key='$escapedValue'; ")
                    }
                    append(command.joinToString(" ") { escapeArg(it) })
                }

                val processBuilder = ProcessBuilder("su", "-c", fullCommand)
                    .redirectErrorStream(false)
                processBuilder.directory(File("/data"))
                val process = processBuilder.start()

                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                val completed = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

                if (!completed) {
                    process.destroyForcibly()
                    callback(ShellResult("", "timeout", -1, false))
                    return@launch
                }

                val exitCode = process.exitValue()
                callback(ShellResult(stdout, stderr, exitCode, exitCode == 0))
            } catch (e: Exception) {
                callback(ShellResult("", e.message ?: "execution error", -1, false))
            }
        }
    }

    private fun escapeArg(arg: String): String {
        return if (arg.contains(" ") || arg.contains("'") || arg.contains("\"") || arg.contains("\\")) {
            "'${arg.replace("'", "'\\''")}'"
        } else {
            arg
        }
    }
}
