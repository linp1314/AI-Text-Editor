package com.aieditor.text.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 全应用共用的尺寸与圆角。
 * 之前每个界面各写一套（46/52/44 高度、8/9/10/18 圆角），
 * 统一到这里之后，按钮和选项的观感才一致。
 */
object Ui {
    val radius = 10.dp
    val radiusBig = 16.dp
    val button = 44.dp
    val chip = 32.dp
    val field = 44.dp
}

/** 主操作：实心品牌绿。 */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    height: Dp = Ui.button,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = if (danger) {
        ButtonDefaults.buttonColors(
            containerColor = scheme.error,
            contentColor = Color.White,
            disabledContainerColor = scheme.surfaceVariant.copy(alpha = 0.7f),
            disabledContentColor = scheme.outline,
        )
    } else {
        ButtonDefaults.buttonColors(
            containerColor = BrandGreen,
            contentColor = Color.White,
            disabledContainerColor = scheme.surfaceVariant.copy(alpha = 0.7f),
            disabledContentColor = scheme.outline,
        )
    }
    Button(
        onClick = onClick,
        modifier = modifier.height(height),
        enabled = enabled,
        shape = RoundedCornerShape(Ui.radius),
        colors = colors,
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 次操作：描边按钮，选中时用品牌绿浅底。 */
@Composable
fun OutlineButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    height: Dp = Ui.button,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(height),
        enabled = enabled,
        shape = RoundedCornerShape(Ui.radius),
        border = BorderStroke(
            1.dp,
            when {
                !enabled -> scheme.outlineVariant
                selected -> BrandGreen
                else -> scheme.outline
            },
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) BrandGreen.copy(alpha = 0.10f) else Color.Transparent,
            contentColor = if (selected) BrandGreen else scheme.onSurfaceVariant,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = scheme.outline.copy(alpha = 0.5f),
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** 快捷选项：胶囊小标签，和按钮同一套圆角与配色。 */
@Composable
fun PillChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val background = when {
        !enabled -> Color.Transparent
        selected -> scheme.primaryContainer
        else -> scheme.surfaceVariant.copy(alpha = 0.55f)
    }
    val contentColor = when {
        !enabled -> scheme.outline.copy(alpha = 0.5f)
        selected -> BrandGreen
        else -> scheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .height(Ui.chip)
            .clip(RoundedCornerShape(Ui.radius))
            .background(background)
            .border(
                1.dp,
                when {
                    !enabled -> scheme.outlineVariant
                    selected -> BrandGreen.copy(alpha = 0.45f)
                    else -> scheme.outlineVariant
                },
                RoundedCornerShape(Ui.radius),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/** 二选一的分段切换（结果 / 改动对比）。 */
@Composable
fun SegmentToggle(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Ui.radius))
            .background(scheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) scheme.surface else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            ) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) BrandGreen else scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 统一下拉选择：范围、分段粒度都用它，
 * 替代此前两套各写一遍的 ScopeSelector / ChunkLimitPicker。
 */
@Composable
fun DropdownSelect(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Ui.chip + 4.dp)
                .clip(RoundedCornerShape(Ui.radius))
                .background(scheme.surfaceVariant.copy(alpha = 0.45f))
                .border(1.dp, scheme.outlineVariant, RoundedCornerShape(Ui.radius))
                .clickable(enabled = enabled) {
                    if (onClick != null) onClick() else expanded = true
                }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = options.getOrNull(selectedIndex) ?: options.firstOrNull().orEmpty(),
                fontSize = 13.sp,
                color = if (enabled) scheme.onSurface else scheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            Text("▾", fontSize = 10.sp, color = scheme.outline)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(Ui.radius),
            containerColor = scheme.surfaceContainer,
            tonalElevation = 3.dp,
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option,
                            fontSize = 14.sp,
                            fontWeight = if (index == selectedIndex) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (index == selectedIndex) BrandGreen else scheme.onSurface,
                        )
                    },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 小标题：输出、状态等分组文字。 */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 12.sp,
        color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
