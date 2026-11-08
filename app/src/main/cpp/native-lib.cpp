#include <jni.h>
#include <android/log.h>

#include <cstdio>
#include <mutex>
#include <dlfcn.h>
#include <cstdint>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <sys/syscall.h>
#include <unistd.h>
#ifdef N64_HAS_MUPEN64PLUS_HEADERS
#include "m64p_types.h"
#include "m64p_frontend.h"
#endif

namespace {
std::mutex g_coreMutex;
void* g_coreHandle = nullptr;
bool g_coreStarted = false;
using CoreStartupSymbol = int (*)(int, const char*, const char*, void*,
                                  void (*)(void*, int, const char*), void*,
                                  void (*)(void*, int, int));
using CoreShutdownSymbol = int (*)();
using CoreErrorMessageSymbol = const char* (*)(int);
using CoreGetAPIVersionsSymbol = int (*)(int*, int*, int*, int*);
using PluginGetVersionSymbol = int (*)(int*, int*, int*, const char**, int*);

void coreDebugCallback(void*, int level, const char* message) {
    __android_log_print(level <= 1 ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO,
                        "N64AndroidCore", "%s", message != nullptr ? message : "(sem mensagem)");
}

void coreStateCallback(void*, int param, int value) {
    __android_log_print(ANDROID_LOG_INFO, "N64AndroidCore",
                        "Core state callback: parametro=%d valor=%d", param, value);
}

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
Java_com_jsr_n64android_MainActivity_nativeStartCore(
        JNIEnv* env, jobject /* thiz */, jstring configPath, jstring dataPath) {
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (g_coreHandle == nullptr) return makeString(env, "Carregue e verifique o Core antes de inicializar.");
    if (g_coreStarted) return makeString(env, "Core ja esta inicializado.");

    dlerror();
    auto startup = reinterpret_cast<CoreStartupSymbol>(dlsym(g_coreHandle, "CoreStartup"));
    const char* lookupError = dlerror();
    if (startup == nullptr || lookupError != nullptr) return makeString(env, "CoreStartup nao encontrado.");

    const char* config = env->GetStringUTFChars(configPath, nullptr);
    if (config == nullptr) return makeString(env, "Falha ao obter caminho de configuracao.");
    const char* data = env->GetStringUTFChars(dataPath, nullptr);
    if (data == nullptr) {
        env->ReleaseStringUTFChars(configPath, config);
        return makeString(env, "Falha ao obter caminho de dados.");
    }

    // FRONTEND_API_VERSION da revisao fixada em version.h.
    const int result = startup(0x020106, config, data, nullptr,
                               coreDebugCallback, nullptr, coreStateCallback);
    env->ReleaseStringUTFChars(configPath, config);
    env->ReleaseStringUTFChars(dataPath, data);

    if (result != 0) {
        dlerror();
        auto errorMessage = reinterpret_cast<CoreErrorMessageSymbol>(dlsym(g_coreHandle, "CoreErrorMessage"));
        const char* error = errorMessage != nullptr ? errorMessage(result) : nullptr;
        char status[768];
        std::snprintf(status, sizeof(status),
                      "CoreStartup falhou: codigo %d (%s). Core continua apenas carregado.",
                      result, error != nullptr ? error : "descricao indisponivel");
        __android_log_print(ANDROID_LOG_ERROR, "N64AndroidCore", "%s", status);
        return makeString(env, status);
    }
    g_coreStarted = true;
    __android_log_print(ANDROID_LOG_INFO, "N64AndroidCore", "CoreStartup concluido sem ROM.");
    return makeString(env, "CoreStartup concluido com sucesso.\nCore inicializado, sem ROM carregada.\nNenhum jogo foi executado.");
}


#ifdef N64_HAS_MUPEN64PLUS_HEADERS
namespace {
m64p_error videoUnsupported() { return M64ERR_UNSUPPORTED; }
m64p_error videoUnsupportedMode(int, int, int, int, int) { return M64ERR_UNSUPPORTED; }
m64p_error videoUnsupportedModeRate(int, int, int, int, int, int) { return M64ERR_UNSUPPORTED; }
m64p_error videoUnsupportedListModes(m64p_2d_size*, int*) { return M64ERR_UNSUPPORTED; }
m64p_error videoUnsupportedListRates(m64p_2d_size, int*, int*) { return M64ERR_UNSUPPORTED; }
m64p_function videoNoProc(const char*) { return nullptr; }
m64p_error videoUnsupportedAttr(m64p_GLattr, int) { return M64ERR_UNSUPPORTED; }
m64p_error videoUnsupportedGetAttr(m64p_GLattr, int*) { return M64ERR_UNSUPPORTED; }
m64p_error videoUnsupportedCaption(const char*) { return M64ERR_UNSUPPORTED; }
m64p_error videoUnsupportedResize(int, int) { return M64ERR_UNSUPPORTED; }
uint32_t videoDefaultFramebuffer() { return 0; }
m64p_error videoUnsupportedRenderMode(m64p_render_mode) { return M64ERR_UNSUPPORTED; }
m64p_error videoUnsupportedVkSurface(void**, void*) { return M64ERR_UNSUPPORTED; }
m64p_error videoUnsupportedVkExtensions(const char**[], uint32_t*) { return M64ERR_UNSUPPORTED; }
}
#endif

extern "C"
JNIEXPORT jstring JNICALL
Java_com_jsr_n64android_MainActivity_nativeTestVideoOverrideAbi(JNIEnv* env, jobject /* thiz */) {
#ifndef N64_HAS_MUPEN64PLUS_HEADERS
    return makeString(env, "Headers oficiais do Core nao estao disponiveis nesta compilacao.");
#else
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (g_coreHandle == nullptr || !g_coreStarted)
        return makeString(env, "Inicialize o Core no botao 4 antes deste teste.");

    using CoreOverrideVidExtSymbol = m64p_error (*)(m64p_video_extension_functions*);
    auto overrideVideo = reinterpret_cast<CoreOverrideVidExtSymbol>(
        dlsym(g_coreHandle, "CoreOverrideVidExt"));
    if (overrideVideo == nullptr)
        return makeString(env, "CoreOverrideVidExt nao foi encontrado.");

    m64p_video_extension_functions probe{};
    probe.Functions = 17;
    probe.VidExtFuncInit = videoUnsupported;
    probe.VidExtFuncQuit = videoUnsupported;
    probe.VidExtFuncListModes = videoUnsupportedListModes;
    probe.VidExtFuncListRates = videoUnsupportedListRates;
    probe.VidExtFuncSetMode = videoUnsupportedMode;
    probe.VidExtFuncSetModeWithRate = videoUnsupportedModeRate;
    probe.VidExtFuncGLGetProc = videoNoProc;
    probe.VidExtFuncGLSetAttr = videoUnsupportedAttr;
    probe.VidExtFuncGLGetAttr = videoUnsupportedGetAttr;
    probe.VidExtFuncGLSwapBuf = videoUnsupported;
    probe.VidExtFuncSetCaption = videoUnsupportedCaption;
    probe.VidExtFuncToggleFS = videoUnsupported;
    probe.VidExtFuncResizeWindow = videoUnsupportedResize;
    probe.VidExtFuncGLGetDefaultFramebuffer = videoDefaultFramebuffer;
    probe.VidExtFuncInitWithRenderMode = videoUnsupportedRenderMode;
    probe.VidExtFuncVKGetSurface = videoUnsupportedVkSurface;
    probe.VidExtFuncVKGetInstanceExtensions = videoUnsupportedVkExtensions;

    const m64p_error registered = overrideVideo(&probe);
    if (registered != M64ERR_SUCCESS)
        return makeString(env, "CoreOverrideVidExt rejeitou a tabela de teste.");

    // Clear the probe immediately; no video callback remains active.
    m64p_video_extension_functions reset{};
    reset.Functions = 17;
    const m64p_error cleared = overrideVideo(&reset);
    if (cleared != M64ERR_SUCCESS)
        return makeString(env, "Tabela aceita, mas nao foi possivel desativar o teste.");

    return makeString(env,
        "ABI de video validada: 17 callbacks aceitos pelo Core.\n"
        "Tabela de teste desativada imediatamente.\n"
        "Nenhuma janela, plugin GFX ou ROM foi iniciada.");
#endif
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_jsr_n64android_MainActivity_nativeShutdown(JNIEnv* env, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (g_coreHandle == nullptr) {
        return makeString(env, "Biblioteca do Core nao estava carregada.");
    }
    if (g_coreStarted) {
        dlerror();
        auto shutdown = reinterpret_cast<CoreShutdownSymbol>(dlsym(g_coreHandle, "CoreShutdown"));
        const char* lookupError = dlerror();
        if (shutdown == nullptr || lookupError != nullptr)
            return makeString(env, "CoreShutdown nao encontrado; biblioteca mantida carregada.");
        const int result = shutdown();
        if (result != 0) {
            char status[256];
            std::snprintf(status, sizeof(status), "CoreShutdown falhou: codigo %d. Biblioteca mantida carregada.", result);
            return makeString(env, status);
        }
        g_coreStarted = false;
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


extern "C"
JNIEXPORT jstring JNICALL
Java_com_jsr_n64android_MainActivity_nativeVerifyEglContext(JNIEnv* env, jobject /* thiz */) {
    const EGLContext context = eglGetCurrentContext();
    const EGLDisplay display = eglGetCurrentDisplay();
    const EGLSurface surface = eglGetCurrentSurface(EGL_DRAW);
    const GLubyte* version = glGetString(GL_VERSION);

    if (context == EGL_NO_CONTEXT || display == EGL_NO_DISPLAY ||
        surface == EGL_NO_SURFACE || version == nullptr) {
        const EGLint error = eglGetError();
        char status[256];
        std::snprintf(status, sizeof(status),
                      "Contexto EGL/GLES nao esta atual nesta thread. EGL error=0x%04X",
                      static_cast<unsigned int>(error));
        return makeString(env, status);
    }

    char status[512];
    std::snprintf(status, sizeof(status),
                  "Contexto EGL valido na thread GL.\n"
                  "OpenGL ES: %s\n"
                  "Thread Linux: %ld\n"
                  "Contexto: 0x%llX | Surface: 0x%llX",
                  reinterpret_cast<const char*>(version),
                  static_cast<long>(syscall(SYS_gettid)),
                  static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(context)),
                  static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(surface)));
    __android_log_print(ANDROID_LOG_INFO, "N64AndroidEGL", "%s", status);
    return makeString(env, status);
}
