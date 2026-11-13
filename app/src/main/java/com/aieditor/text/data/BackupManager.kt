package com.aieditor.text.data

import android.os.Build
import com.aieditor.text.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份调度：负责把「配置 + 当前文档」打包上传到 WebDAV，以及下载并合并回来。
 * 网络操作都在 IO 线程执行，异常统一抛 [WebDavException] 或 [IllegalStateException]。
 */
class BackupManager(
    private val prefs: AppPrefs,
    private val clientFactory: (BackupSettings) -> WebDavClient = { s ->
        WebDavClient(s.serverUrl, s.username, s.password)
    },
) {

    fun settings(): BackupSettings = prefs.loadBackupSettings()

    fun saveSettings(settings: BackupSettings) = prefs.saveBackupSettings(settings)

    fun isConfigured(): Boolean = settings().configured

    /** 到达自动备份时间了吗（用于启动时 / 应用结果后的检查）。 */
    fun isAutoDue(now: Long = System.currentTimeMillis()): Boolean {
        val s = settings()
        if (!s.autoBackup || !s.configured) return false
        if (s.lastBackupAt <= 0L) return true
        val interval = s.intervalHours.coerceAtLeast(1) * 3_600_000L
        return now - s.lastBackupAt >= interval
    }

    /** 立即备份，返回云端文件名。 */
    suspend fun backupNow(): String = withContext(Dispatchers.IO) {
        val s = settings()
        if (!s.configured) throw IllegalStateException("请先填写 WebDAV 地址")

        val dir = normalizeDir(s.directory)
        val payload = BackupPayload(
            createdAt = System.currentTimeMillis(),
            versionName = BuildConfig.VERSION_NAME,
            device = deviceName(),
            config = prefs.loadConfig(),
            draft = prefs.draft,
            chunked = prefs.chunked,
            chunkLimit = prefs.chunkLimit,
            apiKeys = prefs.allApiKeys(),
        )
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(payload.createdAt))
        val name = "aieditor-$stamp.json"

        return@withContext try {
            val client = clientFactory(s)
            client.ensureDirectory(dir)
            client.put("$dir/$name", payload.toJson().toByteArray(Charsets.UTF_8))
            saveSettings(
                s.copy(
                    lastBackupAt = payload.createdAt,
                    lastStatus = "上次备份成功（$name）",
                )
            )
            name
        } catch (e: Exception) {
            saveSettings(
                s.copy(lastStatus = "上次备份失败：${e.message ?: e.javaClass.simpleName}")
            )
            throw e
        }
    }

    /** 列出云端的备份文件，最新的排在最前。 */
    suspend fun listBackups(): List<RemoteFile> = withContext(Dispatchers.IO) {
        val s = settings()
        if (!s.configured) throw IllegalStateException("请先填写 WebDAV 地址")
        clientFactory(s).list(normalizeDir(s.directory))
            .filter { it.name.endsWith(".json", ignoreCase = true) }
            .sortedByDescending { it.name }
    }

    /** 下载指定备份并按 [mode] 合并。 */
    suspend fun restore(file: RemoteFile, mode: RestoreMode): MergeResult =
        withContext(Dispatchers.IO) {
            val s = settings()
            val bytes = clientFactory(s).get(file.href)
            val payload = BackupPayload.fromJson(bytes.toString(Charsets.UTF_8))
            when (mode) {
                RestoreMode.OVERWRITE -> BackupMerge.overwrite(payload)
                RestoreMode.MERGE -> BackupMerge.merge(prefs.loadConfig(), prefs.draft, payload)
            }
        }

    private fun normalizeDir(raw: String): String {
        var dir = raw.trim().ifBlank { BackupSettings.DEFAULT_DIR }
        if (!dir.startsWith("/")) dir = "/$dir"
        return dir.trimEnd('/').ifBlank { "/" }
    }

    private fun deviceName(): String {
        val maker = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: ""
        val model = Build.MODEL ?: ""
        return listOf(maker, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Android" }
    }
}
