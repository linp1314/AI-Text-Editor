package com.aieditor.text.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ApiKeySwitcherTest {

    private val deepSeek = "https://api.deepseek.com/v1"
    private val openAi = "https://api.openai.com/v1"

    @Test
    fun switchingToProviderWithoutKeyShowsEmpty() {
        val switcher = ApiKeySwitcher(deepSeek, "sk-deepseek", lookup = { "" })

        val shown = switcher.moveTo(openAi, currentKey = "sk-deepseek")

        assertEquals("", shown)
        assertEquals("", switcher.apiKey)
    }

    @Test
    fun keyIsRememberedWhenSwitchingBack() {
        val switcher = ApiKeySwitcher(deepSeek, "sk-deepseek", lookup = { "" })

        switcher.moveTo(openAi, currentKey = "sk-deepseek")
        // 在 OpenAI 里填自己的 key
        val backToDeepSeek = switcher.moveTo(deepSeek, currentKey = "sk-openai")
        assertEquals("sk-deepseek", backToDeepSeek)

        val backToOpenAi = switcher.moveTo(openAi, currentKey = "sk-deepseek")
        assertEquals("sk-openai", backToOpenAi)
    }

    @Test
    fun previouslySavedKeyIsLoadedFromStorage() {
        val stored = mapOf(ApiKeySlots.slot(openAi) to "sk-saved-openai")
        val switcher = ApiKeySwitcher(deepSeek, "sk-deepseek", lookup = { url ->
            stored[ApiKeySlots.slot(url)].orEmpty()
        })

        assertEquals("sk-saved-openai", switcher.moveTo(openAi, currentKey = "sk-deepseek"))
    }

    @Test
    fun customAddressKeepsItsOwnKey() {
        val switcher = ApiKeySwitcher(deepSeek, "sk-deepseek", lookup = { "" })
        val custom = "https://my-gateway.example.com/v1"

        assertEquals("", switcher.moveTo(custom, currentKey = "sk-deepseek"))
        // 在自定义地址填写自己的 key 后切走，再切回来仍保留
        assertEquals("sk-deepseek", switcher.moveTo(deepSeek, currentKey = "sk-custom"))
        assertEquals("sk-custom", switcher.moveTo(custom, currentKey = "sk-deepseek"))
    }

    @Test
    fun snapshotCommitsKeysFilledForOtherProviders() {
        val switcher = ApiKeySwitcher(deepSeek, "sk-deepseek", lookup = { "" })

        switcher.moveTo(openAi, currentKey = "sk-deepseek")
        // 在 OpenAI 填了 key，又切回 DeepSeek 才点保存
        switcher.moveTo(deepSeek, currentKey = "sk-openai")
        val keys = switcher.snapshot(currentKey = "sk-deepseek")

        assertEquals("sk-deepseek", keys[ApiKeySlots.slot(deepSeek)])
        assertEquals("sk-openai", keys[ApiKeySlots.slot(openAi)])
    }

    @Test
    fun slotIgnoresCaseSpacesAndTrailingSlash() {
        assertEquals(
            ApiKeySlots.slot("https://api.deepseek.com/v1"),
            ApiKeySlots.slot("  https://API.DeepSeek.com/v1/  "),
        )
        assertNotEquals(
            ApiKeySlots.slot("https://api.deepseek.com/v1"),
            ApiKeySlots.slot("https://api.openai.com/v1"),
        )
    }
}
