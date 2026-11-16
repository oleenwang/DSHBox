/*
 * App 域 NPU 可达性探针 v5。
 *
 * 已确证的事实链：
 *  - appdomain 对 vendor_qdsp_device 只授 ioctl+read，无 open（vendor_sepolicy.cil:12837）
 *    → 直接 open("/dev/fastrpc-cdsp") 必 EACCES（v2/v3 实测 errno=13）。
 *  - /vendor/etc/public.libraries.txt 把 libcdsprpc.so / libadsprpc.so / libsdsprpc.so
 *    列为厂商公开库 → App 的 linker namespace 可直接按 soname 加载，
 *    其依赖在 vendor 命名空间内解析。把副本打进 APK 反而会遮蔽并引入坏依赖
 *    （v3 实测：自带 libhidlbase.so 缺 libvndksupport.so → dlopen 失败）。
 *  - appdomain 读 /sys/devices/soc0/sku 被拒（AVC denied vendor_sysfs_soc read）。
 *
 * v5 验证三件事：
 *  A. dlopen("libcdsprpc.so") 是否命中 /vendor/lib64 那份（dladdr 定位）；
 *  B. remote_handle64_open 能否在 App 域成功 —— 「App 能否触达 cDSP」的判定点；
 *  C. libQnnHtp / libGenie 能否加载并暴露接口（QnnInterface_getProviders、
 *     GenieDialog_create 一族），为进程内推理做准备。
 */
#include <stdio.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>
#include <stdarg.h>
#include <stdint.h>
#include <link.h>

static char g_out[32768];
static void emit(const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    size_t used = strlen(g_out);
    if (used < sizeof(g_out) - 1)
        vsnprintf(g_out + used, sizeof(g_out) - used - 1, fmt, ap);
    va_end(ap);
}

typedef int (*rh64_open_fn)(uint64_t *, const char *, uint32_t);
typedef int (*rh64_close_fn)(uint64_t);
typedef int (*getprov_fn)(const void ***, uint32_t *);

static void where_is(void *sym, const char *label) {
    Dl_info info;
    if (sym && dladdr(sym, &info) && info.dli_fname)
        emit("        %s → %s\n", label, info.dli_fname);
    else
        emit("        %s → <未知>\n", label);
}

/* 打印 /proc/self/maps 里已映射的 dsp/qnn 库，确认真正加载了哪一份 */
static void show_maps(void) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) { emit("        maps: %s\n", strerror(errno)); return; }
    char line[512]; int n = 0;
    while (fgets(line, sizeof(line), f) && n < 12) {
        if (strstr(line, "libcdsprpc") || strstr(line, "libQnn") || strstr(line, "libGenie")) {
            char *p = strchr(line, '/');
            if (p) { char *nl = strchr(p, '\n'); if (nl) *nl = 0; emit("        mapped: %s\n", p); n++; }
        }
    }
    fclose(f);
}

int npu_probe_run(void) {
    g_out[0] = 0;

    emit("[1] open(\"/dev/fastrpc-cdsp\") —— 预期 EACCES\n");
    int fd = open("/dev/fastrpc-cdsp", O_RDWR);
    emit("    fd=%d errno=%d (%s)\n", fd, errno, fd < 0 ? strerror(errno) : "OPENED");
    if (fd >= 0) close(fd);

    emit("[2] read(\"/sys/devices/soc0/sku\") —— 预期 EACCES\n");
    FILE *f = fopen("/sys/devices/soc0/sku", "r");
    if (!f) emit("    FAIL: %s\n", strerror(errno));
    else { char b[128] = {0}; if (fgets(b, sizeof(b), f)) emit("    OK: %s", b); fclose(f); }

    emit("[3] dlopen(\"libcdsprpc.so\") —— 走 /vendor/etc/public.libraries.txt\n");
    void *h = dlopen("libcdsprpc.so", RTLD_NOW | RTLD_GLOBAL);
    if (!h) {
        emit("    FAIL: %s\n", dlerror());
    } else {
        emit("    OK\n");
        where_is(h, "handle");
        rh64_open_fn op = (rh64_open_fn)dlsym(h, "remote_handle64_open");
        rh64_close_fn cl = (rh64_close_fn)dlsym(h, "remote_handle64_close");
        where_is((void *)op, "remote_handle64_open");
        if (op) {
            const char *names[] = { "cdsp", "adspspd", "sdsprpc" };
            for (int i = 0; i < 3; i++) {
                uint64_t hh = 0;
                int r = op(&hh, names[i], 0);
                emit("[4] remote_handle64_open(\"%s\") ret=%d handle=0x%llx %s\n",
                     names[i], r, (unsigned long long)hh, r == 0 ? "SUCCESS <<<<" : "FAILED");
                if (r == 0 && cl) cl(hh);
            }
        } else {
            emit("    dlsym failed: %s\n", dlerror());
        }
    }

    emit("[5] dlopen absolute /vendor/lib64/libcdsprpc.so —— 预期 namespace 拒绝\n");
    void *ha = dlopen("/vendor/lib64/libcdsprpc.so", RTLD_NOW);
    emit("    %s\n", ha ? "OK（意外，说明 namespace 含 vendor）" : dlerror());

    emit("[6] dlopen(\"libQnnHtp.so\") + QnnInterface_getProviders\n");
    void *q = dlopen("libQnnHtp.so", RTLD_NOW);
    if (!q) emit("    FAIL: %s\n", dlerror());
    else {
        emit("    OK\n");
        where_is(q, "handle");
        getprov_fn gp = (getprov_fn)dlsym(q, "QnnInterface_getProviders");
        emit("    QnnInterface_getProviders = %p\n", (void *)gp);
        if (gp) {
            const void **provs = NULL; uint32_t num = 0;
            int r = gp(&provs, &num);
            emit("    -> ret=%d numProviders=%u\n", r, num);
            if (r == 0 && provs && num > 0) {
                const uint32_t *w = (const uint32_t *)provs[0];
                emit("    iface[0] 首 8 个 uint32:");
                for (int i = 0; i < 8; i++) emit(" %u", w[i]);
                emit("\n");
            }
        }
    }

    emit("[7] dlopen(\"libGenie.so\") + 入口符号\n");
    void *g = dlopen("libGenie.so", RTLD_NOW);
    if (!g) emit("    FAIL: %s\n", dlerror());
    else {
        emit("    OK\n");
        where_is(g, "handle");
        const char *syms[] = { "GenieDialogConfig_createFromJson", "GenieDialog_create",
                               "GenieDialog_query", "GenieDialog_free",
                               "GenieDialogConfig_free", "Genie_getVersion" };
        for (int i = 0; i < 6; i++)
            emit("    %-38s = %p\n", syms[i], dlsym(g, syms[i]));
    }

    emit("[8] 当前进程已映射的相关库\n");
    show_maps();
    return 0;
}

const char *npu_probe_result(void) { return g_out; }
