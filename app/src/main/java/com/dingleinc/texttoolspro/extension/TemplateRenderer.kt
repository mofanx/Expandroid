package com.dingleinc.texttoolspro.extension

import com.dingleinc.texttoolspro.data.Match

interface TemplateRenderer {
    fun render(
        template: Match,
        parentScope: Map<String, ExtensionOutput>,
        visitedTriggers: Set<String>,
        callback: (ExtensionResult) -> Unit
    )
}
