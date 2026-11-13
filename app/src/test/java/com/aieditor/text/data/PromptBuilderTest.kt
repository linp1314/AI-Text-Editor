package com.aieditor.text.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 生成模式与编辑模式的提示词拼装。 */
class PromptBuilderTest {

    private val system = "你是一名中文编辑"

    @Test
    fun `生成模式只发任务不带原文`() {
        val messages = PromptBuilder.generate(system, "写一封婉拒加班的邮件，200 字左右")

        assertEquals(2, messages.size)
        assertEquals("system", messages[0].role)
        assertEquals(system, messages[0].content)

        val user = messages[1].content
        assertTrue(user.contains("写一封婉拒加班的邮件"))
        assertTrue(user.contains("从零创作"))
        assertFalse("生成模式不该出现【原文】段落", user.contains("【原文】"))
        assertFalse("生成模式不该出现分段说明", user.contains("【分段说明】"))
    }

    @Test
    fun `生成模式要求只输出正文`() {
        val user = PromptBuilder.generate(system, "列一份出差清单").last().content
        assertTrue(user.contains("只输出成品正文"))
    }

    @Test
    fun `编辑模式带原文`() {
        val messages = PromptBuilder.edit(system, "润色", "今天的会议很顺利。", isRefine = false)
        val user = messages.last().content
        assertTrue(user.contains("【原文】"))
        assertTrue(user.contains("今天的会议很顺利。"))
        assertFalse(user.contains("【分段说明】"))
    }

    @Test
    fun `二次编辑带说明`() {
        val user = PromptBuilder.edit(system, "再精简一半", "第一版结果", isRefine = true).last().content
        assertTrue(user.contains("上一轮 AI 编辑后的结果"))
    }

    @Test
    fun `分段时标注段序号且从 1 开始`() {
        val user = PromptBuilder.edit(
            system, "改写", "第二段内容", isRefine = false, segmentIndex = 1, segmentTotal = 3,
        ).last().content
        assertTrue(user.contains("本段是第 2 段（共 3 段）"))
        assertTrue(user.contains("不要补写原文中不存在的内容"))
    }

    @Test
    fun `单段编辑不出现分段说明`() {
        val user = PromptBuilder.edit(system, "改写", "短文本", isRefine = false).last().content
        assertFalse(user.contains("【分段说明】"))
    }
}
