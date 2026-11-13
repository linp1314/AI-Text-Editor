package com.aieditor.text.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aieditor.text.R
import com.aieditor.text.data.Doc

/**
 * 左侧文档列表：参考 MT 管理器的文本编辑器，编辑区只留正文，
 * 打开过的文档都在这里排队，点一下就切过去，关掉也不影响其它篇。
 */
@Composable
fun DocDrawer(
    docs: List<Doc>,
    activeId: Long,
    importing: Boolean,
    onSelect: (Long) -> Unit,
    onClose: (Doc) -> Unit,
    onRename: (Doc) -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    ModalDrawerSheet(
        modifier = modifier.width(304.dp),
        drawerContainerColor = scheme.surface,
        drawerShape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp),
    ) {
        Column(Modifier.fillMaxHeight()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text("文档", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${docs.size} 篇打开",
                    fontSize = 11.sp,
                    color = scheme.outline,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }

            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.6f))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(docs, key = { it.id }) { doc ->
                    DocRow(
                        doc = doc,
                        active = doc.id == activeId,
                        onSelect = { onSelect(doc.id) },
                        onClose = { onClose(doc) },
                        onRename = { onRename(doc) },
                    )
                }
            }

            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.6f))
            Text(
                "长按可重命名；关闭只移出列表，内容仍保存在本机",
                fontSize = 11.sp,
                color = scheme.outline,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PrimaryButton(text = "新建文档", modifier = Modifier.weight(1f)) { onNew() }
                OutlineButton(
                    text = if (importing) "导入中" else "导入",
                    modifier = Modifier.width(84.dp),
                    enabled = !importing,
                ) { onImport() }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocRow(
    doc: Doc,
    active: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onRename: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = if (active) scheme.primary else scheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (active) BrandGreen.copy(alpha = 0.10f) else scheme.surface)
            .combinedClickable(onClick = onSelect, onLongClick = onRename),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (doc.uri == null) {
                // 还没对应到本地文件：和 MT 一样用铅笔标记，标题也走斜体
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = accent,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_file),
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = accent,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = doc.title,
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    fontStyle = if (doc.uri == null) FontStyle.Italic else FontStyle.Normal,
                    color = if (active) scheme.onSurface else scheme.onSurface.copy(alpha = 0.88f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = doc.subtitle.ifBlank { "未命名文档" },
                    fontSize = 10.sp,
                    color = scheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭 ${doc.title}",
                    modifier = Modifier.size(15.dp),
                    tint = scheme.outline.copy(alpha = 0.75f),
                )
            }
        }
        if (active) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                    .background(BrandGreen),
            )
        }
    }
}
