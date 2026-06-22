package com.dingleinc.texttoolspro.extension.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class ShizukuShellExecutor(private val context: Context) : ShellExecutor {

    companion object {
        private const val TAG = "ShizukuShell"
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    @Volatile private var shellService: IShellService? = null
    @Volatile private var bound = false

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            "com.dingleinc.texttoolspro",
            "com.dingleinc.texttoolspro.extension.shell.ShellServiceImpl"
        )
    )
        .processNameSuffix("shell_service")
        .debuggable(false)
        .version(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                shellService = IShellService.Stub.asInterface(binder)
                bound = true
                Log.d(TAG, "Shizuku shell service connected")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            bound = false
            Log.d(TAG, "Shizuku shell service disconnected")
        }
    }

    override fun isAvailable(): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            if (!bound) {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
                Thread.sleep(500)
            }
            bound && shellService != null
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku not available: ${e.message}")
            false
        }
    }

    override fun execute(
        command: List<String>,
        env: Map<String, String>,
        timeoutMs: Long,
        callback: (ShellResult) -> Unit
    ) {
        scope.launch {
            try {
                val service = shellService
                if (service == null) {
                    callback(ShellResult("", "service not bound", -1, false))
                    return@launch
                }

                val envKeys = env.keys.toTypedArray()
                val envValues = env.values.toTypedArray()
                val cmdArray = command.toTypedArray()

                val result = service.executeCommand(cmdArray, envKeys, envValues, timeoutMs)

                if (result.startsWith("[error:")) {
                    if (result.contains("timeout")) {
                        callback(ShellResult("", "timeout", -1, false))
                    } else {
                        callback(ShellResult("", result, -1, false))
                    }
                } else {
                    callback(ShellResult(result, "", 0, true))
                }
            } catch (e: Exception) {
                callback(ShellResult("", e.message ?: "shizuku error", -1, false))
            }
        }
    }

    fun destroy() {
        try {
            if (bound) {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            }
        } catch (e: Exception) {
            // ignore
        }
        shellService = null
        bound = false
    }
}
