#!/usr/bin/env bash
#
# 一键发版：改版本号 → 写更新记录 → 跑测试 → 构建 APK → 提交 → 打 tag → 推送
#
# 用法：
#   ./scripts/release.sh 1.5.3 "新增批量导出|修复分段重叠"
#
# 说明：
#   - 第二个参数是更新说明，用 | 分隔多条，会写进「关于」页的更新记录
#   - 省略时用「常规更新与问题修复」
#   - 推送失败（无写权限）时，提交与 tag 已留在本地，配好凭证后执行
#     git push origin main --tags 即可补推

set -euo pipefail

VERSION="${1:-}"
NOTES_RAW="${2:-}"

if [ -z "$VERSION" ]; then
    echo "用法: ./scripts/release.sh <版本号> [\"更新说明1|更新说明2\"]"
    echo "例如: ./scripts/release.sh 1.5.3 \"新增批量导出|修复分段重叠\""
    exit 1
fi

if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "版本号格式应为 x.y.z，例如 1.5.3"
    exit 1
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# ---------- 前置检查 ----------
if [ -n "$(git status --porcelain)" ]; then
    echo "工作区有未提交的改动，请先提交或 stash："
    git status --short
    exit 1
fi

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [ "$BRANCH" != "main" ]; then
    echo "当前分支是 $BRANCH，发版请切到 main"
    exit 1
fi

# ---------- 更新版本号与更新记录 ----------
NOTES="$NOTES_RAW" python3 - "$VERSION" <<'PY'
import os, re, sys, datetime

version = sys.argv[1]
notes_raw = os.environ.get("NOTES", "").strip()
notes = [n.strip() for n in notes_raw.split("|") if n.strip()] or ["常规更新与问题修复"]

gradle = "app/build.gradle.kts"
s = open(gradle, encoding="utf-8").read()
m = re.search(r"versionCode\s*=\s*(\d+)", s)
if not m:
    sys.exit("build.gradle.kts 里找不到 versionCode")
code = int(m.group(1)) + 1
s = re.sub(r"versionCode\s*=\s*\d+", f"versionCode = {code}", s, count=1)
s = re.sub(r'versionName\s*=\s*"[^"]*"', f'versionName = "{version}"', s, count=1)
open(gradle, "w", encoding="utf-8").write(s)

changelog = "app/src/main/java/com/aieditor/text/data/Changelog.kt"
c = open(changelog, encoding="utf-8").read()
if f'"{version}"' in c:
    sys.exit(f"Changelog 里已存在版本 {version}")
today = datetime.date.today().isoformat()
items = "\n".join(f'                "{n}",' for n in notes)
block = f'''    val releases = listOf(
        Release(
            "{version}", "{today}", listOf(
{items}
            )
        ),'''
c = c.replace("    val releases = listOf(", block, 1)
open(changelog, "w", encoding="utf-8").write(c)

print(f"versionCode → {code}，versionName → {version}")
print(f"更新记录 {len(notes)} 条，日期 {today}")
PY

# ---------- 测试与构建 ----------
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"

GRADLE_BIN="gradle"
[ -x "/opt/gradle-8.7/bin/gradle" ] && GRADLE_BIN="/opt/gradle-8.7/bin/gradle"

echo "== 运行单元测试 =="
"$GRADLE_BIN" :app:testDebugUnitTest --offline -q

echo "== 构建 release 包 =="
"$GRADLE_BIN" :app:assembleRelease --offline -q

APK="app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || { echo "构建产物不存在：$APK"; exit 1; }

# 校验签名：确保能覆盖安装旧版本
if command -v apksigner >/dev/null 2>&1; then
    APKSIGNER="$(command -v apksigner)"
elif [ -x "$ANDROID_HOME/build-tools/35.0.0/apksigner" ]; then
    APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
else
    APKSIGNER=""
fi
if [ -n "$APKSIGNER" ]; then
    echo "== 签名指纹 =="
    "$APKSIGNER" verify --print-certs "$APK" | grep "SHA-256 digest" | head -1
fi

cp "$APK" "$ROOT/../AI文本编辑-v$VERSION.apk"
echo "APK 已导出：$(cd "$ROOT/.." && pwd)/AI文本编辑-v$VERSION.apk"

# ---------- 提交、打 tag、推送 ----------
git add -A
git commit -q -m "release: v$VERSION"
git tag -a "v$VERSION" -m "v$VERSION"

echo "== 推送到 origin =="
if git push origin main --tags; then
    echo "完成：v$VERSION 已推送"
else
    echo
    echo "推送失败，提交与 tag 已保存在本地。"
    echo "配好凭证后执行： git push origin main --tags"
    exit 1
fi

# ---------- 发布到 GitHub Releases ----------
# 用 ASCII 文件名上传，中文名会被 GitHub 截断成 "AI.-v1.5.2.apk"
UPLOAD="$ROOT/../AI-Text-Editor-v$VERSION.apk"
cp "$APK" "$UPLOAD"

REPO_SLUG="$(git remote get-url origin | sed -E 's#.*github\.com[:/]##; s#\.git$##')"

if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    echo "== 创建 GitHub Release =="
    NOTES_MD="$(printf '%s' "${NOTES_RAW:-常规更新与问题修复}" | tr '|' '\n' | sed 's/^/- /')"
    if gh release create "v$VERSION" "$UPLOAD" \
        --repo "$REPO_SLUG" \
        --title "v$VERSION" \
        --notes "$NOTES_MD" 2>/dev/null; then
        echo "已发布：https://github.com/$REPO_SLUG/releases/tag/v$VERSION"
    else
        echo "Release 创建失败（可能已存在），APK 保留在：$UPLOAD"
    fi
else
    echo
    echo "未检测到已登录的 gh CLI，APK 未上传到 Releases。"
    echo "安装并登录后可自动发布： brew/apt install gh && gh auth login"
    echo "或手动上传： https://github.com/$REPO_SLUG/releases/new?tag=v$VERSION"
fi
