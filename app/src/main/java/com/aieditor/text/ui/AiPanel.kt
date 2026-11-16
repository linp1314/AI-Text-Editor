package com.aieditor.text.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
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
 * AI 面板：指令 → 范围/分段 → 输出预览 → 继续修改 → 底部三个动作。
 * 同一屏内的按钮统一走 Ui 里的三档样式：主操作、次操作、快捷选项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiPanelDialog(
    vm: AppViewModel,
    state: AiState,
    config: ApiConfig,
    onClose: () -> Unit,
    onApply: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val coroutine = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    var showSave by remember { mutableStateOf(false) }
    var showRefine by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editValue by remember { mutableStateOf(TextFieldValue("")) }

    val history by vm.history.collectAsState()
    val historyLimit by vm.historyLimit.collectAsState()
    val editorState by vm.editor.collectAsState()

    val displayText = state.output
    // 编辑区为空：进入「从零生成」模式，指令即创作要求
    val emptyDoc = editorState.text.isBlank()
    val chunkCount = remember(editorState.text, state.scope, state.chunked, state.chunkLimit) {
        vm.estimateChunks()
    }

    // 生成结束后再算差异，避免流式输出期间反复做 LCS；手动改过的结果不再比对
    val diff = remember(displayText, state.sourceText, state.running, state.outputEdited) {
        val source = state.sourceText
        if (!state.running && !state.outputEdited &&
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
    // 生成中不允许编辑预览，避免和流式输出打架
    LaunchedEffect(state.running) { if (state.running) { editing = false; showDiff = false } }
    // 非编辑态时把预览内容同步到编辑缓冲，进入编辑时从当前内容开始
    LaunchedEffect(displayText, editing) {
        if (!editing) editValue = TextFieldValue(displayText, TextRange(displayText.length))
    }

    // 默认文件名跟随当前文档：原文件名 +「AI」，扩展名不变
    val activeDoc = remember(state.docId) {
        val id = state.docId ?: vm.activeDocId.value
        vm.docs.value.firstOrNull { it.id == id }
    }
    val saveTarget = remember(activeDoc?.id, activeDoc?.title, activeDoc?.sourceName) {
        vm.exportTarget()
    }
    val saveContract = remember(saveTarget.mime) {
        ActivityResultContracts.CreateDocument(saveTarget.mime)
    }
    val saveLauncher = rememberLauncherForActivityResult(saveContract) { uri ->
        if (uri != null) {
            val ok = vm.writeToUri(context, uri, displayText)
            coroutine.launch {
                snackbarHost.showSnackbar(if (ok) "已保存 ${saveTarget.name}" else "保存失败")
            }
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // 全屏铺满：原来是 94% 高的浮层，底部会把「导入 / AI 编辑」露出半截，
        // 看着像被弹窗压住、点了又没反应
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                ) {

                    // ---------------- 标题：只留标题 + 三个次要入口
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("AI 编辑", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "${config.model.ifBlank { "未设置模型" }} · 第 ${state.revision} 版",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(
                            onClick = { showHistory = true },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text(
                                if (history.isEmpty()) "历史" else "历史 ${history.size}",
                                fontSize = 13.sp,
                                color = if (historyLimit <= 0) MaterialTheme.colorScheme.outline else BrandGreen,
                            )
                        }
                        IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "关闭",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // ---------------- 主体
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp),
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
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            },
                            textStyle = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
                            minLines = 1,
                            maxLines = 3,
                            shape = RoundedCornerShape(Ui.radius),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                focusedBorderColor = BrandGreen,
                            ),
                        )

                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val chips = if (emptyDoc) {
                                listOf("写一段自我介绍", "写一封工作邮件", "写一篇短文", "列一份清单", "写产品文案", "翻译成英文")
                            } else {
                                listOf("润色", "精简", "纠错", "翻译成英文", "加注释", "扩写", "改正式语气")
                            }
                            chips.forEach { label ->
                                PillChip(text = label, onClick = { vm.setInstruction(label) })
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // 范围 + 分段 + 主按钮，一行解决
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (emptyDoc) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(Ui.chip + 4.dp)
                                        .clip(RoundedCornerShape(Ui.radius))
                                        .background(BrandGreen.copy(alpha = 0.10f))
                                        .border(1.dp, BrandGreen.copy(alpha = 0.3f), RoundedCornerShape(Ui.radius))
                                        .padding(horizontal = 10.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Text(
                                        "从零生成 · 无需原文",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = BrandGreen,
                                    )
                                }
                            } else {
                                DropdownSelect(
                                    options = Scope.values().map { it.label },
                                    selectedIndex = state.scope.ordinal,
                                    modifier = Modifier.weight(1f),
                                    enabled = !state.running,
                                    onSelect = { vm.setScope(Scope.values()[it]) },
                                )
                                DropdownSelect(
                                    options = CHUNK_LABELS,
                                    selectedIndex = chunkIndex(state),
                                    modifier = Modifier.width(104.dp),
                                    enabled = !state.running,
                                    onSelect = { index ->
                                        if (index == 0) {
                                            vm.setChunked(false)
                                        } else {
                                            vm.setChunked(true)
                                            vm.setChunkLimit(TextChunker.LIMIT_OPTIONS[index - 1])
                                        }
                                    },
                                )
                            }
                            PrimaryButton(
                                text = when {
                                    state.running -> "停止"
                                    emptyDoc -> "生成"
                                    else -> "发送"
                                },
                                modifier = Modifier.width(88.dp),
                                enabled = state.running || state.instruction.isNotBlank(),
                                danger = state.running,
                                onClick = { if (state.running) vm.stop() else vm.send() },
                            )
                        }

                        Spacer(Modifier.height(6.dp))

                        // 状态：进度 + 分段预估，不再单独占一行按钮
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
                            if (!emptyDoc && state.chunked && chunkCount > 0) {
                                Text(
                                    text = if (chunkCount > 1) "将分 $chunkCount 段" else "单段",
                                    fontSize = 11.sp,
                                    color = if (chunkCount > 1) BrandGreen else MaterialTheme.colorScheme.outline,
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // ---------------- 输出头部：视图切换 + 字数 + 编辑入口
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "输出",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (hasDiff && diff != null && !editing) {
                                Spacer(Modifier.width(8.dp))
                                SegmentToggle(
                                    options = listOf("结果", "改动 ${diff.changedLines}"),
                                    selected = if (showDiff) 1 else 0,
                                    onSelect = { showDiff = it == 1 },
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (state.outputEdited && !editing) {
                                Text("已编辑", fontSize = 11.sp, color = BrandGreen)
                                Spacer(Modifier.width(8.dp))
                            }
                            if (displayText.isNotEmpty()) {
                                Text(
                                    "${displayText.length} 字",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            if (editing) {
                                TextButton(
                                    onClick = { editing = false },
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    modifier = Modifier.height(30.dp),
                                ) {
                                    Text("完成", fontSize = 12.sp, color = BrandGreen)
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = displayText.isNotBlank() && !state.running) {
                                            showDiff = false
                                            editing = true
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.Edit,
                                        contentDescription = "手动编辑",
                                        modifier = Modifier.size(15.dp),
                                        tint = if (displayText.isNotBlank() && !state.running) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        // ---------------- 预览区：可直接编辑
                        val previewScroll = rememberScrollState()
                        LaunchedEffect(displayText) {
                            if (!editing) previewScroll.scrollTo(previewScroll.maxValue)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .heightIn(min = 120.dp)
                                .clip(RoundedCornerShape(Ui.radius))
                                .background(
                                    if (editing) MaterialTheme.colorScheme.surface
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
                                )
                                .border(
                                    if (editing) 1.5.dp else 1.dp,
                                    if (editing) BrandGreen else MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(Ui.radius),
                                )
                                .padding(12.dp),
                        ) {
                            when {
                                state.running && displayText.isEmpty() -> Text(
                                    "等待模型返回…",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                                displayText.isEmpty() -> Text(
                                    text = if (emptyDoc) {
                                        "写好要求后点「生成」，结果会出现在这里，可直接改、可写入编辑区。"
                                    } else {
                                        "AI 结果会显示在这里，点右上角铅笔可手动润色，改完再应用。"
                                    },
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                                editing -> BasicTextField(
                                    value = editValue,
                                    onValueChange = {
                                        editValue = it
                                        vm.setOutput(it.text)
                                    },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(previewScroll),
                                    textStyle = TextStyle(
                                        fontSize = 14.sp,
                                        lineHeight = 22.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    cursorBrush = SolidColor(BrandGreen),
                                )
                                showDiff && diff != null -> DiffPane(diff = diff)
                                else -> SelectionContainer {
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

                        Spacer(Modifier.height(10.dp))

                        // ---------------- 继续让 AI 改
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlineButton(
                                text = "上一版",
                                modifier = Modifier.width(84.dp),
                                enabled = state.canUndoRefine,
                                height = 40.dp,
                                onClick = vm::undoRefine,
                            )
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
                            OutlineButton(
                                text = "再改",
                                modifier = Modifier.width(64.dp),
                                enabled = !state.running && displayText.isNotBlank(),
                                height = 40.dp,
                                onClick = { showRefine = true },
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                    }

                    // ---------------- 底部：三个动作
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlineButton(
                            text = "清空",
                            modifier = Modifier.weight(1f),
                            enabled = displayText.isNotBlank() && !state.running,
                        ) {
                            // 只清预览，不动编辑区；结果历史里通常还留着一份
                            editing = false
                            showDiff = false
                            vm.clearOutput()
                            coroutine.launch { snackbarHost.showSnackbar("已清空结果，可在历史里找回") }
                        }
                        OutlineButton(
                            text = "复制",
                            modifier = Modifier.weight(1f),
                            enabled = displayText.isNotBlank(),
                        ) {
                            clipboard.setText(AnnotatedString(displayText))
                            coroutine.launch { snackbarHost.showSnackbar("已复制到剪贴板") }
                        }
                        OutlineButton(
                            text = "保存",
                            modifier = Modifier.weight(1f),
                            enabled = displayText.isNotBlank(),
                        ) { showSave = true }
                        PrimaryButton(
                            text = if (emptyDoc) "写入" else "应用",
                            modifier = Modifier.weight(1f),
                            enabled = displayText.isNotBlank() && !state.running,
                            onClick = onApply,
                        )
                    }
                }

                SnackbarHost(
                    hostState = snackbarHost,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 68.dp),
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
                editing = false
                vm.loadHistory(entry)
                coroutine.launch { snackbarHost.showSnackbar("已载入历史结果") }
            },
            onDelete = vm::deleteHistory,
            onClear = vm::clearHistory,
            onDismiss = { showHistory = false },
        )
    }

    if (showSave && displayText.isNotBlank()) {
        Dialog(onDismissRequest = { showSave = false }) {
            Surface(
                shape = RoundedCornerShape(Ui.radiusBig),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 12.dp,
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("保存结果", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "默认文件名沿用原文件名并加上「AI」，可在保存时改。也可以直接分享给其他应用，内容包含你手动修改的部分。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlineButton(
                            text = "分享",
                            modifier = Modifier.weight(1f),
                        ) {
                            showSave = false
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, displayText)
                            }
                            context.startActivity(Intent.createChooser(send, "分享 AI 结果"))
                        }
                        PrimaryButton(
                            text = "保存为 ${saveTarget.name.substringAfterLast('.').uppercase()}",
                            modifier = Modifier.weight(1f),
                        ) {
                            showSave = false
                            saveLauncher.launch(saveTarget.name)
                        }
                    }
                }
            }
        }
    }
}

/** 分段下拉的选项文案与索引映射。 */
private val CHUNK_LABELS = buildList {
    add("不分段")
    TextChunker.LIMIT_OPTIONS.forEach { add("$it 字/段") }
}

private fun chunkIndex(state: AiState): Int {
    if (!state.chunked) return 0
    val index = TextChunker.LIMIT_OPTIONS.indexOf(state.chunkLimit)
    return if (index >= 0) index + 1 else 0
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
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(Ui.radius))
            .border(
                1.dp,
                if (enabled) scheme.outline else scheme.outlineVariant,
                RoundedCornerShape(Ui.radius),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = placeholder,
            fontSize = 13.sp,
            color = if (text.isBlank()) scheme.outline else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("✎", fontSize = 13.sp, color = scheme.outline)
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

    AlertDialogFrame(onDismiss = onDismiss) {
        Text("继续修改（第 $revision 版）", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
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
            placeholder = { Text("想让 AI 怎么改？", fontSize = 13.sp, color = outline) },
            textStyle = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            minLines = 2,
            maxLines = 4,
            shape = RoundedCornerShape(Ui.radius),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedBorderColor = BrandGreen,
            ),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("再精简一半", "更正式", "更口语", "扩写细节", "统一语气").forEach { label ->
                PillChip(text = label, onClick = { text = label })
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineButton(text = "取消", modifier = Modifier.weight(1f), onClick = onDismiss)
            PrimaryButton(
                text = "开始修改",
                modifier = Modifier.weight(1f),
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()) },
            )
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** 统一圆角与内边距的轻量弹窗外壳。 */
@Composable
private fun AlertDialogFrame(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(Ui.radiusBig),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.padding(20.dp), content = { content() })
        }
    }
}

// ------------------------------------------------------------------ 改动对比

/**
 * 改动的呈现方式：
 * 每一行原文与它对应的改后文字上下成对出现，行号在左侧对齐，整行铺淡红 / 淡绿底色。
 * 之前把「删掉的字 + 新增的字」拼在同一行里，读的时候对不上原文，改动也不明显。
 */
private val DiffDelRow = Color(0xFFFDECEA)
private val DiffAddRow = Color(0xFFE9F6EE)
private val DiffDelFg = Color(0xFF9E2A1E)
private val DiffAddFg = Color(0xFF12663B)
private val DiffDelMark = Color(0xFFF7C9C2)
private val DiffAddMark = Color(0xFFB4DFC4)
private val DiffDelBar = Color(0xFFD0453A)
private val DiffAddBar = Color(0xFF2F9B62)

/** 一次改动最多展开多少行未改动内容，避免整篇原文一次性渲染。 */
private const val MAX_EXPANDED_EQUAL = 300

private enum class DiffRole { EQUAL, DELETE, INSERT, MOD_OLD, MOD_NEW }

private data class DiffLine(
    val role: DiffRole,
    val number: Int,
    val text: AnnotatedString,
)

private sealed interface DiffItem {
    data class Single(val line: DiffLine) : DiffItem
    /** 同一处改动的若干行：整块圆角铺底，左侧一条彩条连起来。 */
    data class Block(val lines: List<DiffLine>) : DiffItem
    /** 连续未改动的行，默认折叠成一行提示。 */
    data class Gap(val lines: List<DiffLine>) : DiffItem
}

@Composable
private fun DiffPane(diff: TextDiff.Result) {
    val items = remember(diff) { buildDiffItems(diff) }
    val expanded = remember(diff) { mutableStateMapOf<Int, Boolean>() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        item(key = "legend") { DiffLegend(diff) }
        items.forEachIndexed { index, item ->
            when (item) {
                is DiffItem.Single -> item(key = "row-$index") { DiffLineRow(item.line) }
                is DiffItem.Block -> item(key = "block-$index") { DiffBlock(item.lines) }
                is DiffItem.Gap -> item(key = "gap-$index") {
                    if (expanded[index] == true) {
                        DiffGapExpanded(item.lines)
                    } else {
                        GapRow(item.lines.size) { expanded[index] = true }
                    }
                }
            }
        }
    }
}

/** 顶部图例：说明红绿各代表什么，顺手给出改动行数。 */
@Composable
private fun DiffLegend(diff: TextDiff.Result) {
    val outline = MaterialTheme.colorScheme.outline
    Text(
        buildAnnotatedString {
            withStyle(
                SpanStyle(background = DiffDelRow, color = DiffDelFg, fontWeight = FontWeight.SemiBold),
            ) { append(" − 原文 ") }
            withStyle(SpanStyle(color = outline)) { append("  ") }
            withStyle(
                SpanStyle(background = DiffAddRow, color = DiffAddFg, fontWeight = FontWeight.SemiBold),
            ) { append(" + 改后 ") }
            withStyle(SpanStyle(color = outline)) { append("   共 ${diff.changedLines} 行改动") }
        },
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun DiffBlock(lines: List<DiffLine>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp)),
    ) {
        lines.forEach { DiffLineRow(it) }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine) {
    val scheme = MaterialTheme.colorScheme
    val bg = when (line.role) {
        DiffRole.DELETE, DiffRole.MOD_OLD -> DiffDelRow
        DiffRole.INSERT, DiffRole.MOD_NEW -> DiffAddRow
        DiffRole.EQUAL -> Color.Transparent
    }
    val accent = when (line.role) {
        DiffRole.DELETE, DiffRole.MOD_OLD -> DiffDelBar
        DiffRole.INSERT, DiffRole.MOD_NEW -> DiffAddBar
        DiffRole.EQUAL -> Color.Transparent
    }
    val sign = when (line.role) {
        DiffRole.DELETE, DiffRole.MOD_OLD -> "−"
        DiffRole.INSERT, DiffRole.MOD_NEW -> "+"
        DiffRole.EQUAL -> ""
    }
    val signColor = when (line.role) {
        DiffRole.DELETE, DiffRole.MOD_OLD -> DiffDelFg
        DiffRole.INSERT, DiffRole.MOD_NEW -> DiffAddFg
        DiffRole.EQUAL -> scheme.outline
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(bg),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(accent),
        )
        Text(
            text = line.number.toString(),
            modifier = Modifier
                .width(30.dp)
                .padding(end = 6.dp, top = 3.dp),
            fontSize = 10.sp,
            textAlign = TextAlign.End,
            color = if (line.role == DiffRole.EQUAL) scheme.outline else signColor.copy(alpha = 0.65f),
        )
        Text(
            text = sign,
            modifier = Modifier
                .width(11.dp)
                .padding(top = 2.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = signColor,
        )
        Text(
            text = line.text,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp, top = 2.dp, bottom = 2.dp),
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = scheme.onSurface,
        )
    }
}

@Composable
private fun GapRow(count: Int, onExpand: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onExpand)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "⋯",
            modifier = Modifier
                .width(36.dp)
                .padding(end = 6.dp),
            fontSize = 11.sp,
            textAlign = TextAlign.End,
            color = outline,
        )
        Text("$count 行未改动 · 点按展开", fontSize = 11.sp, color = outline)
    }
}

@Composable
private fun DiffGapExpanded(lines: List<DiffLine>) {
    Column(Modifier.fillMaxWidth()) {
        lines.take(MAX_EXPANDED_EQUAL).forEach { DiffLineRow(it) }
        if (lines.size > MAX_EXPANDED_EQUAL) {
            Text(
                text = "⋯ 还有 ${lines.size - MAX_EXPANDED_EQUAL} 行未展开",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 36.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}

/** 逐行对齐：改动行紧跟在原文行下面，行号左右分开数，一眼能看出哪一行换成了什么。 */
private fun buildDiffItems(diff: TextDiff.Result): List<DiffItem> {
    val items = ArrayList<DiffItem>()
    var oldNo = 1
    var newNo = 1
    var equal = ArrayList<DiffLine>()
    var block = ArrayList<DiffLine>()

    fun flushEqual() {
        if (equal.isEmpty()) return
        if (equal.size > 3) {
            items.add(DiffItem.Gap(equal.toList()))
        } else {
            equal.forEach { items.add(DiffItem.Single(it)) }
        }
        equal = ArrayList()
    }

    fun flushBlock() {
        if (block.isEmpty()) return
        // 整篇重写时一块可能有上千行，切成小段交给列表，避免一屏渲染太多
        block.chunked(40).forEach { items.add(DiffItem.Block(it)) }
        block = ArrayList()
    }

    diff.lines.forEach { line ->
        when (line.kind) {
            TextDiff.Kind.EQUAL -> {
                flushBlock()
                equal.add(DiffLine(DiffRole.EQUAL, newNo, AnnotatedString(line.text.ifEmpty { " " })))
                oldNo++
                newNo++
            }
            TextDiff.Kind.DELETE -> {
                flushEqual()
                block.add(DiffLine(DiffRole.DELETE, oldNo++, AnnotatedString(line.oldText.ifEmpty { " " })))
            }
            TextDiff.Kind.INSERT -> {
                flushEqual()
                block.add(DiffLine(DiffRole.INSERT, newNo++, AnnotatedString(line.newText.ifEmpty { " " })))
            }
            TextDiff.Kind.MODIFY -> {
                flushEqual()
                val parts = TextDiff.inlineDiff(line.oldText, line.newText)
                block.add(DiffLine(DiffRole.MOD_OLD, oldNo++, oldSide(parts)))
                block.add(DiffLine(DiffRole.MOD_NEW, newNo++, newSide(parts)))
            }
        }
    }
    flushEqual()
    flushBlock()
    return items
}

/** 原文一侧：保留原句，被替换掉的字加重标出来，新增的字不出现。 */
private fun oldSide(parts: List<TextDiff.Inline>): AnnotatedString = buildAnnotatedString {
    parts.forEach { part ->
        when (part.kind) {
            TextDiff.InlineKind.EQUAL -> append(part.text)
            TextDiff.InlineKind.DELETE -> withStyle(
                SpanStyle(background = DiffDelMark, color = DiffDelFg, textDecoration = TextDecoration.LineThrough),
            ) { append(part.text) }
            TextDiff.InlineKind.INSERT -> Unit
        }
    }
}

/** 改后一侧：只显示最终写下的内容，新增的字加重标出来。 */
private fun newSide(parts: List<TextDiff.Inline>): AnnotatedString = buildAnnotatedString {
    parts.forEach { part ->
        when (part.kind) {
            TextDiff.InlineKind.EQUAL -> append(part.text)
            TextDiff.InlineKind.INSERT -> withStyle(
                SpanStyle(background = DiffAddMark, color = DiffAddFg),
            ) { append(part.text) }
            TextDiff.InlineKind.DELETE -> Unit
        }
    }
}
