package com.dingleinc.texttoolspro.extension

import com.dingleinc.texttoolspro.data.Params
import app.cash.quickjs.QuickJs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class JavaScriptExtension : VariableExtension {

    override val typeName = "javascript"

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun calculate(
        params: Params,
        scope: Map<String, ExtensionOutput>,
        callback: (ExtensionResult) -> Unit
    ) {
        val code = params.string("code")
        if (code == null) {
            callback(ExtensionResult.Error("missing 'code' param"))
            return
        }

        this.scope.launch {
            try {
                val result = evaluateJavaScript(code, scope)
                callback(ExtensionResult.Success(ExtensionOutput.Single(result)))
            } catch (e: Exception) {
                callback(ExtensionResult.Error(e.message ?: "JavaScript error"))
            }
        }
    }

    private fun evaluateJavaScript(
        code: String,
        scope: Map<String, ExtensionOutput>
    ): String {
        val quickJs = QuickJs.create()
        try {
            scope.forEach { (name, output) ->
                val value = when (output) {
                    is ExtensionOutput.Single -> output.value
                    is ExtensionOutput.Multiple -> output.values.toString()
                }
                quickJs.evaluate("var $name = ${quoteString(value)};")
            }
            val result = quickJs.evaluate(code)
            return result?.toString()?.trim() ?: ""
        } finally {
            quickJs.close()
        }
    }

    private fun quoteString(s: String): String {
        val escaped = s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "'$escaped'"
    }
}
