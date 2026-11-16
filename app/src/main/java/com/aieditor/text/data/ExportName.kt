package com.aieditor.text.data

/** 保存 AI 结果时的建议文件名与对应的 MIME 类型。 */
data class ExportTarget(val name: String, val mime: String)

/**
 * 生成保存对话框里的默认文件名。
 *
 * 规则：沿用来源文件名，在扩展名之前加上「AI」，扩展名保持不变
 * （`笔记.txt` → `笔记AI.txt`、`README.md` → `READMEAI.md`）。
 * 只有纯文本能原样写回的扩展名才保留，docx / pdf 这类二进制格式退回 `.txt`，
 * 否则存出来的文件打不开。
 */
object ExportName {

    /** 可以直接以文本写回、且各大编辑器都认的扩展名。 */
    private val TEXT_EXT = setOf(
        "txt", "text", "md", "markdown", "csv", "tsv", "json", "html", "htm", "xml",
        "log", "yml", "yaml", "ini", "conf", "properties", "srt", "tex", "sql",
        "java", "kt", "kts", "py", "js", "ts", "tsx", "css", "scss", "sh", "bat",
        "c", "h", "cpp", "hpp", "cs", "go", "rs", "php", "rb", "swift", "lua", "gradle",
    )

    private val MIME = mapOf(
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "csv" to "text/csv",
        "tsv" to "text/tab-separated-values",
        "json" to "application/json",
        "html" to "text/html",
        "htm" to "text/html",
        "xml" to "application/xml",
        "yml" to "text/yaml",
        "yaml" to "text/yaml",
    )

    /**
     * @param sourceName 导入时的原始文件名（含扩展名），新建文档为空
     * @param title 当前文档标题，用户可能改过
     * @param stamp 没有可用文件名时的兜底时间戳
     */
    fun suggest(sourceName: String?, title: String?, stamp: String): ExportTarget {
        val source = baseName(sourceName)
        val named = baseName(title)
        // 标题被改成「未命名 / 未命名 3」这类占位名时，说明用户没给它起名字，退回时间戳
        val usableTitle = named.takeIf { it.isNotBlank() && !it.startsWith("未命名") }
        val candidate = usableTitle ?: source

        val ext = textExt(named) ?: textExt(source) ?: ".txt"
        val stem = sanitize(stemOf(candidate.ifBlank { source }))
        if (stem.isBlank()) return ExportTarget("AI文本_$stamp.txt", "text/plain")

        // 已经是 xxxAI 的文件名就不再叠一层，避免出现 xxxAIAI
        val tagged = if (stem.endsWith("AI", ignoreCase = true)) stem else stem + "AI"
        return ExportTarget(tagged + ext, mimeOf(ext))
    }

    /** 只留文件名本身，去掉导入时可能带上的目录。 */
    private fun baseName(name: String?): String =
        name.orEmpty().trim().substringAfterLast('/').substringAfterLast('\\')

    private fun stemOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /** 扩展名可用时原样返回（保留大小写），否则返回 null。 */
    private fun textExt(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return null
        val ext = name.substring(dot)
        return ext.takeIf { it.substring(1).lowercase() in TEXT_EXT }
    }

    private fun mimeOf(ext: String): String =
        MIME[ext.trimStart('.').lowercase()] ?: "text/plain"

    /** 去掉文件系统不认的字符，避免保存对话框直接拒绝这个建议名。 */
    private fun sanitize(name: String): String {
        val cleaned = buildString(name.length) {
            name.forEach { ch ->
                append(
                    when {
                        ch in ILLEGAL -> '_'
                        ch.code < 0x20 -> '_'
                        else -> ch
                    }
                )
            }
        }
        return cleaned.trim().trimEnd('.')
    }

    private val ILLEGAL = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
}
