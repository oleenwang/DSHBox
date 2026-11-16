/*
 * 进程内 Genie 推理入口（不 exec、不 LD_PRELOAD）。
 *
 * 背景：untrusted_app 对 app_data_file 只有 read，没有 execute，
 * 所以 ProcessBuilder 启动 app 私有目录里的 genie-t2t-run 必然 EACCES（error=13）。
 * 唯一可行形态是在 App 进程内 dlopen libGenie.so 并直接调 C API。
 *
 * 依赖（全部随 APK 提供）：libGenie.so / libQnnHtp.so / libQnnHtpV81Skel.so /
 * libQnnHtpV81Stub.so / libQnnSystem.so；libcdsprpc.so 走 vendor 公开库。
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <dlfcn.h>
#include <errno.h>

static char g_out[65536];
static void emit(const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    size_t used = strlen(g_out);
    if (used < sizeof(g_out) - 1) vsnprintf(g_out + used, sizeof(g_out) - used - 1, fmt, ap);
    va_end(ap);
}

/* Genie 的句柄都是不透明指针 */
typedef void *cfg_h;
typedef void *dlg_h;
typedef int   status_t;
typedef void (*query_cb)(const char *, int, const void *);

typedef status_t (*cfg_from_json_fn)(const char *, cfg_h *);
typedef status_t (*cfg_free_fn)(cfg_h);
typedef status_t (*dlg_create_fn)(cfg_h, dlg_h *);
typedef status_t (*dlg_query_fn)(dlg_h, const char *, int, query_cb, const void *);
typedef status_t (*dlg_free_fn)(dlg_h);

static void on_token(const char *text, int code, const void *ud) {
    (void)ud;
    /* code: 0=COMPLETE 1=BEGIN 2=CONTINUE 3=END 4=ABORT 5=REWIND 6=RESUME */
    if (text && *text) emit("%s", text);
    (void)code;
}

/* 返回 0 表示成功；结果文本累积在 g_out 的后半段 */
static int genie_run(const char *cfg_json_path, const char *prompt) {
    void *lib = dlopen("libGenie.so", RTLD_NOW | RTLD_GLOBAL);
    if (!lib) { emit("[genie] dlopen libGenie.so 失败: %s\n", dlerror()); return -1; }

    cfg_from_json_fn c_from  = (cfg_from_json_fn)dlsym(lib, "GenieDialogConfig_createFromJson");
    cfg_free_fn      c_free  = (cfg_free_fn)dlsym(lib, "GenieDialogConfig_free");
    dlg_create_fn    d_new   = (dlg_create_fn)dlsym(lib, "GenieDialog_create");
    dlg_query_fn     d_query = (dlg_query_fn)dlsym(lib, "GenieDialog_query");
    dlg_free_fn      d_free  = (dlg_free_fn)dlsym(lib, "GenieDialog_free");
    if (!c_from || !d_new || !d_query) {
        emit("[genie] dlsym 缺符号: cfg=%p create=%p query=%p\n",
             (void *)c_from, (void *)d_new, (void *)d_query);
        return -2;
    }

    /* 读配置文件 */
    FILE *f = fopen(cfg_json_path, "r");
    if (!f) { emit("[genie] 打不开配置 %s: %s\n", cfg_json_path, strerror(errno)); return -3; }
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    char *json = malloc(n + 1);
    if (!json) { fclose(f); return -4; }
    if (fread(json, 1, n, f) != (size_t)n) { fclose(f); free(json); return -5; }
    json[n] = 0; fclose(f);
    emit("[genie] 配置已读入 %ld 字节\n", n);

    cfg_h cfg = NULL;
    status_t st = c_from(json, &cfg);
    free(json);
    emit("[genie] GenieDialogConfig_createFromJson → %d (cfg=%p)\n", st, cfg);
    if (st != 0 || !cfg) return -6;

    dlg_h dlg = NULL;
    st = d_new(cfg, &dlg);
    emit("[genie] GenieDialog_create → %d (dlg=%p)\n", st, dlg);
    if (st != 0 || !dlg) { if (c_free) c_free(cfg); return -7; }

    emit("[genie] === 生成开始 ===\n");
    st = d_query(dlg, prompt, 1 /* GENIE_DIALOG_SENTENCE_BEGIN */, on_token, NULL);
    emit("\n[genie] GenieDialog_query → %d\n", st);

    if (d_free) d_free(dlg);
    if (c_free) c_free(cfg);
    return st == 0 ? 0 : -8;
}

int npu_genie_run(const char *cfg_json_path, const char *prompt) {
    g_out[0] = 0;
    int r = genie_run(cfg_json_path, prompt);
    emit("[genie] 退出码 %d\n", r);
    return r;
}
const char *npu_genie_result(void) { return g_out; }
