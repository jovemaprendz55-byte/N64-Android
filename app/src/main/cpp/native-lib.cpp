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
using CoreGetAPIVersionsSymbol = int (*)(int*, int*, int*, int*);
using PluginGetVersionSymbol = int (*)(int*, int*, int*, const char**, int*);

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

    dlerror();
    auto getApiVersions = reinterpret_cast<CoreGetAPIVersionsSymbol>(
        dlsym(core, "CoreGetAPIVersions"));
    const char* apiVersionsError = dlerror();
    dlerror();
    auto getCoreVersion = reinterpret_cast<PluginGetVersionSymbol>(
        dlsym(core, "PluginGetVersion"));
    const char* coreVersionError = dlerror();
    if (getApiVersions == nullptr || getCoreVersion == nullptr ||
        apiVersionsError != nullptr || coreVersionError != nullptr) {
        char status[768];
        std::snprintf(status, sizeof(status),
                      "Core carregou, mas faltou verificacao de API: CoreGetAPIVersions=%s; PluginGetVersion=%s",
                      apiVersionsError != nullptr ? apiVersionsError :
                          (getApiVersions ? "OK" : "ausente"),
                      coreVersionError != nullptr ? coreVersionError :
                          (getCoreVersion ? "OK" : "ausente"));
        dlclose(core);
        __android_log_print(ANDROID_LOG_ERROR, "N64AndroidCore", "%s", status);
        return makeString(env, status);
    }

    int pluginType = 0;
    int coreVersion = 0;
    int coreApiVersion = 0;
    int capabilities = 0;
    const char* coreName = nullptr;
    const int versionResult = getCoreVersion(
        &pluginType, &coreVersion, &coreApiVersion, &coreName, &capabilities);
    int configApi = 0;
    int debugApi = 0;
    int videoApi = 0;
    int extraApi = 0;
    const int apiResult = getApiVersions(&configApi, &debugApi, &videoApi, &extraApi);
    if (versionResult != 0 || apiResult != 0 || pluginType != 5 ||
        (coreApiVersion & 0xffff0000) != 0x00020000) {
        char status[768];
        std::snprintf(status, sizeof(status),
                      "API incompatível: PluginGetVersion=%d, CoreGetAPIVersions=%d, tipo=%d, frontend API=0x%06X",
                      versionResult, apiResult, pluginType, coreApiVersion);
        dlclose(core);
        __android_log_print(ANDROID_LOG_ERROR, "N64AndroidCore", "%s", status);
        return makeString(env, status);
    }

    g_coreHandle = core;
    char status[768];
    std::snprintf(status, sizeof(status),
                  "Core Mupen64Plus carregado com sucesso.\\n"
                  "CoreStartup e CoreShutdown encontrados.\\n"
                  "Versao do Core: %d.%d.%d | API Frontend: 0x%06X.\\n"
                  "APIs Config/Debug/Video: 0x%06X / 0x%06X / 0x%06X.\\n"
                  "Compatibilidade verificada; inicializacao ainda nao executada.",
                  (coreVersion >> 16) & 0xff, (coreVersion >> 8) & 0xff,
                  coreVersion & 0xff, coreApiVersion, configApi, debugApi, videoApi);
    __android_log_print(ANDROID_LOG_INFO, "N64AndroidCore", "%s", status);
    return makeString(env, status);
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
