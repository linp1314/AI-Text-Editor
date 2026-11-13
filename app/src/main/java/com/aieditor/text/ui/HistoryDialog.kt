package com.aieditor.text.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aieditor.text.data.HistoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 本地保存的生成结果列表。 */
@Composable
fun HistoryDialog(
    entries: List<HistoryEntry>,
    limit: Int,
    onLoad: (HistoryEntry) -> Unit,
    onDelete: (Long) -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .height(560.dp),
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 2.dp,
            shadowElevation = 16.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(start = 18.dp, end = 8.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("结果历史", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (limit <= 0) {
                                "当前未启用：结果不会保存到本地"
                            } else {
                                "本地保留最近 $limit 条，超出后自动删除最旧的"
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
                }

                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (entries.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (limit <= 0) "结果保存已关闭" else "还没有生成记录",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (limit <= 0) {
                                "把保留条数改成 1 以上即可自动保存每次结果"
                            } else {
                                "每次 AI 生成完成后会在这里留一份副本"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(14.dp))
                        TextButton(onClick = onOpenSettings) { Text("去调整保留条数", fontSize = 13.sp) }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 14.dp),
                        contentPadding = PaddingValues(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(entries, key = { it.id }) { entry ->
                            HistoryRow(
                                entry = entry,
                                time = timeFormat.format(Date(entry.createdAt)),
                                onLoad = { onLoad(entry) },
                                onDelete = { onDelete(entry.id) },
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (entries.isEmpty()) "" else "共 ${entries.size} 条",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { confirmClear = true },
                        enabled = entries.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) { Text("清空", fontSize = 13.sp) }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部历史？") },
            text = { Text("将删除本地保存的 ${entries.size} 条生成结果，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    time: String,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(time, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            if (entry.revision > 1) {
                Text(
                    "  第 ${entry.revision} 版",
                    fontSize = 11.sp,
                    color = BrandGreen,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${entry.charCount} 字",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            entry.instruction.ifBlank { "（无指令）" },
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            entry.text.replace('\n', ' '),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onLoad,
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.height(32.dp),
            ) { Text("载入", fontSize = 13.sp, color = BrandGreen) }
            TextButton(
                onClick = onDelete,
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.height(32.dp),
            ) {
                Text("删除", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
