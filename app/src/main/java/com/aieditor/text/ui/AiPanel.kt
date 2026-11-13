package com.aieditor.text.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aieditor.text.AiState
import com.aieditor.text.AppViewModel
import com.aieditor.text.Scope
import com.aieditor.text.data.ApiConfig
import com.aieditor.text.data.TextChunker
import com.aieditor.text.data.TextDiff
import kotlinx.coroutines.launch

/**
 * AI 文本编辑面板：布局对齐参考截图——
 * 指令输入 → 范围下拉 + 发送 → 状态 → 分段控制 → 输出预览 → 二次编辑 → 复制 / 导出 / 关闭 / 应用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiPanelDialog(
    vm: AppViewModel,
    state: AiState,
    config: ApiConfig,
    onClose: () -> Unit,
    onApply: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val coroutine = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    var showExport by remember { mutableStateOf(false) }
    var showRefine by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val history by vm.history.collectAsState()
    val historyLimit by vm.historyLimit.collectAsState()
    val displayText = state.output
    // 当前范围若开启分段，预计会切成几段
    val editorState by vm.editor.collectAsState()
    // 编辑区为空：进入「从零生成」模式，指令即创作要求
    val emptyDoc = editorState.text.isBlank()
    val chunkCount = remember(editorState.text, state.scope, state.chunked, state.chunkLimit) {
        vm.estimateChunks()
    }

    // 生成结束后再算差异，避免流式输出期间反复做 LCS
    val diff = remember(displayText, state.sourceText, state.running) {
        val source = state.sourceText
        if (!state.running &&
            source.isNotBlank() && displayText.isNotBlank() && source != displayText
        ) {
            TextDiff.compare(source, displayText)
        } else {
            null
        }
    }
    var showDiff by remember { mutableStateOf(false) }
    val hasDiff = diff?.hasChanges == true
    LaunchedEffect(hasDiff) { if (!hasDiff) showDiff = false }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            val ok = vm.writeToUri(context, uri, displayText)
            coroutine.launch { snackbarHost.showSnackbar(if (ok) "已保存到文件" else "保存失败") }
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.94f),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 16.dp,
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {

                    // ---------------- 标题
                    Row(
                        modifier = Modifier.padding(start = 18.dp, end = 8.dp, top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("AI 文本编辑", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "模型 ${config.model.ifBlank { "未设置" }} · ${config.baseUrl}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(
                            onClick = { showHistory = true },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Text(
                                if (history.isEmpty()) "历史" else "历史 ${history.size}",
                                fontSize = 13.sp,
                                color = if (historyLimit <= 0) MaterialTheme.colorScheme.outline else BrandGreen,
                            )
                        }
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "关闭",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // ---------------- 可滚动的主体
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 18.dp),
                    ) {
                        OutlinedTextField(
                            value = state.instruction,
                            onValueChange = vm::setInstruction,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    if (emptyDoc) {
                                        "想生成什么？例如：写一封婉拒加班的邮件，200 字左右，语气客气"
                                    } else {
                                        "让 AI 做什么？例如：给这段代码加注释 / 翻译成英文"
                                    },
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            },
                            textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
                            minLines = 2,
                            maxLines = 3,
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )

                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val chips = if (emptyDoc) {
                                listOf("写一段自我介绍", "写一封工作邮件", "写一篇短文", "列一份清单", "写产品文案", "翻译成英文")
                            } else {
                                listOf("润色", "精简", "纠错", "翻译成英文", "加注释", "扩写", "改正式语气")
                            }
                            chips.forEach { label ->
                                AssistChip(
                                    onClick = { vm.setInstruction(label) },
                                    label = { Text(label, fontSize = 12.sp) },
                                    shape = RoundedCornerShape(16.dp),
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (emptyDoc) {
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = BrandGreen.copy(alpha = 0.10f),
                                    border = BorderStroke(1.dp, BrandGreen.copy(alpha = 0.35f)),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Text(
                                            "从零生成",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = BrandGreen,
                                        )
                                        Text(
                                            "编辑区为空，写清要求即可生成",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else {
                                ScopeSelector(
                                    scope = state.scope,
                                    onSelect = vm::setScope,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Button(
                                onClick = { vm.send() },
                                enabled = !state.running && state.instruction.isNotBlank(),
                                modifier = Modifier
                                    .width(126.dp)
                                    .height(52.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BrandGreen,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Text(
                                    if (emptyDoc) "生成" else "发送",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        // 状态 + 停止 / 配置（合并成一行，给分段控件腾出空间）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.running) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 2.dp,
                                    color = BrandGreen,
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = state.status,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = vm::stop,
                                enabled = state.running,
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp),
                            ) { Text("停止", fontSize = 13.sp) }
                            TextButton(
                                onClick = onOpenSettings,
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp),
                            ) { Text("配置", fontSize = 13.sp) }
                        }

                        // 长文分段控制（生成模式没有原文，不需要分段）
                        if (!emptyDoc) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AssistChip(
                                    onClick = { vm.setChunked(!state.chunked) },
                                    label = {
                                        Text(
                                            if (state.chunked) "长文分段 · 开" else "长文分段 · 关",
                                            fontSize = 12.sp,
                                        )
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = if (state.chunked) BrandGreen.copy(alpha = 0.14f) else Color.Transparent,
                                        labelColor = if (state.chunked) BrandGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                                if (state.chunked) {
                                    ChunkLimitPicker(
                                        limit = state.chunkLimit,
                                        onSelect = vm::setChunkLimit,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        text = when {
                                            chunkCount > 1 -> "将分 $chunkCount 段"
                                            chunkCount == 1 -> "单段发送"
                                            else -> ""
                                        },
                                        fontSize = 11.sp,
                                        color = if (chunkCount > 1) BrandGreen else MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("AI 输出预览", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (hasDiff && diff != null) {
                                Spacer(Modifier.width(10.dp))
                                DiffToggle(
                                    showDiff = showDiff,
                                    changes = diff.changedLines,
                                    onSelect = { showDiff = it },
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (showDiff && diff != null) {
                                Text("+${diff.insertedChars}", fontSize = 11.sp, color = DiffAddFg)
                                Spacer(Modifier.width(8.dp))
                                Text("−${diff.deletedChars}", fontSize = 11.sp, color = DiffDelFg)
                            } else if (displayText.isNotEmpty()) {
                                Text(
                                    "${displayText.length} 字",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        val previewScroll = rememberScrollState()
                        LaunchedEffect(displayText) {
                            previewScroll.scrollTo(previewScroll.maxValue)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .heightIn(min = 110.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                                .padding(12.dp),
                        ) {
                            if (displayText.isEmpty()) {
                                Text(
                                    text = when {
                                        state.running -> "等待模型返回…"
                                        emptyDoc -> "写好要求后点「生成」，结果会出现在这里，可直接写入编辑区。"
                                        else -> "AI 结果会显示在这里，可长按选择、复制或直接应用到编辑区。"
                                    },
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            } else if (showDiff && diff != null) {
                                DiffPane(diff = diff)
                            } else {
                                SelectionContainer {
                                    Text(
                                        text = displayText,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(previewScroll),
                                        fontSize = 14.sp,
                                        lineHeight = 22.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                    }

                    // ---------------- 固定在底部的操作区
                    Column(modifier = Modifier.padding(horizontal = 18.dp)) {
                        // ---------------- 对结果做二次 AI 编辑
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            TextButton(
                                onClick = vm::undoRefine,
                                enabled = state.canUndoRefine,
                                modifier = Modifier
                                    .width(40.dp)
                                    .height(46.dp),
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text(
                                    "↺",
                                    fontSize = 19.sp,
                                    color = if (state.canUndoRefine) BrandGreen else MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                            RefineEntry(
                                text = state.refineInstruction,
                                placeholder = if (displayText.isBlank()) {
                                    "先生成结果，再继续修改"
                                } else {
                                    state.refineInstruction.ifBlank { "继续改这段结果，如：再精简一半" }
                                },
                                enabled = !state.running && displayText.isNotBlank(),
                                onClick = { showRefine = true },
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = { showRefine = true },
                                enabled = !state.running && displayText.isNotBlank(),
                                modifier = Modifier
                                    .width(68.dp)
                                    .height(46.dp),
                                shape = RoundedCornerShape(9.dp),
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BrandGreen,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Text("再改", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        Row(Modifier.fillMaxWidth()) {
                            PanelAction(
                                text = "复制",
                                enable = displayText.isNotBlank(),
                                modifier = Modifier.weight(1f),
                            ) {
                                clipboard.setText(AnnotatedString(displayText))
                                coroutine.launch { snackbarHost.showSnackbar("已复制到剪贴板") }
                            }
                            PanelAction(
                                text = "导出",
                                enable = displayText.isNotBlank(),
                                modifier = Modifier.weight(1f),
                            ) { showExport = true }
                            PanelAction(text = "关闭", modifier = Modifier.weight(1f), onClick = onClose)
                            PanelAction(
                                text = if (emptyDoc) "写入" else "应用",
                                enable = displayText.isNotBlank() && !state.running,
                                highlight = true,
                                modifier = Modifier.weight(1f),
                                onClick = onApply,
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                }

                SnackbarHost(
                    hostState = snackbarHost,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 74.dp),
                )
            }
        }
    }

    if (showRefine && displayText.isNotBlank()) {
        RefineDialog(
            revision = state.revision,
            initial = state.refineInstruction,
            onDismiss = { showRefine = false },
            onConfirm = { instruction ->
                showRefine = false
                vm.setRefineInstruction(instruction)
                vm.refine(instruction)
            },
        )
    }

    if (showHistory) {
        HistoryDialog(
            entries = history,
            limit = historyLimit,
            onLoad = { entry ->
                showHistory = false
                vm.loadHistory(entry)
                coroutine.launch { snackbarHost.showSnackbar("已载入历史结果") }
            },
            onDelete = vm::deleteHistory,
            onClear = vm::clearHistory,
            onOpenSettings = {
                showHistory = false
                onOpenSettings()
            },
            onDismiss = { showHistory = false },
        )
    }

    if (showExport && displayText.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("导出 AI 结果") },
            text = { Text("保存为 txt 文件，或通过其他 App 分享。") },
            confirmButton = {
                TextButton(onClick = {
                    showExport = false
                    saveLauncher.launch(vm.exportFileName())
                }) { Text("保存为 TXT") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExport = false
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, displayText)
                    }
                    context.startActivity(Intent.createChooser(send, "分享 AI 结果"))
                }) { Text("分享") }
            },
        )
    }
}

@Composable
private fun ChunkLimitPicker(
    limit: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("每段 $limit 字", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text("▼", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TextChunker.LIMIT_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text("每段 $option 字", fontSize = 14.sp) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ScopeSelector(
    scope: Scope,
    onSelect: (Scope) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(scope.label, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text("▼", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Scope.values().forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun PanelAction(
    text: String,
    modifier: Modifier = Modifier,
    enable: Boolean = true,
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enable,
        modifier = modifier.height(44.dp),
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                !enable -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                highlight -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/** 底部「继续修改」入口：只展示当前指令，点击后弹窗输入，避免键盘遮挡预览。 */
@Composable
private fun RefineEntry(
    text: String,
    placeholder: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(9.dp))
            .border(
                1.dp,
                if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(9.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = placeholder,
            fontSize = 13.sp,
            color = if (text.isBlank()) {
                MaterialTheme.colorScheme.outline
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("✎", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
    }
}

/** 二次编辑指令弹窗：独立窗口，键盘只在弹窗内弹出，不影响主面板。 */
@Composable
private fun RefineDialog(
    revision: Int,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val outline = MaterialTheme.colorScheme.outline

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("继续修改（第 $revision 版）", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "在第 $revision 版结果的基础上继续改，例如「再精简一半」「改成给客户的邮件」",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = outline,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                    placeholder = { Text("想让 AI 怎么改？", fontSize = 14.sp, color = outline) },
                    textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedBorderColor = BrandGreen,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("再精简一半", "更正式", "更口语", "扩写细节", "统一语气").forEach { label ->
                        AssistChip(
                            onClick = { text = label },
                            label = { Text(label, fontSize = 12.sp) },
                            shape = RoundedCornerShape(16.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
            ) { Text("开始修改", color = if (text.isNotBlank()) BrandGreen else outline) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
    )

    LaunchedEffect(Unit) { focus.requestFocus() }
}

// ------------------------------------------------------------------ 改动对比

private val DiffAddBg = Color(0xFF1B7F4B).copy(alpha = 0.16f)
private val DiffAddFg = Color(0xFF136B3E)
private val DiffDelBg = Color(0xFFB3261E).copy(alpha = 0.12f)
private val DiffDelFg = Color(0xFF9A1B14)
private val DiffModFg = Color(0xFF8A6D3B)

@Composable
private fun DiffToggle(
    showDiff: Boolean,
    changes: Int,
    onSelect: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToggleCell("结果", !showDiff) { onSelect(false) }
        ToggleCell("改动 $changes", showDiff) { onSelect(true) }
    }
}

@Composable
private fun ToggleCell(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) BrandGreen else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DiffPane(diff: TextDiff.Result) {
    Column(Modifier.fillMaxSize()) {
        DiffLines(diff)
    }
}

private sealed interface DiffRow {
    data class One(val line: TextDiff.Line) : DiffRow
    data class Gap(val start: Int, val count: Int) : DiffRow
}

@Composable
private fun DiffLines(diff: TextDiff.Result) {
    val rows = remember(diff) {
        val out = ArrayList<DiffRow>()
        var i = 0
        while (i < diff.lines.size) {
            if (diff.lines[i].kind == TextDiff.Kind.EQUAL) {
                var j = i
                while (j < diff.lines.size && diff.lines[j].kind == TextDiff.Kind.EQUAL) j++
                if (j - i > 4) {
                    out.add(DiffRow.Gap(i, j - i))
                } else {
                    for (k in i until j) out.add(DiffRow.One(diff.lines[k]))
                }
                i = j
            } else {
                out.add(DiffRow.One(diff.lines[i]))
                i++
            }
        }
        out
    }
    val expanded = remember(diff) { mutableStateMapOf<Int, Boolean>() }

    LazyColumn(Modifier.fillMaxSize()) {
        rows.forEach { row ->
            when (row) {
                is DiffRow.Gap -> {
                    if (expanded[row.start] == true) {
                        items(row.count) { offset ->
                            DiffLineRow(diff.lines[row.start + offset])
                        }
                    } else {
                        item(key = "gap-${row.start}") {
                            GapRow(row.count) { expanded[row.start] = true }
                        }
                    }
                }
                is DiffRow.One -> item { DiffLineRow(row.line) }
            }
        }
    }
}

@Composable
private fun GapRow(count: Int, onExpand: () -> Unit) {
    Text(
        text = "⋯ $count 行未改动（点按展开）",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onExpand)
            .padding(vertical = 3.dp),
    )
}

@Composable
private fun DiffLineRow(line: TextDiff.Line) {
    val sign = when (line.kind) {
        TextDiff.Kind.INSERT -> "+"
        TextDiff.Kind.DELETE -> "−"
        TextDiff.Kind.MODIFY -> "~"
        TextDiff.Kind.EQUAL -> " "
    }
    val signColor = when (line.kind) {
        TextDiff.Kind.INSERT -> DiffAddFg
        TextDiff.Kind.DELETE -> DiffDelFg
        TextDiff.Kind.MODIFY -> DiffModFg
        TextDiff.Kind.EQUAL -> MaterialTheme.colorScheme.outline
    }
    val body = remember(line) { buildLineText(line) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
    ) {
        Text(
            text = sign,
            modifier = Modifier.width(13.dp),
            fontSize = 12.sp,
            color = signColor,
        )
        Text(
            text = body,
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun buildLineText(line: TextDiff.Line): AnnotatedString = buildAnnotatedString {
    when (line.kind) {
        TextDiff.Kind.EQUAL -> append(line.text.ifEmpty { " " })
        TextDiff.Kind.INSERT -> withStyle(
            SpanStyle(background = DiffAddBg, color = DiffAddFg),
        ) { append(line.newText.ifEmpty { " " }) }
        TextDiff.Kind.DELETE -> withStyle(
            SpanStyle(
                background = DiffDelBg,
                color = DiffDelFg,
                textDecoration = TextDecoration.LineThrough,
            ),
        ) { append(line.oldText.ifEmpty { " " }) }
        TextDiff.Kind.MODIFY -> {
            TextDiff.inlineDiff(line.oldText, line.newText).forEach { part ->
                when (part.kind) {
                    TextDiff.InlineKind.EQUAL -> append(part.text)
                    TextDiff.InlineKind.INSERT -> withStyle(
                        SpanStyle(background = DiffAddBg, color = DiffAddFg),
                    ) { append(part.text) }
                    TextDiff.InlineKind.DELETE -> withStyle(
                        SpanStyle(
                            background = DiffDelBg,
                            color = DiffDelFg,
                            textDecoration = TextDecoration.LineThrough,
                        ),
                    ) { append(part.text) }
                }
            }
        }
    }
}
