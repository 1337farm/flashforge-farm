#include "farm_progress.hpp"

#include <android/log.h>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

#define TAG "FarmProgress"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace farm {

namespace {

struct Mailbox {
    std::mutex mutex;
    std::condition_variable cv;
    int percent = -1;
    std::string phase;
    std::uint64_t seq = 0;      // bumped on every publish
    std::uint64_t served = 0;   // last seq the pump dispatched
    bool stopping = false;
    std::thread pump;
};

Mailbox g_mb;

JavaVM* g_vm = nullptr;
jobject g_listener = nullptr;   // global ref, owned for the duration of a session
jclass g_listener_cls = nullptr;  // cached global ref
jmethodID g_on_progress = nullptr;

void pump_loop() {
    JNIEnv* env = nullptr;
    JavaVMAttachArgs args;
    args.version = JNI_VERSION_1_6;
    args.name = "FarmProgressPump";
    args.group = nullptr;
    if (g_vm->AttachCurrentThread(&env, &args) != JNI_OK || env == nullptr) {
        LOGE("pump: AttachCurrentThread failed");
        return;
    }

    for (;;) {
        bool done = false;
        int percent = -1;
        std::string phase;
        {
            std::unique_lock<std::mutex> lock(g_mb.mutex);
            g_mb.cv.wait(lock, [] { return g_mb.seq != g_mb.served || g_mb.stopping; });
            if (g_mb.stopping && g_mb.seq == g_mb.served) {
                done = true;
            } else {
                percent = g_mb.percent;
                phase = g_mb.phase;
                g_mb.served = g_mb.seq;
            }
        }
        if (done)
            break;

        // Call out to Java without holding the mailbox lock.
        jstring jphase = env->NewStringUTF(phase.c_str());
        env->CallVoidMethod(g_listener, g_on_progress, static_cast<jint>(percent), jphase);
        if (jphase != nullptr)
            env->DeleteLocalRef(jphase);
        if (env->ExceptionCheck())
            env->ExceptionClear(); // a broken listener must not kill the slice
    }

    g_vm->DetachCurrentThread();
}

}  // namespace

void progress_begin(JNIEnv* env, JavaVM* vm, jobject listener) {
    // Join any stale session first (begin/end must be 1:1).
    progress_end(env);

    g_vm = vm;

    if (g_listener_cls == nullptr) {
        jclass local = env->FindClass("com/flashforge/farm/slic3r/SliceListener");
        if (local == nullptr) {
            LOGE("begin: SliceListener class not found");
            return;
        }
        g_listener_cls = static_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
        g_on_progress = env->GetMethodID(g_listener_cls, "onProgress", "(ILjava/lang/String;)V");
        if (g_on_progress == nullptr) {
            LOGE("begin: onProgress method not found");
            return;
        }
    }

    g_listener = env->NewGlobalRef(listener);

    {
        std::lock_guard<std::mutex> lock(g_mb.mutex);
        g_mb.percent = -1;
        g_mb.phase.clear();
        g_mb.stopping = false;
        g_mb.served = g_mb.seq; // reset the served watermark for the new session
    }

    g_mb.pump = std::thread(pump_loop);
}

void progress_set(int percent, const char* phase) {
    {
        std::lock_guard<std::mutex> lock(g_mb.mutex);
        g_mb.percent = percent;
        g_mb.phase = phase != nullptr ? phase : "";
        ++g_mb.seq;
    }
    g_mb.cv.notify_one();
}

void progress_end(JNIEnv* env) {
    {
        std::lock_guard<std::mutex> lock(g_mb.mutex);
        g_mb.stopping = true;
    }
    g_mb.cv.notify_all();
    if (g_mb.pump.joinable())
        g_mb.pump.join();

    if (g_listener != nullptr && env != nullptr) {
        env->DeleteGlobalRef(g_listener);
        g_listener = nullptr;
    }
}

}  // namespace farm