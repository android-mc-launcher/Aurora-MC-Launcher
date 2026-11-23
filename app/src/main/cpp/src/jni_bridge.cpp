#include <jni.h>
#include <android/log.h>

#include <array>
#include <string>
#include <string_view>

#include "engine.hpp"

namespace {

constexpr const char* kTag = "Aurora-JNI";
constexpr const char* kEngineClass = "com/yuanbao/mcjlauncher/engine/NativeEngine";

std::string fromJava(JNIEnv* env, jstring value) noexcept {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

jstring toJava(JNIEnv* env, std::string_view value) noexcept {
    return env->NewStringUTF(std::string(value).c_str());
}

// 函数名刻意不使用 Java_<包名>_<方法名> 形式：
// 全部 12 个方法在 JNI_OnLoad 中通过 RegisterNatives 动态注册。
jstring JNICALL cppEngineVersion(JNIEnv* env, jobject /*thiz*/) {
    return toJava(env, mcjl::Engine::instance().version());
}

jboolean JNICALL cppNativeInit(JNIEnv* env, jobject /*thiz*/, jstring dataDir, jstring nativeDir) {
    const auto ok = mcjl::Engine::instance().init(fromJava(env, dataDir), fromJava(env, nativeDir));
    return ok ? JNI_TRUE : JNI_FALSE;
}

jint JNICALL cppNativeStartGame(JNIEnv* env, jobject /*thiz*/, jstring planJson) {
    return mcjl::Engine::instance().start(fromJava(env, planJson));
}

jboolean JNICALL cppNativeStopGame(JNIEnv* /*env*/, jobject /*thiz*/) {
    return mcjl::Engine::instance().stop() ? JNI_TRUE : JNI_FALSE;
}

jboolean JNICALL cppNativeKill(JNIEnv* /*env*/, jobject /*thiz*/) {
    return mcjl::Engine::instance().kill() ? JNI_TRUE : JNI_FALSE;
}

jstring JNICALL cppNativePollLog(JNIEnv* env, jobject /*thiz*/) {
    return toJava(env, mcjl::Engine::instance().pollLog());
}

jboolean JNICALL cppNativeIsRunning(JNIEnv* /*env*/, jobject /*thiz*/) {
    return mcjl::Engine::instance().isRunning() ? JNI_TRUE : JNI_FALSE;
}

jstring JNICALL cppNativeGetStats(JNIEnv* env, jobject /*thiz*/) {
    return toJava(env, mcjl::Engine::instance().statsJson());
}

void JNICALL cppNativeSetLogLevel(JNIEnv* /*env*/, jobject /*thiz*/, jint level) {
    mcjl::Engine::instance().setLogLevel(level);
}

jboolean JNICALL cppNativeWriteStdin(JNIEnv* env, jobject /*thiz*/, jstring text) {
    return mcjl::Engine::instance().writeStdin(fromJava(env, text)) ? JNI_TRUE : JNI_FALSE;
}

jint JNICALL cppNativeExitCode(JNIEnv* /*env*/, jobject /*thiz*/) {
    return mcjl::Engine::instance().exitCode();
}

jint JNICALL cppNativeState(JNIEnv* /*env*/, jobject /*thiz*/) {
    return mcjl::Engine::instance().state();
}

const std::array<JNINativeMethod, 12> kMethods = {{
    {"engineVersion",     "()Ljava/lang/String;",                    reinterpret_cast<void*>(&cppEngineVersion)},
    {"nativeInit",        "(Ljava/lang/String;Ljava/lang/String;)Z", reinterpret_cast<void*>(&cppNativeInit)},
    {"nativeStartGame",   "(Ljava/lang/String;)I",                   reinterpret_cast<void*>(&cppNativeStartGame)},
    {"nativeStopGame",    "()Z",                                     reinterpret_cast<void*>(&cppNativeStopGame)},
    {"nativePollLog",     "()Ljava/lang/String;",                    reinterpret_cast<void*>(&cppNativePollLog)},
    {"nativeIsRunning",   "()Z",                                     reinterpret_cast<void*>(&cppNativeIsRunning)},
    {"nativeGetStats",    "()Ljava/lang/String;",                    reinterpret_cast<void*>(&cppNativeGetStats)},
    {"nativeSetLogLevel", "(I)V",                                   reinterpret_cast<void*>(&cppNativeSetLogLevel)},
    {"nativeWriteStdin",  "(Ljava/lang/String;)Z",                   reinterpret_cast<void*>(&cppNativeWriteStdin)},
    {"nativeKill",        "()Z",                                     reinterpret_cast<void*>(&cppNativeKill)},
    {"nativeExitCode",    "()I",                                     reinterpret_cast<void*>(&cppNativeExitCode)},
    {"nativeState",       "()I",                                     reinterpret_cast<void*>(&cppNativeState)},
}};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }

    jclass clazz = env->FindClass(kEngineClass);
    if (clazz == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "FindClass(%s) 失败", kEngineClass);
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_ERR;
    }

    const jint result = env->RegisterNatives(clazz, kMethods.data(), static_cast<jint>(kMethods.size()));
    env->DeleteLocalRef(clazz);
    if (result != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "RegisterNatives 失败：%d 个方法", static_cast<int>(kMethods.size()));
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_ERR;
    }

    __android_log_print(ANDROID_LOG_INFO, kTag, "动态注册完成：%zu 个 native 方法 → %s",
                        kMethods.size(), kEngineClass);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) return;
    if (jclass clazz = env->FindClass(kEngineClass); clazz != nullptr) {
        env->UnregisterNatives(clazz);
        env->DeleteLocalRef(clazz);
    }
}
