#include <jni.h>

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
    (void) level;
    (void) message;
}

void coreStateCallback(void* context, m64p_core_param param, int value) {
    (void) context;
    (void) param;
    (void) value;
}
#endif

jstring makeString(JNIEnv* env, const char* value) {
    return env->NewStringUTF(value);
}

} // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_com_jsr_n64android_MainActivity_nativeGetStatus(JNIEnv* env, jobject /* thiz */) {
    char details[384];
#ifdef N64_HAS_SDL2
    SDL_version version{};
    SDL_GetVersion(&version);
#ifdef N64_HAS_MUPEN64PLUS
    const bool coreApiAvailable =
        (&CoreStartup != nullptr) &&
        (&CoreDoCommand != nullptr) &&
        (&CoreShutdown != nullptr);
    std::snprintf(details, sizeof(details),
                  "Bridge JNI carregada.\\n"
                  "SDL2: %u.%u.%u\\n"
                  "Core Mupen64Plus linkado: sim\\n"
                  "API frontend exportada: %s\\n"
                  "Inicializacao do Core: aguardando comando\\n"
                  "Plugins e execucao de ROM ainda pendentes.",
                  version.major, version.minor, version.patch,
                  coreApiAvailable ? "sim" : "nao");
#else
    std::snprintf(details, sizeof(details),
                  "Bridge JNI carregada.\\nSDL2: %u.%u.%u\\n"
                  "Core Mupen64Plus nao esta linkado.",
                  version.major, version.minor, version.patch);
#endif
#else
    std::snprintf(details, sizeof(details),
                  "Bridge JNI carregada.\\nSDL2 ainda nao vinculada.");
#endif
    return makeString(env, details);
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
    const m64p_error result = CoreStartup(
        kFrontendApiVersion, config, data, nullptr,
        coreDebugCallback, nullptr, coreStateCallback);

    env->ReleaseStringUTFChars(configPath, config);
    env->ReleaseStringUTFChars(dataPath, data);

    if (result == M64ERR_SUCCESS) {
        g_coreStarted = true;
        return makeString(env,
            "CoreStartup concluido com sucesso.\\n"
            "Nenhuma ROM foi aberta. Plugins ainda precisam ser integrados.");
    }

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
