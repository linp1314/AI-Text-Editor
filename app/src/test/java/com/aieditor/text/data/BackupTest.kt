package com.aieditor.text.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {

    @Test
    fun `json round trip keeps config and draft`() {
        val payload = BackupPayload(
            createdAt = 1_700_000_000_000L,
            versionName = "1.5.0",
            device = "Google Pixel 8",
            config = ApiConfig(
                baseUrl = "https://api.example.com/v1",
                apiKey = "sk-test",
                model = "my-model",
                temperature = 0.3f,
                maxTokens = 8192,
                systemPrompt = "自定义提示词",
                stream = false,
            ),
            draft = "第一段\n\n第二段",
            chunked = false,
            chunkLimit = 2500,
        )

        val restored = BackupPayload.fromJson(payload.toJson())

        assertEquals(payload.draft, restored.draft)
        assertEquals("sk-test", restored.config.apiKey)
        assertEquals("my-model", restored.config.model)
        assertEquals(0.3f, restored.config.temperature, 0.001f)
        assertEquals(8192, restored.config.maxTokens)
        assertEquals("自定义提示词", restored.config.systemPrompt)
        assertFalse(restored.config.stream)
        assertFalse(restored.chunked)
        assertEquals(2500, restored.chunkLimit)
        assertEquals(1_700_000_000_000L, restored.createdAt)
    }

    @Test
    fun `json with missing fields falls back to defaults`() {
        val restored = BackupPayload.fromJson("""{"draft":"只有文档","createdAt":1}""")

        assertEquals("只有文档", restored.draft)
        assertEquals(ApiConfig.DEFAULT_BASE_URL, restored.config.baseUrl)
        assertEquals(ApiConfig.DEFAULT_MODEL, restored.config.model)
        assertTrue(restored.chunked)
    }

    @Test
    fun `merge fills unconfigured device as a group`() {
        val backup = BackupPayload(
            createdAt = 0,
            versionName = "1.5.0",
            device = "other",
            config = ApiConfig(
                baseUrl = "https://backup.example.com/v1",
                apiKey = "sk-backup",
                model = "backup-model",
                systemPrompt = "备份提示词",
                temperature = 0.9f,
                maxTokens = 2048,
            ),
            draft = "备份中的文档",
            chunked = false,
            chunkLimit = 800,
        )
        // 本机没填密钥，视为尚未配置
        val local = ApiConfig(
            baseUrl = ApiConfig.DEFAULT_BASE_URL,
            apiKey = "",
            model = "my-model",
        )

        val result = BackupMerge.merge(local, "", backup)

        // 地址、模型、密钥成组采用备份，避免地址与密钥错配
        assertEquals("https://backup.example.com/v1", result.config.baseUrl)
        assertEquals("sk-backup", result.config.apiKey)
        assertEquals("backup-model", result.config.model)
        assertEquals("备份提示词", result.config.systemPrompt)
        assertEquals(0.9f, result.config.temperature, 0.001f)
        assertEquals("备份中的文档", result.draft)
        assertTrue(result.notes.contains("接口地址"))
        assertTrue(result.notes.contains("API Key"))
        assertTrue(result.notes.contains("模型"))
    }

    @Test
    fun `merge keeps local provider when key already filled`() {
        val backup = BackupPayload(
            createdAt = 0,
            versionName = "1.5.0",
            device = "other",
            config = ApiConfig(
                baseUrl = "https://api.openai.com/v1",
                apiKey = "sk-openai",
                model = "gpt-4o-mini",
            ),
            draft = "",
            chunked = true,
            chunkLimit = 1200,
        )
        val local = ApiConfig(
            baseUrl = "https://api.deepseek.com/v1",
            apiKey = "sk-deepseek",
            model = "deepseek-chat",
        )

        val result = BackupMerge.merge(local, "", backup)

        // 不能出现「OpenAI 地址 + DeepSeek 密钥」这种错配
        assertEquals("https://api.deepseek.com/v1", result.config.baseUrl)
        assertEquals("sk-deepseek", result.config.apiKey)
        assertEquals("deepseek-chat", result.config.model)
    }

    @Test
    fun `api keys survive json round trip`() {
        val keys = mapOf(
            ApiKeySlots.slot("https://api.deepseek.com/v1") to "sk-deepseek",
            ApiKeySlots.slot("https://api.openai.com/v1") to "sk-openai",
        )
        val payload = BackupPayload(
            createdAt = 0,
            versionName = "1.5.0",
            device = "d",
            config = ApiConfig(),
            draft = "",
            chunked = true,
            chunkLimit = 1000,
            apiKeys = keys,
        )

        val restored = BackupPayload.fromJson(payload.toJson())

        assertEquals(keys, restored.apiKeys)
    }

    @Test
    fun `merge never overwrites a non-empty local draft`() {
        val backup = BackupPayload(
            createdAt = 0,
            versionName = "1.5.0",
            device = "other",
            config = ApiConfig(apiKey = "sk-backup"),
            draft = "备份中的文档",
            chunked = true,
            chunkLimit = 1500,
        )

        val result = BackupMerge.merge(ApiConfig(apiKey = "sk-local"), "本机文档", backup)

        assertEquals("本机文档", result.draft)
        assertEquals("sk-local", result.config.apiKey)
        assertTrue(result.notes.any { it.startsWith("本地文档") })
    }

    @Test
    fun `overwrite takes everything from backup`() {
        val backup = BackupPayload(
            createdAt = 0,
            versionName = "1.5.0",
            device = "other",
            config = ApiConfig(baseUrl = "https://b/v1", apiKey = "sk-b"),
            draft = "备份文档",
            chunked = false,
            chunkLimit = 4000,
        )

        val result = BackupMerge.overwrite(backup)

        assertEquals("https://b/v1", result.config.baseUrl)
        assertEquals("sk-b", result.config.apiKey)
        assertEquals("备份文档", result.draft)
        assertEquals(4000, result.chunkLimit)
    }
}
