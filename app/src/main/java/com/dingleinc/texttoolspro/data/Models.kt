package com.dingleinc.texttoolspro.data

import kotlinx.serialization.Serializable

@Serializable
data class Params(
    var echo: String? = null,
    var format: String? = null,
    var offset: Long = 0,
    var cmd: String? = null,
    var layout: String? = null,
    var choices: MutableList<String>? = null
) {
    constructor(og: Params) : this(
        og.echo, og.format, og.offset, og.cmd, og.layout,
        og.choices?.toMutableList()
    )
}

@Serializable
data class Var(
    var name: String? = null,
    var type: String? = null,
    var params: Params = Params()
) {
    constructor(og: Var) : this(og.name, og.type, Params(og.params))
}

@Serializable
data class FormOption(
    var multiline: Boolean = false,
    var type: String? = null,
    var values: MutableList<String>? = null
)

@Serializable
data class Match(
    var trigger: String? = null,
    var replace: String? = null,
    var vars: MutableList<Var>? = null,
    var form: String? = null,
    var formFields: HashMap<String, FormOption>? = null,
    var word: Boolean = false
) {
    constructor(og: Match) : this(
        og.trigger, og.replace,
        og.vars?.map { Var(it) }?.toMutableList(),
        og.form,
        og.formFields?.let { HashMap(it) },
        og.word
    )
}

@Serializable
data class DictWrapper(
    @kotlinx.serialization.SerialName("global_vars")
    var globalVars: MutableList<Var>? = null,
    var matches: MutableList<Match>? = null
)
