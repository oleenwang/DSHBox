/*
 * JNI 桥：把纯 C 探针暴露给 Kotlin。
 *   Java_com_dshbox_app_sandbox_NpuReachabilityProbe_runNative   → v5 可达性探针
 *   Java_com_dshbox_app_sandbox_NpuReachabilityProbe_runQnnNative → v6 QNN 设备创建
 *   Java_com_dshbox_app_sandbox_NpuReachabilityProbe_runGenieNative → 进程内 Genie 推理
 */
#include <jni.h>

int npu_probe_run(void);
const char *npu_probe_result(void);
int npu_qnn_run(const char *native_lib_dir, const char *skel_dir);
const char *npu_qnn_result(void);
int npu_genie_run(const char *cfg_json_path, const char *prompt);
const char *npu_genie_result(void);

static const char *jstr(JNIEnv *env, jstring s, const char **out) {
    const char *p = s ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
    *out = p;
    return p;
}
static void jfree(JNIEnv *env, jstring s, const char *p) {
    if (s && p) (*env)->ReleaseStringUTFChars(env, s, p);
}

JNIEXPORT jstring JNICALL
Java_com_dshbox_app_sandbox_NpuReachabilityProbe_runNative(JNIEnv *env, jclass thiz) {
    (void)thiz;
    npu_probe_run();
    return (*env)->NewStringUTF(env, npu_probe_result());
}

JNIEXPORT jstring JNICALL
Java_com_dshbox_app_sandbox_NpuReachabilityProbe_runQnnNative(JNIEnv *env, jclass thiz,
                                                              jstring libDir, jstring skelDir) {
    (void)thiz;
    const char *ld = NULL, *sd = NULL;
    jstr(env, libDir, &ld); jstr(env, skelDir, &sd);
    npu_qnn_run(ld, sd);
    jfree(env, libDir, ld); jfree(env, skelDir, sd);
    return (*env)->NewStringUTF(env, npu_qnn_result());
}

JNIEXPORT jstring JNICALL
Java_com_dshbox_app_sandbox_NpuReachabilityProbe_runGenieNative(JNIEnv *env, jclass thiz,
                                                                jstring cfgPath, jstring prompt) {
    (void)thiz;
    const char *cp = NULL, *pr = NULL;
    jstr(env, cfgPath, &cp); jstr(env, prompt, &pr);
    npu_genie_run(cp, pr ? pr : "你好");
    jfree(env, cfgPath, cp); jfree(env, prompt, pr);
    return (*env)->NewStringUTF(env, npu_genie_result());
}
