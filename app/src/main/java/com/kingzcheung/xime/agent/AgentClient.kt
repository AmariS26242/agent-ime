package com.kingzcheung.xime.agent

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Cancellable SSE client kept outside the IME key-processing path. */
class AgentClient {
    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var worker: Thread? = null

    fun ask(
        question: String,
        onDelta: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        cancel()
        cancelled.set(false)
        worker = Thread {
            try {
                val conn = (URL(AgentConfig.endpoint()).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 45_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "text/event-stream")
                    setRequestProperty("Authorization", "Bearer ${AgentConfig.token()}")
                }
                connection = conn
                val body = JSONObject()
                    .put("model", AgentConfig.DEFAULT_MODEL)
                    .put("messages", listOf(JSONObject().put("role", "user").put("content", question)))
                    .toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode !in 200..299) {
                    val status = conn.responseCode
                    main.post { onError("后端错误 HTTP $status") }
                    return@Thread
                }
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                    while (!cancelled.get()) {
                        val line = reader.readLine() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                        val piece = obj.optJSONObject("choices")?.optJSONObject(0)
                            ?.optJSONObject("delta")?.optString("content").orEmpty()
                        if (piece.isNotEmpty()) main.post { if (!cancelled.get()) onDelta(piece) }
                        if (obj.optBoolean("done", false)) break
                    }
                }
                if (!cancelled.get()) main.post(onDone)
            } catch (t: Throwable) {
                if (!cancelled.get()) main.post { onError(t.message ?: "连接失败") }
            } finally {
                connection = null
            }
        }.also { it.name = "agent-stream"; it.isDaemon = true; it.start() }
    }

    fun cancel() {
        cancelled.set(true)
        runCatching { connection?.disconnect() }
        connection = null
        worker?.interrupt()
        worker = null
    }
}
