#!/usr/bin/env bash
# build-llama-htp.sh —— 产出 llama.cpp 的 HTP 原生库（host + skel）
#
# 为什么不把它们提交进仓库：
#   libllama.so 约 42 MB、libggml-common 57 MB，合计 100 MB+；而且
#   libggml-htp-v8x.so 是 Qualcomm 的 DSP 二进制，随 GPL 仓库分发有许可问题。
#   走 .github/workflows/npu-libs-release.yml 发 Release 附件，或用
#   `-Pdshbox.npuLibs` 让 Gradle 按 npu-libs.lock 下载。
#
# 配方来源：llama.cpp 官方 CI（.github/workflows/build-and-test-snapdragon.yml）
#   container: ghcr.io/snapdragon-toolchain/arm64-android:v0.8   ← 内含 Hexagon SDK
#   cp docs/backend/snapdragon/CMakeUserPresets.json .
#   cmake --preset arm64-android-snapdragon-release -B build
#   cmake --build build
# 不要自己拼 HEXAGON_SDK_ROOT —— SDK 的 hexagon_fun.cmake 会因为变量缺失直接
# FATAL_ERROR（实测踩过）。官方容器里这些变量都已就绪。
#
# 前置：docker（或 podman），能拉 ghcr.io，且宿主是 arm64 —— 镜像是 arm64 的，
#       x86 宿主需要 qemu，编译会慢到不可用。
# 用法：bash npu-runtime/scripts/build-llama-htp.sh [--out <dir>] [--llamacpp <ref>]
# 产出：<out>/ 下是 host 库 + 各版本 skel；<out>/../ 不动仓库其它内容。
#       编译用的 llama.cpp 提交写在 <out>/LLAMACPP_COMMIT，打包时用得上。
set -euo pipefail

OUT=""; LLAMA_REF="${LLAMA_REF:-master}"
while [ $# -gt 0 ]; do
  case "$1" in
    --out)     OUT="${2:-}"; shift 2 ;;
    --llamacpp) LLAMA_REF="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,24p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[ -n "$OUT" ] || OUT="$ROOT/src/main/jniLibs/arm64-v8a"
IMAGE="ghcr.io/snapdragon-toolchain/arm64-android:v0.8"

DOCKER=""
command -v docker  >/dev/null && DOCKER=docker
[ -z "$DOCKER" ] && command -v podman >/dev/null && DOCKER=podman
[ -n "$DOCKER" ] || { echo "需要 docker 或 podman" >&2; exit 2; }

# 临时工作区：以前是在 $PWD 下产出 out/ 和 inc/，从别的目录调用就会写错地方。
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/out" "$WORK/inc"

echo "== 1/4 在官方容器里编译 llama.cpp（首次会拉约 3 GB 镜像）"
"$DOCKER" run --rm -v "$WORK:/io" -w /io "$IMAGE" bash -c '
  set -euo pipefail
  git clone --depth 1 --branch '"$LLAMA_REF"' https://github.com/ggml-org/llama.cpp /llama.cpp
  cd /llama.cpp
  git rev-parse HEAD > /io/out/LLAMACPP_COMMIT
  cp docs/backend/snapdragon/CMakeUserPresets.json .
  # 关掉 OpenCL：libggml.so 会 NEEDED libggml-opencl.so，而它又要 libOpenCL.so，
  # 在 App 的 namespace 里解不开（即使声明了 uses-native-library 也不稳）。
  # 我们只用 HTP。
  cmake --preset arm64-android-snapdragon-release -B build -DGGML_OPENCL=OFF
  cmake --build build -j"$(nproc)" --target llama ggml
  cp build/bin/*.so /io/out/ 2>/dev/null || true
  cp build/ggml/src/ggml-hexagon/*.so /io/out/ 2>/dev/null || true
  cp build/ggml/src/ggml-hexagon/ship/libc++_shared.so /io/out/ 2>/dev/null || true
  cp include/llama.h /io/inc/
  cp ggml/include/*.h /io/inc/
  ls -la /io/out
'

echo "== 2/4 拷贝产物"
mkdir -p "$OUT"
for f in libllama.so libggml.so libggml-base.so libggml-cpu.so libggml-hexagon.so libc++_shared.so; do
  if [ -f "$WORK/out/$f" ]; then cp "$WORK/out/$f" "$OUT/"; echo "  $f"; fi
done
for f in "$WORK"/out/libggml-htp-v*.so; do
  [ -f "$f" ] && cp "$f" "$OUT/" && echo "  $(basename "$f")"
done
[ -f "$WORK/out/LLAMACPP_COMMIT" ] && cp "$WORK/out/LLAMACPP_COMMIT" "$OUT/LLAMACPP_COMMIT"

echo "== 3/4 头文件"
if [ -d "$WORK/inc" ] && ls "$WORK/inc"/*.h >/dev/null 2>&1; then
  mkdir -p "$ROOT/src/main/cpp/include"
  cp "$WORK/inc"/*.h "$ROOT/src/main/cpp/include/" && echo "  → $ROOT/src/main/cpp/include/"
fi

echo "== 4/4 完成"
ls -la "$OUT" | head -20
echo
echo "llama.cpp 提交：$(cat "$OUT/LLAMACPP_COMMIT" 2>/dev/null || echo '(未记录)')"
echo "提示：只保留本机 HTP 版本可省约 5 MB，例如设备是 v81："
echo "  bash npu-runtime/scripts/package-libs.sh --in $OUT --out dist --only v81 --ref \"\$(cat $OUT/LLAMACPP_COMMIT)\""
