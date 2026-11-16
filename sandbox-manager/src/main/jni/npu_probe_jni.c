/*
 * JNI 桥。
 *   runNative()                          → v5 可达性探针
 *   setenvLibs(libDir, skelDir)          → 只设 ADSP/DSP 库路径
 *   runQnnNative(libDir, skelDir)        → QNN deviceCreate + deviceFree
 *   runGenieNative(cfg, prompt, trace)   → 进程内 Genie（每步落盘）
 */
#include <jni.h>

int npu_probe_run(void);
const char *npu_probe_result(void);
const char *npu_setenv_libs(const char *native_lib_dir, const char *skel_dir);
int npu_qnn_run(const char *native_lib_dir, const char *skel_dir);
const char *npu_qnn_result(void);
int npu_genie_run(const char *cfg_json_path, const char *prompt, const char *trace_path);
const char *npu_genie_result(void);

static jstring ret(JNIEnv *env, const char *s) { return (*env)->NewStringUTF(env, s); }

JNIEXPORT jstring JNICALL
Java_com_dshbox_app_sandbox_NpuReachabilityProbe_runNative(JNIEnv *env, jclass c) {
    (void)c; npu_probe_run(); return ret(env, npu_probe_result());
}

JNIEXPORT jstring JNICALL
Java_com_dshbox_app_sandbox_NpuReachabilityProbe_setenvLibs(JNIEnv *env, jclass c,
                                                            jstring libDir, jstring skelDir) {
    (void)c;
    const char *a = libDir ? (*env)->GetStringUTFChars(env, libDir, NULL) : NULL;
    const char *b = skelDir ? (*env)->GetStringUTFChars(env, skelDir, NULL) : NULL;
    const char *r = npu_setenv_libs(a, b);
    jstring out = ret(env, r);
    if (a) (*env)->ReleaseStringUTFChars(env, libDir, a);
    if (b) (*env)->ReleaseStringUTFChars(env, skelDir, b);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_dshbox_app_sandbox_NpuReachabilityProbe_runQnnNative(JNIEnv *env, jclass c,
                                                              jstring libDir, jstring skelDir) {
    (void)c;
    const char *a = libDir ? (*env)->GetStringUTFChars(env, libDir, NULL) : NULL;
    const char *b = skelDir ? (*env)->GetStringUTFChars(env, skelDir, NULL) : NULL;
    npu_qnn_run(a, b);
    jstring out = ret(env, npu_qnn_result());
    if (a) (*env)->ReleaseStringUTFChars(env, libDir, a);
    if (b) (*env)->ReleaseStringUTFChars(env, skelDir, b);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_dshbox_app_sandbox_NpuReachabilityProbe_runGenieNative(JNIEnv *env, jclass c,
                                                                jstring cfg, jstring prompt,
                                                                jstring trace) {
    (void)c;
    const char *a = cfg ? (*env)->GetStringUTFChars(env, cfg, NULL) : NULL;
    const char *b = prompt ? (*env)->GetStringUTFChars(env, prompt, NULL) : NULL;
    const char *t = trace ? (*env)->GetStringUTFChars(env, trace, NULL) : NULL;
    npu_genie_run(a, b ? b : "你好", t);
    jstring out = ret(env, npu_genie_result());
    if (a) (*env)->ReleaseStringUTFChars(env, cfg, a);
    if (b) (*env)->ReleaseStringUTFChars(env, prompt, b);
    if (t) (*env)->ReleaseStringUTFChars(env, trace, t);
    return out;
}
