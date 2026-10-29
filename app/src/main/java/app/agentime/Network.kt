package app.agentime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Talks to our own proxy backend. No upstream secrets here.
 */
class AgentClient(private val backend: String, private val token: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Err(val kind: Kind, val message: String) : Result()
    }

    enum class Kind { OFFLINE, TIMEOUT, UNAUTHORIZED, RATE_LIMITED, HTTP, CANCELED, GENERIC }

    /**
     * Suspending call. On coroutine cancellation the underlying OkHttp Call is
     * explicitly cancelled, which closes the socket.
     */
    suspend fun ask(mode: String, messages: List<Pair<String, String>>): Result {
        val body = buildBody(mode, messages)
        val reqBuilder = Request.Builder()
            .url(trimSlash(backend) + "/v1/chat")
            .post(body.toRequestBody(JSON))
        if (token.isNotBlank()) reqBuilder.header("Authorization", "Bearer $token")
        val request = reqBuilder.build()

        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                val call: Call = client.newCall(request)
                cont.invokeOnCancellation {
                    try { call.cancel() } catch (_: Throwable) { }
                }
                call.enqueue(object : Callback {
                    override fun onFailure(c: Call, e: IOException) {
                        if (!cont.isActive) return
                        val result = when (e) {
                            is java.net.SocketTimeoutException ->
                                Result.Err(Kind.TIMEOUT, e.message ?: "timeout")
                            else -> {
                                if (c.isCanceled()) Result.Err(Kind.CANCELED, e.message ?: "canceled")
                                else Result.Err(Kind.OFFLINE, e.message ?: "io")
                            }
                        }
                        cont.resume(result)
                    }

                    override fun onResponse(c: Call, resp: Response) {
                        try {
                            resp.use { r ->
                                val text = r.body?.string().orEmpty()
                                val result = when (r.code) {
                                    200 -> {
                                        val answer = try {
                                            JSONObject(text).optString("answer", "")
                                        } catch (_: Exception) { "" }
                                        if (answer.isBlank()) Result.Err(Kind.GENERIC, "Empty answer")
                                        else Result.Ok(answer)
                                    }
                                    401, 403 -> Result.Err(Kind.UNAUTHORIZED, text)
                                    429 -> Result.Err(Kind.RATE_LIMITED, text)
                                    else -> Result.Err(Kind.HTTP, "HTTP ${'$'}{r.code}")
                                }
                                if (cont.isActive) cont.resume(result)
                            }
                        } catch (t: Throwable) {
                            if (cont.isActive) cont.resume(Result.Err(Kind.GENERIC, t.message ?: "error"))
                        }
                    }
                })
            }
        }
    }

    private fun buildBody(mode: String, messages: List<Pair<String, String>>): String {
        val arr = JSONArray()
        messages.forEach { (role, content) ->
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        return JSONObject()
            .put("mode", mode)
            .put("messages", arr)
            .toString()
    }

    private fun trimSlash(s: String) = s.trimEnd('/')

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
