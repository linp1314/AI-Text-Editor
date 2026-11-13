package com.aieditor.text.data

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.concurrent.TimeUnit

/** WebDAV 操作失败，message 直接展示给用户。 */
class WebDavException(message: String) : Exception(message)

/** 远端文件条目。 */
data class RemoteFile(
    val name: String,
    /** 服务器返回的原始路径（可能是绝对 URL 或 /dav/xx.json）。 */
    val href: String,
    val size: Long,
    val modified: String,
)

/**
 * 极简 WebDAV 客户端：只用到 MKCOL / PROPFIND / PUT / GET / DELETE，
 * 够备份一个 JSON 文件用，不引入额外依赖。
 */
class WebDavClient(
    serverUrl: String,
    private val username: String,
    private val password: String,
    private val client: OkHttpClient = defaultClient(),
) {
    private val root = serverUrl.trim().trimEnd('/')

    private fun url(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val p = if (path.startsWith("/")) path else "/$path"
        return root + p
    }

    private fun Request.Builder.withAuth(): Request.Builder = apply {
        if (username.isNotEmpty() || password.isNotEmpty()) {
            header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
        }
    }

    /** 创建目录；已存在（405）视为成功。 */
    fun ensureDirectory(directory: String) {
        val res = client.newCall(
            Request.Builder().url(url(directory)).method("MKCOL", null).withAuth().build()
        ).execute()
        res.use {
            when {
                it.code in 200..299 -> Unit
                it.code == 405 -> Unit
                it.code == 401 -> throw WebDavException("目录创建失败：认证失败（401），请检查用户名和密码")
                it.code == 409 -> throw WebDavException("目录创建失败：上级目录不存在（409）")
                else -> throw WebDavException("目录创建失败：HTTP ${it.code}")
            }
        }
    }

    fun put(path: String, bytes: ByteArray) {
        val body = bytes.toRequestBody("application/json; charset=utf-8".toMediaType())
        val res = client.newCall(
            Request.Builder().url(url(path)).put(body).withAuth().build()
        ).execute()
        res.use {
            when {
                it.isSuccessful -> Unit
                it.code == 401 -> throw WebDavException("上传失败：认证失败（401）")
                it.code == 403 -> throw WebDavException("上传失败：没有写入权限（403）")
                it.code == 507 -> throw WebDavException("上传失败：服务器空间不足（507）")
                else -> throw WebDavException("上传失败：HTTP ${it.code}")
            }
        }
    }

    fun get(hrefOrPath: String): ByteArray {
        val res = client.newCall(
            Request.Builder().url(url(hrefOrPath)).get().withAuth().build()
        ).execute()
        res.use {
            if (!it.isSuccessful) throw WebDavException("下载失败：HTTP ${it.code}")
            return it.body?.bytes() ?: throw WebDavException("下载失败：响应为空")
        }
    }

    fun delete(hrefOrPath: String) {
        val res = client.newCall(
            Request.Builder().url(url(hrefOrPath)).delete().withAuth().build()
        ).execute()
        res.use {
            if (!it.isSuccessful && it.code != 404) throw WebDavException("删除失败：HTTP ${it.code}")
        }
    }

    /** 列出目录下的文件（不含子目录）。 */
    fun list(directory: String): List<RemoteFile> {
        val dirPath = if (directory.endsWith("/")) directory else "$directory/"
        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val res = client.newCall(
            Request.Builder()
                .url(url(dirPath))
                .method("PROPFIND", body)
                .header("Depth", "1")
                .withAuth()
                .build()
        ).execute()
        res.use {
            when {
                it.code == 401 -> throw WebDavException("认证失败（401），请检查用户名和密码")
                it.code == 404 -> throw WebDavException("目录不存在（404），请先执行一次备份")
                it.code != 207 && !it.isSuccessful -> throw WebDavException("列目录失败：HTTP ${it.code}")
            }
            return parseMultiStatus(it.body?.string().orEmpty())
        }
    }

    companion object {
        private const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
                "<d:getcontentlength/><d:getlastmodified/><d:resourcetype/>" +
                "</d:prop></d:propfind>"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /**
         * 解析 207 Multi-Status。命名空间前缀不固定（d: / D: / 无前缀），
         * 所以统一按「去掉前缀后的本地名」比较。
         */
        fun parseMultiStatus(xml: String): List<RemoteFile> {
            if (xml.isBlank()) return emptyList()
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xml))

            val files = mutableListOf<RemoteFile>()
            var href: String? = null
            var size = 0L
            var modified = ""
            var isDir = false
            var inResponse = false

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                val tag = parser.name?.substringAfter(':')?.lowercase()
                when (event) {
                    XmlPullParser.START_TAG -> when (tag) {
                        "response" -> {
                            inResponse = true
                            href = null
                            size = 0L
                            modified = ""
                            isDir = false
                        }
                        "href" -> if (inResponse) href = parser.nextText().trim()
                        "getcontentlength" -> if (inResponse) {
                            size = parser.nextText().trim().toLongOrNull() ?: 0L
                        }
                        "getlastmodified" -> if (inResponse) modified = parser.nextText().trim()
                        "collection" -> if (inResponse) isDir = true
                    }
                    XmlPullParser.END_TAG -> if (tag == "response") {
                        inResponse = false
                        val raw = href
                        if (raw != null && !isDir) {
                            val decoded = decodeHref(raw)
                            val name = decoded.trimEnd('/').substringAfterLast('/')
                            if (name.isNotEmpty()) files.add(RemoteFile(name, decoded, size, modified))
                        }
                    }
                }
                event = parser.next()
            }
            return files
        }

        /** 只处理 %XX 转义，不把 + 当作空格（文件名里可能真的有加号）。 */
        fun decodeHref(href: String): String {
            if ('%' !in href) return href
            val out = ByteArrayOutputStream()
            var i = 0
            while (i < href.length) {
                val c = href[i]
                if (c == '%' && i + 2 < href.length) {
                    val hex = href.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        out.write(hex)
                        i += 3
                        continue
                    }
                }
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return out.toString("UTF-8")
        }
    }
}
