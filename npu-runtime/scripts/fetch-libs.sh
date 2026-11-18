#!/usr/bin/env bash
# fetch-libs.sh —— 按 npu-libs.lock 下载并校验 NPU 原生库
#
# 为什么是脚本而不是纯 Gradle 任务：下载 / 校验 / 防穿越 / 幂等这些逻辑本来就该
# 能用一条命令单独跑，也才能在没有 Gradle、没有 Android SDK 的地方验证。
# Gradle 的 downloadNpuLibs 只是这层的一行包装。
#
# 用法：
#   bash npu-runtime/scripts/fetch-libs.sh                  # 读 npu-libs.lock
#   bash npu-runtime/scripts/fetch-libs.sh --url <zip> --sha256 <hex>
#   bash npu-runtime/scripts/fetch-libs.sh --lock <file> --dest <dir>
#
# 行为：
#   - sha256 未标定（长度不是 64 / 非十六进制）→ 直接失败，不下载
#   - 下载后逐字节校验，不符 → 删掉下载文件并失败，**一个 .so 都不落盘**
#   - 只取 zip 内的 basename（unzip -j），防路径穿越
#   - 已在位（标记文件记录了同一个 sha256 且 libllama.so 存在）→ 跳过
#
# 退出码：0 成功或已就位；1 校验/内容失败；2 用法或环境问题
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK="$ROOT/npu-libs.lock"
DEST="$ROOT/src/main/jniLibs/arm64-v8a"
URL=""; SHA=""

while [ $# -gt 0 ]; do
  case "$1" in
    --lock)   LOCK="${2:-}";   shift 2 ;;
    --dest)   DEST="${2:-}";   shift 2 ;;
    --url)    URL="${2:-}";    shift 2 ;;
    --sha256) SHA="${2:-}";    shift 2 ;;
    -h|--help) sed -n '2,22p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

lock_get() { # key
  [ -f "$LOCK" ] || return 1
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$LOCK" | head -1 | tr -d '\r'
}

[ -n "$URL" ] || URL="$(lock_get url || true)"
[ -n "$SHA" ] || SHA="$(lock_get sha256 || true)"
SHA="$(printf '%s' "${SHA:-}" | tr 'A-Z' 'a-z')"

if [ -z "$SHA" ] || [ "${#SHA}" -ne 64 ] || printf '%s' "$SHA" | grep -qv '^[0-9a-f]\{64\}$'; then
  echo "❌ npu-libs.lock 里的 sha256 还没标定（当前值 \"${SHA:-}\"）" >&2
  echo "   先跑一次 .github/workflows/npu-libs-release.yml，把产出的 sha256 抄进 $LOCK" >&2
  echo "   或用 --sha256 <hex> 临时指定" >&2
  exit 2
fi
if [ -z "$URL" ]; then
  echo "❌ npu-libs.lock 里缺 url，且没有 --url" >&2
  exit 2
fi

MARKER="$DEST/.installed.sha256"
if [ -f "$MARKER" ] && [ "$(tr -d '\r\n' < "$MARKER")" = "$SHA" ] && [ -s "$DEST/libllama.so" ]; then
  echo "NPU 原生库已就位（sha256 与 lock 一致），跳过下载"
  exit 0
fi

WORK="${TMPDIR:-/tmp}/npu-libs-fetch.$$"
mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT
ZIPF="$WORK/npu-libs.zip"

echo "下载 NPU 原生库：$URL"
if command -v curl >/dev/null; then
  # -f 让 404 直接失败；-L 跟 github.com → objects.githubusercontent.com 的 302
  curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 -o "$ZIPF" "$URL" || {
    echo "❌ 下载失败：$URL" >&2; exit 1; }
elif command -v wget >/dev/null; then
  wget -q -O "$ZIPF" "$URL" || { echo "❌ 下载失败：$URL" >&2; exit 1; }
else
  echo "❌ 需要 curl 或 wget" >&2; exit 2
fi

ACTUAL="$(sha256sum "$ZIPF" | cut -d' ' -f1)"
if [ "$ACTUAL" != "$SHA" ]; then
  rm -f "$ZIPF"
  echo "❌ NPU 原生库校验失败，已丢弃下载文件" >&2
  echo "   期望 sha256: $SHA" >&2
  echo "   实际 sha256: $ACTUAL" >&2
  echo "   来源: $URL" >&2
  exit 1
fi

# 先解到临时目录，齐了才搬 —— 避免半套库污染构建
STAGE="$WORK/stage"; mkdir -p "$STAGE"
command -v unzip >/dev/null || { echo "❌ 需要 unzip" >&2; exit 2; }
# -j 只取 basename：既防 ../ 穿越，也匹配包内 arm64-v8a/ 前缀
unzip -j -q -o "$ZIPF" '*.so' -d "$STAGE" || { echo "❌ 解压失败" >&2; exit 1; }

for f in libllama.so libggml-hexagon.so; do
  [ -s "$STAGE/$f" ] || { echo "❌ 包不完整，缺 $f（$URL）" >&2; exit 1; }
done
ls "$STAGE"/libggml-htp-v*.so >/dev/null 2>&1 || {
  echo "❌ 包里没有 libggml-htp-v*.so（DSP skel 缺失），到设备上跑不起来" >&2; exit 1; }

mkdir -p "$DEST"
cp "$STAGE"/*.so "$DEST/"
printf '%s' "$SHA" > "$MARKER"
echo "NPU 原生库就位：$DEST（$(ls "$STAGE"/*.so | wc -l) 个 .so，sha256 已校验）"
