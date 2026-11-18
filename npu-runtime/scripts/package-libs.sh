#!/usr/bin/env bash
# package-libs.sh —— 把原生库打成可发布的 zip（确定性打包，同名同 SHA）
#
# 为什么单独一个脚本：库由 build-llama-htp.sh 产出后，要变成 Release 附件才有人能用。
# 这一步必须可复现 —— 同一批库打出来的 zip 字节一致、SHA-256 一致，
# 否则 npu-libs.lock 里钉的校验值每次都要重抄，等于没有校验。
#
# 用法：
#   bash npu-runtime/scripts/package-libs.sh --in <库目录> --out <输出目录> [选项]
#
# 选项：
#   --ref <llamacpp-ref>   产出这批库的 llama.cpp 提交（进 zip 名，默认 unknown）
#   --only v81,v79         只保留指定 HTP 版本的 skel（默认全带）
#   --stored               不压缩（默认 deflate；调试用）
#
# 产出：
#   <out>/llama-htp-arm64-v8a-<ref7>.zip
#   <out>/SHA256SUMS.txt       格式：<sha256>  <文件名>（sha256sum -c 可直接用）
set -euo pipefail

IN=""; OUT=""; REF="unknown"; ONLY=""; METHOD="deflate"
while [ $# -gt 0 ]; do
  case "$1" in
    --in)     IN="${2:-}";    shift 2 ;;
    --out)    OUT="${2:-}";   shift 2 ;;
    --ref)    REF="${2:-}";   shift 2 ;;
    --only)   ONLY="${2:-}";  shift 2 ;;
    --stored) METHOD="stored"; shift ;;
    -h|--help) sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done
[ -n "$IN" ] && [ -n "$OUT" ] || { echo "需要 --in 和 --out（--help 看用法）" >&2; exit 2; }
[ -d "$IN" ] || { echo "输入目录不存在：$IN" >&2; exit 2; }

command -v python3 >/dev/null || { echo "需要 python3（用它做确定性打包）" >&2; exit 2; }

REF7="$(printf '%s' "$REF" | cut -c1-7)"
ZIPNAME="llama-htp-arm64-v8a-$REF7.zip"
mkdir -p "$OUT"

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/arm64-v8a"

# ---- 1. 必需要件（缺一个就说明这批库是坏的，宁可不发）----
REQUIRED="libllama.so libggml-hexagon.so"
missing=""
for f in $REQUIRED; do
  [ -f "$IN/$f" ] || missing="$missing $f"
done
if [ -n "$missing" ]; then
  echo "❌ 输入目录缺必需库：$missing" >&2
  echo "   先跑 bash npu-runtime/scripts/build-llama-htp.sh" >&2
  exit 1
fi
ls "$IN"/libggml-htp-v*.so >/dev/null 2>&1 || {
  echo "❌ 没有 libggml-htp-v*.so —— DSP 侧 skel 缺失，这批库到设备上用不了" >&2
  exit 1
}

# ---- 2. 收集（按 HTP 版本过滤，排除符号链接）----
n=0
for f in "$IN"/*.so; do
  [ -e "$f" ] || continue
  [ -L "$f" ] && continue
  base="$(basename "$f")"
  case "$base" in
    libggml-htp-v*.so)
      if [ -n "$ONLY" ]; then
        ver="${base#libggml-htp-}"; ver="${ver%.so}"
        case ",$ONLY," in
          *",$ver,"*) ;;
          *) echo "  跳过 $base（--only 未包含）"; continue ;;
        esac
      fi
      ;;
  esac
  cp "$f" "$STAGE/arm64-v8a/$base"
  n=$((n + 1))
done
[ "$n" -gt 0 ] || { echo "❌ 没收集到任何 .so" >&2; exit 1; }

# ---- 3. 确定性 zip（固定时间戳 1980-01-01、固定条目顺序、固定压缩级别）----
python3 - "$STAGE" "$OUT/$ZIPNAME" "$METHOD" <<'PY'
import os, sys, zipfile

stage, out, method = sys.argv[1], sys.argv[2], sys.argv[3]
root = os.path.join(stage, "arm64-v8a")
names = sorted(os.listdir(root))
comp = zipfile.ZIP_STORED if method == "stored" else zipfile.ZIP_DEFLATED
with zipfile.ZipFile(out, "w", compression=comp, compresslevel=9) as z:
    for name in names:
        path = os.path.join(root, name)
        # ZipInfo 显式构造：不用文件 mtime，避免同一批库因拷贝时间不同而 SHA 漂移
        info = zipfile.ZipInfo(f"arm64-v8a/{name}", date_time=(1980, 1, 1, 0, 0, 0))
        info.external_attr = 0o644 << 16
        info.compress_type = comp
        with open(path, "rb") as src:
            z.writestr(info, src.read(), compresslevel=9 if comp == zipfile.ZIP_DEFLATED else None)
print(f"  打包 {len(names)} 个文件")
PY

# ---- 4. SHA256SUMS ----
( cd "$OUT" && sha256sum "$ZIPNAME" > SHA256SUMS.txt )
SHA="$(cut -d' ' -f1 "$OUT/SHA256SUMS.txt")"
SIZE="$(stat -c '%s' "$OUT/$ZIPNAME")"
RAW="$(du -sb "$STAGE/arm64-v8a" | cut -f1)"

echo
echo "✅ $OUT/$ZIPNAME"
printf "   文件数 %s | 原始 %s MB | 压缩后 %s MB\n" \
  "$n" "$(awk -v b="$RAW" 'BEGIN{printf "%.1f", b/1048576}')" \
  "$(awk -v b="$SIZE" 'BEGIN{printf "%.1f", b/1048576}')"
echo "   sha256 $SHA"
echo
echo "抄进 npu-runtime/npu-libs.lock："
echo "  version=npu-libs-$(date -u +%Y%m%d)-$REF7"
echo "  file=$ZIPNAME"
echo "  sha256=$SHA"
echo "  size=$SIZE"
echo "  llamacpp=$REF"
