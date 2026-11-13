package com.aieditor.text.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentImporterTest {

    private fun docx(documentXml: String): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("<Types/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(documentXml.toByteArray())
            zip.closeEntry()
        }
        return buffer.toByteArray()
    }

    @Test
    fun docx_paragraphs_runs_and_tabs_are_extracted() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>第一段</w:t></w:r></w:p>
                <w:p><w:r><w:t>第二</w:t></w:r><w:r><w:t>段</w:t></w:r></w:p>
                <w:p><w:r><w:t>Tab</w:t><w:tab/><w:t>后面</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()

        val text = DocumentImporter.extractDocxText(docx(xml))

        assertEquals("第一段\n第二段\nTab\t后面\n", text)
        assertTrue(text.contains("第二段"))
    }

    @Test
    fun docx_without_document_xml_is_rejected() {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry("word/styles.xml"))
            zip.write("<styles/>".toByteArray())
            zip.closeEntry()
        }
        val error = runCatching { DocumentImporter.extractDocxText(buffer.toByteArray()) }.exceptionOrNull()
        assertTrue(error is ImportException)
    }
}
