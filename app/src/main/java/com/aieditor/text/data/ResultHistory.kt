package com.aieditor.text.data

import org.json.JSONObject
import java.io.File

/** 一次 AI 生成结果的本地快照。 */
data class HistoryEntry(
    /** 唯一标识，用于定位文件与删除。 */
    val id: Long,
    /** 生成时间（毫秒）。 */
    val createdAt: Long,
    /** 当时的指令；二次编辑时是这一步的指令。 */
    val instruction: String,
    val model: String,
    /** 第几版结果（1 = 首轮）。 */
    val revision: Int,
    val text: String,
) {
    val charCount: Int get() = text.length

    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("createdAt", createdAt)
        put("instruction", instruction)
        put("model", model)
        put("revision", revision)
        put("text", text)
    }.toString()

    companion object {
        fun fromJson(raw: String): HistoryEntry? = runCatching {
            val o = JSONObject(raw)
            HistoryEntry(
                id = o.optLong("id", 0L),
                createdAt = o.optLong("createdAt", 0L),
                instruction = o.optString("instruction", ""),
                model = o.optString("model", ""),
                revision = o.optInt("revision", 1),
                text = o.optString("text", ""),
            )
        }.getOrNull()
    }
}

/**
 * 结果历史存储：一条记录一个 JSON 文件，按生成时间倒序返回。
 *
 * 用文件而不是 SharedPreferences，因为生成结果可能很长（整篇文档）。
 * 构造参数直接接收目录，便于单元测试。
 */
class ResultHistoryStore(private val dir: File) {

    fun list(): List<HistoryEntry> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: return emptyList()
        return files
            .mapNotNull { f -> runCatching { HistoryEntry.fromJson(f.readText()) }.getOrNull() }
            .filter { it.text.isNotBlank() }
            .sortedByDescending { it.createdAt }
    }

    /**
     * 保存一条，并按 [keep] 裁剪。
     * @return 保存后的条目；[keep] <= 0（功能关闭）时不落盘，返回 null。
     */
    fun add(entry: HistoryEntry, keep: Int): HistoryEntry? {
        if (keep <= 0) return null
        if (!dir.exists() && !dir.mkdirs()) return null
        val saved = runCatching {
            File(dir, fileName(entry)).writeText(entry.toJson())
        }.isSuccess
        if (!saved) return null
        trim(keep)
        return entry
    }

    /** 只保留最新 [keep] 条，返回被删除的条数。 */
    fun trim(keep: Int): Int {
        if (keep <= 0) return clear()
        val all = list()
        if (all.size <= keep) return 0
        return all.drop(keep).count { e -> fileFor(e)?.delete() == true }
    }

    fun delete(id: Long): Boolean = fileFor(id)?.delete() == true

    fun clear(): Int = dir.listFiles()?.count { it.delete() } ?: 0

    fun count(): Int = list().size

    private fun fileName(entry: HistoryEntry) = "${entry.createdAt}-${entry.id}.json"

    private fun fileFor(entry: HistoryEntry) = File(dir, fileName(entry)).takeIf { it.exists() }

    private fun fileFor(id: Long) = dir.listFiles()?.firstOrNull { it.name.endsWith("-$id.json") }
}
