plugins {
    // 不要加 id("org.jetbrains.kotlin.android")：上游是 AGP 9.x，Kotlin 已内置。
    // 再叠一个独立 Kotlin 插件会直接让配置期失败：
    //   AgpWithBuiltInKotlinAppliedCheck ...
    // 照抄上游 common/build.gradle.kts 的写法即可（library + 顶层 kotlin{}）。
    alias(libs.plugins.android.library)
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
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
}

// AGP 9 的内置 Kotlin 用顶层 kotlin{} 设 jvmTarget，kotlinOptions{} 已经不用了
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

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
// 裸脚本：bash npu-runtime/scripts/fetch-libs.sh    （不需要 Gradle，逻辑都在那里）
//
// 覆盖项（自建镜像 / 临时验证）：-Pdshbox.npuLibsUrl=<zip 直链>
//                                -Pdshbox.npuLibsSha256=<hex>
// ---------------------------------------------------------------------------

val npuLibsEnabled = (findProperty("dshbox.npuLibs") as String?)?.toBoolean() ?: false
val npuLibsOverrideUrl = (findProperty("dshbox.npuLibsUrl") as String?)?.trim().orEmpty()
val npuLibsOverrideSha = (findProperty("dshbox.npuLibsSha256") as String?)?.trim().orEmpty()

val downloadNpuLibs by tasks.registering(Exec::class) {
    group = "npu"
    description = "按 npu-libs.lock 下载并校验 NPU 原生库（需要 -Pdshbox.npuLibs；默认关闭）"
    workingDir = rootProject.projectDir

    val args = mutableListOf(
        "bash", "npu-runtime/scripts/fetch-libs.sh",
        "--dest", file("src/main/jniLibs/arm64-v8a").absolutePath,
    )
    if (npuLibsOverrideUrl.isNotEmpty()) { args += "--url"; args += npuLibsOverrideUrl }
    if (npuLibsOverrideSha.isNotEmpty()) { args += "--sha256"; args += npuLibsOverrideSha }
    commandLine(args)

    // 脚本自己判幂等（标记文件里的 sha256 与 lock 一致就跳过），不去猜 Gradle 的 up-to-date
    outputs.upToDateWhen { false }
}

if (npuLibsEnabled) {
    // 库必须在 jniLibs 合并进 APK 之前落地。preBuild 是所有构建任务的祖先，
    // 挂在这里比挂到某个 AGP 内部任务名上稳。
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(downloadNpuLibs) }
}
