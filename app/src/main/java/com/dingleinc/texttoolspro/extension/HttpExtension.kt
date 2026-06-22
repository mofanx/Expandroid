package com.dingleinc.texttoolspro.extension

import com.dingleinc.texttoolspro.data.Params
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HttpExtension : VariableExtension {

    override val typeName = "http"

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun calculate(
        params: Params,
        scope: Map<String, ExtensionOutput>,
        callback: (ExtensionResult) -> Unit
    ) {
        val url = params.string("url")
        if (url == null) {
            callback(ExtensionResult.Error("missing 'url' param"))
            return
        }
        val method = params.string("method") ?: "GET"
        val timeout = params.long("timeout").takeIf { it > 0 }?.toInt() ?: 5000
        val jsonPath = params.string("json_path")

        this.scope.launch {
            try {
                val client = OkHttpClient.Builder()
                    .callTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
                    .build()
                val request = when (method.uppercase()) {
                    "GET" -> Request.Builder().url(url).get().build()
                    "POST", "PUT", "PATCH" -> {
                        val body = params.string("body") ?: ""
                        val mediaType = (params.string("content_type") ?: "application/json").toMediaTypeOrNull()
                        val reqBody = body.toRequestBody(mediaType)
                        Request.Builder().url(url).method(method, reqBody).build()
                    }
                    "DELETE" -> Request.Builder().url(url).delete().build()
                    "HEAD" -> Request.Builder().url(url).head().build()
                    else -> Request.Builder().url(url).method(method, null).build()
                }
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                val result = if (jsonPath != null) {
                    extractJsonPath(body, jsonPath)
                } else {
                    body.trim()
                }
                callback(ExtensionResult.Success(ExtensionOutput.Single(result)))
            } catch (e: Exception) {
                callback(ExtensionResult.Error(e.message ?: "HTTP error"))
            }
        }
    }

    private fun extractJsonPath(json: String, path: String): String {
        return try {
            val obj = JSONObject(json)
            val parts = path.removePrefix("$.").split(".")
            var current: Any = obj
            for (part in parts) {
                current = when (current) {
                    is JSONObject -> current.get(part)
                    else -> return current.toString()
                }
            }
            current.toString()
        } catch (e: Exception) {
            "[http error: invalid json path: $path]"
        }
    }
}
