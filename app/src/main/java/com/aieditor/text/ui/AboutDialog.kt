package com.aieditor.text.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aieditor.text.BuildConfig
import com.aieditor.text.R
import com.aieditor.text.data.Changelog
import androidx.compose.ui.res.painterResource

/** 关于页：版本号 + 历次更新内容。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("关于", fontSize = 19.sp, fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.mipmap.ic_launcher),
                            contentDescription = null,
                            modifier = Modifier
                                .size(58.dp)
                                .clip(RoundedCornerShape(14.dp)),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("AI 文本编辑", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "版本 ${BuildConfig.VERSION_NAME}（build ${BuildConfig.VERSION_CODE}）",
                                fontSize = 12.sp,
                                color = BrandGreen,
                            )
                            Text(
                                "数据只存在本机与你的 WebDAV",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        "一个轻量的文本编辑器：接入任意 OpenAI 兼容接口，用一句话完成润色、改写、翻译、校对；" +
                            "支持导入手机文档、长文自动分段、改动对比与二次编辑。",
                        fontSize = 12.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(14.dp))

                    Text("更新记录", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))

                    Changelog.releases.forEach { release ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "v${release.version}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BrandGreen,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                release.date,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            if (release.version == BuildConfig.VERSION_NAME) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "当前版本",
                                    fontSize = 10.sp,
                                    color = BrandGreen,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(BrandGreen.copy(alpha = 0.12f))
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        release.items.forEach { item ->
                            Row {
                                Text(
                                    "·",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.width(10.dp),
                                )
                                Text(
                                    item,
                                    fontSize = 12.sp,
                                    lineHeight = 19.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(3.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "技术栈：Kotlin · Jetpack Compose · Material 3 · OkHttp · PdfBox-Android\n" +
                            "API 密钥仅保存在本机，所有请求直连你自己填写的接口，不经过任何中间服务器。",
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
