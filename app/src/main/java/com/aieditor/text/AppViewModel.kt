package com.aieditor.text

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aieditor.text.data.AiClient
import com.aieditor.text.data.ApiConfig
import com.aieditor.text.data.AppPrefs
import com.aieditor.text.data.BackupManager
import com.aieditor.text.data.BackupSettings
import com.aieditor.text.data.ChatMessage
import com.aieditor.text.data.DocumentImporter
import com.aieditor.text.data.HistoryEntry
import com.aieditor.text.data.RemoteFile
import com.aieditor.text.data.RestoreMode
import com.aieditor.text.data.ResultHistoryStore
import com.aieditor.text.data.TextChunker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** AI 处理的范围。 */
enum class Scope(val label: String) {
    WHOLE("整个文件"),
    SELECTION("选中文本"),
    PARAGRAPH("当前段落"),
}

data class ImportOutcome(val success: Boolean, val message: String)

data class AiState(
    val running: Boolean = false,
    val scope: Scope = Scope.WHOLE,
    /** 首轮的指令。 */
    val instruction: String = "",
    /** 二次编辑的指令（对结果继续说）。 */
    val refineInstruction: String = "",
    val output: String = "",
    val status: String = "就绪。",
    val isError: Boolean = false,
    /** 本次任务最初送出的原文，用于「改动对比」。 */
    val sourceText: String = "",
    /** 二次编辑前的历史结果，最后一项是最近一次的上一版。 */
    val versions: List<String> = emptyList(),
    /** 长文是否自动分段处理。 */
    val chunked: Boolean = true,
    /** 每段字符上限。 */
    val chunkLimit: Int = TextChunker.DEFAULT_LIMIT,
    /** 本次请求实际切出的段数（1 表示未分段）。 */
    val chunkCount: Int = 0,
    /** 已完成/正在处理的段序号（从 1 开始）。 */
    val chunkProgress: Int = 0,
) {
    /** 当前结果属于第几版：首轮结果为第 1 版，每次二次编辑 +1。 */
    val revision: Int get() = versions.size + 1

    /** 是否可以回退到上一版结果。 */
    val canUndoRefine: Boolean get() = versions.isNotEmpty() && !running
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefs(app)

    private val _config = MutableStateFlow(prefs.loadConfig())
    val config: StateFlow<ApiConfig> = _config.asStateFlow()

    private val _editor = MutableStateFlow(TextFieldValue(prefs.draft, TextRange(prefs.draft.length)))
    val editor: StateFlow<TextFieldValue> = _editor.asStateFlow()

    private val _ai = MutableStateFlow(AiState(chunked = prefs.chunked, chunkLimit = prefs.chunkLimit))
    val ai: StateFlow<AiState> = _ai.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val backupManager = BackupManager(prefs)

    private val _backup = MutableStateFlow(prefs.loadBackupSettings())
    val backup: StateFlow<BackupSettings> = _backup.asStateFlow()

    private val historyStore = ResultHistoryStore(File(app.filesDir, "ai_history"))

    private val _history = MutableStateFlow(historyStore.list())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _historyLimit = MutableStateFlow(prefs.historyLimit)
    val historyLimit: StateFlow<Int> = _historyLimit.asStateFlow()

    private var job: Job? = null
    private var undoSnapshot: TextFieldValue? = null
    private var pendingRange: IntRange? = null

    // ------------------------------------------------------------ 编辑区

    fun onEditorChange(value: TextFieldValue) {
        _editor.value = value
        prefs.draft = value.text
    }

    fun setEditorText(text: String) {
        _editor.value = TextFieldValue(text, TextRange(text.length))
        prefs.draft = text
    }

    /** 从系统文件选择器导入文档，成功后替换编辑区内容（可撤销）。 */
    suspend fun importDocument(context: Context, uri: Uri): ImportOutcome {
        _importing.value = true
        return try {
            val document = DocumentImporter.load(context, uri)
            undoSnapshot = _editor.value
            setEditorText(document.text)
            val hint = if (prefs.chunked && document.text.length > prefs.chunkLimit) "，发送时会自动分段" else ""
            maybeAutoBackup()
            ImportOutcome(true, "已导入 ${document.name}（${document.text.length} 字）$hint")
        } catch (e: Exception) {
            ImportOutcome(false, "导入失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            _importing.value = false
        }
    }

    fun clearEditor() {
        undoSnapshot = _editor.value
        setEditorText("")
    }

    fun undo() {
        val snapshot = undoSnapshot ?: return
        undoSnapshot = null
        onEditorChange(snapshot)
    }

    fun hasUndo(): Boolean = undoSnapshot != null

    // ------------------------------------------------------------ 配置

    fun saveConfig(config: ApiConfig, keys: Map<String, String> = emptyMap()) {
        _config.value = config
        prefs.saveConfig(config)
        // 设置页里给多个服务商填的 Key 一并落盘，避免只保存当前这一个
        prefs.saveApiKeys(keys)
    }

    /** 某接口地址已保存的 Key，供设置页切换服务商时回填。 */
    fun apiKeyFor(baseUrl: String): String = prefs.apiKeyFor(baseUrl)

    suspend fun testConnection(config: ApiConfig): String {
        return try {
            // 用短超时的探测：先查 /models，不消耗生成额度，连不上立刻失败
            AiClient(config.copy(stream = false, maxTokens = 16)).probe()
        } catch (e: Exception) {
            "连接失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 拉取该地址的可用模型列表。 */
    suspend fun listModels(config: ApiConfig): List<String> =
        AiClient(config.copy(stream = false)).listModels()

    // ------------------------------------------------------------ 备份

    fun saveBackupSettings(settings: BackupSettings) {
        backupManager.saveSettings(settings)
        _backup.value = settings
    }

    suspend fun backupNow(): String {
        val name = backupManager.backupNow()
        _backup.value = backupManager.settings()
        return name
    }

    suspend fun listBackups(): List<RemoteFile> = backupManager.listBackups()

    /** 按覆盖 / 增量方式恢复，成功后立刻应用到编辑区与配置。 */
    suspend fun restoreBackup(file: RemoteFile, mode: RestoreMode): String {
        val result = backupManager.restore(file, mode)
        prefs.saveConfig(result.config)
        prefs.restoreApiKeys(result.apiKeys, overwrite = mode == RestoreMode.OVERWRITE)
        // 写回各服务商 Key 后重新读一次，保证内存配置与磁盘一致
        _config.value = prefs.loadConfig()
        setEditorText(result.draft)
        prefs.chunked = result.chunked
        prefs.chunkLimit = result.chunkLimit
        _ai.update { it.copy(chunked = result.chunked, chunkLimit = result.chunkLimit) }
        pendingRange = null
        _backup.value = backupManager.settings()

        return when (mode) {
            RestoreMode.OVERWRITE -> "已覆盖恢复：配置 + 文档（${result.draft.length} 字）"
            RestoreMode.MERGE -> {
                val filled = result.notes.filterNot { it.startsWith("本地文档") }
                val keptLocalDoc = result.notes.any { it.startsWith("本地文档") }
                when {
                    filled.isEmpty() && keptLocalDoc -> "本地配置已完整，已保留本地文档"
                    filled.isEmpty() -> "本地配置已完整，没有需要补充的内容"
                    else -> buildString {
                        append("已补全：").append(filled.joinToString("、"))
                        if (keptLocalDoc) append("；本地文档已保留")
                    }
                }
            }
        }
    }

    /** 到点就自动备份，失败静默（只在备份页留下结果）。 */
    fun maybeAutoBackup() {
        if (!backupManager.isAutoDue()) return
        // 既没有密钥也没有文档时不必生成一份空备份
        if (prefs.draft.isBlank() && prefs.loadConfig().apiKey.isBlank()) return
        viewModelScope.launch {
            runCatching { backupManager.backupNow() }
            _backup.value = backupManager.settings()
        }
    }

    // ------------------------------------------------------------ AI 请求

    fun setInstruction(text: String) = _ai.update { it.copy(instruction = text) }

    fun setRefineInstruction(text: String) = _ai.update { it.copy(refineInstruction = text) }

    fun setScope(scope: Scope) = _ai.update { it.copy(scope = scope) }

    fun setChunked(enabled: Boolean) {
        _ai.update { it.copy(chunked = enabled) }
        prefs.chunked = enabled
    }

    fun setChunkLimit(limit: Int) {
        val safe = limit.coerceIn(300, 20000)
        _ai.update { it.copy(chunkLimit = safe) }
        prefs.chunkLimit = safe
    }

    /** 当前范围内的文本会被切成几段，供面板提示；0 表示没有可处理内容。 */
    fun estimateChunks(): Int {
        val state = _ai.value
        val text = targetText(state.scope)?.first ?: return 0
        if (!state.chunked) return 1
        return TextChunker.count(text, state.chunkLimit)
    }

    /** 首轮：处理编辑区里的目标文本。 */
    fun send() {
        val state = _ai.value
        if (state.running) return
        val instruction = state.instruction.trim()
        if (instruction.isEmpty()) {
            notify("请先输入想让 AI 做什么")
            return
        }
        val target = targetText(state.scope)
        if (target == null) {
            notify("编辑区还没有内容")
            return
        }
        val (context, range) = target
        pendingRange = range
        startEdit(
            instruction = instruction,
            context = context,
            sourceText = context,
            isRefine = false,
            pushVersion = null,
            clearRefineInput = false,
        )
    }

    /** 二次编辑：把当前结果当作原文，用新的指令继续改。 */
    fun refine(instruction: String = _ai.value.refineInstruction) {
        val state = _ai.value
        if (state.running) return
        val current = state.output.trim()
        if (current.isEmpty()) {
            notify("还没有可继续编辑的结果")
            return
        }
        val task = instruction.trim()
        if (task.isEmpty()) {
            notify("请先输入要怎么继续修改")
            return
        }
        startEdit(
            instruction = task,
            context = current,
            // 对比始终对照最初送出的原文，避免改动统计反复跳动
            sourceText = state.sourceText.ifBlank { current },
            isRefine = true,
            pushVersion = current,
            clearRefineInput = true,
        )
    }

    /** 回退到上一版结果。 */
    fun undoRefine() {
        val state = _ai.value
        if (state.running) return
        val previous = state.versions.lastOrNull() ?: return
        _ai.update {
            val rest = it.versions.dropLast(1)
            it.copy(
                output = previous,
                versions = rest,
                status = "已回到上一版（第 ${rest.size + 1} 版）。",
                isError = false,
            )
        }
    }

    private fun startEdit(
        instruction: String,
        context: String,
        sourceText: String,
        isRefine: Boolean,
        pushVersion: String?,
        clearRefineInput: Boolean,
    ) {
        val state = _ai.value
        val chunks = if (state.chunked) {
            TextChunker.split(context, state.chunkLimit)
        } else {
            listOf(TextChunker.Chunk(context, ""))
        }
        val total = chunks.size

        _ai.update {
            it.copy(
                running = true,
                output = "",
                status = when {
                    isRefine -> "正在按新指令修改结果…"
                    total > 1 -> "文本较长，已切分为 $total 段，开始处理第 1 段…"
                    else -> "正在生成…"
                },
                isError = false,
                chunkCount = total,
                chunkProgress = if (total > 1) 1 else 0,
                sourceText = sourceText,
                // 首轮重新开始时清空版本栈；二次编辑把当前结果压栈以便回退
                versions = when {
                    pushVersion != null -> it.versions + pushVersion
                    isRefine -> it.versions
                    else -> emptyList()
                },
                refineInstruction = if (clearRefineInput) "" else it.refineInstruction,
            )
        }

        job = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            val finished = ArrayList<String>(total)
            var currentIndex = 0
            var buffer = StringBuilder()
            try {
                for ((index, chunk) in chunks.withIndex()) {
                    currentIndex = index
                    buffer = StringBuilder()
                    val messages = buildMessages(
                        instruction = instruction,
                        context = chunk.text,
                        isRefine = isRefine,
                        segmentIndex = index,
                        segmentTotal = total,
                    )
                    AiClient(_config.value).chat(messages).collect { delta ->
                        buffer.append(delta)
                        val seconds = (System.currentTimeMillis() - startedAt) / 1000
                        val liveText = TextChunker.merge(chunks, finished, index, buffer.toString())
                        _ai.update {
                            it.copy(
                                output = liveText,
                                status = if (total > 1) {
                                    "分段 ${index + 1}/$total · 生成中… ${seconds}s"
                                } else {
                                    "正在生成… ${seconds}s"
                                },
                                chunkProgress = index + 1,
                            )
                        }
                    }
                    val piece = buffer.toString().trim()
                    when {
                        piece.isNotEmpty() -> finished.add(piece)
                        total > 1 -> {
                            // 某段模型空回复：保留该段原文，避免结果缺块
                            finished.add(chunk.text)
                            notify("第 ${index + 1}/$total 段模型没有返回内容，已保留原文")
                        }
                        else -> throw IllegalStateException("模型没有返回内容")
                    }
                    if (total > 1 && index < total - 1) {
                        _ai.update {
                            it.copy(
                                status = "已完成 ${index + 1}/$total 段，继续下一段…",
                                chunkProgress = index + 1,
                            )
                        }
                    }
                }

                val result = TextChunker.merge(chunks, finished).trim()
                if (result.isEmpty()) throw IllegalStateException("模型没有返回内容")
                val seconds = (System.currentTimeMillis() - startedAt) / 1000
                _ai.update {
                    it.copy(
                        running = false,
                        output = result,
                        status = when {
                            isRefine -> "第 ${it.revision} 版完成（${seconds}s），可继续修改或应用。"
                            total > 1 -> "完成，共处理 $total 段，用时 ${seconds}s。"
                            else -> "完成。"
                        },
                        isError = false,
                        chunkProgress = total,
                    )
                }
                recordHistory(instruction, result, _ai.value.revision)
            } catch (e: CancellationException) {
                val partial = collectPartial(chunks, finished, currentIndex, buffer.toString())
                _ai.update {
                    val settled = partial.ifBlank { it.versions.lastOrNull().orEmpty() }
                    it.copy(
                        running = false,
                        output = settled,
                        status = when {
                            total > 1 && finished.isNotEmpty() ->
                                "已停止：已完成 ${finished.size}/$total 段，其余保留原文。"
                            settled.isEmpty() -> "已停止。"
                            partial.isBlank() -> "已停止，已恢复上一版结果。"
                            else -> "已停止，已保留部分结果。"
                        },
                        isError = false,
                    )
                }
            } catch (e: Exception) {
                val partial = collectPartial(chunks, finished, currentIndex, buffer.toString())
                val reason = e.message ?: e.javaClass.simpleName
                _ai.update {
                    val settled = partial.ifBlank { it.versions.lastOrNull().orEmpty() }
                    it.copy(
                        running = false,
                        output = settled,
                        status = when {
                            total > 1 && finished.isEmpty() -> "第 ${currentIndex + 1}/$total 段失败：$reason"
                            total > 1 -> "第 ${currentIndex + 1}/$total 段失败：$reason（前 ${finished.size} 段结果已保留，其余为原文）"
                            partial.isBlank() && settled.isNotEmpty() -> "本次修改失败：$reason（已保留上一版结果）"
                            else -> "请求失败：$reason"
                        },
                        isError = true,
                    )
                }
            } finally {
                job = null
            }
        }
    }

    /**
     * 中断时尽量给出可用文本：
     * - 单段：保留半截流式结果；
     * - 多段：已完成的段用 AI 结果，未完成的段回落为原文，避免应用后丢内容。
     */
    private fun collectPartial(
        chunks: List<TextChunker.Chunk>,
        finished: List<String>,
        currentIndex: Int,
        buffered: String,
    ): String {
        if (chunks.size <= 1) {
            return TextChunker.merge(chunks, finished, currentIndex, buffered).trim()
        }
        if (finished.isEmpty()) return ""
        val sb = StringBuilder()
        for (index in chunks.indices) {
            sb.append(chunks[index].separatorBefore)
            sb.append(if (index < finished.size) finished[index] else chunks[index].text)
        }
        return sb.toString().trim()
    }

    fun stop() {
        val target = job
        job = null
        target?.cancel()
        // 协程的 CancellationException 分支会写入「已完成 N/M 段」等更具体的结果，
        // 这里只在它还没来的及更新时兜底，避免把详细文案覆盖掉。
        _ai.update { if (it.running) it.copy(running = false, status = "已停止。") else it }
    }

    fun newSession() {
        _ai.update {
            it.copy(
                output = "",
                versions = emptyList(),
                status = "就绪。",
                isError = false,
            )
        }
    }

    private fun buildMessages(
        instruction: String,
        context: String,
        isRefine: Boolean,
        segmentIndex: Int = 0,
        segmentTotal: Int = 1,
    ): List<ChatMessage> {
        val system = _config.value.systemPrompt
        val user = buildString {
            append("【任务】\n").append(instruction).append("\n\n")
            if (isRefine) {
                append("【说明】\n下面这段文本是上一轮 AI 编辑后的结果，请直接在它的基础上按本次任务继续修改，")
                append("保留没有被要求改动的部分。\n\n")
            }
            if (segmentTotal > 1) {
                append("【分段说明】\n")
                append("原文较长，已分成 ").append(segmentTotal).append(" 段依次处理，本段是第 ")
                append(segmentIndex + 1).append(" 段（共 ").append(segmentTotal).append(" 段）。\n")
                append("请只处理本段内容并直接输出结果：不要输出“第 x 段”“以下为处理结果”等任何说明文字，")
                append("不要补写原文中不存在的内容，也不要重复本段之外的文字。\n\n")
            }
            append("【原文】\n").append(context)
        }
        return listOf(
            ChatMessage("system", system),
            ChatMessage("user", user),
        )
    }

    /** 返回（要处理的文本, 在原文中的范围；null 表示整篇）。 */
    private fun targetText(scope: Scope): Pair<String, IntRange?>? {
        val value = _editor.value
        val text = value.text
        if (text.isBlank()) return null
        return when (scope) {
            Scope.WHOLE -> text to null
            Scope.SELECTION -> {
                val selection = value.selection
                if (selection.collapsed) {
                    val range = paragraphRange(text, selection.start)
                    text.substring(range.first, range.last + 1) to range
                } else {
                    val start = minOf(selection.start, selection.end).coerceIn(0, text.length)
                    val end = maxOf(selection.start, selection.end).coerceIn(start, text.length)
                    if (start == end) {
                        val range = paragraphRange(text, start)
                        text.substring(range.first, range.last + 1) to range
                    } else {
                        text.substring(start, end) to (start until end)
                    }
                }
            }
            Scope.PARAGRAPH -> {
                val range = paragraphRange(text, value.selection.start)
                text.substring(range.first, range.last + 1) to range
            }
        }
    }

    private fun paragraphRange(text: String, offset: Int): IntRange {
        val pos = offset.coerceIn(0, text.length)
        var start = pos
        while (start > 0 && text[start - 1] != '\n') start--
        var end = pos
        while (end < text.length && text[end] != '\n') end++
        if (end <= start) {
            // 光标正好落在换行处：取下一行
            start = (start + 1).coerceAtMost(text.length)
            end = start
            while (end < text.length && text[end] != '\n') end++
        }
        return start..(end - 1).coerceAtLeast(start)
    }

    // ------------------------------------------------------------ 结果历史

    /** 把一次生成结果存到本地；条数为 0（功能关闭）时不保存。 */
    private fun recordHistory(instruction: String, text: String, revision: Int) {
        val limit = prefs.historyLimit
        if (limit <= 0 || text.isBlank()) return
        val now = System.currentTimeMillis()
        val entry = HistoryEntry(
            id = System.nanoTime(),
            createdAt = now,
            instruction = instruction,
            model = _config.value.model,
            revision = revision,
            text = text,
        )
        // 结果可能很长，读写放 IO 线程，避免生成完成时界面卡一下
        viewModelScope.launch(Dispatchers.IO) {
            historyStore.add(entry, limit)
            _history.value = historyStore.list()
        }
    }

    /** 把历史结果载入预览区，可继续二次编辑或直接应用。 */
    fun loadHistory(entry: HistoryEntry) {
        pendingRange = null
        _ai.update {
            it.copy(
                output = entry.text,
                // 载入的内容当作原文，改动对比自然为空
                sourceText = entry.text,
                versions = emptyList(),
                status = "已载入历史结果（${entry.text.length} 字），可继续修改或应用。",
                isError = false,
            )
        }
    }

    fun deleteHistory(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            historyStore.delete(id)
            _history.value = historyStore.list()
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            historyStore.clear()
            _history.value = emptyList()
        }
    }

    /** 调整保留条数：0 表示关闭，同时立即裁剪超出部分。 */
    fun setHistoryLimit(limit: Int) {
        val safe = limit.coerceIn(0, 20)
        prefs.historyLimit = safe
        _historyLimit.value = safe
        viewModelScope.launch(Dispatchers.IO) {
            if (safe == 0) historyStore.clear() else historyStore.trim(safe)
            _history.value = historyStore.list()
        }
    }

    // ------------------------------------------------------------ 结果处理

    /** 把 AI 输出写回编辑区，成功返回 true。 */
    fun applyResult(): Boolean {
        val output = _ai.value.output.trim()
        if (output.isEmpty()) return false
        val value = _editor.value
        val range = pendingRange
        undoSnapshot = value
        val (newText, cursor) = if (range == null) {
            output to output.length
        } else {
            val start = range.first.coerceIn(0, value.text.length)
            val end = (range.last + 1).coerceIn(start, value.text.length)
            val merged = value.text.substring(0, start) + output + value.text.substring(end)
            merged to (start + output.length)
        }
        setEditorText(newText)
        _editor.value = TextFieldValue(newText, TextRange(cursor.coerceIn(0, newText.length)))
        pendingRange = null
        _ai.update { it.copy(status = "已应用到编辑区。") }
        maybeAutoBackup()
        return true
    }

    fun exportFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        return "AI文本_$stamp.txt"
    }

    fun writeToUri(context: Context, uri: Uri, text: String): Boolean = try {
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        true
    } catch (e: Exception) {
        notify("保存失败：${e.message}")
        false
    }

    private fun notify(text: String) {
        _messages.tryEmit(text)
    }
}
