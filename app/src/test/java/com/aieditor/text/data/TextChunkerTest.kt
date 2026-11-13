package com.aieditor.text.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {

    private fun paragraphs(count: Int, charsPerParagraph: Int = 120): String =
        (1..count).joinToString("\n\n") { index ->
            "第${index}段：" + "文".repeat(charsPerParagraph - 5) + "。"
        }

    @Test
    fun shortTextKeepsSingleChunk() {
        val text = "第一段。\n\n第二段。"
        val chunks = TextChunker.split(text, 1500)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0].text)
        assertEquals(1, TextChunker.count(text, 1500))
    }

    @Test
    fun longTextIsSplitWithinLimit() {
        val text = paragraphs(20)
        val limit = 500
        val chunks = TextChunker.split(text, limit)
        assertTrue("应切成多段", chunks.size > 1)
        chunks.forEach { chunk ->
            // 允许为保住段落边界而略超几个字符
            assertTrue("单段长度 ${chunk.text.length} 超限", chunk.text.length <= limit + 8)
            assertTrue(chunk.text.isNotBlank())
        }
        assertTrue(chunks.size >= text.length / limit)
    }

    @Test
    fun mergeRestoresOriginalText() {
        val text = paragraphs(12, 200)
        val chunks = TextChunker.split(text, 400)
        assertTrue(chunks.size > 1)
        // 模型原样返回时，合并结果应与原文完全一致
        val restored = TextChunker.merge(chunks, chunks.map { it.text })
        assertEquals(text, restored)
    }

    @Test
    fun cutsPreferParagraphBoundary() {
        val text = paragraphs(6, 100)
        val chunks = TextChunker.split(text, 300)
        // 除最后一块外，每块都应以段落结尾而不是句子中间
        chunks.dropLast(1).forEach { chunk ->
            assertTrue("块尾应落在句末：${chunk.text.takeLast(6)}", chunk.text.endsWith("。"))
        }
    }

    @Test
    fun mergeWithPartialCurrentChunk() {
        val text = paragraphs(5, 80)
        val chunks = TextChunker.split(text, 120)
        assertTrue(chunks.size >= 3)
        val merged = TextChunker.merge(chunks, listOf(chunks[0].text), 1, "正在生成")
        assertEquals(chunks[0].separatorBefore + chunks[0].text + chunks[1].separatorBefore + "正在生成", merged)
    }

    @Test
    fun singleHugeParagraphIsHardSplit() {
        val text = "字".repeat(3000)
        val chunks = TextChunker.split(text, 600)
        assertEquals(5, chunks.size)
        assertEquals(text, TextChunker.merge(chunks, chunks.map { it.text }))
    }

    @Test
    fun blankTextYieldsNothing() {
        assertEquals(0, TextChunker.split("   \n\n ").size)
        assertEquals(0, TextChunker.count("", 1000))
    }
}
