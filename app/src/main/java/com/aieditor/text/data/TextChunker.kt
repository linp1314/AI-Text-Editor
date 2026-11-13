package com.aieditor.text.data

/**
 * 长文本切分器。
 *
 * 切分优先级：段落边界（空行）> 句子边界（。！？；…/.!?;）> 空白 > 硬切。
 * 每块都记录「块前的原始分隔符」，合并结果时按顺序拼回去，排版基本与原文一致。
 */
object TextChunker {

    /** 默认每段字数上限（按字符计，中文约等于 token 的 1.5～2 倍关系里的字符数）。 */
    const val DEFAULT_LIMIT = 1500

    /** 可选的分段长度。 */
    val LIMIT_OPTIONS = listOf(800, 1500, 2500, 4000)

    data class Chunk(val text: String, val separatorBefore: String)

    /** 切分文本；不足一段时返回单块。 */
    fun split(text: String, limit: Int = DEFAULT_LIMIT): List<Chunk> {
        if (text.isBlank()) return emptyList()
        val max = limit.coerceAtLeast(200)
        if (text.length <= max) return listOf(Chunk(text, ""))

        val result = ArrayList<Chunk>(text.length / max + 2)
        var pos = 0
        var pendingSep = StringBuilder()

        while (pos < text.length) {
            var start = pos
            while (start < text.length && text[start].isWhitespace()) {
                pendingSep.append(text[start])
                start++
            }
            if (start >= text.length) break

            val hardEnd = (start + max).coerceAtMost(text.length)
            val cut = if (hardEnd >= text.length) text.length else findCut(text, start + max / 2, hardEnd)

            var contentEnd = cut
            // 只在后面还有内容时剥离尾部空白，最后一块保留原样以便合并时完全还原
            if (cut < text.length) {
                while (contentEnd > start && text[contentEnd - 1].isWhitespace()) contentEnd--
            }
            if (contentEnd <= start) contentEnd = cut.coerceAtLeast(start + 1)

            result.add(Chunk(text.substring(start, contentEnd), pendingSep.toString()))
            pendingSep = StringBuilder()
            pos = contentEnd
        }
        return if (result.isEmpty()) listOf(Chunk(text, "")) else result
    }

    /** 只估算段数，不真正切分，用于界面提示。 */
    fun count(text: String, limit: Int = DEFAULT_LIMIT): Int {
        if (text.isBlank()) return 0
        val max = limit.coerceAtLeast(200)
        if (text.length <= max) return 1
        return split(text, max).size
    }

    /**
     * 在 [lower, upper] 之间从后往前找最合适的切点（返回下一块的起始位置）。
     */
    private fun findCut(text: String, lower: Int, upper: Int): Int {
        val from = lower.coerceIn(1, upper)

        // 1. 段落边界：切在 "\n\n" 之后的第一个非空白处
        var i = upper
        while (i >= from) {
            if (text[i] == '\n' && text[i - 1] == '\n') {
                var next = i + 1
                while (next < text.length && text[next] == '\n') next++
                return next
            }
            i--
        }

        // 2. 句子边界：句末标点之后
        i = upper
        while (i >= from) {
            if (text[i - 1] in SENTENCE_END) return i
            i--
        }

        // 3. 空白处
        i = upper
        while (i >= from) {
            if (text[i].isWhitespace()) return i
            i--
        }

        // 4. 硬切
        return upper
    }

    private const val SENTENCE_END = "。！？；.!?;\n…"

    /** 把「已完成的块结果 + 当前正在生成的块」拼成完整文本。 */
    fun merge(
        chunks: List<Chunk>,
        finished: List<String>,
        currentIndex: Int = -1,
        currentText: String = "",
    ): String {
        val sb = StringBuilder()
        for (index in chunks.indices) {
            val part = when {
                index < finished.size -> finished[index]
                index == currentIndex -> currentText
                else -> return sb.toString()
            }
            sb.append(chunks[index].separatorBefore).append(part)
        }
        return sb.toString()
    }
}
