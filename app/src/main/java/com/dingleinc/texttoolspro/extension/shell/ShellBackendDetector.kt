package com.dingleinc.texttoolspro.extension.shell

import android.content.Context
import android.content.pm.PackageManager

enum class ShellBackendType {
    NONE, ROOT, SHIZUKU, TERMUX
}

data class BackendStatus(
    val type: ShellBackendType,
    val available: Boolean,
    val message: String
)

object ShellBackendDetector {

    fun detectBestBackend(context: Context): ShellExecutor? {
        val rootExecutor = RootShellExecutor()
        if (rootExecutor.isAvailable()) return rootExecutor

        try {
            val shizukuExecutor = ShizukuShellExecutor(context)
            if (shizukuExecutor.isAvailable()) return shizukuExecutor
        } catch (e: Exception) {
            // Shizuku not available
        }

        val termuxExecutor = TermuxShellExecutor(context)
        if (termuxExecutor.isAvailable()) return termuxExecutor

        return null
    }

    fun getAllBackendStatuses(context: Context): List<BackendStatus> {
        val statuses = mutableListOf<BackendStatus>()

        val rootExecutor = RootShellExecutor()
        statuses.add(
            BackendStatus(
                ShellBackendType.ROOT,
                rootExecutor.isAvailable(),
                if (rootExecutor.isAvailable()) "available" else "device not rooted"
            )
        )

        var shizukuAvailable = false
        try {
            val shizukuExecutor = ShizukuShellExecutor(context)
            shizukuAvailable = shizukuExecutor.isAvailable()
        } catch (e: Exception) {
            // not available
        }
        statuses.add(
            BackendStatus(
                ShellBackendType.SHIZUKU,
                shizukuAvailable,
                if (shizukuAvailable) "connected" else "not connected"
            )
        )

        val termuxExecutor = TermuxShellExecutor(context)
        val termuxInstalled = isTermuxInstalled(context)
        val termuxPermission = hasTermuxPermission(context)
        statuses.add(
            BackendStatus(
                ShellBackendType.TERMUX,
                termuxExecutor.isAvailable(),
                when {
                    !termuxInstalled -> "not installed"
                    !termuxPermission -> "installed, not authorized"
                    else -> "available"
                }
            )
        )

        return statuses
    }

    private fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.termux", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun hasTermuxPermission(context: Context): Boolean {
        return context.checkSelfPermission("com.termux.permission.RUN_COMMAND") ==
            PackageManager.PERMISSION_GRANTED
    }
}
