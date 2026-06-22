package com.dingleinc.texttoolspro.extension.shell

import com.dingleinc.texttoolspro.data.Params
import com.dingleinc.texttoolspro.extension.ExtensionOutput
import com.dingleinc.texttoolspro.extension.ExtensionResult
import com.dingleinc.texttoolspro.extension.InjectVariables
import com.dingleinc.texttoolspro.extension.VariableExtension
import android.util.Log

class ScriptExtension(
    private val executorProvider: () -> ShellExecutor?,
    private val configPath: String,
    private val homePath: String,
    private val packagesPath: String
) : VariableExtension {

    companion object {
        private const val TAG = "ScriptExtension"
    }

    override val typeName = "script"

    override fun calculate(
        params: Params,
        scope: Map<String, ExtensionOutput>,
        callback: (ExtensionResult) -> Unit
    ) {
        val args = params.stringList("args")
        if (args == null || args.isEmpty()) {
            callback(ExtensionResult.Error("missing 'args' param"))
            return
        }

        val executor = executorProvider()
        if (executor == null || !executor.isAvailable()) {
            callback(ExtensionResult.Error("shell unavailable"))
            return
        }

        val trim = (params.data["trim"] as? Boolean) ?: true
        val debug = (params.data["debug"] as? Boolean) ?: false
        val ignoreError = (params.data["ignore_error"] as? Boolean) ?: false

        val processedArgs = args.map { arg ->
            arg.replace("%HOME%", homePath)
                .replace("%CONFIG%", configPath)
                .replace("%PACKAGES%", packagesPath)
        }

        val env = InjectVariables.convertToEnvVariables(scope).toMutableMap()
        env["CONFIG"] = configPath

        if (debug) {
            Log.d(TAG, "Executing: $processedArgs")
            Log.d(TAG, "Env: $env")
        }

        executor.execute(processedArgs, env, 5000) { result ->
            if (debug) {
                Log.d(TAG, "stdout: ${result.stdout}")
                Log.d(TAG, "stderr: ${result.stderr}")
                Log.d(TAG, "exitCode: ${result.exitCode}")
            }

            if (!result.success) {
                if (!ignoreError) {
                    val errorMsg = result.stderr.ifBlank { "exit code: ${result.exitCode}" }
                    if (result.stderr == "timeout") {
                        callback(ExtensionResult.Error("timeout"))
                    } else {
                        callback(ExtensionResult.Error(errorMsg))
                    }
                    return@execute
                }
            }

            val output = if (trim) result.stdout.trim() else result.stdout
            callback(ExtensionResult.Success(ExtensionOutput.Single(output)))
        }
    }
}
