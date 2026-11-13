# AI 文本编辑（Android）

一个轻量的 Android 文本编辑器：接入**任意 OpenAI 兼容接口**（DeepSeek、OpenAI、硅基流动、Moonshot，以及自建网关 vLLM、one-api / new-api 等），用一句自然语言指令编辑、润色、优化你的文本；也可以直接**从手机里导入文档**（txt / md / docx / pdf 等）再处理，结果可直接写回编辑区。

当前版本 **1.5.2**。

界面按你给的参考图实现：指令输入 → 范围下拉 + 发送 → 状态（含停止 / 配置）→ 长文分段开关 + 每段字数 → AI 输出预览 → 对结果二次编辑 → 复制 / 导出 / 关闭 / 应用。

---

## 1. 功能

| 模块 | 说明 |
| --- | --- |
| 编辑区 | 全屏纯文本编辑，支持长按选择、字数 / 行数统计，草稿自动保存 |
| 导入文档 | 系统文件选择器导入 txt / md / csv / json / 日志 / 代码文件 / html / **docx** / **pdf**，替换编辑区内容并可撤销 |
| AI 面板 | 一句话指令 + 常用指令快捷芯片（润色 / 精简 / 纠错 / 翻译 / 加注释 / 扩写 / 改正式语气） |
| 处理范围 | 整个文件 / 选中文本 / 当前段落（未选中时自动退化为当前段落） |
| 长文分段 | 文本超过「每段字数」时自动按段落 / 句子边界切分，逐段处理再按原排版合并；中途停止或失败时已完成段保留 AI 结果、其余回落原文，不丢内容 |
| 流式输出 | SSE 增量渲染，随时「停止」，已生成的部分保留 |
| 二次编辑 | 结果出来后，可在底部输入框里继续下达指令（「再精简一半」「改成邮件口吻」），AI 在当前结果上继续改，形成多版迭代；`↺` 一键回退到上一版 |
| 结果处理 | 复制、导出（分享或保存为 .txt）、应用（写回原文，支持撤销） |
| 改动对比 | 结果旁可切换「结果 / 改动」：逐行比对原文，新增绿底、删除红底加删除线，行内精确到字；顶部显示 `+新增 / −删除` 字数，未改动行自动折叠、可点开 |
| 配置 | 接口地址、API Key、模型名、温度、最大 tokens、系统提示词、流式开关、连接测试；预设 DeepSeek / OpenAI / 硅基流动 / 月之暗面 / 自定义 |
| 拉取模型 | 模型名右侧「拉取」按钮请求 `{地址}/models` 取回账号可用模型，列表可搜索、点选回填 |
| 多服务商密钥 | API Key 按接口地址分开存储，切换服务商不会串用；没填过的服务商保持空白 |
| WebDAV 备份 | 手动 / 自动（12 小时 / 每天 / 每周）把配置与当前文档备份到自己的 WebDAV；恢复支持覆盖或增量补全 |
| 关于页 | 显示版本号与逐版本更新记录 |
| 接收分享 | 其他 App 里「分享文本」可直接发送到本应用并打开 AI 面板 |

**隐私**：API Key 只保存在本机应用私有目录（SharedPreferences），所有请求直连你填写的地址，不经过任何中间服务器。导入文档通过系统 SAF 只读取你选中的那一个文件，不需要申请存储权限。

### 支持导入的格式

| 类型 | 说明 |
| --- | --- |
| `.txt .md .markdown .csv .tsv .json .log .xml .yml .ini .srt` | 纯文本，自动识别 UTF-8（含 BOM）/ GBK 编码 |
| 代码文件 | `.java .kt .py .js .ts .css .sql .sh .c/cpp .go .rs .php .rb` 等按文本导入 |
| `.html .htm` | 去掉标签后取正文 |
| `.docx` | 直接解析 OOXML，保留段落与制表符（旧版 `.doc` 不支持，请另存为 docx 或 txt） |
| `.pdf` | 使用 PdfBox-Android 抽取文字，最多解析前 120 页；扫描版 / 图片型 PDF 无文字层，会提示“没有提取到文字” |

导入后会替换编辑区内容，底部提示条可「撤销」；超过分段阈值的文档会在提示里说明「发送时会自动分段」，无需手动挑选范围。

### 长文分段处理

编辑器里的文本太长（超出模型上下文）时，不必手动拆分：

1. AI 面板里的 **长文分段 · 开** 默认开启，右侧会实时显示「将分 N 段」；
2. **每段 N 字** 可切换 800 / 1500 / 2500 / 4000，默认 1500（中文约对应 1k~1.5k tokens 的量级）；
3. 点发送后按顺序逐段请求，状态行显示「分段 2/4 · 生成中… 12s」，预览区实时拼接已完成的段；
4. 全部完成后合并为一份完整文本，可直接「应用」回编辑区。

切分规则：**段落边界（空行）→ 句子边界（。！？；…）→ 空白 → 硬切**，块与块之间的原始空白会一并记录并在合并时还原，所以最终排版与原文一致；每段请求都会附带「这是第 x/n 段，只处理本段、不要输出说明文字」的提示，模型不会加多余的过渡语。

### 改动对比

AI 改完之后，点预览标题旁的 **改动 N** 即可看到具体改了哪里：

- **逐行对齐**：先用 LCS 按行比较，相邻的「删除 + 新增」会配成「修改」，读起来是逐句改写而不是整段替换；
- **行内高亮**：修改行内部再做字符级比较，`很好 → 非常出色` 这种只标出真正变化的几个字，删除部分红色删除线、新增部分绿底；
- **统计**：标题行右侧显示 `+64 −28`（新增 / 删除字符数），切换按钮上显示改动的行数；
- **折叠**：连续超过 4 行的未改动内容折叠成 `⋯ 7 行未改动（点按展开）`，一眼就能看到改动位置，点击可展开原文；
- **性能保护**：超长文本（行数超过约 2000×2000 组合）自动降级为「首尾相同行 + 中间整体改写」的粗粒度对比，不会卡死；单行超过 1200 字只整行标色，不做字符级细分。

对比只用于查看，复制 / 导出 / 应用拿到的始终是最终结果文本。改动始终对照「最初送出的原文」，所以多轮编辑后看到的是累计改了什么。

异常处理：
- **中途停止**：已完成的段用 AI 结果，未处理的段回落为原文，状态提示「已完成 2/4 段，其余保留原文」，应用后不会丢内容；
- **某段失败**：同样保留前序结果 + 其余原文，状态行标注失败段号与原因，可直接重试；
- **某段模型空回复**：该段自动保留原文并在底部提示，不会让结果出现空洞；
- **二次编辑同样分段**：每一轮都以当前结果为原文，重新按「每段字数」切分。

---

## 2. 构建

### 方式一：Android Studio

1. Android Studio（Koala 2024.1 及以上）→ `Open` → 选择本目录 `AiTextEditor`。
2. 首次同步会自动下载 Gradle 8.7 与依赖；JDK 需 17（Android Studio 自带）。
3. Run ▶ 即可安装到手机（需 Android 7.0 / API 24 及以上）。

### 方式二：命令行

```bash
# 需要 JDK 17 + Android SDK（platform 34、build-tools 34.0.0）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :app:assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # 运行 API 客户端单元测试
```

仓库内已包含 Gradle Wrapper（8.7）。若 `services.gradle.org` 访问慢，可以把 `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 换成国内镜像，例如：
`https\://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip`。

签名后的正式包：

```bash
./gradlew :app:assembleRelease   # 需先在 app/build.gradle.kts 中配置 signingConfigs
```

---

## 3. 配置接口

打开应用 → 右上角 ⚙︎ → 填三项即可用：

| 服务 | Base URL | 模型示例 |
| --- | --- | --- |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| 硅基流动 | `https://api.siliconflow.cn/v1` | `Qwen/Qwen2.5-72B-Instruct` |
| 月之暗面 | `https://api.moonshot.cn/v1` | `moonshot-v1-8k` |
| Ollama（局域网） | `http://192.168.1.10:11434/v1` | `qwen2.5:7b` |
| 自建 / 中转 | 你的地址，填到 `/v1` 即可 | 看服务说明 |

- Base URL 只填到 `/v1`，程序会自动补 `/chat/completions`；直接粘贴完整端点也能识别。
- 没写协议时：`localhost` / `127.0.0.1` / `192.168.*` / `10.*` / `172.16–31.*` 默认走 `http`，其它默认 `https`。
- 点「测试连接」可先验证地址、密钥和模型名是否可用。

### 关于二次编辑（多轮迭代）

结果不理想时不用重新来一遍，直接接着说：

1. 生成结果后，面板底部出现输入条 `继续改这段结果，如：再精简一半`；
2. 输入新指令（或按输入法回车键）→ 点「再改」；
3. AI 以**当前结果**为原文继续修改，产出第 2 版；可以一直改下去；
4. 点 `↺` 回到上一版，每点一次退一版，状态行会显示「已回到上一版（第 N 版）」。

几个细节：

- 二次编辑**不受首轮范围限制**：首轮只处理「当前段落」，二次编辑就是在这一段的结果上改，应用时仍写回同一位置；
- 每轮请求都不带对话历史，只把「当前结果」作为原文发送，避免多轮之后上下文膨胀或被旧版本干扰；
- 改动对比始终对照最初送出的原文，所以第 3 版看到的仍是「相对原文总共改了什么」；
- 某轮失败或中途停止且没有任何输出时，会自动恢复上一版结果，不会把预览区清空；
- 系统提示词（配置页）对首轮与二次编辑都生效。

---

## 4. 代码结构

```
app/src/main/java/com/aieditor/text/
├── MainActivity.kt             入口，处理「分享文本」进入
├── AppViewModel.kt             状态机：编辑区、首轮请求、二次编辑与版本回退、写回与撤销
├── data/
│   ├── ApiConfig.kt            API 配置、服务预设、SharedPreferences 持久化
│   ├── ApiKeys.kt              API Key 按接口地址分槽存储与切换
│   ├── AiClient.kt             OkHttp + org.json，SSE 流式 / 非流式、模型列表、错误信息解析
│   ├── DocumentImporter.kt     文档导入：编码识别、docx(OOXML)、pdf(PdfBox)、html
│   ├── TextChunker.kt          长文切分：段落 / 句子 / 硬切，保留分隔符可原样合并
│   ├── TextDiff.kt             改动对比：行级 LCS + 行内字符级 diff、折叠统计
│   ├── WebDavClient.kt         WebDAV：PROPFIND / GET / PUT / MKCOL
│   ├── BackupManager.kt        备份打包、上传、列表与恢复
│   ├── Backup.kt               备份 JSON 结构、覆盖 / 增量合并
│   ├── BackupSettings.kt       备份地址与自动备份间隔
│   └── Changelog.kt            版本更新记录
└── ui/
    ├── AppRoot.kt              主界面：编辑区、顶栏、底栏、面板与配置调度
    ├── AiPanel.kt              AI 文本编辑面板（对齐参考图）
    ├── SettingsDialog.kt       配置页（预设、密钥、模型拉取、测试连接）
    ├── BackupDialog.kt         WebDAV 备份与恢复
    ├── AboutDialog.kt          关于页（版本号与更新记录）
    ├── Theme.kt                Material 3 配色（品牌绿 #3CA55C）
    └── Widgets.kt              浅灰块按钮等通用控件

app/src/test/java/com/aieditor/text/data/
   AiClientTest.kt           端点推导、SSE 解析、模型列表、HTTP 报错（7 个）
   ApiKeySwitcherTest.kt     密钥分槽、切换、快照（6 个）
   BackupTest.kt             备份序列化、覆盖与增量合并（5 个）
   WebDavClientTest.kt       PROPFIND 解析、鉴权、路径拼接（5 个）
   DocumentImporterTest.kt   docx 段落/制表符解析、非法 docx 拒绝（2 个）
   TextChunkerTest.kt        切分上限、段落优先、合并还原、空文本（7 个）
   TextDiffTest.kt           行级增删改、修改配对、行内高亮、超长降级（9 个）
```

技术栈：Kotlin 1.9.24 / Jetpack Compose（BOM 2024.09）/ Material 3 / OkHttp 4.12 / PdfBox-Android 2.0.27 / AGP 8.5.2 / minSdk 24 / targetSdk 34。没有用注解处理器，构建快。

**签名说明**：签名密钥**不在仓库里**。要出正式签名包，在项目根目录的 `local.properties`（已被 `.gitignore` 忽略）里加四项：

```properties
RELEASE_STORE_FILE=app/keystore/release.jks
RELEASE_STORE_PASSWORD=你的口令
RELEASE_KEY_ALIAS=你的别名
RELEASE_KEY_PASSWORD=你的口令
```

`RELEASE_STORE_FILE` 相对项目根目录解析，也可以用同名环境变量提供。没有配置时，`assembleRelease` 会自动退回 debug 签名，克隆下来就能直接构建。正式分发请自己生成密钥并妥善备份——同一份密钥才能覆盖安装已有版本。

---

## 5. 常见问题

**401 / 403** 密钥无效或没有该模型权限，检查 Key 是否带了多余空格、是否支持所选模型。

**404** 地址或模型名不对。Base URL 一般填到 `/v1`；模型名必须与服务端一致（区分大小写）。

**一直「正在连接…」后超时** 手机网络访问不到该域名；换成国内可直连的服务，或检查是否被公司网络拦截。应用已允许明文 HTTP，局域网 Ollama 需保证手机和电脑同网段，并确认 Ollama 监听 `0.0.0.0`。

**输出被截断** 调大「最大输出 tokens」（长文改写建议 4096–8192），注意不要超过模型的上下文上限。

**报「context length exceeded」** 打开「长文分段」并把「每段字数」调小（如 800），让应用自动切块处理。

**预览是空白但状态显示完成** 部分中转服务会忽略 `stream: true` 直接返回 JSON，客户端已做兜底解析；若仍失败，在配置里关闭「流式输出」再试。

**想换一种改法** 直接在面板里再发一条指令（例如「再简洁一半」），会带上最近几轮上下文继续修改；点「应用」后原位置被替换，可用底部提示条的「撤销」回退。

---

## 6. 发版与自动推送

### 6.1 配置一次凭证

推送需要写权限。**HTTPS + PAT** 最省事（本机 SSH 22 端口常被运营商拦截）：

```bash
# 1. 生成 PAT：GitHub → Settings → Developer settings → Personal access tokens →
#    Tokens (classic) → Generate new token，勾选 repo
# 2. 记住凭证，之后 push 不再询问
git config --global credential.helper store
git push            # 用户名填 GitHub 用户名，密码粘贴 PAT
```

也可以直接用凭据嵌入远程地址（PAT 会被写进 `.git/config`，注意别提交）：

```bash
git remote set-url origin https://oauth2:<PAT>@github.com/linp1314/AI-Text-Editor.git
```

### 6.2 一条命令发版

```bash
./scripts/release.sh 1.5.3 "新增批量导出|修复分段重叠"
```

脚本会依次完成：

1. `versionCode` 自动 +1、`versionName` 改为 1.5.3
2. 把更新说明写进「关于」页的 `Changelog.kt`（日期取当天）
3. 跑单元测试 → 构建 release 包 → 校验签名指纹
4. 导出 `../AI文本编辑-v1.5.3.apk`
5. `git commit` → 打 tag `v1.5.3` → `git push origin main --tags`

推送失败时提交和 tag 会留在本地，配好凭证后补一条 `git push origin main --tags` 即可。

> 脚本拒绝在脏工作区或非 `main` 分支上发版，避免把半成品带进 tag。

### 6.3 打 tag 自动构建并发布 APK

`.github/workflows/release.yml` 会在推送 `v*` tag 时自动跑测试、构建 APK 并挂到 GitHub Releases。要在 CI 里产出**与本地同一签名**的包，需在仓库 Settings → Secrets and variables → Actions 添加：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 app/keystore/release.jks` 的输出 |
| `ANDROID_STORE_PASSWORD` | keystore 口令 |
| `ANDROID_KEY_ALIAS` | 密钥别名 |
| `ANDROID_KEY_PASSWORD` | 密钥口令 |

不配置这些 Secret 也能跑通，只是产物为 debug 签名，不能覆盖安装已发布的版本。

工作流也支持手动触发（Actions → Release → Run workflow），但版本号仍以仓库里的 `versionName` 为准。
