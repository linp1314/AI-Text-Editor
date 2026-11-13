package com.aieditor.text.data

import android.content.Context
import android.content.SharedPreferences

data class ApiConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val temperature: Float = 0.6f,
    val maxTokens: Int = 4096,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val stream: Boolean = true,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"
        const val DEFAULT_SYSTEM_PROMPT =
            "你是一名专业的中文文本编辑与写作助手，负责按照用户要求修改、润色、重写、翻译、缩写或校对文本。\n\n" +
                "要求：\n" +
                "1. 只输出修改后的最终文本，不要输出任何解释、前言、结语，也不要出现“以下是修改后的文本”之类的说明。\n" +
                "2. 完整保留原文的结构与排版，包括 Markdown 标记、代码块、缩进、编号、空行。\n" +
                "3. 不擅自增删与任务无关的内容；原文若含代码，不要改动代码逻辑。\n" +
                "4. 若任务要求翻译，只输出译文，不要保留原文。"
    }
}

object Presets {
    data class Preset(
        val name: String,
        val baseUrl: String,
        val model: String,
        /** 自定义项：不套用任何地址，点击后清空输入框由用户自己填写。 */
        val custom: Boolean = false,
    )

    val all = listOf(
        Preset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
        Preset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        Preset("硅基流动", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-72B-Instruct"),
        Preset("月之暗面", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
        Preset("自定义", "", "", custom = true),
    )

    /** 当前地址命中的预设，没有命中则视为自定义。 */
    fun match(baseUrl: String): Preset? {
        val target = baseUrl.trim().trimEnd('/')
        if (target.isEmpty()) return null
        return all.firstOrNull {
            !it.custom && it.baseUrl.trimEnd('/').equals(target, ignoreCase = true)
        }
    }
}

class AppPrefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("ai_text_editor", Context.MODE_PRIVATE)

    fun loadConfig(): ApiConfig {
        val baseUrl = sp.getString(KEY_BASE_URL, ApiConfig.DEFAULT_BASE_URL)
            ?: ApiConfig.DEFAULT_BASE_URL
        return ApiConfig(
            baseUrl = baseUrl,
            apiKey = apiKeyFor(baseUrl),
            model = sp.getString(KEY_MODEL, ApiConfig.DEFAULT_MODEL) ?: ApiConfig.DEFAULT_MODEL,
            temperature = sp.getFloat(KEY_TEMPERATURE, 0.6f),
            maxTokens = sp.getInt(KEY_MAX_TOKENS, 4096),
            systemPrompt = sp.getString(KEY_SYSTEM, ApiConfig.DEFAULT_SYSTEM_PROMPT)
                ?: ApiConfig.DEFAULT_SYSTEM_PROMPT,
            stream = sp.getBoolean(KEY_STREAM, true),
        )
    }

    /** 某接口地址专用的 Key，没填过就是空串，不会沿用其他服务商的 Key。 */
    fun apiKeyFor(baseUrl: String): String {
        migrateLegacyKey()
        return sp.getString(ApiKeySlots.slot(baseUrl), "") ?: ""
    }

    /** 保存设置页里各服务商的 Key：空值表示用户清空，删掉该地址的记录。 */
    fun saveApiKeys(keys: Map<String, String>) {
        if (keys.isEmpty()) return
        val editor = sp.edit()
        keys.forEach { (slot, key) ->
            if (key.isBlank()) editor.remove(slot) else editor.putString(slot, key)
        }
        editor.apply()
    }

    /** 所有服务商已保存的 Key（槽位 → Key），用于备份。 */
    fun allApiKeys(): Map<String, String> = sp.all.entries
        .filter { it.key.startsWith(ApiKeySlots.PREFIX) && it.value is String }
        .associate { it.key to (it.value as String) }
        .filterValues { it.isNotBlank() }

    /**
     * 写回备份里的 Key。
     * @param overwrite true 时连已有槽位一起替换（覆盖恢复）；false 只补本机没有的（增量恢复）。
     */
    fun restoreApiKeys(keys: Map<String, String>, overwrite: Boolean) {
        if (keys.isEmpty()) return
        val editor = sp.edit()
        if (overwrite) {
            sp.all.keys
                .filter { it.startsWith(ApiKeySlots.PREFIX) && it !in keys }
                .forEach { editor.remove(it) }
        }
        keys.forEach { (slot, key) ->
            // 增量模式下空白槽位也算「没有」，免得刚保存的空配置挡住备份里的 Key
            if (key.isNotBlank() && (overwrite || sp.getString(slot, "").isNullOrBlank())) {
                editor.putString(slot, key)
            }
        }
        editor.apply()
    }

    fun saveConfig(config: ApiConfig) {
        sp.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            // 仍写一份全局值，兼容旧版恢复逻辑与降级
            .putString(KEY_API_KEY, config.apiKey)
            .putString(ApiKeySlots.slot(config.baseUrl), config.apiKey)
            .putString(KEY_MODEL, config.model)
            .putFloat(KEY_TEMPERATURE, config.temperature)
            .putInt(KEY_MAX_TOKENS, config.maxTokens)
            .putString(KEY_SYSTEM, config.systemPrompt)
            .putBoolean(KEY_STREAM, config.stream)
            .apply()
    }

    /** 老版本只有一个全局 Key，迁移到当时正在使用的地址名下，其他服务商保持空白。 */
    private fun migrateLegacyKey() {
        if (sp.getBoolean(KEY_KEYS_MIGRATED, false)) return
        val legacy = sp.getString(KEY_API_KEY, "").orEmpty()
        val editor = sp.edit().putBoolean(KEY_KEYS_MIGRATED, true)
        if (legacy.isNotBlank()) {
            val current = sp.getString(KEY_BASE_URL, ApiConfig.DEFAULT_BASE_URL)
                ?: ApiConfig.DEFAULT_BASE_URL
            editor.putString(ApiKeySlots.slot(current), legacy)
        }
        editor.apply()
    }

    var draft: String
        get() = sp.getString(KEY_DRAFT, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_DRAFT, value).apply()
        }

    /** 长文分段开关，默认开启（短文本会自动按单段处理）。 */
    var chunked: Boolean
        get() = sp.getBoolean(KEY_CHUNKED, true)
        set(value) {
            sp.edit().putBoolean(KEY_CHUNKED, value).apply()
        }

    /** 每段字数上限。 */
    var chunkLimit: Int
        get() = sp.getInt(KEY_CHUNK_LIMIT, TextChunker.DEFAULT_LIMIT)
        set(value) {
            sp.edit().putInt(KEY_CHUNK_LIMIT, value).apply()
        }

    // ------------------------------------------------------------ 备份

    fun loadBackupSettings(): BackupSettings = BackupSettings(
        serverUrl = sp.getString(KEY_BACKUP_URL, "") ?: "",
        username = sp.getString(KEY_BACKUP_USER, "") ?: "",
        password = sp.getString(KEY_BACKUP_PASS, "") ?: "",
        directory = sp.getString(KEY_BACKUP_DIR, BackupSettings.DEFAULT_DIR)
            ?: BackupSettings.DEFAULT_DIR,
        autoBackup = sp.getBoolean(KEY_BACKUP_AUTO, false),
        intervalHours = sp.getInt(KEY_BACKUP_INTERVAL, 24),
        lastBackupAt = sp.getLong(KEY_BACKUP_AT, 0L),
        lastStatus = sp.getString(KEY_BACKUP_STATUS, "") ?: "",
    )

    fun saveBackupSettings(settings: BackupSettings) {
        sp.edit()
            .putString(KEY_BACKUP_URL, settings.serverUrl)
            .putString(KEY_BACKUP_USER, settings.username)
            .putString(KEY_BACKUP_PASS, settings.password)
            .putString(KEY_BACKUP_DIR, settings.directory)
            .putBoolean(KEY_BACKUP_AUTO, settings.autoBackup)
            .putInt(KEY_BACKUP_INTERVAL, settings.intervalHours)
            .putLong(KEY_BACKUP_AT, settings.lastBackupAt)
            .putString(KEY_BACKUP_STATUS, settings.lastStatus)
            .apply()
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_KEYS_MIGRATED = "api_keys_migrated"
        const val KEY_MODEL = "model"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_SYSTEM = "system_prompt"
        const val KEY_STREAM = "stream"
        const val KEY_DRAFT = "draft"
        const val KEY_CHUNKED = "chunked"
        const val KEY_CHUNK_LIMIT = "chunk_limit"
        const val KEY_BACKUP_URL = "backup_url"
        const val KEY_BACKUP_USER = "backup_user"
        const val KEY_BACKUP_PASS = "backup_pass"
        const val KEY_BACKUP_DIR = "backup_dir"
        const val KEY_BACKUP_AUTO = "backup_auto"
        const val KEY_BACKUP_INTERVAL = "backup_interval"
        const val KEY_BACKUP_AT = "backup_at"
        const val KEY_BACKUP_STATUS = "backup_status"
    }
}
