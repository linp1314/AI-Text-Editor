package com.aieditor.text.data

/**
 * 轻量文本差异比较：先按行做 LCS 对齐，再把「删除 + 新增」相邻对配成「修改」，
 * 修改行内部再做字符级 LCS，用来高亮具体改动了哪几个字。
 *
 * 纯 Kotlin，无 Android 依赖，便于单元测试。
 */
object TextDiff {

    enum class Kind { EQUAL, INSERT, DELETE, MODIFY }

    data class Line(
        val kind: Kind,
        val oldText: String = "",
        val newText: String = "",
    ) {
        val text: String get() = if (kind == Kind.DELETE) oldText else newText
    }

    data class Result(
        val lines: List<Line>,
        val insertedChars: Int,
        val deletedChars: Int,
        val changedLines: Int,
        val unchangedLines: Int,
    ) {
        val hasChanges: Boolean get() = changedLines > 0
        val charDelta: Int get() = insertedChars - deletedChars
    }

    enum class InlineKind { EQUAL, INSERT, DELETE }

    data class Inline(val kind: InlineKind, val text: String)

    /** 超过这个规模就退化为粗粒度对齐，避免 O(n·m) 撑爆内存。 */
    private const val MAX_CELLS = 4_000_000L

    /** 单行字符级比较的上限，超长行只整行标红/标绿。 */
    private const val INLINE_LIMIT = 1200

    fun compare(old: String, new: String): Result {
        if (old == new) {
            val rows = old.split("\n")
            return Result(
                lines = rows.map { Line(Kind.EQUAL, oldText = it, newText = it) },
                insertedChars = 0,
                deletedChars = 0,
                changedLines = 0,
                unchangedLines = rows.size,
            )
        }

        val a = old.split("\n")
        val b = new.split("\n")
        val raw = if (a.size.toLong() * b.size > MAX_CELLS) coarseOps(a, b) else exactOps(a, b)
        return build(mergePairs(raw))
    }

    // ---------------------------------------------------------------- 行级

    private fun exactOps(a: List<String>, b: List<String>): List<Line> {
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }

        val ops = ArrayList<Line>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    ops.add(Line(Kind.EQUAL, oldText = a[i], newText = a[i]))
                    i++
                    j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    ops.add(Line(Kind.DELETE, oldText = a[i]))
                    i++
                }
                else -> {
                    ops.add(Line(Kind.INSERT, newText = b[j]))
                    j++
                }
            }
        }
        while (i < n) {
            ops.add(Line(Kind.DELETE, oldText = a[i]))
            i++
        }
        while (j < m) {
            ops.add(Line(Kind.INSERT, newText = b[j]))
            j++
        }
        return ops
    }

    /** 超长文本的降级方案：先剥掉首尾相同的行，中间整体视为改写。 */
    private fun coarseOps(a: List<String>, b: List<String>): List<Line> {
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix &&
            a[a.size - 1 - suffix] == b[b.size - 1 - suffix]
        ) suffix++

        val ops = ArrayList<Line>(a.size + b.size)
        for (k in 0 until prefix) ops.add(Line(Kind.EQUAL, oldText = a[k], newText = a[k]))
        for (k in prefix until a.size - suffix) ops.add(Line(Kind.DELETE, oldText = a[k]))
        for (k in prefix until b.size - suffix) ops.add(Line(Kind.INSERT, newText = b[k]))
        for (k in a.size - suffix until a.size) ops.add(Line(Kind.EQUAL, oldText = a[k], newText = a[k]))
        return ops
    }

    /** 把相邻的「删除 + 新增」配成「修改」，读起来更像逐句改写。 */
    private fun mergePairs(raw: List<Line>): List<Line> {
        val merged = ArrayList<Line>(raw.size)
        var k = 0
        while (k < raw.size) {
            when (raw[k].kind) {
                Kind.EQUAL -> {
                    merged.add(raw[k])
                    k++
                }
                Kind.DELETE -> {
                    val dels = ArrayList<String>()
                    while (k < raw.size && raw[k].kind == Kind.DELETE) {
                        dels.add(raw[k].oldText)
                        k++
                    }
                    val ins = ArrayList<String>()
                    while (k < raw.size && raw[k].kind == Kind.INSERT) {
                        ins.add(raw[k].newText)
                        k++
                    }
                    val pairs = minOf(dels.size, ins.size)
                    for (p in 0 until pairs) merged.add(Line(Kind.MODIFY, dels[p], ins[p]))
                    for (p in pairs until dels.size) merged.add(Line(Kind.DELETE, oldText = dels[p]))
                    for (p in pairs until ins.size) merged.add(Line(Kind.INSERT, newText = ins[p]))
                }
                Kind.INSERT -> {
                    merged.add(raw[k])
                    k++
                }
                Kind.MODIFY -> {
                    merged.add(raw[k])
                    k++
                }
            }
        }
        return merged
    }

    private fun build(lines: List<Line>): Result {
        var inserted = 0
        var deleted = 0
        var changed = 0
        var unchanged = 0
        lines.forEach { line ->
            when (line.kind) {
                Kind.EQUAL -> unchanged++
                Kind.INSERT -> {
                    inserted += line.newText.length
                    changed++
                }
                Kind.DELETE -> {
                    deleted += line.oldText.length
                    changed++
                }
                Kind.MODIFY -> {
                    inserted += line.newText.length
                    deleted += line.oldText.length
                    changed++
                }
            }
        }
        return Result(lines, inserted, deleted, changed, unchanged)
    }

    // ---------------------------------------------------------------- 行内

    fun inlineDiff(old: String, new: String): List<Inline> {
        if (old == new) return listOf(Inline(InlineKind.EQUAL, old))
        if (old.length > INLINE_LIMIT || new.length > INLINE_LIMIT) {
            return buildList {
                if (old.isNotEmpty()) add(Inline(InlineKind.DELETE, old))
                if (new.isNotEmpty()) add(Inline(InlineKind.INSERT, new))
            }
        }

        var prefix = 0
        while (prefix < old.length && prefix < new.length && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.length - prefix && suffix < new.length - prefix &&
            old[old.length - 1 - suffix] == new[new.length - 1 - suffix]
        ) suffix++

        val oldMid = old.substring(prefix, old.length - suffix)
        val newMid = new.substring(prefix, new.length - suffix)

        val out = ArrayList<Inline>()
        if (prefix > 0) out.add(Inline(InlineKind.EQUAL, old.substring(0, prefix)))
        out.addAll(lcsInline(oldMid, newMid))
        if (suffix > 0) out.add(Inline(InlineKind.EQUAL, old.substring(old.length - suffix)))
        return out
    }

    private fun lcsInline(a: String, b: String): List<Inline> {
        if (a.isEmpty()) return if (b.isEmpty()) emptyList() else listOf(Inline(InlineKind.INSERT, b))
        if (b.isEmpty()) return listOf(Inline(InlineKind.DELETE, a))

        val n = a.length
        val m = b.length
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }

        val out = ArrayList<Inline>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    out.add(Inline(InlineKind.EQUAL, a[i].toString()))
                    i++
                    j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    out.add(Inline(InlineKind.DELETE, a[i].toString()))
                    i++
                }
                else -> {
                    out.add(Inline(InlineKind.INSERT, b[j].toString()))
                    j++
                }
            }
        }
        while (i < n) {
            out.add(Inline(InlineKind.DELETE, a[i].toString()))
            i++
        }
        while (j < m) {
            out.add(Inline(InlineKind.INSERT, b[j].toString()))
            j++
        }
        return mergeInline(out)
    }

    private fun mergeInline(items: List<Inline>): List<Inline> {
        if (items.isEmpty()) return items
        val merged = ArrayList<Inline>(items.size)
        val sb = StringBuilder()
        var kind = items[0].kind
        items.forEach { item ->
            if (item.kind == kind) {
                sb.append(item.text)
            } else {
                merged.add(Inline(kind, sb.toString()))
                sb.setLength(0)
                sb.append(item.text)
                kind = item.kind
            }
        }
        if (sb.isNotEmpty()) merged.add(Inline(kind, sb.toString()))
        return merged
    }
}
