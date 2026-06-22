package com.dingleinc.texttoolspro.extension

import com.dingleinc.texttoolspro.data.Params

sealed class ExtensionOutput {
    data class Single(val value: String) : ExtensionOutput()
    data class Multiple(val values: Map<String, String>) : ExtensionOutput()
}

sealed class ExtensionResult {
    data class Success(val output: ExtensionOutput) : ExtensionResult()
    object Aborted : ExtensionResult()
    data class Error(val message: String) : ExtensionResult()
}

interface VariableExtension {
    val typeName: String

    fun calculate(
        params: Params,
        scope: Map<String, ExtensionOutput>,
        callback: (ExtensionResult) -> Unit
    )
}

class ExtensionRegistry {
    private val extensions = mutableMapOf<String, VariableExtension>()

    fun register(ext: VariableExtension) {
        extensions[ext.typeName] = ext
    }

    fun get(typeName: String): VariableExtension? = extensions[typeName]

    fun has(typeName: String): Boolean = extensions.containsKey(typeName)
}
