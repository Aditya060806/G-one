/**
 * infinity_jni_stub.cpp
 *
 * Compiled when the vendored llama.cpp sources are missing
 * (app/src/main/cpp/llama/include/llama.h). Every function returns a safe
 * "not available" value so the app builds and runs — AI features report an error
 * state instead of crashing.
 *
 * Restore the vendored llama/ directory and CMakeLists.txt switches to the real
 * infinity_jni.cpp automatically. Signatures must stay in step with LlamaJniBridge.kt.
 */

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "InfinityStub"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_gone_ai_ai_runtime_LlamaJniBridge_loadModel(
        JNIEnv*, jobject, jstring, jint, jint) {
    LOGI("STUB: loadModel called — llama.cpp sources are not vendored");
    return JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_gone_ai_ai_runtime_LlamaJniBridge_generate(
        JNIEnv* env, jobject, jstring, jint, jobject callback) {
    LOGI("STUB: generate called — llama.cpp sources are not vendored");
    jclass cb_class = env->GetObjectClass(callback);
    jmethodID on_error = env->GetMethodID(cb_class, "onError", "(Ljava/lang/String;)V");
    jstring msg = env->NewStringUTF("AI engine not set up: the llama.cpp sources are missing from this build.");
    env->CallVoidMethod(callback, on_error, msg);
    env->DeleteLocalRef(msg);
    env->DeleteLocalRef(cb_class);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_gone_ai_ai_runtime_LlamaJniBridge_stopGeneration(JNIEnv*, jobject, jlong) {}

JNIEXPORT void JNICALL
Java_com_gone_ai_ai_runtime_LlamaJniBridge_stopAllGenerations(JNIEnv*, jobject) {}

JNIEXPORT jint JNICALL
Java_com_gone_ai_ai_runtime_LlamaJniBridge_countTokens(JNIEnv*, jobject, jstring) {
    return -1;
}

JNIEXPORT void JNICALL
Java_com_gone_ai_ai_runtime_LlamaJniBridge_unloadModel(JNIEnv*, jobject) {}

JNIEXPORT jboolean JNICALL
Java_com_gone_ai_ai_runtime_LlamaJniBridge_isModelLoaded(JNIEnv*, jobject) {
    return JNI_FALSE;
}

} // extern "C"
