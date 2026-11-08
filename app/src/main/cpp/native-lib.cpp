#include <jni.h>

extern "C"
JNIEXPORT jstring JNICALL
Java_com_jsr_n64android_MainActivity_nativeGetStatus(JNIEnv* env, jobject /* thiz */) {
    constexpr char kStatus[] =
        "Bridge JNI carregada com sucesso.\n"
        "Core Mupen64Plus ainda não foi integrado.\n"
        "Nenhuma ROM está sendo executada nesta etapa.";
    return env->NewStringUTF(kStatus);
}
