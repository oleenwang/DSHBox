#!/usr/bin/env bash
# prepush-npu.sh —— 推之前必须过的本地闸门（不需要 Gradle / 不需要网络）
#
# 为什么存在：2026-11-18 PR #100 带着一个「配置期就 BUILD FAILED」的错公开挂了
# 9 小时 20 分（04:51 推、14:11 才发现），因为当时我的验证完全押在 CI 上，而
# 那个 fork 的 Actions 被 GitHub 停用。没有本地门 → 只能靠运气。
#
# 这个脚本把「能本地验的」全部变成一条命令，且**只做机械判定**：
#   1. 所有 .sh 语法
#   2. package-libs.sh：确定性 + 三条失败路径
#   3. fetch-libs.sh：正向 / 幂等 / sha 不符 / sha 未标定 / 404 / 缺 skel
#   4. 两个 workflow 的 YAML 能解析
#   5. 【AGP 守卫】模块不能 apply 独立 Kotlin 插件，也不能用 kotlinOptions{}
#   6. 【lock 守卫】sha256 形状、url/file/size/llamacpp 齐全且自洽
#
# 不覆盖（必须靠真仓库 CI）：Gradle 配置、AAR 打包、真链接下载。
#
# 用法：bash npu-runtime/scripts/prepush-npu.sh
# 退出码：0 全过；1 有用例失败
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 1
FAIL=0
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"; pkill -f "http.server 18123" 2>/dev/null' EXIT

ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$1"; FAIL=1; }
head_() { printf '\n\033[1m%s\033[0m\n' "$1"; }

head_ "1. shell 语法"
for f in npu-runtime/scripts/*.sh; do
  if bash -n "$f" 2>/dev/null; then ok "$(basename "$f")"; else bad "$(basename "$f") 语法错"; fi
done

head_ "2. package-libs.sh"
mkdir -p "$TMP/in"; for n in libllama libggml-hexagon libggml; do head -c 4096 /dev/urandom > "$TMP/in/$n.so"; done
head -c 2048 /dev/urandom > "$TMP/in/libggml-htp-v81.so"
head -c 2048 /dev/urandom > "$TMP/in/libggml-htp-v79.so"
PKG=npu-runtime/scripts/package-libs.sh
bash "$PKG" --in "$TMP/in" --out "$TMP/o1" --ref abcdef1234 >/dev/null 2>&1 && ok "正常打包" || bad "正常打包失败"
bash "$PKG" --in "$TMP/in" --out "$TMP/o2" --ref abcdef1234 >/dev/null 2>&1
[ "$(sha256sum "$TMP"/o1/*.zip | cut -d' ' -f1)" = "$(sha256sum "$TMP"/o2/*.zip | cut -d' ' -f1)" ] \
  && ok "确定性（两次打包字节一致）" || bad "确定性不成立"
mkdir -p "$TMP/noskel"; cp "$TMP/in/libllama.so" "$TMP/in/libggml-hexagon.so" "$TMP/noskel/"
bash "$PKG" --in "$TMP/noskel" --out "$TMP/o3" >/dev/null 2>&1 && bad "缺 skel 竟然通过" || ok "缺 DSP skel 被拒"
mkdir -p "$TMP/nolib"; cp "$TMP/in/libggml-hexagon.so" "$TMP/nolib/"
bash "$PKG" --in "$TMP/nolib" --out "$TMP/o4" >/dev/null 2>&1 && bad "缺 libllama 竟然通过" || ok "缺必需库被拒"

head_ "3. fetch-libs.sh"
ZIP="$(ls "$TMP"/o1/*.zip | head -1)"; GOOD=$(sha256sum "$ZIP" | cut -d' ' -f1); BAD64=$(printf '0%.0s' $(seq 1 64))
mkdir -p "$TMP/srv"; cp "$ZIP" "$TMP/srv/pack.zip"
python3 - "$TMP/srv" <<'PY' &
import http.server, sys, os
os.chdir(sys.argv[1])
http.server.HTTPServer(("127.0.0.1", 18123), http.server.SimpleHTTPRequestHandler).serve_forever()
PY
SRV=$!; sleep 1.5
FETCH=npu-runtime/scripts/fetch-libs.sh; U="http://127.0.0.1:18123/pack.zip"
bash "$FETCH" --url "$U" --sha256 "$GOOD" --dest "$TMP/d1" >/dev/null 2>&1 && [ -s "$TMP/d1/libllama.so" ] \
  && ok "正向下载落盘" || bad "正向下载失败"
bash "$FETCH" --url "$U" --sha256 "$GOOD" --dest "$TMP/d1" 2>&1 | grep -q "跳过下载" \
  && ok "幂等跳过" || bad "幂等不成立"
bash "$FETCH" --url "$U" --sha256 "$BAD64" --dest "$TMP/d2" >/dev/null 2>&1; RC=$?
[ "$RC" = 1 ] && [ ! -e "$TMP/d2/libllama.so" ] && ok "sha 不符 → 拒绝且不落盘" || bad "sha 不符没被正确拒绝（exit=$RC）"
printf 'url=%s\nsha256=\n' "$U" > "$TMP/unset.lock"
bash "$FETCH" --lock "$TMP/unset.lock" --dest "$TMP/d3" >/dev/null 2>&1; RC=$?
[ "$RC" = 2 ] && ok "sha 未标定 → 拒绝（exit 2）" || bad "未标定没被拒（exit=$RC）"
bash "$FETCH" --url "http://127.0.0.1:18123/nope.zip" --sha256 "$GOOD" --dest "$TMP/d4" >/dev/null 2>&1; RC=$?
[ "$RC" = 1 ] && ok "404 → 失败" || bad "404 没失败（exit=$RC）"
python3 - "$TMP/noskel.zip" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1], 'w')
z.writestr('arm64-v8a/libllama.so', b'x'*100)
z.writestr('arm64-v8a/libggml-hexagon.so', b'y'*100)
z.close()
PY
cp "$TMP/noskel.zip" "$TMP/srv/"; NS=$(sha256sum "$TMP/noskel.zip" | cut -d' ' -f1)
bash "$FETCH" --url "http://127.0.0.1:18123/noskel.zip" --sha256 "$NS" --dest "$TMP/d5" >/dev/null 2>&1; RC=$?
[ "$RC" = 1 ] && ok "包内缺 skel → 拒绝" || bad "缺 skel 没被拒（exit=$RC）"
kill $SRV 2>/dev/null

head_ "4. workflow YAML"
for w in .github/workflows/npu-verify.yml .github/workflows/npu-libs-release.yml; do
  [ -f "$w" ] || { ok "$(basename "$w") 不存在（可选）"; continue; }
  python3 -c "import yaml,sys; yaml.safe_load(open('$w'))" 2>/dev/null \
    && ok "$(basename "$w")" || bad "$(basename "$w") 解析失败"
done

head_ "5. AGP 守卫（这条是 2026-11-18 那个事故的直接产物）"
BG=npu-runtime/build.gradle.kts
# 先去掉注释再判：说明文字里提到插件名不算违规（第一版就是这样自伤的）
BGC="$TMP/build.gradle.nocomment"
sed 's://.*::' "$BG" > "$BGC"
if grep -qE 'id\("org\.jetbrains\.kotlin\.android"\)' "$BGC"; then
  bad "$BG 里还 apply 了独立 Kotlin 插件 —— 上游 AGP 9 内置 Kotlin，叠加会 BUILD FAILED"
else ok "没有 apply 独立 Kotlin 插件"; fi
if grep -qE '^\s*kotlinOptions\s*\{' "$BGC"; then
  bad "$BG 里还有 kotlinOptions{} —— AGP 9 要用顶层 kotlin { compilerOptions { } }"
else ok "没有 kotlinOptions{}"; fi
grep -q 'alias(libs.plugins.android.library)' "$BGC" \
  && ok "用 alias(libs.plugins.android.library)，与上游模块一致" \
  || bad "没用 alias(libs.plugins.android.library)"

head_ "6. npu-libs.lock"
LOCK=npu-runtime/npu-libs.lock
if [ -f "$LOCK" ]; then
  g() { sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$LOCK" | head -1; }
  S=$(g sha256); U=$(g url); F=$(g file); Z=$(g size); L=$(g llamacpp)
  if printf '%s' "$S" | grep -qE '^[0-9a-f]{64}$'; then ok "sha256 形状正确"; else bad "sha256 未标定或格式错：'$S'"; fi
  case "$U" in https://*/releases/download/*) ok "url 指向 Release 附件" ;; *) bad "url 不像 Release 附件：'$U'" ;; esac
  case "$U" in *"$F") ok "url 末段与 file 一致" ;; *) bad "url 末段与 file($F) 不一致" ;; esac
  [ -n "$Z" ] && ok "size 有值（$Z）" || bad "缺 size"
  printf '%s' "$L" | grep -qE '^[0-9a-f]{40}$' && ok "llamacpp 是完整 40 位提交" || bad "llamacpp 不是 40 位：'$L'"
else bad "缺 $LOCK"; fi

echo
if [ "$FAIL" = 0 ]; then printf '\033[32m全部通过\033[0m\n'; else printf '\033[31m有失败项 —— 不要推\033[0m\n'; fi
exit "$FAIL"
