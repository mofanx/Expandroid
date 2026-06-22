package com.dingleinc.texttoolspro.extension

import com.dingleinc.texttoolspro.data.Match
import com.dingleinc.texttoolspro.data.Params
import com.dingleinc.texttoolspro.data.Var

class MatchExtension(
    private val dict: Map<String, Match>,
    private val globalVars: List<Var>,
    private val renderer: TemplateRenderer
) : VariableExtension {

    override val typeName = "match"

    override fun calculate(
        params: Params,
        scope: Map<String, ExtensionOutput>,
        callback: (ExtensionResult) -> Unit
    ) {
        val trigger = params.string("trigger")
        if (trigger == null) {
            callback(ExtensionResult.Error("missing 'trigger' param"))
            return
        }
        val referencedMatch = dict[trigger]
        if (referencedMatch == null) {
            callback(ExtensionResult.Error("match not found: $trigger"))
            return
        }

        renderer.render(referencedMatch, scope, emptySet()) { result ->
            when (result) {
                is ExtensionResult.Success -> callback(result)
                is ExtensionResult.Aborted -> callback(result)
                is ExtensionResult.Error -> callback(result)
            }
        }
    }
}
