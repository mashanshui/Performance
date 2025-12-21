#include <jni.h>
#include <string>
#include <sys/times.h>
#include <unistd.h>
#include <android/log.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_nativelib_NativeLib_stringFromJNI(
        JNIEnv *env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}
extern "C"
JNIEXPORT jfloat JNICALL
Java_com_example_nativelib_NativeLib_getCpuTime(JNIEnv *env, jobject thiz) {
    struct tms time_info;
    clock_t ticks;

    ticks = times(&time_info);
    if (ticks != (clock_t) -1) {
        // 获取用户态和系统态CPU时间
        clock_t user_time = time_info.tms_utime;
        clock_t system_time = time_info.tms_stime;

        // 可以转换为秒
        long clock_ticks_per_sec = sysconf(_SC_CLK_TCK);
        double user_seconds = (double) user_time / clock_ticks_per_sec;
        double system_seconds = (double) system_time / clock_ticks_per_sec;

        // 使用Android日志输出
        __android_log_print(ANDROID_LOG_INFO, "NativeLib", "clock_ticks_per_sec: %ld", clock_ticks_per_sec);
        __android_log_print(ANDROID_LOG_INFO, "NativeLib", "user_seconds: %f", user_seconds);
        __android_log_print(ANDROID_LOG_INFO, "NativeLib", "system_seconds: %f", system_seconds);
        return (jfloat) (user_seconds + system_seconds);
    }
    return (jfloat) -1;
}