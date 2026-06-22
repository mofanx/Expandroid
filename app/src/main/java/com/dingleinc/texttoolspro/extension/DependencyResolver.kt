package com.dingleinc.texttoolspro.extension

import com.dingleinc.texttoolspro.data.Params
import com.dingleinc.texttoolspro.data.Var

data class DependencyNode(
    val name: String,
    val variable: Var?,
    val dependencies: Set<String>
)

object DependencyResolver {

    private val VAR_REGEX = Regex("\\{\\{\\s*((\\w+)(\\.(\\w+))?)\\s*\\}\\}")

    fun resolveEvaluationOrder(
        body: String,
        localVars: List<Var>,
        globalVars: List<Var>
    ): Result<List<Var>> {
        val nodeMap = mutableMapOf<String, DependencyNode>()

        localVars.forEachIndexed { index, v ->
            val deps = mutableSetOf<String>()
            if (v.injectVars) {
                deps.addAll(scanParamVariableNames(v.params))
            }
            v.dependsOn?.let { deps.addAll(it) }
            if (index > 0) {
                localVars[index - 1].name?.let { deps.add(it) }
            }
            val name = v.name ?: ""
            nodeMap[name] = DependencyNode(name, v, deps)
        }

        globalVars.forEach { v ->
            val deps = mutableSetOf<String>()
            if (v.injectVars) deps.addAll(scanParamVariableNames(v.params))
            v.dependsOn?.let { deps.addAll(it) }
            val name = v.name ?: ""
            nodeMap[name] = DependencyNode(name, v, deps)
        }

        val bodyDeps = mutableSetOf<String>()
        bodyDeps.addAll(localVars.mapNotNull { it.name })
        bodyDeps.addAll(scanBodyVariableNames(body))
        nodeMap["__match_body"] = DependencyNode("__match_body", null, bodyDeps)

        val evalOrder = mutableListOf<String>()
        val resolved = mutableSetOf<String>()
        val seen = mutableSetOf<String>()

        fun resolveDeps(name: String): Result<Unit> {
            if (name in resolved) return Result.success(Unit)
            if (name in seen) return Result.failure(
                Exception("Circular dependency detected: $name")
            )
            seen.add(name)
            val node = nodeMap[name]
                ?: return Result.failure(Exception("Missing dependency: $name"))
            for (dep in node.dependencies) {
                resolveDeps(dep).onFailure { return it }
            }
            seen.remove(name)
            resolved.add(name)
            if (node.variable != null) evalOrder.add(name)
            return Result.success(Unit)
        }

        resolveDeps("__match_body").onFailure {
            return Result.failure(it)
        }
        return Result.success(evalOrder.mapNotNull { nodeMap[it]?.variable })
    }

    fun scanParamVariableNames(params: Params): Set<String> {
        val names = mutableSetOf<String>()
        params.data.forEach { (_, value) ->
            scanValueVariableNames(value, names)
        }
        return names
    }

    private fun scanValueVariableNames(value: Any?, names: MutableSet<String>) {
        when (value) {
            is String -> {
                VAR_REGEX.findAll(value).forEach { match ->
                    names.add(match.groupValues[2])
                }
            }
            is List<*> -> value.forEach { scanValueVariableNames(it, names) }
            is Map<*, *> -> value.values.forEach { scanValueVariableNames(it, names) }
        }
    }

    fun scanBodyVariableNames(body: String): Set<String> {
        return VAR_REGEX.findAll(body).map { it.groupValues[2] }.toSet()
    }
}
