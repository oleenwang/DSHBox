/*
 * App 域进程内 QNN HTP 初始化探针（v6）。
 *
 * v5 已证：App 域 dlopen("libcdsprpc.so") 成功且命中 /vendor/lib64（vendor 公开库），
 * libQnnHtp.so 可加载、QnnInterface_getProviders 返回 1 个 provider。
 * v5 里 remote_handle64_open("cdsp") 返回 14 是我的调用形态非法
 * （日志原文：remote_handle64_open: Invalid domain -1 in uri），不能据此判权限。
 *
 * 正确的判定是让 QNN 自己去建 HTP 设备：
 *   backendCreate(NULL, NULL) → deviceCreate(logger, cfg)
 *   其中 cfg 带 QNN_HTP_DEVICE_CONFIG_OPTION_SOC=87 (SM8850) 与 ARCH=v81。
 * 若这一步在 App 域成功，普通 App 就能用 NPU（QNN 模型可自行构建）。
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <dlfcn.h>
#include <stdbool.h>

#include <QNN/QnnInterface.h>
#include <QNN/QnnDevice.h>
#include <QNN/QnnBackend.h>
#include <QNN/QnnTypes.h>
#include <QNN/HTP/QnnHtpDevice.h>

static char g_out[32768];
static void emit(const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    size_t used = strlen(g_out);
    if (used < sizeof(g_out) - 1) vsnprintf(g_out + used, sizeof(g_out) - used - 1, fmt, ap);
    va_end(ap);
}

typedef Qnn_ErrorHandle_t (*getprov_t)(const QnnInterface_t ***, uint32_t *);

/* 只设 ADSP/DSP 库路径，不碰 QNN 设备。让 Genie 成为 DSP 的唯一使用者。 */
const char *npu_setenv_libs(const char *native_lib_dir, const char *skel_dir) {
    g_out[0] = 0;
    if (native_lib_dir && *native_lib_dir) {
        char buf[2048];
        snprintf(buf, sizeof(buf), "%s%s%s", native_lib_dir,
                 (skel_dir && *skel_dir) ? ";" : "", (skel_dir && *skel_dir) ? skel_dir : "");
        setenv("ADSP_LIBRARY_PATH", buf, 1);
        setenv("DSP_LIBRARY_PATH", buf, 1);
        emit("[env] ADSP_LIBRARY_PATH=%s\n", buf);
    } else {
        emit("[env] native_lib_dir 为空，未设置\n");
    }
    return g_out;
}

int npu_qnn_run(const char *native_lib_dir, const char *skel_dir) {
    g_out[0] = 0;

    /* QNN 通过 ADSP_LIBRARY_PATH 找 libQnnHtpV81Skel.so 等要推到 DSP 的库；
     * 必须在 libcdsprpc 初始化前设好。 */
    if (native_lib_dir && *native_lib_dir) {
        char buf[2048];
        snprintf(buf, sizeof(buf), "%s%s%s", native_lib_dir,
                 (skel_dir && *skel_dir) ? ";" : "", (skel_dir && *skel_dir) ? skel_dir : "");
        setenv("ADSP_LIBRARY_PATH", buf, 1);
        setenv("DSP_LIBRARY_PATH", buf, 1);
        emit("[env] ADSP_LIBRARY_PATH=%s\n", buf);
    }
    /* QNN 自身的日志级别，便于看到失败原因 */
    setenv("QNN_LOG_LEVEL", "4", 0);

    void *lib = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_GLOBAL);
    if (!lib) { emit("[qnn] dlopen 失败: %s\n", dlerror()); return -1; }
    emit("[qnn] libQnnHtp.so 已加载\n");

    getprov_t getprov = (getprov_t)dlsym(lib, "QnnInterface_getProviders");
    if (!getprov) { emit("[qnn] 缺 QnnInterface_getProviders\n"); return -2; }

    const QnnInterface_t **provs = NULL;
    uint32_t num = 0;
    Qnn_ErrorHandle_t rc = getprov(&provs, &num);
    emit("[qnn] getProviders ret=%d num=%u\n", (int)rc, num);
    if (rc || !num || !provs) return -3;

    const QnnInterface_t *it = provs[0];
    emit("[qnn] backendId=%u apiVersion %u.%u.%u\n", it->backendId,
         it->apiVersion.coreApiVersion.major, it->apiVersion.coreApiVersion.minor,
         it->apiVersion.coreApiVersion.patch);

    const QNN_INTERFACE_VER_TYPE *api = &it->QNN_INTERFACE_VER_NAME;

    Qnn_BackendHandle_t be = NULL;
    rc = api->backendCreate(NULL, NULL, &be);
    emit("[qnn] backendCreate → %d (0x%llx) backend=%p\n", (int)rc, (unsigned long long)rc, be);

    /* HTP 设备自定义配置。QnnHtpDevice_CustomConfig_t 的布局是
     * { int32 option; union { uint32 socModel; int32 arch; ... } }，
     * 这里用同布局的本地结构避免依赖匿名 union 支持。 */
    typedef struct { int32_t option; int32_t value; } cc_t;
    cc_t cSoc  = { QNN_HTP_DEVICE_CONFIG_OPTION_SOC,  (int32_t)QNN_SOC_MODEL_SM8850 };
    cc_t cArch = { QNN_HTP_DEVICE_CONFIG_OPTION_ARCH, (int32_t)QNN_HTP_DEVICE_ARCH_V81 };

    QnnDevice_Config_t dSoc = {0}, dArch = {0};
    dSoc.option  = QNN_DEVICE_CONFIG_OPTION_CUSTOM;
    dSoc.customConfig  = (QnnDevice_CustomConfig_t)&cSoc;
    dArch.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM;
    dArch.customConfig = (QnnDevice_CustomConfig_t)&cArch;

    const QnnDevice_Config_t *cfgWith[3] = { &dSoc, &dArch, NULL };
    const QnnDevice_Config_t *cfgNone[1] = { NULL };
    Qnn_DeviceHandle_t dev2 = NULL;

    Qnn_DeviceHandle_t dev = NULL;
    rc = api->deviceCreate(NULL, cfgWith, &dev);
    emit("[qnn] deviceCreate(SOC=87, ARCH=v81) → %d (0x%llx) %s device=%p\n",
         (int)rc, (unsigned long long)rc, rc == 0 ? "<<< 成功 >>>" : "失败", dev);
    if (rc != 0) {
        rc = api->deviceCreate(NULL, cfgNone, &dev2);
        emit("[qnn] deviceCreate(无配置) → %d (0x%llx) device=%p\n", (int)rc, (unsigned long long)rc, dev2);
        if (rc == 0) dev = dev2;
    }

    /* 必须释放：QNN 的 HTP 设备独占 DSP 会话，
     * 不释放会让后续 Genie 里的 deviceCreate 一直阻塞（实测 RSS 停在 134MB 不涨）。 */
    if (dev && api->deviceFree) {
        Qnn_ErrorHandle_t fr = api->deviceFree(dev);
        emit("[qnn] deviceFree → %d\n", (int)fr);
    }
    if (be && api->backendFree) {
        Qnn_ErrorHandle_t fr = api->backendFree(be);
        emit("[qnn] backendFree → %d\n", (int)fr);
    }
    return (int)rc;
}

const char *npu_qnn_result(void) { return g_out; }
