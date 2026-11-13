package com.aieditor.text.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Html
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

class ImportException(message: String) : Exception(message)

data class ImportedDocument(val name: String, val text: String)

/**
 * 从系统文件选择器拿到的 Uri 中读出可编辑的纯文本。
 * 支持：txt / md / markdown / csv / json / log / xml / html / 代码文件、docx、pdf。
 */
object DocumentImporter {

    const val MAX_PDF_PAGES = 120

    private val TEXT_EXTENSIONS = setOf(
        "txt", "text", "md", "markdown", "csv", "tsv", "json", "log", "xml", "html", "htm",
        "yml", "yaml", "ini", "conf", "srt", "rtf", "java", "kt", "py", "js", "ts", "css",
        "sql", "sh", "c", "cpp", "h", "go", "rs", "php", "rb",
    )

    suspend fun load(context: Context, uri: Uri): ImportedDocument = withContext(Dispatchers.IO) {
        val name = queryDisplayName(context, uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ImportException("无法读取这个文件")

        val raw = when {
            ext == "docx" -> extractDocxText(bytes)
            ext == "pdf" -> readPdf(context, bytes)
            ext == "doc" -> throw ImportException("暂不支持旧版 .doc，请另存为 .docx 或 .txt")
            ext == "html" || ext == "htm" -> stripHtml(decodeText(bytes))
            ext in TEXT_EXTENSIONS -> decodeText(bytes)
            else -> decodeTextOrNull(bytes)
                ?: throw ImportException("不支持的文件类型${if (ext.isNotEmpty()) "：.$ext" else ""}")
        }

        val text = raw.replace("\r\n", "\n").replace("\r", "\n").trim()
        if (text.isBlank()) {
            throw ImportException("没有提取到文字，可能是扫描版 PDF 或图片型文档")
        }
        ImportedDocument(name.ifBlank { "未命名文档" }, text)
    }

    // ------------------------------------------------------------ 各格式解析

    internal fun extractDocxText(bytes: ByteArray): String {
        var documentXml: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    documentXml = zip.readBytes()
                    break
                }
                entry = zip.nextEntry
            }
        }
        val data = documentXml ?: throw ImportException("不是有效的 .docx 文件")

        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(ByteArrayInputStream(data), null)
        val builder = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "w:t" -> builder.append(parser.nextText())
                    "w:tab" -> builder.append('\t')
                    "w:br" -> builder.append('\n')
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "w:p" -> if (builder.isNotEmpty() && builder.last() != '\n') builder.append('\n')
                }
            }
            event = parser.next()
        }
        return builder.toString()
    }

    private fun readPdf(context: Context, bytes: ByteArray): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        return try {
            PDDocument.load(ByteArrayInputStream(bytes)).use { document ->
                val total = document.numberOfPages
                if (total == 0) throw ImportException("PDF 没有页面")
                val stripper = PDFTextStripper()
                stripper.sortByPosition = true
                stripper.startPage = 1
                stripper.endPage = minOf(total, MAX_PDF_PAGES)
                stripper.getText(document)
            }
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            throw ImportException("PDF 解析失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun stripHtml(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()

    // ------------------------------------------------------------ 编码处理

    private fun decodeText(bytes: ByteArray): String = decodeTextOrNull(bytes)
        ?: runCatching { String(bytes, Charset.forName("GBK")) }.getOrElse { String(bytes, Charsets.ISO_8859_1) }

    private fun decodeTextOrNull(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return ""
        val offset = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        val decoded = try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (e: Exception) {
            null
        } ?: return null
        // 二进制文件通常含大量控制字符，直接拒绝
        val control = decoded.count { it.code < 9 || (it.code in 14..31) }
        return if (decoded.isNotEmpty() && control * 100 / decoded.length > 2) null else decoded
    }

    /**
     * 给文档列表用的来源路径。
     * SAF 的 content:// 地址本身看不出位置，这里把常见的 primary: 文档 id 还原成真实路径；
     * 还原不出来时退回文件名，不显示一串无意义的 URI。
     */
    fun displayPath(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.path.orEmpty()
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        if (!documentId.isNullOrBlank()) {
            val path = when {
                documentId.startsWith("primary:") ->
                    "/storage/emulated/0/" + documentId.removePrefix("primary:")
                documentId.startsWith("raw:") -> documentId.removePrefix("raw:")
                else -> null
            }
            if (path != null) return path
        }
        // 下载目录等由系统托管的位置，路径里只有一串数字 id，直接用文件名更清楚
        return queryDisplayName(context, uri)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        val value = cursor.getString(index)
                        if (!value.isNullOrBlank()) return value
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    }
}
