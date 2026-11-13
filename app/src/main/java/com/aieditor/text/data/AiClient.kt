package com.aieditor.text.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ChatMessage(val role: String, val content: String)

class ApiException(message: String) : Exception(message)

/**
 * 与任何 OpenAI 兼容接口（/chat/completions）通信的最小客户端。
 * 只依赖 OkHttp + org.json，不需要额外的序列化库。
 */
class AiClient(private val config: ApiConfig) {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 测试连接 / 拉模型用的短超时客户端：连不上要立刻失败，不能让人干等。 */
    private val probeHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** 归一化后的接口根地址，例如 https://api.deepseek.com/v1 。 */
    fun apiBase(): String {
        var base = config.baseUrl.trim().ifBlank { ApiConfig.DEFAULT_BASE_URL }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            // 局域网 / 本机地址（常见于 LM Studio、vLLM 等自建服务）默认走 http
            val host = base.substringBefore('/').substringBefore(':')
            val isLocal = host == "localhost" || host == "127.0.0.1" ||
                host.startsWith("192.168.") || host.startsWith("10.") ||
                Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\.").containsMatchIn(host)
            base = (if (isLocal) "http://" else "https://") + base
        }
        base = base.trimEnd('/')
        val hadEndpoint = base.endsWith("/chat/completions") || base.endsWith("/completions")
        base = base.removeSuffix("/chat/completions").removeSuffix("/completions").trimEnd('/')
        // 用户直接填了完整端点时不要自作主张补 /v1
        if (hadEndpoint) return base
        return if (Regex("/v\\d+$").containsMatchIn(base)) base else "$base/v1"
    }

    /** 根据用户填写的地址推导出真正的 /chat/completions 端点。 */
    fun endpoint(): String = apiBase() + "/chat/completions"

    /** 拉取该服务商的可用模型列表（GET /models）。 */
    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(apiBase() + "/models")
            .get()
            .header("Accept", "application/json")
            .apply {
                config.apiKey.trim().takeIf { it.isNotEmpty() }?.let {
                    header("Authorization", "Bearer $it")
                }
            }
            .build()

        probeHttp.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException(describeHttpError(response.code, response.message, body))
            }
            val arr = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull()
                ?: runCatching { JSONArray(body) }.getOrNull()
                ?: throw ApiException("返回内容不是模型列表，请手动填写模型名称")
            (0 until arr.length()).mapNotNull { i ->
                val item = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = item.optString("id").ifBlank { item.optString("name") }
                id.ifBlank { null }
            }.distinct().sorted()
        }
    }

    /**
     * 快速验证地址与密钥：优先请求 /models（不消耗生成额度、返回快），
     * 该服务没有 /models 时退回一次极短的对话。
     */
    suspend fun probe(): String = withContext(Dispatchers.IO) {
        try {
            val models = listModels()
            "连接成功，可用模型 ${models.size} 个"
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            val noModelApi = message.contains("404") || message.contains("405") ||
                message.contains("不存在") || message.contains("列表")
            if (e is ApiException && noModelApi) {
                val sb = StringBuilder()
                onceChat(listOf(ChatMessage("user", "只回复两个字母：OK")), probeHttp)
                    .collect { sb.append(it) }
                "连接成功，模型回复：${sb.toString().trim().take(40)}"
            } else {
                throw ApiException(friendlyError(e))
            }
        }
    }

    fun chat(messages: List<ChatMessage>): Flow<String> =
        if (config.stream) streamChat(messages) else onceChat(messages)

    // ---------------------------------------------------------------- 流式

    private fun streamChat(messages: List<ChatMessage>): Flow<String> = flow {
        val call = http.newCall(buildRequest(messages, stream = true))
        var emitted = false
        val raw = StringBuilder()
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw ApiException(describeHttpError(response.code, response.message, response.body?.string()))
                }
                val source = response.body?.source() ?: throw ApiException("服务器返回了空响应")
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    if (!line.startsWith("data:")) {
                        raw.append(line)
                        continue
                    }
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break
                    val delta = parseDelta(data)
                    if (delta.isNotEmpty()) {
                        emitted = true
                        emit(delta)
                    }
                }
            }
            // 有些服务端忽略 stream=true，直接返回一段 JSON，这里兜底解析
            if (!emitted && raw.isNotBlank()) {
                val text = parseContent(raw.toString())
                if (text.isNotBlank()) emit(text) else throw ApiException("响应格式无法识别：${raw.toString().take(120)}")
            }
        } finally {
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun parseDelta(data: String): String {
        val choices = runCatching { JSONObject(data).optJSONArray("choices") }.getOrNull() ?: return ""
        val first = choices.optJSONObject(0) ?: return ""
        val delta = first.optJSONObject("delta")
        if (delta != null) {
            val content = delta.optString("content", "")
            if (content.isNotEmpty()) return content
        }
        // 部分兼容实现把增量放在 message.content / text 里
        val message = first.optJSONObject("message")
        if (message != null) {
            val content = message.optString("content", "")
            if (content.isNotEmpty()) return content
        }
        return first.optString("text", "")
    }

    // ---------------------------------------------------------------- 非流式

    private fun onceChat(
        messages: List<ChatMessage>,
        client: OkHttpClient = http,
    ): Flow<String> = flow {
        val call = client.newCall(buildRequest(messages, stream = false))
        try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ApiException(describeHttpError(response.code, response.message, body))
                }
                val text = parseContent(body)
                if (text.isBlank()) throw ApiException("模型没有返回内容")
                emit(text)
            }
        } finally {
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    // ---------------------------------------------------------------- 工具

    /** 把网络异常翻译成用户看得懂的一句话。 */
    private fun friendlyError(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "连接超时：服务器没有响应，请检查接口地址与网络"
        is java.net.UnknownHostException -> "域名解析失败：请检查接口地址是否填写正确"
        is java.net.ConnectException -> "无法连接到服务器：请检查地址与端口"
        is java.net.NoRouteToHostException -> "网络不可达：请检查手机网络连接"
        is javax.net.ssl.SSLException -> "HTTPS 握手失败：请确认该地址支持 https"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun buildRequest(messages: List<ChatMessage>, stream: Boolean): Request {
        val array = JSONArray()
        messages.forEach { m ->
            array.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        val payload = JSONObject()
            .put("model", config.model.ifBlank { ApiConfig.DEFAULT_MODEL })
            .put("messages", array)
            .put("temperature", config.temperature.toDouble())
            .put("max_tokens", config.maxTokens)
            .put("stream", stream)

        val builder = Request.Builder()
            .url(endpoint())
            .post(payload.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")

        val key = config.apiKey.trim()
        if (key.isNotEmpty()) builder.header("Authorization", "Bearer $key")
        if (stream) builder.header("Accept", "text/event-stream")
        return builder.build()
    }

    private fun parseContent(body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return ""
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            json.optJSONObject("error")?.let { throw ApiException(it.optString("message", "接口返回错误")) }
            return ""
        }
        val first = choices.optJSONObject(0) ?: return ""
        first.optJSONObject("message")?.let { return it.optString("content", "") }
        first.optJSONObject("delta")?.let { return it.optString("content", "") }
        return first.optString("text", "")
    }

    private fun describeHttpError(code: Int, message: String, body: String?): String {
        val detail = body?.let { raw ->
            runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()
        }.orEmpty().ifEmpty { body?.take(200).orEmpty() }
        val hint = when (code) {
            401, 403 -> "密钥无效或没有权限"
            404 -> "接口地址或模型名称不存在"
            429 -> "请求过于频繁或额度不足"
            in 500..599 -> "服务端错误"
            else -> message
        }
        return "HTTP $code（$hint）${if (detail.isNotBlank()) "：$detail" else ""}"
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
