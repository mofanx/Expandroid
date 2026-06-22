package com.dingleinc.texttoolspro.extension.shell

data class ShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val success: Boolean
)

interface ShellExecutor {
    fun isAvailable(): Boolean
    fun execute(
        command: List<String>,
        env: Map<String, String>,
        timeoutMs: Long,
        callback: (ShellResult) -> Unit
    )
}
