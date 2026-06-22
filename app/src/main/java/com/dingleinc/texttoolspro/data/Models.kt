package com.dingleinc.texttoolspro.data

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter

data class Params(
    @get:JsonAnyGetter
    @JsonAnySetter
    var data: MutableMap<String, Any> = mutableMapOf()
) {
    constructor(og: Params) : this(og.data.toMutableMap())

    operator fun get(key: String): Any? = data[key]
    operator fun set(key: String, value: Any?) { data[key] = value }

    fun string(key: String): String? = (data[key] as? String)
    fun long(key: String): Long = (data[key] as? Long) ?: (data[key] as? Int)?.toLong() ?: 0L
    fun stringList(key: String): MutableList<String>? {
        val v = data[key] ?: return null
        if (v is MutableList<*>) return v.filterIsInstance<String>().toMutableList()
        if (v is List<*>) return v.filterIsInstance<String>().toMutableList()
        return null
    }
}

data class Var(
    var name: String? = null,
    var type: String? = null,
    var params: Params = Params(),
    var injectVars: Boolean = true,
    var dependsOn: MutableList<String>? = null
) {
    constructor(og: Var) : this(og.name, og.type, Params(og.params), og.injectVars, og.dependsOn?.toMutableList())
}

data class FormOption(
    var multiline: Boolean = false,
    var type: String? = null,
    var values: MutableList<String>? = null
)

data class Match(
    var trigger: String? = null,
    var replace: String? = null,
    var vars: MutableList<Var>? = null,
    var form: String? = null,
    var formFields: HashMap<String, FormOption>? = null,
    var word: Boolean = false,
    var triggers: MutableList<String>? = null,
    var leftWord: Boolean = false,
    var rightWord: Boolean = false,
    var propagateCase: Boolean = false,
    var uppercaseStyle: String? = null,
    var regex: String? = null,
    var label: String? = null,
    var searchTerms: MutableList<String>? = null
) {
    constructor(og: Match) : this(
        og.trigger, og.replace,
        og.vars?.map { Var(it) }?.toMutableList(),
        og.form,
        og.formFields?.let { HashMap(it) },
        og.word,
        og.triggers?.toMutableList(),
        og.leftWord,
        og.rightWord,
        og.propagateCase,
        og.uppercaseStyle,
        og.regex,
        og.label,
        og.searchTerms?.toMutableList()
    )
}

data class DictWrapper(
    var globalVars: MutableList<Var>? = null,
    var matches: MutableList<Match>? = null
)
