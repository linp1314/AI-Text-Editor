package com.aieditor.text.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
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
    var showMenu by remember { mutableStateOf(false) }
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
                        Text("AI 文本编辑", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (config.apiKey.isBlank()) {
                                "未配置密钥 · 右上角菜单里设置"
                            } else {
                                "${config.model} · 已连接"
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    // 清空、设置、备份、关于收进一个菜单，顶栏只留一个入口
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "更多",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            shape = RoundedCornerShape(Ui.radius),
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            tonalElevation = 3.dp,
                        ) {
                            MenuItem("清空文档", enabled = text.isNotEmpty()) {
                                showMenu = false
                                vm.clearEditor()
                            }
                            MenuItem("接口与模型设置") {
                                showMenu = false
                                showSettings = true
                            }
                            MenuItem("WebDAV 备份") {
                                showMenu = false
                                showBackup = true
                            }
                            MenuItem("关于与更新记录") {
                                showMenu = false
                                showAbout = true
                            }
                        }
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
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${text.length} 字 · ${text.count { it == '\n' } + if (text.isEmpty()) 0 else 1} 行",
                            fontSize = 12.sp,
                        )
                        Text(
                            text = if (selectedCount > 0) "已选中 $selectedCount 字" else "长按可选文本",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    OutlineButton(
                        text = if (importing) "导入中" else "导入",
                        modifier = Modifier.width(76.dp),
                        enabled = !importing,
                        height = 42.dp,
                    ) { importLauncher.launch(IMPORT_MIME_TYPES) }
                    if (importing) {
                        Spacer(Modifier.width(6.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = BrandGreen,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    PrimaryButton(
                        text = "AI 编辑",
                        modifier = Modifier.width(100.dp),
                        height = 42.dp,
                        // 空白文档也能打开：面板会自动进入「从零生成」模式
                        onClick = { showPanel = true },
                    )
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
                                "在这里输入或粘贴需要编辑的文本…\n\n" +
                                    "写好后点右下角「AI 编辑」，用一句话告诉 AI 你要改什么。\n" +
                                    "也可以什么都不写，直接描述要求让它从零生成。",
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

@Composable
private fun MenuItem(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                fontSize = 14.sp,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            )
        },
        enabled = enabled,
        onClick = onClick,
    )
}
