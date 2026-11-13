package com.aieditor.text.data

import org.json.JSONObject

/** 一次备份的内容：软件配置 + 各服务商密钥 + 所有打开的文档。 */
data class BackupPayload(
    val createdAt: Long,
    val versionName: String,
    val device: String,
    val config: ApiConfig,
    /** 当前文档正文；新备份同时写进 [documents]，保留它只为兼容旧版恢复。 */
    val draft: String,
    val chunked: Boolean,
    val chunkLimit: Int,
    /** 各接口地址已保存的 Key（槽位 → Key），换机后不用逐个重填。 */
    val apiKeys: Map<String, String> = emptyMap(),
    /** 所有打开的文档（含标题与来源路径），老备份里没有这一项。 */
    val documents: List<Doc> = emptyList(),
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
        if (documents.isNotEmpty()) {
            put("documents", DocCodec.encodeArray(documents))
        }
    }.toString(2)

    companion object {
        const val SCHEMA = 3

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
            val docs = root.optJSONArray("documents")
                ?.let { DocCodec.decodeArray(it) }
                .orEmpty()
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
                documents = docs,
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
    /** 备份里的文档列表；老备份为空，只有 [draft] 这一篇。 */
    val documents: List<Doc> = emptyList(),
    /** true 表示文档列表整体替换备份内容（覆盖恢复）。 */
    val overwriteDocs: Boolean = false,
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
        documents = backup.documents,
        overwriteDocs = true,
    )

    /**
     * 增量恢复：只填补空缺。
     * 地址、模型、密钥必须成组补全——只换地址不换密钥会让请求带着上一个服务商的 Key 去访问新地址。
     * 是否「空缺」以本地有没有密钥为准：没有密钥就视为尚未配置，整组采用备份。
     */
    fun merge(
        local: ApiConfig,
        localDraft: String,
        backup: BackupPayload,
        localDocs: List<Doc> = emptyList(),
    ): MergeResult {
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

        val docs = mergeDocs(localDocs, localDraft, backup)
        val draft = docs.localDraft.ifBlank { backup.draft }

        return MergeResult(
            config = config,
            draft = draft,
            chunked = backup.chunked,
            chunkLimit = backup.chunkLimit,
            notes = notes + docs.note,
            apiKeys = backup.apiKeys,
            documents = docs.added,
            overwriteDocs = docs.replace,
        )
    }

    private data class DocMerge(
        val added: List<Doc>,
        val replace: Boolean,
        val note: String,
        val localDraft: String,
    )

    /**
     * 文档合并规则：
     * - 本地只有一篇空白文档 → 直接用备份的文档替换；
     * - 本地有内容 → 只追加备份里本地没有的（按正文比对），不覆盖本地。
     */
    private fun mergeDocs(local: List<Doc>, localDraft: String, backup: BackupPayload): DocMerge {
        val incoming = backup.documents
        if (incoming.isEmpty()) {
            return if (localDraft.isBlank() && backup.draft.isNotBlank()) {
                DocMerge(emptyList(), false, "文档", backup.draft)
            } else {
                if (localDraft.isNotBlank() && backup.draft.isNotBlank()) {
                    DocMerge(emptyList(), false, "本地文档非空，已保留本地文档", localDraft)
                } else {
                    DocMerge(emptyList(), false, "", localDraft)
                }
            }
        }

        val blankLocal = local.isEmpty() || local.all { it.text.isBlank() }
        if (blankLocal) {
            return DocMerge(incoming, true, "文档（${incoming.size} 篇）", localDraft)
        }

        val existing = local.map { it.text.trim() }.toHashSet()
        val added = incoming.filter { it.text.isNotBlank() && it.text.trim() !in existing }
        return if (added.isEmpty()) {
            DocMerge(emptyList(), false, "本地文档非空，已保留本地文档", localDraft)
        } else {
            DocMerge(added, false, "文档（新增 ${added.size} 篇）", localDraft)
        }
    }
}
