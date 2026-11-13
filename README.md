# AI 文本编辑（Android）

轻量的 Android 文本编辑器：接入**任意 OpenAI 兼容接口**，用一句自然语言指令润色、改写、翻译文本；可从手机导入 docx / pdf / txt 等文档，结果直接写回编辑区。

当前版本 **1.9.0** ｜ [下载 APK](https://github.com/linp1314/AI-Text-Editor/releases/latest) ｜ 需要 Android 7.0+（arm64）

## 功能

| 模块 | 说明 |
| --- | --- |
| 编辑区 | 全屏纯文本编辑，字数 / 行数统计，草稿自动保存 |
| 撤销 / 恢复 | 顶栏一键撤销、恢复；连续输入合并成一步，导入与写回同样可撤 |
| 清空 | 顶栏清空整篇文档；AI 面板内清空当前结果，编辑区内容不受影响 |
| 导入文档 | txt / md / csv / json / 代码 / html / **docx** / **pdf**，走系统文件选择器，不需要存储权限 |
| 从零生成 | 编辑区为空时自动切到生成模式，写下要求就能直接产出内容，结果「写入」编辑区 |
| AI 面板 | 一句话指令 + 快捷标签（润色 / 精简 / 纠错 / 翻译 / 扩写 / 改语气） |
| 处理范围 | 整个文件 / 选中文本 / 当前段落 |
| 长文分段 | 超长文本按段落 → 句子边界切块，逐段处理再按原排版合并；中途停止或某段失败时已完成段保留结果、其余回落原文 |
| 流式输出 | SSE 增量渲染，随时停止，已生成部分保留 |
| 二次编辑 | 在结果上继续下指令，多轮迭代，一键回退上一版 |
| 手动改结果 | 预览区点铅笔即可直接编辑 AI 输出，改完可应用、可保存、也可继续交给 AI |
| 改动对比 | 逐行 LCS + 行内字符级 diff，新增绿底、删除红底删除线，未改动行自动折叠 |
| 多服务商 | 每个接口地址独立保存 API Key，切换服务商不会串用 |
| 拉取模型 | 点「拉取」请求 `{地址}/models` 取回可用模型，可搜索后点选 |
| 系统提示词预设 | 默认编辑助手 / 代码助手 / 翻译助手 / 润色助手 / 精简助手，点一下套用，改字自动转为自定义 |
| 结果历史 | 生成结果自动缓存到本机，默认 3 条，可自定义 0–20 条，填 0 关闭 |
| WebDAV 备份 | 手动 / 自动（12 小时 / 每天 / 每周）备份配置与文档，恢复支持覆盖或增量 |
| 统一设置 | 只有右上角齿轮一个入口：接口、生成参数、系统提示词、历史、备份、关于都在这一页 |

## 快速上手

打开应用 → 右上角齿轮（设置）→ 填三项即可用：

| 服务 | Base URL | 模型示例 |
| --- | --- | --- |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| 硅基流动 | `https://api.siliconflow.cn/v1` | `Qwen/Qwen2.5-72B-Instruct` |
| 月之暗面 | `https://api.moonshot.cn/v1` | `moonshot-v1-8k` |
| 自建 / 中转 | 你的地址，填到 `/v1` | 看服务说明 |

- Base URL 填到 `/v1` 即可，程序自动补 `/chat/completions`；粘贴完整端点也能识别。
- 没写协议时：`localhost` / `127.0.0.1` / `192.168.*` / `10.*` / `172.16–31.*` 走 `http`，其它走 `https`。
- 点「测试连接」验证地址、密钥与模型名，连不上会直接报错（6 秒超时）。

## 隐私与安全

- **API Key 只存在本机**：写入应用私有目录的 SharedPreferences，不上传任何第三方，请求直连你填写的接口地址，没有中间服务器。
- **系统云备份已关闭**（`allowBackup=false`），API Key、草稿、结果历史不会随手机备份同步到 Google / 厂商云。
- **WebDAV 备份是明文 JSON**，含接口地址、API Key 与当前文档。备份页有明确提示，请只放到自己可信的目录。
- **结果历史**存放在应用私有目录，可在设置里填 0 关闭，或随时在「历史」里删除、清空。
- **导入文档**通过 SAF 只读取你选中的那一个文件，应用不申请存储权限。
- 应用允许明文 HTTP，仅为兼容局域网自建服务；公网接口请一律用 `https://`。

## 构建

```bash
# 需要 JDK 17 + Android SDK（platform 34 / build-tools 34+）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # 单元测试
./gradlew :app:assembleRelease      # 正式包（未配签名时自动退回 debug 签名）
```

Android Studio（Koala 2024.1+）直接 `Open` 本目录，Run 即可。

**签名**：密钥不入库。在 `local.properties` 里加四项即可出正式包，也支持同名环境变量；`RELEASE_STORE_FILE` 相对项目根目录解析。

```properties
RELEASE_STORE_FILE=app/keystore/release.jks
RELEASE_STORE_PASSWORD=你的口令
RELEASE_KEY_ALIAS=你的别名
RELEASE_KEY_PASSWORD=你的口令
```

## 项目结构

```
app/src/main/java/com/aieditor/text/
├── AppViewModel.kt          状态机：请求、分段、二次编辑、版本回退、写回撤销
├── MainActivity.kt          入口，处理「分享文本」进入
├── data/
│   ├── ApiConfig.kt         配置、服务预设、SharedPreferences 持久化
│   ├── ApiKeys.kt           API Key 按接口地址分槽
│   ├── AiClient.kt          OkHttp + SSE 流式、模型列表、错误信息
│   ├── DocumentImporter.kt  编码识别、docx / pdf / html 解析
│   ├── TextChunker.kt       长文切分与合并还原
│   ├── TextDiff.kt          行级 LCS + 行内字符级 diff
│   ├── WebDavClient.kt      PROPFIND / GET / PUT / MKCOL
│   ├── BackupManager.kt     备份打包、上传、列表、恢复
│   ├── ResultHistory.kt     结果本地缓存
│   ├── PromptPresets.kt     系统提示词预设
│   └── Changelog.kt         版本更新记录
└── ui/                      主界面、AI 面板、配置、备份、历史、关于
```

Kotlin 1.9.24 / Jetpack Compose（BOM 2024.09）/ Material 3 / OkHttp 4.12 / PdfBox-Android / AGP 8.5.2 / minSdk 24 / targetSdk 34。61 个单元测试覆盖端点推导、SSE 解析、密钥分槽、备份合并、切分、diff、提示词预设。依赖只有 Compose、OkHttp、PdfBox 与协程，**不含任何统计、广告或崩溃上报 SDK**。

## 发版

```bash
./scripts/release.sh 1.9.1 "新增批量导出|修复分段重叠"
```

脚本依次完成：`versionCode` +1、`versionName` 与 README 版本号同步、更新记录写入 `Changelog.kt`（只保留最近 5 条）、跑测试、构建 release 包、校验签名指纹、提交、打 tag、推送。检测到 `.github/workflows/release.yml` 时，构建与发布交给 GitHub Actions，本地不再重复上传。

CI 的 `.github/workflows/release.yml` 在推送 `v*` tag 时跑测试 → 构建 → `zipalign -c -P 16` 校验 16KB 对齐 → 生成 `SHA256SUMS.txt` → 发布到 Releases（手动触发走草稿）。要产出与本地同一签名的包，在仓库 Secrets 里加 `ANDROID_KEYSTORE_BASE64`（keystore 的 base64）、`ANDROID_STORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`；不配也能跑，只是产物带 `-debug-signed` 后缀。

## 更新记录

**1.9.0**（2026-11-13）
- 设置统一到一个入口：右上角齿轮进入设置页，接口、生成参数、提示词、历史、备份、关于都在这一页
- 顶栏新增撤销 / 恢复 / 清空；连续输入合并成一步撤销，撤销后还能恢复
- AI 面板底部新增「清空」，只清当前结果，编辑区不动，历史里仍可找回
- 新增系统提示词预设：默认编辑助手 / 代码助手 / 翻译助手 / 润色助手 / 精简助手

**1.8.0**（2026-11-13）
- 输出预览可直接手动编辑：点右上角铅笔进入，改完可应用 / 保存 / 继续让 AI 改
- 新增「保存」：结果（含手动修改）存成 txt 或分享给其他应用
- 界面精简，按钮整合：发送兼作停止，范围与分段合并为下拉，配置与关闭移到标题栏
- 统一全应用按钮与选项的尺寸、圆角、配色

**1.7.0**（2026-11-13）
- 新增「从零生成」：编辑区为空时自动切到生成模式，写下要求直接产出内容
- 生成模式快捷短语换成创作类，处理范围与长文分段自动隐藏
- 结果点「写入」落到编辑区；生成期间自己写的会追加而不是覆盖

**1.6.1**（2026-11-13）
- 关闭 Android 系统云备份，API Key、草稿与结果历史不再随手机备份同步到云端
- WebDAV 备份页新增明文提示；更新记录精简为最近 5 个版本

**1.6.0**（2026-11-13）
- 生成结果自动缓存到本机，默认保留 3 条，可自定义 0–20 条，填 0 关闭
- 面板右上角「历史」入口：查看时间 / 指令 / 字数，支持载入、删除、清空

更早的版本见 [Releases](https://github.com/linp1314/AI-Text-Editor/releases)。

## 常见问题

| 现象 | 处理 |
| --- | --- |
| 401 / 403 | 密钥无效或无该模型权限，检查 Key 是否带多余空格 |
| 404 | 地址或模型名不对，Base URL 一般填到 `/v1`，模型名区分大小写 |
| 一直「正在连接…」 | 网络到不了该域名，换国内可直连服务；局域网服务确认监听 `0.0.0.0` |
| 输出被截断 | 调大「最大输出 tokens」（4096–8192），别超模型上下文 |
| context length exceeded | 打开长文分段并把每段字数调小（如 800） |
| 预览空白但已完成 | 部分中转忽略 `stream: true`，在配置里关闭流式输出再试 |
