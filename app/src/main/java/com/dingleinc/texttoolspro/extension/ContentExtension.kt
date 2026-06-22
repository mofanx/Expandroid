package com.dingleinc.texttoolspro.extension

import android.content.Context
import android.net.Uri
import com.dingleinc.texttoolspro.data.Params
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ContentExtension(private val context: Context) : VariableExtension {

    override val typeName = "content"

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun calculate(
        params: Params,
        scope: Map<String, ExtensionOutput>,
        callback: (ExtensionResult) -> Unit
    ) {
        val uri = params.string("uri")
        if (uri == null) {
            callback(ExtensionResult.Error("missing 'uri' param"))
            return
        }
        val column = params.string("column")
        if (column == null) {
            callback(ExtensionResult.Error("missing 'column' param"))
            return
        }
        val selection = params.string("selection")

        this.scope.launch {
            try {
                val cursor = context.contentResolver.query(
                    Uri.parse(uri), arrayOf(column), selection, null, null
                )
                val result = cursor?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                if (result != null) {
                    callback(ExtensionResult.Success(ExtensionOutput.Single(result)))
                } else {
                    callback(ExtensionResult.Error("no result from content provider"))
                }
            } catch (e: Exception) {
                callback(ExtensionResult.Error(e.message ?: "content error"))
            }
        }
    }
}
