package com.aieditor.text.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aieditor.text.data.BackupSettings
import com.aieditor.text.data.RemoteFile
import com.aieditor.text.data.RestoreMode
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WebDAV 备份页：连接配置 + 立即备份 / 恢复 + 自动备份间隔。
 * 所有网络操作都通过回调用 ViewModel 完成，页面只负责收集参数与展示结果。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupDialog(
    initial: BackupSettings,
    onDismiss: () -> Unit,
    onPersist: (BackupSettings) -> Unit,
    onBackup: suspend () -> String,
    onList: suspend () -> List<RemoteFile>,
    onRestore: suspend (RemoteFile, RestoreMode) -> String,
) {
    var serverUrl by remember { mutableStateOf(initial.serverUrl) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var directory by remember { mutableStateOf(initial.directory) }
    var autoBackup by remember { mutableStateOf(initial.autoBackup) }
    var interval by remember { mutableStateOf(initial.intervalHours) }
    var showPassword by remember { mutableStateOf(false) }
    var lastStatus by remember { mutableStateOf(initial.lastStatus) }
    var lastBackupAt by remember { mutableStateOf(initial.lastBackupAt) }

    var busy by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf<List<RemoteFile>?>(null) }
    var pickingMode by remember { mutableStateOf<RemoteFile?>(null) }

    val scope = rememberCoroutineScope()

    fun collect(): BackupSettings = initial.copy(
        serverUrl = serverUrl.trim(),
        username = username.trim(),
        password = password,
        directory = directory.trim().ifBlank { BackupSettings.DEFAULT_DIR },
        autoBackup = autoBackup,
        intervalHours = interval,
        lastBackupAt = lastBackupAt,
        lastStatus = lastStatus,
    )

    fun persist() = onPersist(collect())

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("WebDAV 备份", fontSize = 19.sp, fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp),
                ) {
                    Text(
                        "把软件配置和当前文档备份到自己的 WebDAV（群晖、坚果云、Nextcloud、Alist 等均可），" +
                            "换机或重装后可以直接恢复。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(14.dp))
                    DavField(
                        label = "服务器地址",
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        placeholder = "https://dav.example.com/dav",
                        supporting = "填到 WebDAV 根目录即可，例如坚果云 https://dav.jianguoyun.com/dav",
                        keyboardType = KeyboardType.Uri,
                    )
                    Spacer(Modifier.height(10.dp))
                    DavField(
                        label = "用户名",
                        value = username,
                        onValueChange = { username = it },
                        placeholder = "账号或邮箱",
                    )
                    Spacer(Modifier.height(10.dp))
                    DavField(
                        label = "密码 / 应用密码",
                        value = password,
                        onValueChange = { password = it },
                        placeholder = "WebDAV 密码",
                        visualTransformation = if (showPassword) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailing = {
                            TextButton(onClick = { showPassword = !showPassword }) {
                                Text(if (showPassword) "隐藏" else "显示", fontSize = 12.sp)
                            }
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    DavField(
                        label = "远程目录",
                        value = directory,
                        onValueChange = { directory = it },
                        placeholder = BackupSettings.DEFAULT_DIR,
                        supporting = "备份文件会以 aieditor-时间.json 命名存放在这个目录",
                    )

                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                persist()
                                busy = true
                                lastStatus = "正在备份…"
                                scope.launch {
                                    lastStatus = try {
                                        val name = onBackup()
                                        lastBackupAt = System.currentTimeMillis()
                                        "备份成功：$name"
                                    } catch (e: Exception) {
                                        "备份失败：${e.message ?: e.javaClass.simpleName}"
                                    }
                                    busy = false
                                }
                            },
                            enabled = !busy && serverUrl.isNotBlank(),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(44.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandGreen),
                        ) {
                            if (busy) {
                                CircularProgressIndicator(
                                    Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = androidx.compose.ui.graphics.Color.White,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (busy) "处理中…" else "立即备份", fontSize = 14.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        OutlinedButton(
                            onClick = {
                                persist()
                                busy = true
                                lastStatus = "正在读取云端备份…"
                                scope.launch {
                                    try {
                                        files = onList()
                                        lastStatus = if (files.isNullOrEmpty()) {
                                            "云端还没有备份文件"
                                        } else {
                                            "共 ${files?.size} 个备份，选择要恢复的文件"
                                        }
                                    } catch (e: Exception) {
                                        lastStatus = "读取失败：${e.message ?: e.javaClass.simpleName}"
                                    }
                                    busy = false
                                }
                            },
                            enabled = !busy && serverUrl.isNotBlank(),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(44.dp),
                        ) {
                            Text("恢复备份", fontSize = 14.sp)
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        lastStatus,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = when {
                            lastStatus.startsWith("备份失败") || lastStatus.startsWith("读取失败") ->
                                MaterialTheme.colorScheme.error
                            lastStatus.startsWith("备份成功") -> BrandGreen
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (lastBackupAt > 0) {
                        Text(
                            "上次备份：${formatTime(lastBackupAt)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(14.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("自动备份", fontSize = 14.sp)
                            Text(
                                "距离上次备份超过设定间隔时，在打开应用或应用 AI 结果后自动备份一次",
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Switch(checked = autoBackup, onCheckedChange = { autoBackup = it })
                    }

                    if (autoBackup) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BackupSettings.INTERVALS.forEach { (hours, label) ->
                                FilterChip(
                                    selected = interval == hours,
                                    onClick = { interval = hours },
                                    label = { Text(label, fontSize = 12.sp) },
                                    shape = RoundedCornerShape(16.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        "说明",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(4.dp))
                    listOf(
                        "备份是明文 JSON，内含 API Key 与文档内容，请使用自己可控的 WebDAV 空间。",
                        "「覆盖」会用备份内容替换本机全部配置与文档。",
                        "「增量」只填补本机空缺项（如未填的密钥、空文档），不覆盖已有设置。",
                    ).forEach {
                        Text(
                            "· $it",
                            fontSize = 11.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }

                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                onPersist(collect())
                                onDismiss()
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(46.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandGreen),
                        ) {
                            Text("保存", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }

    // 云端备份列表
    files?.let { list ->
        AlertDialog(
            onDismissRequest = { files = null },
            title = { Text("选择要恢复的备份") },
            text = {
                if (list.isEmpty()) {
                    Text("这个目录下还没有备份文件", fontSize = 13.sp)
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        list.forEach { file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        files = null
                                        pickingMode = file
                                    }
                                    .padding(vertical = 9.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(file.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${formatSize(file.size)} · ${formatRemoteTime(file.modified)}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                Text("选择", fontSize = 12.sp, color = BrandGreen)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { files = null }) { Text("关闭") }
            },
        )
    }

    // 覆盖 / 增量
    pickingMode?.let { file ->
        AlertDialog(
            onDismissRequest = { pickingMode = null },
            title = { Text("恢复方式") },
            text = {
                Column {
                    Text(file.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Text("覆盖：用备份替换本机配置与文档。", fontSize = 13.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "增量：只补充本机空缺的配置项，本地已有文档不会被覆盖。",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pickingMode = null
                    busy = true
                    lastStatus = "正在恢复…"
                    scope.launch {
                        lastStatus = try {
                            onRestore(file, RestoreMode.MERGE)
                        } catch (e: Exception) {
                            "恢复失败：${e.message ?: e.javaClass.simpleName}"
                        }
                        busy = false
                    }
                }) { Text("增量恢复", color = BrandGreen) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pickingMode = null
                    busy = true
                    lastStatus = "正在恢复…"
                    scope.launch {
                        lastStatus = try {
                            onRestore(file, RestoreMode.OVERWRITE)
                        } catch (e: Exception) {
                            "恢复失败：${e.message ?: e.javaClass.simpleName}"
                        }
                        busy = false
                    }
                }) { Text("覆盖恢复", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

@Composable
private fun DavField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 13.sp) },
        placeholder = { Text(placeholder, fontSize = 13.sp) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        supportingText = supporting?.let {
            { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline) }
        },
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

/** WebDAV 的 getlastmodified 是 RFC1123，转成本地时间显示。 */
private fun formatRemoteTime(raw: String): String {
    if (raw.isBlank()) return "时间未知"
    return try {
        val parser = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH)
        val date = parser.parse(raw.trim()) ?: return raw
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(date)
    } catch (e: Exception) {
        raw
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "大小未知"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}
