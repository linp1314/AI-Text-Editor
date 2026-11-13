package com.aieditor.text.data

/** WebDAV 备份设置。 */
data class BackupSettings(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    /** WebDAV 上的子目录，例如 /AiTextEditor。 */
    val directory: String = DEFAULT_DIR,
    /** 是否开启自动备份。 */
    val autoBackup: Boolean = false,
    /** 自动备份间隔（小时）。 */
    val intervalHours: Int = 24,
    /** 上次成功备份的时间戳（毫秒），0 表示从未备份。 */
    val lastBackupAt: Long = 0L,
    /** 上次备份的结果说明，成功或失败原因。 */
    val lastStatus: String = "",
) {
    /** 是否已经填好可用的连接信息。 */
    val configured: Boolean get() = serverUrl.isNotBlank()

    companion object {
        const val DEFAULT_DIR = "/AiTextEditor"

        /** 可选的自动备份间隔。 */
        val INTERVALS = listOf(12 to "12 小时", 24 to "每天", 168 to "每周")
    }
}
