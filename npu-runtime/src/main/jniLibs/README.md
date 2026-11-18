# jniLibs/arm64-v8a/

本目录**不入库**实际的原生库。两条路拿：

```bash
# A. 用发布好的预编译库（校验值见 ../../npu-libs.lock）
./gradlew :npu-runtime:assembleDebug -Pdshbox.npuLibs

# B. 自己编
bash npu-runtime/scripts/build-llama-htp.sh
```

放进来的是这些：

| 文件 | 作用 | 体积 |
|---|---|---|
| `libllama.so` | llama.cpp 主库 | ~42 MB |
| `libggml.so` / `libggml-base.so` / `libggml-cpu.so` | ggml 核心 + CPU 后端 | ~13 MB |
| `libggml-hexagon.so` | HTP host 侧后端 | ~9.5 MB |
| `libggml-htp-v8x.so` | **DSP 侧 skel**（按设备 HTP 版本，可只留一个） | ~1 MB/个 |
| `.installed.sha256` | 下载路径写的标记，记录已安装的 zip 校验值（幂等用） | 64 B |

体积原因（合计 100 MB+）与 Qualcomm 二进制许可，是这个目录不进版本控制的两个理由。
没有这些库时 `NpuLlama.isAvailable()` 返回 false，App 正常降级，不会启动失败。

只留本机 HTP 版本可省约 5 MB，设备是 v81 就这么打：

```bash
bash npu-runtime/scripts/package-libs.sh --in . --out dist --only v81 --ref <llamacpp-commit>
```
