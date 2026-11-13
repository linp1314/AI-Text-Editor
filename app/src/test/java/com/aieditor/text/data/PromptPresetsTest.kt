package com.aieditor.text.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptPresetsTest {

    @Test
    fun `预设名称不重复且都有内容`() {
        val names = PromptPresets.all.map { it.name }
        assertEquals(names.size, names.toSet().size)
        PromptPresets.all.forEach { preset ->
            assertTrue(preset.summary.isNotBlank())
            assertTrue(preset.prompt.length > 40)
        }
    }

    @Test
    fun `默认提示词命中默认编辑助手`() {
        val matched = PromptPresets.match(ApiConfig.DEFAULT_SYSTEM_PROMPT)
        assertEquals("默认编辑助手", matched?.name)
    }

    @Test
    fun `首尾空白不影响匹配`() {
        val prompt = PromptPresets.all[2].prompt
        assertEquals(PromptPresets.all[2].name, PromptPresets.match("  $prompt\n")?.name)
    }

    @Test
    fun `改过之后按自定义处理`() {
        val edited = PromptPresets.all[1].prompt + "\n5. 额外要求"
        assertNull(PromptPresets.match(edited))
        assertNull(PromptPresets.match(""))
    }

    @Test
    fun `每个预设都要求只输出结果`() {
        // 编辑类任务的共同底线：不要解释、不要前言，避免结果里混入客套话
        PromptPresets.all.forEach { preset ->
            assertTrue(
                "${preset.name} 缺少「只输出」约束",
                preset.prompt.contains("只输出"),
            )
        }
    }
}
