//
// Created by shanshui on 2025/12/23.
//
#include "shadowhook.h"
#include <android/log.h>
#include <jni.h>
#include <unistd.h>
#include <time.h>
#include <sys/time.h>

void *orig = NULL;
void *stub = NULL;

// 全局变量存储延迟时间（毫秒）
static jint g_gc_inhibit_milliseconds = 2;  // 默认2秒

// 被 hook 函数的类型定义
typedef void (*gcRun_invoke_func_type_t)(void *, void *);

// 代理函数
void gcRun_invoke_proxy(void *thiz, void *thread) {
    // do something
    __android_log_print(ANDROID_LOG_INFO, "Hook", "gcRun_invoke_proxy");

    sleep(g_gc_inhibit_milliseconds);
    
    ((gcRun_invoke_func_type_t) orig)(thiz, thread);
    __android_log_print(ANDROID_LOG_INFO, "Hook", "gcRun_invoke_proxy end");
    // do something
}

void do_hook() {
    stub = shadowhook_hook_sym_name(
            "libart.so",
            "_ZN3art2gc4Heap16ConcurrentGCTask3RunEPNS_6ThreadE",
            (void *) gcRun_invoke_proxy,
            (void **) &orig);

    if (stub == NULL) {
        int err_num = shadowhook_get_errno();
        const char *err_msg = shadowhook_to_errmsg(err_num);
        __android_log_print(ANDROID_LOG_INFO, "Hook", "hook error %d - %s", err_num, err_msg);
    }
}

void do_unhook() {
    int result = shadowhook_unhook(stub);

    if (result != 0) {
        int err_num = shadowhook_get_errno();
        const char *err_msg = shadowhook_to_errmsg(err_num);
        __android_log_print(ANDROID_LOG_INFO, "Hook", "unhook error %d - %s", err_num, err_msg);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_nativelib_NativeLib_openGcInhibit(JNIEnv *env, jobject thiz, jint milliseconds) {
    g_gc_inhibit_milliseconds = milliseconds;
    do_hook();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_nativelib_NativeLib_closeGcInhibit(JNIEnv *env, jobject thiz) {
    do_unhook();
}
