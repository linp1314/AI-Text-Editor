package com.aieditor.text.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aieditor.text.data.ApiConfig
import com.aieditor.text.data.ApiKeySwitcher
import com.aieditor.text.data.Presets
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    initial: ApiConfig,
    onDismiss: () -> Unit,
    onSave: (ApiConfig, Map<String, String>) -> Unit,
    onTest: suspend (ApiConfig) -> String,
    onOpenBackup: () -> Unit,
    onOpenAbout: () -> Unit,
    /** 读取某个接口地址已保存的 Key，用于切换服务商时回填。 */
    lookupKey: (String) -> String = { "" },
    /** 拉取该地址的可用模型列表。 */
    onFetchModels: suspend (ApiConfig) -> List<String> = { emptyList() },
    /** 结果历史保留条数（0 = 关闭）。 */
    historyLimit: Int = 3,
    onHistoryLimitChange: (Int) -> Unit = {},
) {
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var temperature by remember { mutableStateOf(initial.temperature) }
    var maxTokens by remember { mutableStateOf(initial.maxTokens.toString()) }
    var systemPrompt by remember { mutableStateOf(initial.systemPrompt) }
    var stream by remember { mutableStateOf(initial.stream) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf("") }
    var fetchingModels by remember { mutableStateOf(false) }
    var modelOptions by remember { mutableStateOf<List<String>?>(null) }
    var modelError by remember { mutableStateOf("") }
    val switcher = remember { ApiKeySwitcher(initial.baseUrl, initial.apiKey, lookupKey) }
    val scope = rememberCoroutineScope()

    /** 切换接口地址：Key 跟着换成该地址自己保存的那把，没保存过就是空。 */
    fun applyUrl(newBase: String, newModel: String? = null, newPrompt: String? = null) {
        apiKey = switcher.moveTo(newBase, apiKey)
        baseUrl = newBase
        if (newModel != null) model = newModel
        newPrompt?.let { systemPrompt = it }
        testResult = ""
        saveError = ""
    }

    fun collect(): ApiConfig = ApiConfig(
        baseUrl = baseUrl.trim().ifBlank { ApiConfig.DEFAULT_BASE_URL },
        apiKey = apiKey.trim(),
        model = model.trim().ifBlank { ApiConfig.DEFAULT_MODEL },
        temperature = temperature,
        maxTokens = (maxTokens.toIntOrNull() ?: 4096).coerceIn(64, 32768),
        systemPrompt = systemPrompt.ifBlank { ApiConfig.DEFAULT_SYSTEM_PROMPT },
        stream = stream,
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("配置 API", fontSize = 19.sp, fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp),
                ) {
                    Text(
                        "支持任何兼容 OpenAI Chat Completions 的服务。选下面任意预设可一键填入，也可以选「自定义」自己填地址与模型名。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(14.dp))
                    Text("快速预设", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Presets.all.forEach { preset ->
                            val matched = Presets.match(baseUrl)
                            val selected = if (preset.custom) {
                                matched == null
                            } else {
                                matched?.name == preset.name
                            }
                            PillChip(
                                text = preset.name,
                                selected = selected,
                                onClick = {
                                    if (preset.custom) {
                                        applyUrl("", newModel = "")
                                    } else {
                                        applyUrl(preset.baseUrl, newModel = preset.model)
                                    }
                                },
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Field(
                        label = "接口地址 Base URL",
                        value = baseUrl,
                        onValueChange = {
                            // 手动改地址时保留已填的 Key：可能只是换了个接入点/代理，
                            // 切换服务商仍走上面的预设按钮，由它负责换成该服务商自己的 Key
                            baseUrl = it
                            saveError = ""
                        },
                        placeholder = "https://api.deepseek.com/v1",
                        supporting = "只填到 /v1 即可；程序会自动补 /chat/completions",
                    )

                    Spacer(Modifier.height(12.dp))
                    Field(
                        label = "API Key",
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        placeholder = "sk-...（自建服务无鉴权时可留空）",
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailing = {
                            TextButton(onClick = { showKey = !showKey }) {
                                Text(if (showKey) "隐藏" else "显示", fontSize = 12.sp)
                            }
                        },
                    )

                    Spacer(Modifier.height(12.dp))
                    Field(
                        label = "模型名称",
                        value = model,
                        onValueChange = { model = it; modelError = "" },
                        placeholder = "deepseek-chat",
                        supporting = "按服务商文档填写，或点右侧「拉取」从账号可用列表中选择",
                        trailing = {
                            TextButton(
                                enabled = !fetchingModels,
                                onClick = {
                                    if (baseUrl.trim().isBlank()) {
                                        modelError = "请先填写接口地址"
                                        return@TextButton
                                    }
                                    fetchingModels = true
                                    modelError = ""
                                    scope.launch {
                                        runCatching { onFetchModels(collect()) }
                                            .onSuccess { list ->
                                                if (list.isEmpty()) {
                                                    modelError = "该服务没有返回可用模型，请手动填写"
                                                } else {
                                                    modelOptions = list
                                                }
                                            }
                                            .onFailure {
                                                modelError = "拉取失败：${it.message ?: it.javaClass.simpleName}"
                                            }
                                        fetchingModels = false
                                    }
                                },
                            ) {
                                if (fetchingModels) {
                                    CircularProgressIndicator(
                                        Modifier.width(13.dp).height(13.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(if (fetchingModels) "拉取中" else "拉取", fontSize = 12.sp)
                            }
                        },
                    )
                    if (modelError.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            modelError,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("生成参数", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("温度", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            String.format(Locale.getDefault(), "%.1f", temperature),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.width(8.dp))
                        Slider(
                            value = temperature,
                            onValueChange = { temperature = it },
                            valueRange = 0f..1.5f,
                            steps = 14,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        "改写类任务建议 0.5–0.8；需要严格忠于原文时用 0.2–0.4。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )

                    Spacer(Modifier.height(10.dp))
                    Field(
                        label = "最大输出 tokens",
                        value = maxTokens,
                        onValueChange = { input -> maxTokens = input.filter { it.isDigit() }.take(5) },
                        keyboardType = KeyboardType.Number,
                        supporting = "长文改写可设 4096–8192，注意模型上下文上限",
                    )

                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("流式输出", fontSize = 14.sp)
                            Text(
                                "边生成边显示，速度更快；个别服务不支持时可关闭",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Switch(checked = stream, onCheckedChange = { stream = it })
                    }

                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it },
                        label = { Text("系统提示词") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 5,
                        maxLines = 10,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                    )

                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlineButton(
                            text = if (testing) "正在测试…" else "测试连接",
                            enabled = !testing,
                            onClick = {
                                testing = true
                                testResult = ""
                                scope.launch {
                                    testResult = onTest(collect())
                                    testing = false
                                }
                            },
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            applyUrl(
                                ApiConfig.DEFAULT_BASE_URL,
                                newModel = ApiConfig.DEFAULT_MODEL,
                                newPrompt = ApiConfig.DEFAULT_SYSTEM_PROMPT,
                            )
                            temperature = 0.6f
                            maxTokens = "4096"
                            stream = true
                        }) {
                            Text("恢复默认", fontSize = 13.sp)
                        }
                    }

                    if (testResult.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            testResult,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = if (testResult.startsWith("连接成功")) BrandGreen else MaterialTheme.colorScheme.error,
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "密钥保存在本机应用私有目录，仅用于向你自己填写的接口发请求；本应用不经过任何中间服务器。",
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )

                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(12.dp))
                    HistoryLimitSetting(limit = historyLimit, onChange = onHistoryLimitChange)
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsEntry(
                        title = "WebDAV 备份",
                        subtitle = "备份配置与文档，支持手动 / 自动备份",
                        onClick = onOpenBackup,
                    )
                    SettingsEntry(
                        title = "关于",
                        subtitle = "版本号与更新记录",
                        onClick = onOpenAbout,
                    )
                    Spacer(Modifier.height(20.dp))
                }

                Surface(tonalElevation = 3.dp) {
                    Column {
                        if (saveError.isNotEmpty()) {
                            Text(
                                saveError,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlineButton(
                                text = "取消",
                                modifier = Modifier.weight(1f),
                                onClick = onDismiss,
                            )
                            Spacer(Modifier.width(10.dp))
                            PrimaryButton(
                                text = "保存",
                                modifier = Modifier.weight(1.4f),
                                onClick = {
                                    if (baseUrl.trim().isBlank()) {
                                        saveError = "请先填写接口地址，或点上面任一服务商预设"
                                    } else {
                                        saveError = ""
                                        onSave(collect(), switcher.snapshot(apiKey))
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    modelOptions?.let { options ->
        ModelPickerDialog(
            models = options,
            current = model,
            onPick = {
                model = it
                modelError = ""
                testResult = ""
                modelOptions = null
            },
            onDismiss = { modelOptions = null },
        )
    }
}

@Composable
private fun ModelPickerDialog(
    models: List<String>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = if (query.isBlank()) {
        models
    } else {
        models.filter { it.contains(query.trim(), ignoreCase = true) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 4.dp,
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "可用模型（${models.size} 个）",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索模型名称", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                if (filtered.isEmpty()) {
                    Text(
                        "没有匹配的模型",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(filtered) { id ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onPick(id) }
                                    .padding(vertical = 10.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    id,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                    color = if (id == current) BrandGreen else MaterialTheme.colorScheme.onSurface,
                                )
                                if (id == current) {
                                    Text("当前", fontSize = 11.sp, color = BrandGreen)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 13.sp) },
        placeholder = { Text(placeholder, fontSize = 13.sp) },
        modifier = modifier.fillMaxWidth(),
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

@Composable
private fun HistoryLimitSetting(limit: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("结果历史", fontSize = 14.sp)
            Text(
                when (limit) {
                    0 -> "已关闭：生成结果不保存到本地"
                    else -> "本机保留最近 $limit 条生成结果，超出后删最旧的"
                },
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        OutlinedButton(
            onClick = { onChange((limit - 1).coerceAtLeast(0)) },
            enabled = limit > 0,
            modifier = Modifier
                .width(38.dp)
                .height(36.dp),
            contentPadding = PaddingValues(0.dp),
            shape = RoundedCornerShape(9.dp),
        ) { Text("−", fontSize = 16.sp) }
        Text(
            "$limit",
            modifier = Modifier.width(34.dp),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            color = if (limit == 0) MaterialTheme.colorScheme.outline else BrandGreen,
        )
        OutlinedButton(
            onClick = { onChange((limit + 1).coerceAtMost(20)) },
            enabled = limit < 20,
            modifier = Modifier
                .width(38.dp)
                .height(36.dp),
            contentPadding = PaddingValues(0.dp),
            shape = RoundedCornerShape(9.dp),
        ) { Text("+", fontSize = 16.sp) }
    }
}

@Composable
private fun SettingsEntry(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(
                subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.outline)
    }
}
