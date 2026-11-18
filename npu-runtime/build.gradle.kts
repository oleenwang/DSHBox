import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipInputStream

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dshbox.app.npu"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        ndk {
            // 只给真机架构编；x86_64 模拟器没有 DSP，编了也用不上
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            ndkBuild {
                // 原生库由 scripts/build-llama-htp.sh 产出（或 downloadNpuLibs 下载），
                // 这里只编我们自己的 JNI 桥。
                arguments += "-j4"
            }
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    buildFeatures { buildConfig = false }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// 本模块不依赖 :common —— JNI + Kotlin 都是自包含的，减少与宿主的耦合。

// ---------------------------------------------------------------------------
// 可选的 NPU 原生库下载（默认关闭）
//
// 这些库不进版本控制：libllama.so 约 42 MB、libggml 全家 60 MB+，而且
// libggml-htp-v8x.so 是 Qualcomm 的 DSP 二进制，随 GPL 源码树分发许可边界不清。
// 它们以 Release 附件形式发布，校验值钉在 npu-runtime/npu-libs.lock。
//
// 开启：  ./gradlew :npu-runtime:assembleDebug -Pdshbox.npuLibs
// 默认：  不下载。模块照常编译，NpuLlama.isAvailable() 返回 false，App 正常降级。
//
// 覆盖项（本地验证 / 自建镜像用）：
//         -Pdshbox.npuLibsUrl=<zip 直链>   -Pdshbox.npuLibsSha256=<hex>
//
// 设计约束（与 PR #100 的 CI 断言一致）：
//   1. 校验不通过就**不落盘**，绝不半套库进 APK —— 半套的后果是运行时 dlopen
//      失败，比没有库更难查。
//   2. 解压只取 basename，避免 zip 内路径穿越写出目录。
//   3. 幂等：标记文件里的 sha256 与期望一致且 libllama.so 在位就跳过。
// ---------------------------------------------------------------------------

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val read = stream.read(buf)
            if (read < 0) break
            digest.update(buf, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val npuLibsLockFile = rootProject.layout.projectDirectory.file("npu-runtime/npu-libs.lock").asFile
val npuLibsLock = Properties().apply {
    if (npuLibsLockFile.isFile) npuLibsLockFile.inputStream().use { load(it) }
}

val npuLibsEnabled = (findProperty("dshbox.npuLibs") as String?)?.toBoolean() ?: false
// 配置期只读值，执行期不再碰 project.property（对 configuration cache 友好）
val npuLibsUrl = ((findProperty("dshbox.npuLibsUrl") as String?)?.takeIf { it.isNotBlank() }
    ?: npuLibsLock.getProperty("url") ?: "").trim()
val npuLibsExpectedSha = ((findProperty("dshbox.npuLibsSha256") as String?)?.takeIf { it.isNotBlank() }
    ?: npuLibsLock.getProperty("sha256") ?: "").trim().lowercase()

val npuLibsTargetDir = file("src/main/jniLibs/arm64-v8a")
val npuLibsMarker = File(npuLibsTargetDir, ".installed.sha256")
val npuLibsWorkDir = layout.buildDirectory.dir("npu-libs")

val downloadNpuLibs by tasks.registering {
    group = "npu"
    description = "下载并校验 NPU 原生库（需要 -Pdshbox.npuLibs；默认关闭）"

    doLast {
        if (npuLibsExpectedSha.length != 64 || npuLibsExpectedSha.any { it !in "0123456789abcdef" }) {
            throw GradleException(
                "npu-libs.lock 里的 sha256 还没标定（当前值 \"${npuLibsExpectedSha}\"）。\n" +
                    "先跑一次 .github/workflows/npu-libs-release.yml，把产出的 sha256 抄进\n" +
                    "npu-runtime/npu-libs.lock；或者用 -Pdshbox.npuLibsSha256=<hex> 临时指定。"
            )
        }
        if (npuLibsUrl.isEmpty()) {
            throw GradleException("npu-libs.lock 里缺 url，且没有 -Pdshbox.npuLibsUrl")
        }

        if (npuLibsMarker.isFile &&
            npuLibsMarker.readText().trim() == npuLibsExpectedSha &&
            File(npuLibsTargetDir, "libllama.so").isFile
        ) {
            logger.lifecycle("NPU 原生库已就位（sha256 与 npu-libs.lock 一致），跳过下载")
            return@doLast
        }

        val zipFile = npuLibsWorkDir.get().file("npu-libs.zip").asFile
        zipFile.parentFile.mkdirs()
        logger.lifecycle("下载 NPU 原生库：$npuLibsUrl")
        java.net.URI(npuLibsUrl).toURL().openStream().use { input ->
            zipFile.outputStream().use { output -> input.copyTo(output) }
        }

        val actualSha = sha256Of(zipFile)
        if (actualSha != npuLibsExpectedSha) {
            zipFile.delete()
            throw GradleException(
                "NPU 原生库校验失败，已丢弃下载文件。\n" +
                    "  期望 sha256: $npuLibsExpectedSha\n" +
                    "  实际 sha256: $actualSha\n" +
                    "  来源: $npuLibsUrl"
            )
        }

        // 先解到临时目录，全部到齐才搬进 jniLibs —— 避免半套库污染构建缓存
        val stage = npuLibsWorkDir.get().dir("stage").asFile
        stage.deleteRecursively()
        stage.mkdirs()
        val wanted = mutableSetOf<String>()
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) { zis.closeEntry(); continue }
                val base = entry.name.substringAfterLast('/')   // 只取 basename，防路径穿越
                if (!base.endsWith(".so")) { zis.closeEntry(); continue }
                File(stage, base).outputStream().use { out -> zis.copyTo(out) }
                wanted += base
                zis.closeEntry()
            }
        }
        val missing = listOf("libllama.so", "libggml-hexagon.so").filterNot { File(stage, it).isFile }
        if (missing.isNotEmpty()) {
            stage.deleteRecursively()
            throw GradleException("NPU 原生库包不完整，缺：${missing.joinToString(", ")}（来自 $npuLibsUrl）")
        }
        if (wanted.none { it.startsWith("libggml-htp-v") }) {
            stage.deleteRecursively()
            throw GradleException("NPU 原生库包里没有 libggml-htp-v*.so（DSP skel 缺失），到设备上跑不起来")
        }

        npuLibsTargetDir.mkdirs()
        stage.listFiles()?.forEach { it.copyTo(File(npuLibsTargetDir, it.name), overwrite = true) }
        stage.deleteRecursively()
        npuLibsMarker.writeText(npuLibsExpectedSha)
        logger.lifecycle("NPU 原生库就位：${npuLibsTargetDir}（${wanted.size} 个 .so，sha256 已校验）")
    }
}

if (npuLibsEnabled) {
    // 库必须在 jniLibs 合并进 APK 之前落地。preBuild 是所有构建任务的祖先，
    // 挂在这里比挂到某个 AGP 内部任务名上稳。
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(downloadNpuLibs) }
}
