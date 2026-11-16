/*
 * 进程内 Genie 推理入口（不 exec、不 LD_PRELOAD）。
 *
 * 背景：untrusted_app 对 app_data_file 只有 read，没有 execute，
 * 所以 ProcessBuilder 启动 app 私有目录里的 genie-t2t-run 必然 EACCES（error=13）。
 * 唯一可行形态是在 App 进程内 dlopen libGenie.so 并直接调 C API。
 *
 * 每一步都直接写进 trace_path（追加 + fsync），因为返回值只在函数结束时才可用，
 * 而 Genie 建图可能耗时数十秒甚至卡住 —— 必须能中途看到进度。
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <dlfcn.h>
#include <errno.h>
#include <time.h>
#include <unistd.h>

static char g_out[65536];
static char g_trace[1024];
static const char *g_text_start;

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

/* 同时进缓冲区（最终返回给 Java）和 trace 文件（实时可见） */
static void emit(const char *fmt, ...) {
    char line[4096];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);

    size_t used = strlen(g_out);
    if (used < sizeof(g_out) - 1)
        snprintf(g_out + used, sizeof(g_out) - used, "%s", line);

    if (g_trace[0]) {
        FILE *f = fopen(g_trace, "a");
        if (f) {
            fputs(line, f);
            if (strchr(line, '\n') == NULL) fputc('\n', f);
            fflush(f);
            fsync(fileno(f));
            fclose(f);
        }
    }
}

typedef void *cfg_h;
typedef void *dlg_h;
typedef int   status_t;
typedef void (*query_cb)(const char *, int, const void *);

typedef status_t (*cfg_from_json_fn)(const char *, cfg_h *);
typedef status_t (*cfg_free_fn)(cfg_h);
typedef status_t (*dlg_create_fn)(cfg_h, dlg_h *);
typedef status_t (*dlg_query_fn)(dlg_h, const char *, int, query_cb, const void *);
typedef status_t (*dlg_free_fn)(dlg_h);

static int  g_tokens;
static long g_t0, g_first, g_last;

/* 正文累积到 g_out 的 "文本区"，从 g_text_start 开始 */
static char g_text[32768];
static size_t g_text_len;

static void on_token(const char *text, int code, const void *ud) {
    (void)ud;
    long t = now_ms();
    if (g_tokens == 0) g_first = t;
    g_last = t;
    g_tokens++;
    if (text && *text && g_text_len + strlen(text) < sizeof(g_text) - 1) {
        strcpy(g_text + g_text_len, text);
        g_text_len += strlen(text);
    }
    (void)code;
}

int npu_genie_run(const char *cfg_json_path, const char *prompt, const char *trace_path) {
    g_out[0] = 0;
    g_text[0] = 0;
    g_text_len = 0;
    g_tokens = 0;
    g_trace[0] = 0;
    if (trace_path && *trace_path)
        snprintf(g_trace, sizeof(g_trace), "%s", trace_path);

    long tStart = now_ms();
    emit("[genie] %ld 开始，配置 %s\n", tStart, cfg_json_path ? cfg_json_path : "(null)");

    void *lib = dlopen("libGenie.so", RTLD_NOW | RTLD_GLOBAL);
    if (!lib) { emit("[genie] dlopen libGenie.so 失败: %s\n", dlerror()); return -1; }
    emit("[genie] +%ldms libGenie.so 已加载\n", now_ms() - tStart);

    cfg_from_json_fn c_from  = (cfg_from_json_fn)dlsym(lib, "GenieDialogConfig_createFromJson");
    cfg_free_fn      c_free  = (cfg_free_fn)dlsym(lib, "GenieDialogConfig_free");
    dlg_create_fn    d_new   = (dlg_create_fn)dlsym(lib, "GenieDialog_create");
    dlg_query_fn     d_query = (dlg_query_fn)dlsym(lib, "GenieDialog_query");
    dlg_free_fn      d_free  = (dlg_free_fn)dlsym(lib, "GenieDialog_free");
    if (!c_from || !d_new || !d_query) {
        emit("[genie] dlsym 缺符号 cfg=%p create=%p query=%p\n",
             (void *)c_from, (void *)d_new, (void *)d_query);
        return -2;
    }

    FILE *f = fopen(cfg_json_path, "r");
    if (!f) { emit("[genie] 打不开配置: %s\n", strerror(errno)); return -3; }
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    char *json = malloc(n + 1);
    if (!json) { fclose(f); return -4; }
    if (fread(json, 1, n, f) != (size_t)n) { fclose(f); free(json); return -5; }
    json[n] = 0;
    fclose(f);
    emit("[genie] +%ldms 配置读入 %ld 字节\n", now_ms() - tStart, n);

    cfg_h cfg = NULL;
    emit("[genie] 调用 GenieDialogConfig_createFromJson …\n");
    status_t st = c_from(json, &cfg);
    free(json);
    emit("[genie] +%ldms createFromJson → %d cfg=%p\n", now_ms() - tStart, st, cfg);
    if (st != 0 || !cfg) return -6;

    dlg_h dlg = NULL;
    emit("[genie] 调用 GenieDialog_create（会建 QNN 图并加载权重，最耗时）…\n");
    st = d_new(cfg, &dlg);
    emit("[genie] +%ldms GenieDialog_create → %d dlg=%p\n", now_ms() - tStart, st, dlg);
    if (st != 0 || !dlg) { if (c_free) c_free(cfg); return -7; }

    g_t0 = now_ms();
    emit("[genie] 调用 GenieDialog_query …\n");
    st = d_query(dlg, prompt, 0 /* GENIE_DIALOG_SENTENCE_COMPLETE */, on_token, NULL);
    long tEnd = now_ms();
    emit("[genie] +%ldms query → %d，回调 %d 次，decode 耗时 %ldms\n",
         tEnd - tStart, st, g_tokens, tEnd - g_t0);
    if (g_tokens > 1)
        emit("[genie] 首字 %ldms，%.1f char/s\n",
             g_first - g_t0,
             (g_tokens - 1) * 1000.0 / (double)(g_last - g_first > 0 ? g_last - g_first : 1));

    if (g_text_len) emit("[genie] 输出正文：%s\n", g_text);

    if (d_free) d_free(dlg);
    if (c_free) c_free(cfg);
    emit("[genie] 结束，退出码 %d\n", st == 0 ? 0 : -8);
    return st == 0 ? 0 : -8;
}

const char *npu_genie_result(void) { return g_out; }
