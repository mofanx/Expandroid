package com.dingleinc.texttoolspro.extension.shell

import android.content.Intent
import android.os.Bundle
import android.app.IntentService

class TermuxResultService : IntentService("TermuxResultService") {

    companion object {
        const val EXTRA_EXECUTION_ID = "execution_id"
    }

    override fun onHandleIntent(intent: Intent?) {
        val bundle = intent?.getBundleExtra("com.termux.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE")
            ?: return
        val execId = intent.getIntExtra(EXTRA_EXECUTION_ID, 0)
        val stdout = bundle.getString("com.termux.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT", "")
        val stderr = bundle.getString("com.termux.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDERR", "")
        val exitCode = bundle.getInt("com.termux.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE", -1)

        TermuxShellExecutor.handleResult(execId, stdout, stderr, exitCode)
    }
}
