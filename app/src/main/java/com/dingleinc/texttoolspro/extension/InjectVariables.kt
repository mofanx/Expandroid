package com.dingleinc.texttoolspro.extension

import com.dingleinc.texttoolspro.data.Params

object InjectVariables {

    private val VAR_REGEX = Regex("\\{\\{\\s*((\\w+)(\\.(\\w+))?)\\s*\\}\\}")

    fun injectVariablesIntoParams(
        params: Params,
        scope: Map<String, ExtensionOutput>
    ): Params {
        val result = mutableMapOf<String, Any>()
        params.data.forEach { (key, value) ->
            result[key] = injectVariablesIntoValue(value, scope)
        }
        return Params(result)
    }

    private fun injectVariablesIntoValue(value: Any, scope: Map<String, ExtensionOutput>): Any {
        return when (value) {
            is String -> renderVariablesInString(value, scope)
            is List<*> -> value.map { injectVariablesIntoValue(it ?: "", scope) }
            is Map<*, *> -> value.mapValues { injectVariablesIntoValue(it.value ?: "", scope) }
            else -> value
        }
    }

    private fun renderVariablesInString(text: String, scope: Map<String, ExtensionOutput>): String {
        return VAR_REGEX.replace(text) { match ->
            val varName = match.groupValues[2]
            val subName = match.groupValues[4].takeIf { it.isNotEmpty() }
            when (val output = scope[varName]) {
                is ExtensionOutput.Single -> output.value
                is ExtensionOutput.Multiple -> {
                    if (subName != null) {
                        output.values[subName] ?: ""
                    } else {
                        output.values.toString()
                    }
                }
                null -> match.value
            }
        }
    }

    fun convertToEnvVariables(scope: Map<String, ExtensionOutput>): Map<String, String> {
        val env = mutableMapOf<String, String>()
        scope.forEach { (key, output) ->
            when (output) {
                is ExtensionOutput.Single -> {
                    env["ESPANSO_${key.uppercase()}"] = output.value
                }
                is ExtensionOutput.Multiple -> {
                    output.values.forEach { (subKey, subValue) ->
                        env["ESPANSO_${key.uppercase()}_${subKey.uppercase()}"] = subValue
                    }
                }
            }
        }
        return env
    }
}
