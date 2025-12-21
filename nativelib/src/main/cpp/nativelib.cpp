#include <jni.h>
#include <string>
#include <sys/times.h>
#include <unistd.h>
#include <android/log.h>
#include <dirent.h>
#include <fstream>

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
        __android_log_print(ANDROID_LOG_INFO, "NativeLib", "clock_ticks_per_sec: %ld",
                            clock_ticks_per_sec);
        __android_log_print(ANDROID_LOG_INFO, "NativeLib", "user_seconds: %f", user_seconds);
        __android_log_print(ANDROID_LOG_INFO, "NativeLib", "system_seconds: %f", system_seconds);
        return (jfloat) (user_seconds + system_seconds);
    }
    return (jfloat) -1;
}

int getNumberOfCPUCores() {
    int cores = 0;
    DIR *dir;
    struct dirent *ent;
    if ((dir = opendir("/sys/devices/system/cpu/")) != NULL) {
        while ((ent = readdir(dir)) != NULL) {
            std::string path = ent->d_name;
            if (path.find("cpu") == 0) {
                bool isCore = true;
                for (int i = 3; i < path.length(); i++) {
                    if (path[i] < '0' || path[i] > '9') {
                        isCore = false;
                        break;
                    }
                }
                if (isCore) {
                    cores++;
                }
            }
        }
        closedir(dir);
    }
    return cores;
}

int getMaxFreqCPU() {
    int maxFreq = -1;
    int maxFreqCore = -1;
    for (int i = 0; i < getNumberOfCPUCores(); i++) {
        std::string filename = "/sys/devices/system/cpu/cpu" +
                               std::to_string(i) + "/cpufreq/cpuinfo_max_freq";
        std::ifstream cpuInfoMaxFreqFile(filename);
        if (cpuInfoMaxFreqFile.is_open()) {
            std::string line;
            if (std::getline(cpuInfoMaxFreqFile, line)) {
                try {
                    int freqBound = std::stoi(line);
                    if (freqBound > maxFreq) {
                        maxFreq = freqBound;
                        maxFreqCore = i;
                    }
                } catch (const std::invalid_argument &e) {

                }
            }
            cpuInfoMaxFreqFile.close();
        }
    }
    __android_log_print(ANDROID_LOG_INFO, "NativeLib", "MaxFreqCPUCore: %d", maxFreqCore);
    return maxFreqCore;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_nativelib_NativeLib_bindMainThreadToMaxCore(JNIEnv *env, jobject thiz) {
    cpu_set_t mask;    //CPU核的集合
    CPU_ZERO(&mask);    //将mask置空
    int maxFreqCore = getMaxFreqCPU();
    if (maxFreqCore == -1) {
        __android_log_print(ANDROID_LOG_ERROR, "NativeLib", "Failed to get max frequency CPU core");
        return false;
    }
    CPU_SET(maxFreqCore, &mask);   //将需要绑定的CPU核序列设置给mask，核为序列0,1,2,3……
    if (sched_setaffinity(0, sizeof(mask), &mask) == -1) {    //pid 指的是线程的 id，如果 pid 的值为 0，则表示是主线程
        __android_log_print(ANDROID_LOG_ERROR, "NativeLib", "bind main thread to core %d failed", maxFreqCore);
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, "NativeLib", "Successfully bind main thread to core %d", maxFreqCore);
    return true;
}
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_nativelib_NativeLib_bindCurrentThreadToMaxCore(JNIEnv *env, jobject thiz) {
    cpu_set_t mask;    //CPU核的集合
    CPU_ZERO(&mask);    //将mask置空
    int maxFreqCore = getMaxFreqCPU();
    if (maxFreqCore == -1) {
        __android_log_print(ANDROID_LOG_ERROR, "NativeLib", "Failed to get max frequency CPU core");
        return false;
    }
    CPU_SET(maxFreqCore, &mask);   //将需要绑定的CPU核序列设置给mask，核为序列0,1,2,3……
    // 获取当前线程ID
    pid_t tid = gettid();
    if (sched_setaffinity(tid, sizeof(mask), &mask) == -1) {    //将当前线程绑定到指定CPU核心
        __android_log_print(ANDROID_LOG_ERROR, "NativeLib", "bind current thread to core %d failed", maxFreqCore);
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, "NativeLib", "Successfully bind current thread to core %d", maxFreqCore);
    return true;
}