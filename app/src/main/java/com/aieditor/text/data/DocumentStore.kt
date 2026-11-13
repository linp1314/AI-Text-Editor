package com.aieditor.text.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一篇打开的文档，对应编辑器侧栏里的一行。
 * [text] 与编辑区保持同步，切换文档时直接从这里取内容。
 */
data class Doc(
    val id: Long,
    val title: String,
    /** 副标题：导入文件的路径，或「未命名文档」。 */
    val subtitle: String = "",
    /** 来源文件 URI；为空表示这篇内容还没有对应的本地文件。 */
    val uri: String? = null,
    val text: String = "",
)

/**
 * 文档列表的读写。
 * 与文件系统分开，方便单测直接覆盖编码 / 解码；解析失败时尽量保留可用内容，
 * 不让一条坏记录把整份文档列表带走。
 */
object DocCodec {

    fun encode(docs: List<Doc>): String =
        JSONObject().put("schema", SCHEMA).put("docs", encodeArray(docs)).toString()

    /** 文档数组：备份文件也复用这个格式。 */
    fun encodeArray(docs: List<Doc>): JSONArray {
        val array = JSONArray()
        docs.forEach { doc ->
            array.put(JSONObject().apply {
                put("id", doc.id)
                put("title", doc.title)
                put("subtitle", doc.subtitle)
                if (!doc.uri.isNullOrBlank()) put("uri", doc.uri)
                put("text", doc.text)
            })
        }
        return array
    }

    fun decode(raw: String): List<Doc> {
        if (raw.isBlank()) return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val array = root.optJSONArray("docs") ?: return emptyList()
        return decodeArray(array)
    }

    fun decodeArray(array: JSONArray): List<Doc> {
        val seen = HashSet<Long>()
        val result = ArrayList<Doc>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val text = item.optString("text", "")
            val id = item.optLong("id", 0L).takeIf { it > 0L && it !in seen } ?: fallbackId(seen)
            seen.add(id)
            val title = item.optString("title", "").ifBlank { "未命名" }
            result.add(
                Doc(
                    id = id,
                    title = title,
                    subtitle = item.optString("subtitle", ""),
                    uri = item.optString("uri", "").takeIf { it.isNotBlank() },
                    text = text,
                )
            )
        }
        return result
    }

    /** 备份里的文档没有本地 id，恢复时重新分配，避免和现有文档撞号。 */
    fun reassign(docs: List<Doc>): List<Doc> {
        var seq = System.nanoTime()
        val used = HashSet<Long>()
        return docs.map { doc ->
            var id = ++seq
            while (!used.add(id)) id = ++seq
            doc.copy(id = id)
        }
    }

    private fun fallbackId(seen: Set<Long>): Long {
        var id = System.nanoTime()
        while (id in seen) id++
        return id
    }

    const val SCHEMA = 1
}

/** 文档列表的本地存储：一个 JSON 文件，读写都在调用方指定的线程上完成。 */
class DocumentStore(private val dir: File) {

    private val file = File(dir, "docs.json")

    fun load(): List<Doc> = runCatching {
        if (!file.exists()) return emptyList()
        DocCodec.decode(file.readText())
    }.getOrDefault(emptyList())

    fun save(docs: List<Doc>) {
        runCatching {
            dir.mkdirs()
            // 先写临时文件再改名，避免写到一半被杀进程留下半个 JSON
            val tmp = File(dir, "docs.json.tmp")
            tmp.writeText(DocCodec.encode(docs))
            if (!tmp.renameTo(file)) {
                file.writeText(DocCodec.encode(docs))
                tmp.delete()
            }
        }
    }
}
