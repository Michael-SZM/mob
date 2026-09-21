/*
 * security_detect.c
 * Android 客户端风控检测 · Native C 核心安全层
 *
 * 职责（对应方案 3.1 节，核心逻辑下沉 Native 防 Java 层 Hook）：
 *   1. 解析 /proc/self/status 的 TracerPid，检测是否被 ptrace 附加（Frida 核心特征）；
 *   2. 读取 /proc/self/maps 检测内存恶意 SO（frida-agent / frida-gadget / xposed / lsposed）；
 *   3. 遍历系统 SU 路径，识别原生 Root 文件；
 *   4. 统一输出结构化 JSON 风险实体（fridaRisk / xposedRisk / rootNativeRisk），
 *      由 Kotlin 层解析后并入全量上报。
 *
 * 安全原则：本层只做特征采集，不做任何本地拦截 / 弹窗 / 封禁，
 * 所有风控决策交由服务端权重打分完成。
 */

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>

/* ============================ 动态字符串缓冲 ============================ */

typedef struct {
    char  *data;
    size_t len;
    size_t cap;
} strbuf_t;

static void sb_init(strbuf_t *sb) {
    sb->cap = 4096;
    sb->len = 0;
    sb->data = (char *)malloc(sb->cap);
    if (sb->data != NULL) {
        sb->data[0] = '\0';
    }
}

static void sb_append(strbuf_t *sb, const char *s) {
    if (sb->data == NULL || s == NULL) {
        return;
    }
    size_t add = strlen(s);
    if (sb->len + add + 1 > sb->cap) {
        size_t new_cap = sb->cap;
        while (sb->len + add + 1 > new_cap) {
            new_cap *= 2;
        }
        char *nb = (char *)realloc(sb->data, new_cap);
        if (nb == NULL) {
            return; /* OOM：保留旧数据，宁缺毋错 */
        }
        sb->data = nb;
        sb->cap = new_cap;
    }
    memcpy(sb->data + sb->len, s, add);
    sb->len += add;
    sb->data[sb->len] = '\0';
}

static void sb_free(strbuf_t *sb) {
    free(sb->data);
    sb->data = NULL;
    sb->len = 0;
    sb->cap = 0;
}

/* ============================ 内部工具：朴素子串查找 ============================ */
/* 自实现，避免依赖 GNU 扩展 memmem（bionic API 28 才引入），兼容 minSdk 24 */
static int buffer_contains(const char *hay, size_t hay_len,
                           const char *needle, size_t needle_len) {
    if (needle_len == 0 || hay_len < needle_len) {
        return 0;
    }
    for (size_t i = 0; i + needle_len <= hay_len; i++) {
        if (hay[i] == needle[0] &&
            (needle_len == 1 || memcmp(hay + i, needle, needle_len) == 0)) {
            return 1;
        }
    }
    return 0;
}

/* ============================ 工具方法 1：file_contains ============================ */
/*
 * 分块读取文件内容并匹配关键字。
 * 通过"块尾回退关键字长度-1字节"机制，保证跨读取块边界的关键字不漏检。
 * 文件不存在 / 无权限读取时返回 0（未命中）。
 */
static int file_contains(const char *path, const char *keyword) {
    if (path == NULL || keyword == NULL || keyword[0] == '\0') {
        return 0;
    }

    const size_t kw_len = strlen(keyword);
    const size_t buf_size = 8192;
    /* 额外分配 kw_len 空间，用于回退窗口拼接 */
    char *buf = (char *)malloc(buf_size + kw_len);
    if (buf == NULL) {
        return 0;
    }

    int fd = open(path, O_RDONLY);
    int found = 0;
    if (fd >= 0) {
        size_t carry = 0;
        ssize_t n;
        while (!found &&
               (n = read(fd, buf + carry, (size_t)buf_size)) > 0) {
            size_t total = carry + (size_t)n;
            if (buffer_contains(buf, total, keyword, kw_len)) {
                found = 1;
                break;
            }
            /* 保留块尾 kw_len-1 字节，防止关键字恰好被读取边界切断 */
            if (kw_len > 1 && total >= kw_len - 1) {
                carry = kw_len - 1;
                memmove(buf, buf + total - carry, carry);
            } else {
                carry = 0;
            }
        }
        close(fd);
    }
    free(buf);
    return found;
}

/* ============================ 工具方法 2：get_tracer_pid ============================ */
/*
 * 解析 /proc/self/status 中的 TracerPid 字段。
 * 返回值：>0 表示正被 ptrace 附加（Frida 核心特征）；0 表示未被追踪；-1 表示读取失败。
 */
static long get_tracer_pid(void) {
    FILE *fp = fopen("/proc/self/status", "r");
    if (fp == NULL) {
        return -1;
    }

    char line[512];
    long tracer_pid = -1;
    while (fgets(line, sizeof(line), fp) != NULL) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            tracer_pid = strtol(line + 10, NULL, 10);
            break;
        }
    }
    fclose(fp);
    return tracer_pid;
}

/* ============================ 工具方法 3：maps_contain_lib ============================ */
/* 检测当前进程内存 maps 中是否加载了指定恶意 SO 关键字 */
static int maps_contain_lib(const char *lib_keyword) {
    return file_contains("/proc/self/maps", lib_keyword);
}

/* ============================ 强制检测特征清单（方案 5.2 节） ============================ */

/* Frida 内存 SO 特征 */
static const char *FRIDA_LIB_KEYWORDS[] = {
    "libfrida-agent.so",
    "libfrida-gadget.so",
};

/* Xposed / LSPosed 内存 SO 特征 */
static const char *XPOSED_LIB_KEYWORDS[] = {
    "libxposed_agent.so",
    "liblsposed.so",
    "liblspd.so",
};

/* LSPosed 挂载目录特征关键字（/data/adb/lspd 挂载节点） */
static const char *LSPD_MOUNT_KEYWORD = "lspd";

/* 主流原生 Root 文件路径 */
static const char *SU_PATHS[] = {
    "/system/bin/su",
    "/system/xbin/su",
    "/sbin/su",
    "/system/sd/xbin/su",
    "/system/bin/failsafe/su",
    "/system/usr/we-need-root/su",
    "/system/bin/.ext/.su",
    "/data/local/xbin/su",
    "/data/local/bin/su",
    "/data/local/su",
    "/su/bin/su",
    "/su/bin/magisk",
    "/sbin/magisk",
    "/system/bin/magisk",
    "/system/app/Superuser.apk",
    "/system/app/SuperSU",
    "/system/app/Superuser",
    "/data/adb/magisk",
};

/* ============================ 三大检测入口（方案 5.1 节） ============================ */

typedef struct {
    int      detected;
    strbuf_t details_json; /* 形如 "a","b","c" 的 JSON 数组元素片段 */
} risk_result_t;

static void risk_init(risk_result_t *r) {
    r->detected = 0;
    sb_init(&r->details_json);
}

static void risk_hit(risk_result_t *r, const char *detail) {
    if (r->detected) {
        sb_append(&r->details_json, ",");
    }
    r->detected = 1;
    sb_append(&r->details_json, "\"");
    sb_append(&r->details_json, detail);
    sb_append(&r->details_json, "\"");
}

static void risk_hit_pid(risk_result_t *r, const char *prefix, long pid) {
    char tmp[64];
    snprintf(tmp, sizeof(tmp), "%s%ld", prefix, pid);
    risk_hit(r, tmp);
}

/* 检测入口 1：Frida 注入环境（TracerPid + 内存恶意 SO 交叉校验） */
static void detect_frida(risk_result_t *r) {
    /* 特征 1：TracerPid > 0，正被 ptrace 附加 */
    long tracer = get_tracer_pid();
    if (tracer > 0) {
        risk_hit_pid(r, "TracerPid:", tracer);
    }
    /* 特征 2：内存 maps 中的 frida 恶意 SO */
    for (size_t i = 0; i < sizeof(FRIDA_LIB_KEYWORDS) / sizeof(FRIDA_LIB_KEYWORDS[0]); i++) {
        if (maps_contain_lib(FRIDA_LIB_KEYWORDS[i])) {
            risk_hit(r, FRIDA_LIB_KEYWORDS[i]);
        }
    }
}

/* 检测入口 2：Xposed / LSPosed 注入环境（内存恶意 SO + 挂载节点） */
static void detect_xposed(risk_result_t *r) {
    /* 特征 1：内存 maps 中的 xposed / lsposed 恶意 SO */
    for (size_t i = 0; i < sizeof(XPOSED_LIB_KEYWORDS) / sizeof(XPOSED_LIB_KEYWORDS[0]); i++) {
        if (maps_contain_lib(XPOSED_LIB_KEYWORDS[i])) {
            risk_hit(r, XPOSED_LIB_KEYWORDS[i]);
        }
    }
    /* 特征 2：/data/adb/lspd 挂载节点（检查挂载表） */
    if (file_contains("/proc/self/mounts", LSPD_MOUNT_KEYWORD)) {
        risk_hit(r, "/data/adb/lspd-mount");
    }
}

/* 检测入口 3：原生 Root 环境（遍历主流 SU 系统路径） */
static void detect_root_native(risk_result_t *r) {
    for (size_t i = 0; i < sizeof(SU_PATHS) / sizeof(SU_PATHS[0]); i++) {
        if (access(SU_PATHS[i], F_OK) == 0) {
            risk_hit(r, SU_PATHS[i]);
        }
    }
}

/* ============================ JSON 组装 ============================ */

static void append_risk_json(strbuf_t *sb, const char *name, const risk_result_t *r) {
    sb_append(sb, "\"");
    sb_append(sb, name);
    sb_append(sb, "\":{\"detected\":");
    sb_append(sb, r->detected ? "true" : "false");
    sb_append(sb, ",\"hitDetails\":[");
    sb_append(sb, r->details_json.data != NULL ? r->details_json.data : "");
    sb_append(sb, "]}");
}

/* ============================ JNI 导出 ============================ */

/*
 * 统一采集 Native 层三大核心风险，输出结构化 JSON：
 * {
 *   "fridaRisk":     { "detected": bool, "hitDetails": [...] },
 *   "xposedRisk":    { "detected": bool, "hitDetails": [...] },
 *   "rootNativeRisk":{ "detected": bool, "hitDetails": [...] }
 * }
 */
JNIEXPORT jstring JNICALL
Java_com_example_demotest_security_NativeSecurityDetector_nativeCollectRiskJson(
        JNIEnv *env, jclass clazz) {
    (void)clazz;

    risk_result_t frida;
    risk_result_t xposed;
    risk_result_t root;
    risk_init(&frida);
    risk_init(&xposed);
    risk_init(&root);

    detect_frida(&frida);
    detect_xposed(&xposed);
    detect_root_native(&root);

    strbuf_t out;
    sb_init(&out);
    sb_append(&out, "{");
    append_risk_json(&out, "fridaRisk", &frida);
    sb_append(&out, ",");
    append_risk_json(&out, "xposedRisk", &xposed);
    sb_append(&out, ",");
    append_risk_json(&out, "rootNativeRisk", &root);
    sb_append(&out, "}");

    jstring result = (*env)->NewStringUTF(env, out.data != NULL ? out.data : "{}");

    sb_free(&frida.details_json);
    sb_free(&xposed.details_json);
    sb_free(&root.details_json);
    sb_free(&out);
    return result;
}
