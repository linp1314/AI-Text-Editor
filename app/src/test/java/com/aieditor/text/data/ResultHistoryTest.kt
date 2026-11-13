package com.aieditor.text.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ResultHistoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = ResultHistoryStore(File(tmp.root, "ai_history"))

    private fun entry(id: Long, at: Long, text: String, instruction: String = "润色") =
        HistoryEntry(
            id = id,
            createdAt = at,
            instruction = instruction,
            model = "deepseek-chat",
            revision = 1,
            text = text,
        )

    @Test
    fun `json round trip keeps every field`() {
        val original = HistoryEntry(
            id = 42L,
            createdAt = 1_700_000_000_000L,
            instruction = "翻译成英文",
            model = "gpt-4o-mini",
            revision = 3,
            text = "第一行\n第二行",
        )

        val restored = HistoryEntry.fromJson(original.toJson())

        assertNotNull(restored)
        assertEquals(original, restored)
    }

    @Test
    fun `add keeps only the newest entries`() {
        val s = store()
        s.add(entry(1, 1_000L, "一"), keep = 3)
        s.add(entry(2, 2_000L, "二"), keep = 3)
        s.add(entry(3, 3_000L, "三"), keep = 3)
        s.add(entry(4, 4_000L, "四"), keep = 3)

        val kept = s.list()
        assertEquals(3, kept.size)
        assertEquals(listOf("四", "三", "二"), kept.map { it.text })
    }

    @Test
    fun `zero limit disables saving`() {
        val s = store()
        val result = s.add(entry(1, 1_000L, "不该保存"), keep = 0)

        assertNull(result)
        assertEquals(0, s.list().size)
    }

    @Test
    fun `list sorts newest first`() {
        val s = store()
        s.add(entry(1, 1_000L, "旧"), keep = 10)
        s.add(entry(2, 5_000L, "新"), keep = 10)
        s.add(entry(3, 3_000L, "中"), keep = 10)

        assertEquals(listOf("新", "中", "旧"), s.list().map { it.text })
    }

    @Test
    fun `trim drops oldest when limit is lowered`() {
        val s = store()
        repeat(5) { i -> s.add(entry(i.toLong(), (i + 1) * 1_000L, "第${i}条"), keep = 5) }
        assertEquals(5, s.list().size)

        val removed = s.trim(2)

        assertEquals(3, removed)
        assertEquals(2, s.list().size)
        assertEquals(listOf("第4条", "第3条"), s.list().map { it.text })
    }

    @Test
    fun `delete removes single entry and clear removes all`() {
        val s = store()
        s.add(entry(1, 1_000L, "一"), keep = 5)
        s.add(entry(2, 2_000L, "二"), keep = 5)

        assertTrue(s.delete(1))
        assertEquals(listOf("二"), s.list().map { it.text })

        assertEquals(1, s.clear())
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun `blank text is ignored when listing`() {
        val s = store()
        s.add(entry(1, 1_000L, "正常"), keep = 5)
        s.add(entry(2, 2_000L, "   "), keep = 5)

        assertEquals(listOf("正常"), s.list().map { it.text })
    }
}
