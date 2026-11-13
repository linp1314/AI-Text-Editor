package com.aieditor.text.data

import org.json.JSONObject

/** 一次备份的内容：软件配置 + 各服务商密钥 + 当前文档。 */
data class BackupPayload(
    val createdAt: Long,
    val versionName: String,
    val device: String,
    val config: ApiConfig,
    val draft: String,
    val chunked: Boolean,
    val chunkLimit: Int,
    /** 各接口地址已保存的 Key（槽位 → Key），换机后不用逐个重填。 */
    val apiKeys: Map<String, String> = emptyMap(),
) {
    fun toJson(): String = JSONObject().apply {
        put("app", "AI文本编辑")
        put("schema", SCHEMA)
        put("createdAt", createdAt)
        put("versionName", versionName)
        put("device", device)
        put("draft", draft)
        put("chunked", chunked)
        put("chunkLimit", chunkLimit)
        put("config", JSONObject().apply {
            put("baseUrl", config.baseUrl)
            put("apiKey", config.apiKey)
            put("model", config.model)
            put("temperature", config.temperature.toDouble())
            put("maxTokens", config.maxTokens)
            put("systemPrompt", config.systemPrompt)
            put("stream", config.stream)
        })
        if (apiKeys.isNotEmpty()) {
            put("apiKeys", JSONObject().apply { apiKeys.forEach { (k, v) -> put(k, v) } })
        }
    }.toString(2)

    companion object {
        const val SCHEMA = 2

        fun fromJson(text: String): BackupPayload {
            val root = JSONObject(text)
            val c = root.optJSONObject("config") ?: JSONObject()
            val config = ApiConfig(
                baseUrl = c.optString("baseUrl", ApiConfig.DEFAULT_BASE_URL)
                    .ifBlank { ApiConfig.DEFAULT_BASE_URL },
                apiKey = c.optString("apiKey", ""),
                model = c.optString("model", ApiConfig.DEFAULT_MODEL)
                    .ifBlank { ApiConfig.DEFAULT_MODEL },
                temperature = c.optDouble("temperature", 0.6).toFloat().coerceIn(0f, 2f),
                maxTokens = c.optInt("maxTokens", 4096).coerceIn(64, 32768),
                systemPrompt = c.optString("systemPrompt", ApiConfig.DEFAULT_SYSTEM_PROMPT)
                    .ifBlank { ApiConfig.DEFAULT_SYSTEM_PROMPT },
                stream = c.optBoolean("stream", true),
            )
            val keys = mutableMapOf<String, String>()
            root.optJSONObject("apiKeys")?.let { obj ->
                obj.keys().forEach { name ->
                    val value = obj.optString(name, "")
                    if (value.isNotBlank()) keys[name] = value
                }
            }
            return BackupPayload(
                createdAt = root.optLong("createdAt", 0L),
                versionName = root.optString("versionName", ""),
                device = root.optString("device", ""),
                config = config,
                draft = root.optString("draft", ""),
                chunked = root.optBoolean("chunked", true),
                chunkLimit = root.optInt("chunkLimit", TextChunker.DEFAULT_LIMIT)
                    .coerceIn(300, 20000),
                apiKeys = keys,
            )
        }
    }
}

/** 恢复方式。 */
enum class RestoreMode {
    /** 覆盖：完全以备份为准。 */
    OVERWRITE,

    /** 增量：只补本地空缺，不覆盖本地已有内容。 */
    MERGE,
}

data class MergeResult(
    val config: ApiConfig,
    val draft: String,
    val chunked: Boolean,
    val chunkLimit: Int,
    /** 逐项说明做了什么，用于恢复后的提示。 */
    val notes: List<String>,
    /** 备份里各服务商的 Key，恢复时一并写回。 */
    val apiKeys: Map<String, String> = emptyMap(),
)

object BackupMerge {

    /** 覆盖恢复：全部采用备份内容。 */
    fun overwrite(backup: BackupPayload): MergeResult = MergeResult(
        config = backup.config,
        draft = backup.draft,
        chunked = backup.chunked,
        chunkLimit = backup.chunkLimit,
        notes = listOf("配置与文档已用备份覆盖"),
        apiKeys = backup.apiKeys,
    )

    /**
     * 增量恢复：只填补空缺。
     * 地址、模型、密钥必须成组补全——只换地址不换密钥会让请求带着上一个服务商的 Key 去访问新地址。
     * 是否「空缺」以本地有没有密钥为准：没有密钥就视为尚未配置，整组采用备份。
     */
    fun merge(local: ApiConfig, localDraft: String, backup: BackupPayload): MergeResult {
        val notes = mutableListOf<String>()
        val b = backup.config

        val config = if (local.apiKey.isBlank()) {
            if (b.apiKey.isNotBlank()) notes.add("API Key")
            if (b.baseUrl.trim().trimEnd('/') != local.baseUrl.trim().trimEnd('/')) {
                notes.add("接口地址")
            }
            if (b.model != local.model) notes.add("模型")
            local.copy(
                baseUrl = b.baseUrl,
                apiKey = b.apiKey,
                model = b.model,
                temperature = b.temperature,
                maxTokens = b.maxTokens,
                systemPrompt = b.systemPrompt,
            )
        } else {
            // 本地已配置好，保持原样，不冲掉现有设置
            local
        }

        val draft = if (localDraft.isBlank()) backup.draft else localDraft
        if (localDraft.isNotBlank() && backup.draft.isNotBlank()) {
            notes.add("本地文档非空，已保留本地文档")
        } else if (localDraft.isBlank() && backup.draft.isNotBlank()) {
            notes.add("文档")
        }

        return MergeResult(
            config = config,
            draft = draft,
            chunked = backup.chunked,
            chunkLimit = backup.chunkLimit,
            notes = notes,
            apiKeys = backup.apiKeys,
        )
    }
}
