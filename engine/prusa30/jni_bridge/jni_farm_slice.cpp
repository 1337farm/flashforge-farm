#include <jni.h>
#include <android/log.h>

#include "../farm_driver.hpp"

#define TAG "FarmPrusaJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_flashforge_farm_engine_Prusa30Bridge_sliceModel(JNIEnv* env, jobject /*thiz*/,
                                                         jstring modelPath,
                                                         jstring configPath)
{
    const char* modelCStr = env->GetStringUTFChars(modelPath, nullptr);
    const char* configCStr = env->GetStringUTFChars(configPath, nullptr);
    try {
        auto progress = [](const Slic3r::Biz::Slicing::Progress& p){ (void)p; };
        auto result = farm::slice(modelCStr, configCStr, progress);
        (void)result;
        env->ReleaseStringUTFChars(modelPath, modelCStr);
        env->ReleaseStringUTFChars(configPath, configCStr);
        return env->NewStringUTF("OK");
    } catch (const std::exception& e) {
        env->ReleaseStringUTFChars(modelPath, modelCStr);
        env->ReleaseStringUTFChars(configPath, configCStr);
        LOGE("slice failed: %s", e.what());
        return env->NewStringUTF(e.what());
    }
}
