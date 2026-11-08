#include <jni.h>
#include <android/log.h>

#include <cstdio>
#include <mutex>

#ifdef N64_HAS_SDL2
#include <SDL.h>
#endif

#ifdef N64_HAS_MUPEN64PLUS
#define M64P_CORE_PROTOTYPES
#include "m64p_frontend.h"
#endif

namespace {

#ifdef N64_HAS_MUPEN64PLUS
constexpr int kFrontendApiVersion = 0x020000;
std::mutex g_coreMutex;
bool g_coreStarted = false;

void coreDebugCallback(void* context, int level, const char* message) {
    (void) context;
    const int priority = level <= 1 ? ANDROID_LOG_ERROR :
                         level == 2 ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    __android_log_print(priority, "N64AndroidCore", "%s",
                        message != nullptr ? message : "(mensagem vazia)");
}

void coreStateCallback(void* context, m64p_core_param param, int value) {
    (void) context;
    __android_log_print(ANDROID_LOG_INFO, "N64AndroidCore",
                        "Core state: parametro=%d valor=%d",
                        static_cast<int>(param), value);
}
#endif

jstring makeString(JNIEnv* env, const char* value) {
    return env->NewStringUTF(value);
}

} // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_com_jsr_n64android_MainActivity_nativeGetStatus(JNIEnv* env, jobject /* thiz */) {
    // Diagnostic isolation: avoid calling SDL or Core symbols here.
    // This confirms JNI entry-point execution independently from native dependencies.
    return makeString(env,
        "JNI bridge carregada e chamada com sucesso.\n"
        "Teste isolado: SDL2 e Core ainda nao foram consultados.");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_jsr_n64android_MainActivity_nativeInitialize(
        JNIEnv* env, jobject /* thiz */, jstring configPath, jstring dataPath) {
#ifdef N64_HAS_MUPEN64PLUS
    if (configPath == nullptr || dataPath == nullptr) {
        return makeString(env, "Falha: diretorios de configuracao/dados ausentes.");
    }

    const char* config = env->GetStringUTFChars(configPath, nullptr);
    const char* data = env->GetStringUTFChars(dataPath, nullptr);
    if (config == nullptr || data == nullptr) {
        if (config != nullptr) env->ReleaseStringUTFChars(configPath, config);
        if (data != nullptr) env->ReleaseStringUTFChars(dataPath, data);
        return makeString(env, "Falha: nao foi possivel ler os caminhos Android.");
    }

    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (g_coreStarted) {
        env->ReleaseStringUTFChars(configPath, config);
        env->ReleaseStringUTFChars(dataPath, data);
        return makeString(env, "Core ja esta inicializado.");
    }

    // CoreStartup's fourth parameter is the frontend context pointer,
    // not a core-name string. No frontend context is needed yet.
    __android_log_print(ANDROID_LOG_INFO, "N64AndroidCore", "Iniciando CoreStartup");
    const m64p_error result = CoreStartup(
        kFrontendApiVersion, config, data, nullptr,
        coreDebugCallback, nullptr, coreStateCallback);

    env->ReleaseStringUTFChars(configPath, config);
    env->ReleaseStringUTFChars(dataPath, data);

    if (result == M64ERR_SUCCESS) {
        g_coreStarted = true;
        __android_log_print(ANDROID_LOG_INFO, "N64AndroidCore", "CoreStartup concluido: sucesso");
        return makeString(env,
            "CoreStartup concluido com sucesso.\n"
            "Nenhuma ROM foi aberta. Plugins ainda precisam ser integrados.");
    }

    __android_log_print(ANDROID_LOG_ERROR, "N64AndroidCore",
                        "CoreStartup falhou: codigo=%d", static_cast<int>(result));
    char error[128];
    std::snprintf(error, sizeof(error),
                  "CoreStartup falhou. Codigo Mupen64Plus: %d", (int) result);
    return makeString(env, error);
#else
    (void) configPath;
    (void) dataPath;
    return makeString(env, "Core Mupen64Plus nao esta disponivel neste build.");
#endif
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_jsr_n64android_MainActivity_nativeShutdown(JNIEnv* env, jobject /* thiz */) {
#ifdef N64_HAS_MUPEN64PLUS
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (!g_coreStarted) {
        return makeString(env, "Core nao estava inicializado.");
    }

    __android_log_print(ANDROID_LOG_INFO, "N64AndroidCore", "Iniciando CoreShutdown");
    const m64p_error result = CoreShutdown();
    if (result == M64ERR_SUCCESS) {
        g_coreStarted = false;
        return makeString(env, "Core encerrado corretamente.");
    }

    char error[128];
    std::snprintf(error, sizeof(error),
                  "CoreShutdown retornou codigo: %d", (int) result);
    return makeString(env, error);
#else
    return makeString(env, "Core Mupen64Plus nao esta disponivel neste build.");
#endif
}
