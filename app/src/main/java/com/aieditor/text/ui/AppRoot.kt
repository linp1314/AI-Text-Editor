package com.aieditor.text.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aieditor.text.AppViewModel
import kotlinx.coroutines.launch

private val IMPORT_MIME_TYPES = arrayOf(
    "text/*",
    "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/octet-stream",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(sharedText: String? = null, vm: AppViewModel = viewModel()) {
    val editor by vm.editor.collectAsState()
    val ai by vm.ai.collectAsState()
    val config by vm.config.collectAsState()
    val backup by vm.backup.collectAsState()
    val historyLimit by vm.historyLimit.collectAsState()

    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showPanel by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val importing by vm.importing.collectAsState()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val outcome = vm.importDocument(context, uri)
                if (outcome.success) {
                    val result = snackbarHost.showSnackbar(
                        message = outcome.message,
                        actionLabel = "撤销",
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) vm.undo()
                } else {
                    snackbarHost.showSnackbar(outcome.message)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbarHost.showSnackbar(it) }
    }

    // 启动时检查一次自动备份是否到点
    LaunchedEffect(Unit) { vm.maybeAutoBackup() }

    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) {
            vm.setEditorText(sharedText)
            showPanel = true
        }
    }

    val text = editor.text
    val selectedCount = kotlin.math.abs(editor.selection.end - editor.selection.start)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI 文本编辑", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (config.apiKey.isBlank()) "未配置密钥 · 点右上角设置" else "${config.model} · 已连接",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { importLauncher.launch(IMPORT_MIME_TYPES) },
                        enabled = !importing,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                    ) {
                        if (importing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text("导入", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(onClick = { vm.clearEditor() }, enabled = text.isNotEmpty()) {
                        Icon(Icons.Filled.Delete, contentDescription = "清空", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "配置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("${text.length} 字 · ${text.count { it == '\n' } + if (text.isEmpty()) 0 else 1} 行", fontSize = 12.sp)
                        Text(
                            text = if (selectedCount > 0) "已选中 $selectedCount 字" else "长按可选文本",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { showPanel = true },
                        enabled = text.isNotBlank() || ai.output.isNotBlank(),
                        modifier = Modifier.height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandGreen),
                    ) {
                        Text("AI 编辑", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 18.dp),
        ) {
            if (text.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("空白文档", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            val clipboardText = clipboard.getText()?.text
                            if (!clipboardText.isNullOrBlank()) vm.setEditorText(clipboardText)
                            else scope.launch { snackbarHost.showSnackbar("剪贴板是空的") }
                        },
                        modifier = Modifier.height(32.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("粘贴剪贴板", fontSize = 12.sp)
                    }
                }
            }
            BasicTextField(
                value = editor,
                onValueChange = vm::onEditorChange,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 6.dp),
                textStyle = TextStyle(
                    fontSize = 16.sp,
                    lineHeight = 26.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize()) {
                        if (text.isEmpty()) {
                            Text(
                                "在这里输入或粘贴需要编辑的文本…\n\n写好后点右下角「AI 编辑」，用一句话告诉 AI 你要改什么。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                fontSize = 15.sp,
                                lineHeight = 24.sp,
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }

    if (showPanel) {
        AiPanelDialog(
            vm = vm,
            state = ai,
            config = config,
            onClose = { showPanel = false },
            onApply = {
                if (vm.applyResult()) {
                    showPanel = false
                    scope.launch {
                        val result = snackbarHost.showSnackbar(
                            message = "已应用到编辑区",
                            actionLabel = "撤销",
                            duration = SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed) vm.undo()
                    }
                }
            },
            onOpenSettings = { showSettings = true },
        )
    }

    if (showSettings) {
        // 以配置为 key：备份恢复等外部改动后重建，避免继续显示打开时的旧值（尤其是密钥）
        key(config) {
            SettingsDialog(
                initial = config,
                onDismiss = { showSettings = false },
                onSave = { updated, keys ->
                    vm.saveConfig(updated, keys)
                    showSettings = false
                    scope.launch { snackbarHost.showSnackbar("配置已保存") }
                },
                onTest = { candidate -> vm.testConnection(candidate) },
                onOpenBackup = { showBackup = true },
                onOpenAbout = { showAbout = true },
                lookupKey = { url -> vm.apiKeyFor(url) },
                onFetchModels = { candidate -> vm.listModels(candidate) },
                historyLimit = historyLimit,
                onHistoryLimitChange = vm::setHistoryLimit,
            )
        }
    }

    if (showBackup) {
        BackupDialog(
            initial = backup,
            onDismiss = { showBackup = false },
            onPersist = vm::saveBackupSettings,
            onBackup = { vm.backupNow() },
            onList = { vm.listBackups() },
            onRestore = { file, mode -> vm.restoreBackup(file, mode) },
        )
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

