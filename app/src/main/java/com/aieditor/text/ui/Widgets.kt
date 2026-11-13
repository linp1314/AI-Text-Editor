package com.aieditor.text.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 参考图里那种浅灰块按钮：未启用时变淡，选中时用主色浅底。 */
@Composable
fun SoftButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    height: Int = 46,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val background = when {
        !enabled -> colors.surfaceVariant.copy(alpha = 0.45f)
        selected -> colors.primaryContainer
        else -> colors.surfaceVariant
    }
    val contentColor = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.4f)
        selected -> colors.onPrimaryContainer
        else -> colors.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .height(height.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}
