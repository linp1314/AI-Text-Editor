package com.aieditor.text.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDiffTest {

    @Test
    fun identicalTextHasNoChanges() {
        val result = TextDiff.compare("第一行\n第二行", "第一行\n第二行")
        assertFalse(result.hasChanges)
        assertEquals(2, result.unchangedLines)
        assertTrue(result.lines.all { it.kind == TextDiff.Kind.EQUAL })
    }

    @Test
    fun changedLineBecomesModify() {
        val result = TextDiff.compare("今天天气很好。\n第二行", "今天天气非常好。\n第二行")
        assertEquals(1, result.changedLines)
        assertEquals(1, result.unchangedLines)
        val modify = result.lines.first { it.kind == TextDiff.Kind.MODIFY }
        assertEquals("今天天气很好。", modify.oldText)
        assertEquals("今天天气非常好。", modify.newText)
    }

    @Test
    fun insertedLineIsCounted() {
        val result = TextDiff.compare("A\nB", "A\nB\nC")
        assertEquals(1, result.lines.count { it.kind == TextDiff.Kind.INSERT })
        assertEquals(1, result.insertedChars)
        assertEquals(2, result.unchangedLines)
    }

    @Test
    fun deletedLineIsCounted() {
        val result = TextDiff.compare("A\nB\nC", "A\nC")
        assertEquals(1, result.lines.count { it.kind == TextDiff.Kind.DELETE })
        assertEquals(1, result.deletedChars)
        assertEquals(0, result.insertedChars)
        assertEquals(2, result.unchangedLines)
    }

    @Test
    fun adjacentDeleteInsertMergeIntoModify() {
        val result = TextDiff.compare("一\n二\n三", "一\n贰\n叁")
        assertEquals(2, result.changedLines)
        assertEquals(0, result.lines.count { it.kind == TextDiff.Kind.DELETE })
        assertEquals(0, result.lines.count { it.kind == TextDiff.Kind.INSERT })
    }

    @Test
    fun inlineDiffMarksInsertedChars() {
        val parts = TextDiff.inlineDiff("今天天气很好。", "今天天气非常好。")
        val inserted = parts.filter { it.kind == TextDiff.InlineKind.INSERT }.joinToString("") { it.text }
        assertEquals("非常", inserted)
    }

    @Test
    fun inlineDiffRebuildsBothSides() {
        val old = "我们下周三开会。"
        val new = "公司下周五下午开会。"
        val parts = TextDiff.inlineDiff(old, new)
        val rebuiltOld = parts.filter { it.kind != TextDiff.InlineKind.INSERT }.joinToString("") { it.text }
        val rebuiltNew = parts.filter { it.kind != TextDiff.InlineKind.DELETE }.joinToString("") { it.text }
        assertEquals(old, rebuiltOld)
        assertEquals(new, rebuiltNew)
    }

    @Test
    fun longTextFallsBackWithoutHanging() {
        val old = (1..3000).joinToString("\n") { "第 $it 行内容" }
        val new = (1..3000).joinToString("\n") { "第 ${it + 1} 行内容" }
        val result = TextDiff.compare(old, new)
        assertTrue(result.hasChanges)
        assertTrue(result.changedLines > 0)
    }

    @Test
    fun emptyToTextProducesInsert() {
        val result = TextDiff.compare("", "新增内容")
        assertTrue(result.hasChanges)
        assertEquals(4, result.insertedChars)
    }
}
