#include <jni.h>
#include <android/log.h>

#include <cstdio>
#include <mutex>
#include <dlfcn.h>

namespace {
std::mutex g_coreMutex;
void* g_coreHandle = nullptr;
using CoreStartupSymbol = int (*)(int, const char*, const char*, void*,
                                  void (*)(void*, int, const char*), void*,
                                  void (*)(void*, int, int));
using CoreShutdownSymbol = int (*)();

jstring makeString(JNIEnv* env, const char* value) {
    return env->NewStringUTF(value);
}
} // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_com_jsr_n64android_MainActivity_nativeGetStatus(JNIEnv* env, jobject /* thiz */) {
    void* sdl = dlopen("libSDL2.so", RTLD_NOW | RTLD_LOCAL);
    if (sdl == nullptr) {
        const char* error = dlerror();
        char status[512];
        std::snprintf(status, sizeof(status), "JNI OK, mas dlopen(SDL2) falhou: %s",
                      error != nullptr ? error : "erro desconhecido");
        return makeString(env, status);
    }

    struct SDLVersion { unsigned char major, minor, patch; };
    using SDLGetVersionFn = void (*)(SDLVersion*);
    auto getVersion = reinterpret_cast<SDLGetVersionFn>(dlsym(sdl, "SDL_GetVersion"));
    if (getVersion == nullptr) {
        const char* error = dlerror();
        char status[512];
        std::snprintf(status, sizeof(status), "SDL2 abriu, mas SDL_GetVersion falhou: %s",
                      error != nullptr ? error : "simbolo ausente");
        dlclose(sdl);
        return makeString(env, status);
    }

    SDLVersion version{};
    getVersion(&version);
    char status[192];
    std::snprintf(status, sizeof(status),
                  "JNI OK. SDL2 carregada dinamicamente: %u.%u.%u.\\n"
                  "Core ainda sera testado separadamente.",
                  version.major, version.minor, version.patch);
    dlclose(sdl);
    return makeString(env, status);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_jsr_n64android_MainActivity_nativeInitialize(
        JNIEnv* env, jobject /* thiz */, jstring configPath, jstring dataPath) {
    (void) configPath;
    (void) dataPath;
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (g_coreHandle != nullptr) {
        return makeString(env, "Biblioteca do Core ja esta carregada.");
    }

    dlerror();
    void* core = dlopen("libmupen64plus.so", RTLD_NOW | RTLD_LOCAL);
    if (core == nullptr) {
        const char* error = dlerror();
        char status[768];
        std::snprintf(status, sizeof(status), "Falha ao carregar libmupen64plus.so: %s",
                      error != nullptr ? error : "erro desconhecido");
        __android_log_print(ANDROID_LOG_ERROR, "N64AndroidCore", "%s", status);
        return makeString(env, status);
    }

    dlerror();
    auto startup = reinterpret_cast<CoreStartupSymbol>(dlsym(core, "CoreStartup"));
    const char* startupError = dlerror();
    dlerror();
    auto shutdown = reinterpret_cast<CoreShutdownSymbol>(dlsym(core, "CoreShutdown"));
    const char* shutdownError = dlerror();
    if (startup == nullptr || shutdown == nullptr ||
        startupError != nullptr || shutdownError != nullptr) {
        char status[768];
        std::snprintf(status, sizeof(status),
                      "Core carregou, mas faltou simbolo: CoreStartup=%s; CoreShutdown=%s",
                      startupError != nullptr ? startupError : (startup ? "OK" : "ausente"),
                      shutdownError != nullptr ? shutdownError : (shutdown ? "OK" : "ausente"));
        dlclose(core);
        __android_log_print(ANDROID_LOG_ERROR, "N64AndroidCore", "%s", status);
        return makeString(env, status);
    }

    g_coreHandle = core;
    __android_log_print(ANDROID_LOG_INFO, "N64AndroidCore",
                        "Core Mupen64Plus carregado; CoreStartup e CoreShutdown encontrados.");
    return makeString(env,
        "Core Mupen64Plus carregado com sucesso.\\n"
        "CoreStartup e CoreShutdown encontrados.\\n"
        "A inicializacao do emulador ainda nao foi executada.");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_jsr_n64android_MainActivity_nativeShutdown(JNIEnv* env, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (g_coreHandle == nullptr) {
        return makeString(env, "Biblioteca do Core nao estava carregada.");
    }
    if (dlclose(g_coreHandle) != 0) {
        const char* error = dlerror();
        char status[512];
        std::snprintf(status, sizeof(status), "Falha ao descarregar Core: %s",
                      error != nullptr ? error : "erro desconhecido");
        return makeString(env, status);
    }
    g_coreHandle = nullptr;
    return makeString(env, "Biblioteca do Core descarregada corretamente.");
}
