package com.dingleinc.texttoolspro.extension.shell

import com.dingleinc.texttoolspro.data.Params
import com.dingleinc.texttoolspro.extension.ExtensionOutput
import com.dingleinc.texttoolspro.extension.ExtensionResult
import com.dingleinc.texttoolspro.extension.InjectVariables
import com.dingleinc.texttoolspro.extension.VariableExtension
import android.util.Log

class ShellExtension(
    private val executorProvider: () -> ShellExecutor?
) : VariableExtension {

    companion object {
        private const val TAG = "ShellExtension"
    }

    override val typeName = "shell"

    override fun calculate(
        params: Params,
        scope: Map<String, ExtensionOutput>,
        callback: (ExtensionResult) -> Unit
    ) {
        val cmd = params.string("cmd")
        if (cmd == null) {
            callback(ExtensionResult.Error("missing 'cmd' param"))
            return
        }

        val executor = executorProvider()
        if (executor == null || !executor.isAvailable()) {
            callback(ExtensionResult.Error("shell unavailable"))
            return
        }

        val shell = params.string("shell") ?: "sh"
        val trim = (params.data["trim"] as? Boolean) ?: true
        val debug = (params.data["debug"] as? Boolean) ?: false

        val env = InjectVariables.convertToEnvVariables(scope)

        val command = listOf(shell, "-c", cmd)

        if (debug) {
            Log.d(TAG, "Executing: $shell -c $cmd")
            Log.d(TAG, "Env: $env")
        }

        executor.execute(command, env, 5000) { result ->
            if (debug) {
                Log.d(TAG, "stdout: ${result.stdout}")
                Log.d(TAG, "stderr: ${result.stderr}")
                Log.d(TAG, "exitCode: ${result.exitCode}")
            }

            if (!result.success) {
                val errorMsg = result.stderr.ifBlank { "exit code: ${result.exitCode}" }
                if (result.stderr == "timeout") {
                    callback(ExtensionResult.Error("timeout"))
                } else {
                    callback(ExtensionResult.Error(errorMsg))
                }
                return@execute
            }

            val output = if (trim) result.stdout.trim() else result.stdout
            callback(ExtensionResult.Success(ExtensionOutput.Single(output)))
        }
    }
}
