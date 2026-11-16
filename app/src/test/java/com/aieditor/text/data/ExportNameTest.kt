package com.aieditor.text.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportNameTest {

    private val stamp = "20261116_1300"

    private fun suggest(source: String?, title: String? = null) =
        ExportName.suggest(source, title, stamp)

    @Test
    fun `keep original extension and append AI before it`() {
        val target = suggest("笔记.txt", "笔记.txt")

        assertEquals("笔记AI.txt", target.name)
        assertEquals("text/plain", target.mime)
    }

    @Test
    fun `markdown keeps md and gets text-markdown mime`() {
        val target = suggest("README.md", "README.md")

        assertEquals("READMEAI.md", target.name)
        assertEquals("text/markdown", target.mime)
    }

    @Test
    fun `extension casing is preserved`() {
        assertEquals("CHANGELOGAI.TXT", suggest("CHANGELOG.TXT", "CHANGELOG.TXT").name)
    }

    @Test
    fun `binary sources fall back to txt`() {
        assertEquals("报告AI.txt", suggest("报告.docx", "报告.docx").name)
        assertEquals("论文AI.txt", suggest("论文.pdf", "论文.pdf").name)
    }

    @Test
    fun `directory part of the path is dropped`() {
        val target = suggest(
            "/storage/emulated/0/Download/配置.json",
            "/storage/emulated/0/Download/配置.json",
        )

        assertEquals("配置AI.json", target.name)
        assertEquals("application/json", target.mime)
    }

    @Test
    fun `renamed document keeps the original extension`() {
        val target = suggest("notes.md", "读书笔记")

        assertEquals("读书笔记AI.md", target.name)
    }

    @Test
    fun `missing extension becomes txt`() {
        assertEquals("notesAI.txt", suggest("notes", "notes").name)
    }

    @Test
    fun `name already ending with AI is not doubled`() {
        assertEquals("sampleAI.txt", suggest("sampleAI.txt", "sampleAI.txt").name)
        assertEquals("sampleai.txt", suggest("sampleai.txt", "sampleai.txt").name)
    }

    @Test
    fun `untitled documents fall back to timestamp`() {
        val target = suggest(null, "未命名 3")

        assertEquals("AI文本_20261116_1300.txt", target.name)
        assertEquals("text/plain", target.mime)
    }

    @Test
    fun `blank name falls back to timestamp`() {
        assertEquals("AI文本_20261116_1300.txt", suggest("", "").name)
        assertEquals("AI文本_20261116_1300.txt", suggest(null, null).name)
    }

    @Test
    fun `characters rejected by file systems are replaced`() {
        assertEquals("a_b_AI.txt", suggest("a:b?.txt", "a:b?.txt").name)
    }

    @Test
    fun `csv and yaml get matching mime types`() {
        assertEquals("text/csv", suggest("表.csv", "表.csv").mime)
        assertEquals("text/yaml", suggest("配置.yml", "配置.yml").mime)
        assertEquals("text/html", suggest("page.html", "page.html").mime)
    }

    @Test
    fun `source name wins when title is a placeholder`() {
        val target = suggest("archive.zip", "未命名")

        assertEquals("archiveAI.txt", target.name)
    }
}
