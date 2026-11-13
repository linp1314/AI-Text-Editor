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
import com.aieditor.text.data.Doc
import com.aieditor.text.data.DocCodec
import com.aieditor.text.data.DocumentImporter
import com.aieditor.text.data.DocumentStore
import com.aieditor.text.data.HistoryEntry
import com.aieditor.text.data.MergeResult
import com.aieditor.text.data.PromptBuilder
import com.aieditor.text.data.RemoteFile
import com.aieditor.text.data.RestoreMode
import com.aieditor.text.data.ResultHistoryStore
import com.aieditor.text.data.TextChunker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

data class ImportOutcome(
    val success: Boolean,
    val message: String,
    /** 导入成功后的新文档，可用于撤回这次导入。 */
    val docId: Long? = null,
)

data class AiState(
    val running: Boolean = false,
    /** 本次会话属于哪篇文档；切换文档后不会把结果写进另一篇。 */
    val docId: Long? = null,
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
    /** 本次是「无原文、从零生成」。 */
    val generating: Boolean = false,
    /** 结果被手动改过：此时不再展示「改动对比」，避免和 AI 改动混在一起。 */
    val outputEdited: Boolean = false,
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

    private val _editor = MutableStateFlow(TextFieldValue(""))
    val editor: StateFlow<TextFieldValue> = _editor.asStateFlow()

    // 多文档：打开的文档列表 + 当前文档；每篇各自保留撤销栈与光标
    private val docStore = DocumentStore(app.filesDir)

    private val _docs = MutableStateFlow<List<Doc>>(emptyList())
    val docs: StateFlow<List<Doc>> = _docs.asStateFlow()

    private val _activeDocId = MutableStateFlow(0L)
    val activeDocId: StateFlow<Long> = _activeDocId.asStateFlow()

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
    private var pendingRange: IntRange? = null
    private var saveJob: Job? = null

    // 编辑区撤销 / 恢复：存整份快照，连续输入合并成一步。撤销栈按文档分开存，
    // 切换文档后各篇的撤销历史互不影响。
    private class DocHistory {
        val undo = ArrayDeque<TextFieldValue>()
        val redo = ArrayDeque<TextFieldValue>()
        var lastEditAt = 0L
        var burstActive = false
        /** 上次离开这篇文档时的光标位置，-1 表示还没记录过。 */
        var cursor = -1
    }

    private val histories = HashMap<Long, DocHistory>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    init {
        // 首次启动或老版本升级：把原来的单篇草稿当作第一篇文档
        val loaded = docStore.load()
        val docs = loaded.ifEmpty {
            listOf(Doc(id = newId(), title = "未命名", subtitle = "未命名文档", text = prefs.draft))
        }
        _docs.value = docs
        // 回到上次停留的那篇，找不到就打开第一篇
        val remembered = prefs.activeDocId
        val active = docs.firstOrNull { it.id == remembered } ?: docs.first()
        _activeDocId.value = active.id
        openDoc(active.id)
    }

    // ------------------------------------------------------------ 文档列表

    private fun newId(): Long = System.nanoTime()

    private fun history(): DocHistory = histories.getOrPut(_activeDocId.value) { DocHistory() }

    /** 未命名文档按顺序编号：未命名、未命名 2、未命名 3… */
    private fun untitledTitle(): String {
        val existing = _docs.value.map { it.title }.toSet()
        if ("未命名" !in existing) return "未命名"
        var index = 2
        while ("未命名 $index" in existing) index++
        return "未命名 $index"
    }

    /** 把当前编辑区内容写回所属文档；[schedule] 为 false 时由调用方自己落盘。 */
    private fun updateActiveText(text: String, schedule: Boolean = true) {
        val id = _activeDocId.value
        _docs.update { list -> list.map { if (it.id == id) it.copy(text = text) else it } }
        if (schedule) scheduleSave()
    }

    /** 切换、新建、关闭后统一走这里：把内容、光标、存档一起换过来。 */
    private fun openDoc(id: Long) {
        val doc = _docs.value.firstOrNull { it.id == id } ?: return
        val saved = histories.getOrPut(id) { DocHistory() }
        val cursor = if (saved.cursor < 0) doc.text.length else saved.cursor.coerceIn(0, doc.text.length)
        _editor.value = TextFieldValue(doc.text, TextRange(cursor))
        prefs.draft = doc.text
        pendingRange = null
        refreshHistoryFlags()
        ensureSessionForActiveDoc()
    }

    fun switchDoc(id: Long) {
        if (id == _activeDocId.value) return
        if (_docs.value.none { it.id == id }) return
        // 离开前记住光标，回来时还能接着写
        history().apply {
            cursor = _editor.value.selection.start
            burstActive = false
        }
        _activeDocId.value = id
        prefs.activeDocId = id
        openDoc(id)
    }

    /** 新建空白文档，排在列表最前面并立即切换过去。 */
    fun newDoc(
        title: String = untitledTitle(),
        text: String = "",
        subtitle: String = "未命名文档",
    ) {
        val doc = Doc(id = newId(), title = title, subtitle = subtitle, text = text)
        _docs.update { listOf(doc) + it }
        _activeDocId.value = doc.id
        prefs.activeDocId = doc.id
        openDoc(doc.id)
        scheduleSave()
    }

    /** 关闭一篇文档；返回被关闭的内容，供「撤销」时重新打开。 */
    fun closeDoc(id: Long): Doc? {
        val list = _docs.value
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return null
        val removed = list[index]
        val rest = list.filterNot { it.id == id }
        histories.remove(id)

        if (rest.isEmpty()) {
            // 最后一篇不真的关掉，换成空白文档，避免界面上没有可编辑内容
            val blank = Doc(id = newId(), title = untitledTitle(), subtitle = "未命名文档")
            _docs.value = listOf(blank)
            _activeDocId.value = blank.id
            prefs.activeDocId = blank.id
            openDoc(blank.id)
        } else {
            _docs.value = rest
            if (_activeDocId.value == id) {
                val next = rest[minOf(index, rest.lastIndex)]
                _activeDocId.value = next.id
                prefs.activeDocId = next.id
                openDoc(next.id)
            }
        }
        if (_ai.value.docId == id) newSession()
        scheduleSave()
        return removed
    }

    /** 重新打开刚关闭的文档（撤销关闭）。 */
    fun restoreDoc(doc: Doc) {
        if (_docs.value.any { it.id == doc.id }) return
        val list = _docs.value
        // 关掉最后一篇时会留下一个空白占位，撤销时直接用它换回来，免得越撤越多
        val placeholder = list.size == 1 && list.first().text.isBlank() && list.first().uri == null
        _docs.value = if (placeholder) listOf(doc) else listOf(doc) + list
        _activeDocId.value = doc.id
        prefs.activeDocId = doc.id
        openDoc(doc.id)
        scheduleSave()
    }

    /** 重命名：只改标题，正文与来源路径不动。 */
    fun renameDoc(id: Long, title: String) {
        val name = title.trim()
        if (name.isEmpty()) return
        _docs.update { list -> list.map { if (it.id == id) it.copy(title = name) else it } }
        scheduleSave()
    }

    /** 文档内容自动保存到应用目录，连续输入时延迟合并成一次写盘。 */
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            val snapshot = _docs.value
            withContext(Dispatchers.IO) { docStore.save(snapshot) }
        }
    }

    private fun saveNow() {
        saveJob?.cancel()
        val snapshot = _docs.value
        viewModelScope.launch(Dispatchers.IO) { docStore.save(snapshot) }
    }

    override fun onCleared() {
        saveJob?.cancel()
        docStore.save(_docs.value)
        super.onCleared()
    }

    // ------------------------------------------------------------ 编辑区

    fun onEditorChange(value: TextFieldValue) {
        val previous = _editor.value
        if (value.text != previous.text) {
            recordUndo(previous)
            updateActiveText(value.text)
        }
        _editor.value = value
        prefs.draft = value.text
    }

    /** 整篇替换当前文档（应用结果、恢复备份时使用）。 */
    fun setEditorText(text: String) {
        _editor.value = TextFieldValue(text, TextRange(text.length))
        prefs.draft = text
        updateActiveText(text)
    }

    /**
     * 从系统文件选择器导入文档：作为新的一篇打开，不覆盖当前正在编辑的内容。
     * 关闭新文档即可撤回这次导入。
     */
    suspend fun importDocument(context: Context, uri: Uri): ImportOutcome {
        _importing.value = true
        return try {
            val document = DocumentImporter.load(context, uri)
            val path = DocumentImporter.displayPath(context, uri)
            val doc = Doc(
                id = newId(),
                title = document.name,
                subtitle = path.ifBlank { "导入的文件" },
                uri = uri.toString(),
                text = document.text,
            )
            _docs.update { listOf(doc) + it }
            _activeDocId.value = doc.id
            prefs.activeDocId = doc.id
            openDoc(doc.id)
            scheduleSave()
            val hint = if (prefs.chunked && document.text.length > prefs.chunkLimit) "，发送时会自动分段" else ""
            maybeAutoBackup()
            ImportOutcome(true, "已打开 ${document.name}（${document.text.length} 字）$hint", doc.id)
        } catch (e: Exception) {
            ImportOutcome(false, "导入失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            _importing.value = false
        }
    }

    fun clearEditor() {
        if (_editor.value.text.isEmpty()) return
        pushSnapshot(_editor.value)
        setEditorText("")
    }

    /** 撤销上一步：输入、清空、应用结果都算一步，只影响当前这篇文档。 */
    fun undo(): Boolean {
        val history = history()
        val snapshot = history.undo.removeLastOrNull() ?: return false
        history.redo.addLast(_editor.value)
        history.burstActive = false
        applyEditor(snapshot)
        refreshHistoryFlags()
        return true
    }

    /** 恢复刚撤销的那一步。 */
    fun redo(): Boolean {
        val history = history()
        val snapshot = history.redo.removeLastOrNull() ?: return false
        history.undo.addLast(_editor.value)
        history.burstActive = false
        applyEditor(snapshot)
        refreshHistoryFlags()
        return true
    }

    private fun applyEditor(value: TextFieldValue) {
        _editor.value = value
        prefs.draft = value.text
        updateActiveText(value.text)
    }

    /**
     * 连续输入合并成一步：800 ms 内的连击只记起始状态，
     * 撤销时整段回退，而不是一个字一个字往回退。
     */
    private fun recordUndo(previous: TextFieldValue) {
        val history = history()
        val now = android.os.SystemClock.uptimeMillis()
        val continuing = history.burstActive && now - history.lastEditAt < BURST_WINDOW_MS
        history.lastEditAt = now
        history.burstActive = true
        if (continuing) {
            // 新输入一旦开始，原来的「恢复」分支就作废
            history.redo.clear()
            refreshHistoryFlags()
        } else {
            pushSnapshot(previous, keepBurst = true)
        }
    }

    /** 清空、应用结果这类一次性动作，各自占一步。 */
    private fun pushSnapshot(snapshot: TextFieldValue, keepBurst: Boolean = false) {
        val history = history()
        history.undo.addLast(snapshot)
        if (!keepBurst) history.burstActive = false
        // 同时按步数与总字数回收，避免长文档下快照堆积占内存
        var total = history.undo.sumOf { it.text.length }
        while (history.undo.size > 1 &&
            (history.undo.size > MAX_UNDO_STEPS || total > MAX_UNDO_CHARS)
        ) {
            total -= history.undo.removeFirst().text.length
        }
        history.redo.clear()
        refreshHistoryFlags()
    }

    private fun refreshHistoryFlags() {
        val history = histories[_activeDocId.value]
        _canUndo.value = history?.undo?.isNotEmpty() == true
        _canRedo.value = history?.redo?.isNotEmpty() == true
    }

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
        val name = backupManager.backupNow(_docs.value)
        _backup.value = backupManager.settings()
        return name
    }

    suspend fun listBackups(): List<RemoteFile> = backupManager.listBackups()

    /** 按覆盖 / 增量方式恢复，成功后立刻应用到文档列表与配置。 */
    suspend fun restoreBackup(file: RemoteFile, mode: RestoreMode): String {
        val result = backupManager.restore(file, mode, _docs.value)
        prefs.saveConfig(result.config)
        prefs.restoreApiKeys(result.apiKeys, overwrite = mode == RestoreMode.OVERWRITE)
        // 写回各服务商 Key 后重新读一次，保证内存配置与磁盘一致
        _config.value = prefs.loadConfig()
        val docCount = applyRestoredDocs(result)
        prefs.chunked = result.chunked
        prefs.chunkLimit = result.chunkLimit
        _ai.update { it.copy(chunked = result.chunked, chunkLimit = result.chunkLimit) }
        _backup.value = backupManager.settings()

        return when (mode) {
            RestoreMode.OVERWRITE -> "已覆盖恢复：配置 + $docCount"
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

    /**
     * 把备份里的文档写回列表：覆盖模式整体替换，增量模式只补本地没有的。
     * 老备份只有一篇 [MergeResult.draft]，此时按单独一篇处理。
     * 返回给用户看的描述。
     */
    private fun applyRestoredDocs(result: MergeResult): String {
        val incoming = result.documents
        if (incoming.isEmpty()) {
            // 老版本备份：只有一篇正文
            if (result.draft.isBlank()) return "文档"
            pushSnapshot(_editor.value)
            setEditorText(result.draft)
            return "文档（${result.draft.length} 字）"
        }

        val docs = DocCodec.reassign(incoming)
        val replaced = result.overwriteDocs || _docs.value.all { it.text.isBlank() }
        _docs.value = if (replaced) docs else _docs.value + docs
        if (replaced) _activeDocId.value = docs.first().id
        histories.clear()
        openDoc(_activeDocId.value)
        saveNow()
        return if (replaced) {
            "文档 ${docs.size} 篇（${docs.sumOf { it.text.length }} 字）"
        } else {
            "新增文档 ${docs.size} 篇"
        }
    }

    /** 到点就自动备份，失败静默（只在备份页留下结果）。 */
    fun maybeAutoBackup() {
        if (!backupManager.isAutoDue()) return
        // 既没有密钥也没有文档时不必生成一份空备份
        if (prefs.draft.isBlank() && prefs.loadConfig().apiKey.isBlank()) return
        viewModelScope.launch {
            runCatching { backupManager.backupNow(_docs.value) }
            _backup.value = backupManager.settings()
        }
    }

    // ------------------------------------------------------------ AI 请求

    fun setInstruction(text: String) = _ai.update { it.copy(instruction = text) }

    fun setRefineInstruction(text: String) = _ai.update { it.copy(refineInstruction = text) }

    fun setScope(scope: Scope) = _ai.update { it.copy(scope = scope) }

    /**
     * 打开面板前调用：上一次的结果若属于别的文档，先清掉，
     * 避免在 A 文档生成的内容被写进 B 文档。
     */
    fun ensureSessionForActiveDoc() {
        val state = _ai.value
        if (state.running) return
        if (state.docId != null && state.docId != _activeDocId.value) newSession()
    }

    /** 在预览区手动修改 AI 结果：修改后的文本同样可应用、可二次 AI 编辑、可保存。 */
    fun setOutput(text: String) {
        val state = _ai.value
        if (state.running || text == state.output) return
        _ai.update { it.copy(output = text, outputEdited = true) }
    }

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

    /** 首轮：处理编辑区里的目标文本；编辑区为空时改为按指令从零生成。 */
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
            // 没有原文：把指令当成创作要求，生成的内容可以一键写入空白编辑区
            pendingRange = null
            startEdit(
                instruction = instruction,
                context = "",
                sourceText = "",
                isRefine = false,
                pushVersion = null,
                clearRefineInput = false,
                isGenerate = true,
            )
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
            isGenerate = false,
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
                outputEdited = false,
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
        isGenerate: Boolean = false,
    ) {
        val state = _ai.value
        // 生成模式没有原文，不切段
        val chunks = when {
            isGenerate -> listOf(TextChunker.Chunk("", ""))
            state.chunked -> TextChunker.split(context, state.chunkLimit)
            else -> listOf(TextChunker.Chunk(context, ""))
        }
        val total = chunks.size

        _ai.update {
            it.copy(
                running = true,
                docId = _activeDocId.value,
                output = "",
                status = when {
                    isRefine -> "正在按新指令修改结果…"
                    isGenerate -> "正在按你的要求生成内容…"
                    total > 1 -> "文本较长，已切分为 $total 段，开始处理第 1 段…"
                    else -> "正在生成…"
                },
                isError = false,
                chunkCount = total,
                chunkProgress = if (total > 1) 1 else 0,
                sourceText = sourceText,
                generating = isGenerate,
                outputEdited = false,
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
                        isGenerate = isGenerate,
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
                docId = null,
                sourceText = "",
                versions = emptyList(),
                status = "就绪。",
                isError = false,
                generating = false,
                outputEdited = false,
                refineInstruction = "",
                chunkCount = 0,
                chunkProgress = 0,
            )
        }
    }

    /** 清空预览区的结果，回到刚打开面板的状态（历史里仍留有副本）。 */
    fun clearOutput() {
        if (_ai.value.running) return
        newSession()
    }

    private fun buildMessages(
        instruction: String,
        context: String,
        isRefine: Boolean,
        isGenerate: Boolean = false,
        segmentIndex: Int = 0,
        segmentTotal: Int = 1,
    ): List<ChatMessage> {
        val system = _config.value.systemPrompt
        return if (isGenerate) {
            PromptBuilder.generate(system, instruction)
        } else {
            PromptBuilder.edit(system, instruction, context, isRefine, segmentIndex, segmentTotal)
        }
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
                outputEdited = false,
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

    /** 把 AI 输出写回它所属的文档，成功返回 true。 */
    fun applyResult(): Boolean {
        val state = _ai.value
        val output = state.output.trim()
        if (output.isEmpty()) return false
        val range = pendingRange
        // 结果属于另一篇文档时先切过去，再写入；否则会改错文件
        val targetId = state.docId
        if (targetId != null && targetId != _activeDocId.value) {
            if (_docs.value.none { it.id == targetId }) {
                notify("这篇文档已经关闭，结果仍保留在历史里")
                return false
            }
            switchDoc(targetId)
            pendingRange = range
        }
        val value = _editor.value
        pushSnapshot(value)
        val (newText, cursor) = if (range == null) {
            // 生成模式下若用户在生成期间已经往编辑区写了内容，追加而不是覆盖
            val existing = value.text
            if (state.generating && existing.isNotBlank()) {
                val sep = if (existing.endsWith("\n")) "\n" else "\n\n"
                val merged = existing + sep + output
                merged to merged.length
            } else {
                output to output.length
            }
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

    private companion object {
        /** 撤销栈最多保留的步数。 */
        const val MAX_UNDO_STEPS = 60
        /** 撤销栈里快照正文的总字数上限，超出后从最旧的开始丢。 */
        const val MAX_UNDO_CHARS = 2_000_000
        /** 两次输入间隔小于该值时合并为一步撤销。 */
        const val BURST_WINDOW_MS = 800L
    }
}
