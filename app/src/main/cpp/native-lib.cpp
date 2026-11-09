#include <jni.h>
#include <android/log.h>

#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <string>
#include <mutex>
#include <atomic>
#include <dlfcn.h>
#include <cstdint>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <sys/syscall.h>
#include <unistd.h>
#include "core_runtime_coordinator.h"
#include "core_run_loop_handshake.h"
#ifdef N64_HAS_MUPEN64PLUS_HEADERS
#include "m64p_types.h"
#include "m64p_frontend.h"
#endif

namespace {
std::mutex g_coreMutex;
CoreRuntimeCoordinator g_coreRuntime;
void* g_coreHandle = nullptr;
bool g_coreStarted = false;
using CoreStartupSymbol = int (*)(int, const char*, const char*, void*,
                                  void (*)(void*, int, const char*), void*,
                                  void (*)(void*, int, int));
using CoreShutdownSymbol = int (*)();
using CoreErrorMessageSymbol = const char* (*)(int);
using CoreGetAPIVersionsSymbol = int (*)(int*, int*, int*, int*);
using PluginGetVersionSymbol = int (*)(int*, int*, int*, const char**, int*);

CoreRunLoopHandshake g_runLoopHandshake;
constexpr char kRunLoopReadyMarker[] = "N64ANDROID_R4300_READY_20261109";

void coreDebugCallback(void*, int level, const char* message) {
    if (message != nullptr && std::strstr(message, kRunLoopReadyMarker) != nullptr) {
        g_runLoopHandshake.markReady();
        __android_log_print(ANDROID_LOG_INFO, "N64AndroidCore",
                            "Handshake: R4300 passou pelo reset da flag STOP; STOP agora pode ser considerado.");
        return;
    }
    __android_log_print(level <= 1 ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO,
                        "N64AndroidCore", "%s", message != nullptr ? message : "(sem mensagem)");
}

void coreStateCallback(void*, int param, int value) {
    __android_log_print(ANDROID_LOG_INFO, "N64AndroidCore",
                        "Core state callback: parametro=%d valor=%d", param, value);
}

#ifdef N64_HAS_MUPEN64PLUS_HEADERS
namespace {
int g_androidVideoWidth = 640;
int g_androidVideoHeight = 480;
int g_androidVideoAttributes[14] = {};

bool androidVideoContextReady() {
    return eglGetCurrentContext() != EGL_NO_CONTEXT &&
           eglGetCurrentDisplay() != EGL_NO_DISPLAY &&
           eglGetCurrentSurface(EGL_DRAW) != EGL_NO_SURFACE &&
           glGetString(GL_VERSION) != nullptr;
}
bool androidVideoGetGlesVersion(int* major, int* minor) {
    if (major == nullptr || minor == nullptr || !androidVideoContextReady()) return false;
    const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    const char* marker = version != nullptr ? std::strstr(version, "OpenGL ES ") : nullptr;
    if (marker == nullptr) return false;
    int parsedMajor = 0;
    int parsedMinor = 0;
    if (std::sscanf(marker + 10, "%d.%d", &parsedMajor, &parsedMinor) != 2) return false;
    *major = parsedMajor;
    *minor = parsedMinor;
    return true;
}
m64p_error androidVideoInit() {
    return androidVideoContextReady() ? M64ERR_SUCCESS : M64ERR_INVALID_STATE;
}
m64p_error androidVideoQuit() { return M64ERR_SUCCESS; }
m64p_error androidVideoListModes(m64p_2d_size* sizes, int* count) {
    if (count == nullptr) return M64ERR_INPUT_ASSERT;
    if (sizes == nullptr || *count < 1) { *count = 1; return M64ERR_SUCCESS; }
    sizes[0].uiWidth = static_cast<unsigned int>(g_androidVideoWidth);
    sizes[0].uiHeight = static_cast<unsigned int>(g_androidVideoHeight);
    *count = 1;
    return M64ERR_SUCCESS;
}
m64p_error androidVideoListRates(m64p_2d_size, int* rates, int* count) {
    if (count == nullptr) return M64ERR_INPUT_ASSERT;
    if (rates == nullptr || *count < 1) { *count = 1; return M64ERR_SUCCESS; }
    rates[0] = 60;
    *count = 1;
    return M64ERR_SUCCESS;
}
m64p_error androidVideoSetMode(int width, int height, int, int, int) {
    if (!androidVideoContextReady()) return M64ERR_INVALID_STATE;
    if (width <= 0 || height <= 0) return M64ERR_INPUT_INVALID;
    g_androidVideoWidth = width;
    g_androidVideoHeight = height;
    return M64ERR_SUCCESS;
}
m64p_error androidVideoSetModeRate(int width, int height, int bits, int mode, int flags, int) {
    return androidVideoSetMode(width, height, bits, mode, flags);
}
m64p_function androidVideoGetProc(const char* name) {
    if (name == nullptr || !androidVideoContextReady()) return nullptr;
    auto proc = eglGetProcAddress(name);
    if (proc != nullptr) return reinterpret_cast<m64p_function>(proc);
    return reinterpret_cast<m64p_function>(dlsym(RTLD_DEFAULT, name));
}
m64p_error androidVideoSetAttr(m64p_GLattr attr, int value) {
    const int index = static_cast<int>(attr);
    if (index < 1 || index >= 14) return M64ERR_INPUT_INVALID;
    if (!androidVideoContextReady()) return M64ERR_INVALID_STATE;

    if (attr == M64P_GL_CONTEXT_PROFILE_MASK &&
        value != M64P_GL_CONTEXT_PROFILE_ES) {
        return M64ERR_UNSUPPORTED;
    }

    if (attr == M64P_GL_CONTEXT_MAJOR_VERSION ||
        attr == M64P_GL_CONTEXT_MINOR_VERSION) {
        int actualMajor = 0;
        int actualMinor = 0;
        if (!androidVideoGetGlesVersion(&actualMajor, &actualMinor))
            return M64ERR_INVALID_STATE;
        const int requestedMajor = attr == M64P_GL_CONTEXT_MAJOR_VERSION
            ? value : g_androidVideoAttributes[M64P_GL_CONTEXT_MAJOR_VERSION];
        const int requestedMinor = attr == M64P_GL_CONTEXT_MINOR_VERSION
            ? value : g_androidVideoAttributes[M64P_GL_CONTEXT_MINOR_VERSION];
        if (requestedMajor < 2 || requestedMinor < 0 ||
            requestedMajor > actualMajor ||
            (requestedMajor == actualMajor && requestedMinor > actualMinor)) {
            return M64ERR_UNSUPPORTED;
        }
    }

    g_androidVideoAttributes[index] = value;
    return M64ERR_SUCCESS;
}
m64p_error androidVideoGetAttr(m64p_GLattr attr, int* value) {
    const int index = static_cast<int>(attr);
    if (value == nullptr) return M64ERR_INPUT_ASSERT;
    if (index < 1 || index >= 14) return M64ERR_INPUT_INVALID;
    if (attr == M64P_GL_CONTEXT_PROFILE_MASK) {
        *value = M64P_GL_CONTEXT_PROFILE_ES;
        return M64ERR_SUCCESS;
    }
    if (attr == M64P_GL_CONTEXT_MAJOR_VERSION ||
        attr == M64P_GL_CONTEXT_MINOR_VERSION) {
        int actualMajor = 0;
        int actualMinor = 0;
        if (androidVideoGetGlesVersion(&actualMajor, &actualMinor)) {
            *value = attr == M64P_GL_CONTEXT_MAJOR_VERSION ? actualMajor : actualMinor;
            return M64ERR_SUCCESS;
        }
    }
    *value = g_androidVideoAttributes[index];
    return M64ERR_SUCCESS;
}
std::atomic<uint64_t> g_androidVideoSwapCount{0};

m64p_error androidVideoSwapBuffers() {
    // M64CMD_EXECUTE occupies the GLSurfaceView GL thread. While the Core's
    // synchronous loop is active, GLSurfaceView cannot reach its normal
    // post-onDrawFrame swap, so the video extension presents completed frames.
    if (!androidVideoContextReady()) {
        __android_log_print(ANDROID_LOG_ERROR, "N64AndroidVideo",
                            "Swap de video recusado: contexto EGL ausente. TID=%ld",
                            static_cast<long>(syscall(SYS_gettid)));
        return M64ERR_INVALID_STATE;
    }

    const uint64_t frame = g_androidVideoSwapCount.fetch_add(1) + 1;
    if (frame == 1 || (frame % 120) == 0) {
        __android_log_print(ANDROID_LOG_INFO, "N64AndroidVideo",
                            "Callback de apresentacao #%llu na thread GL. Contexto=%p Surface=%p TID=%ld",
                            static_cast<unsigned long long>(frame),
                            reinterpret_cast<void*>(eglGetCurrentContext()),
                            reinterpret_cast<void*>(eglGetCurrentSurface(EGL_DRAW)),
                            static_cast<long>(syscall(SYS_gettid)));
    }

    const EGLDisplay display = eglGetCurrentDisplay();
    const EGLSurface surface = eglGetCurrentSurface(EGL_DRAW);
    if (eglSwapBuffers(display, surface) == EGL_TRUE) return M64ERR_SUCCESS;

    const EGLint error = eglGetError();
    __android_log_print(ANDROID_LOG_ERROR, "N64AndroidVideo",
                        "eglSwapBuffers falhou no callback #%llu: EGL error=0x%04X TID=%ld",
                        static_cast<unsigned long long>(frame),
                        static_cast<unsigned int>(error),
                        static_cast<long>(syscall(SYS_gettid)));
    return M64ERR_INVALID_STATE;
}
m64p_error androidVideoSetCaption(const char*) { return M64ERR_SUCCESS; }
m64p_error androidVideoToggleFullscreen() { return M64ERR_SUCCESS; }
m64p_error androidVideoResize(int width, int height) {
    if (width <= 0 || height <= 0) return M64ERR_INPUT_INVALID;
    g_androidVideoWidth = width;
    g_androidVideoHeight = height;
    return M64ERR_SUCCESS;
}
uint32_t androidVideoDefaultFramebuffer() {
    if (!androidVideoContextReady()) return 0;
    GLint framebuffer = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &framebuffer);
    return static_cast<uint32_t>(framebuffer);
}
m64p_error androidVideoInitRenderMode(m64p_render_mode mode) {
    return mode == M64P_RENDER_OPENGL && androidVideoContextReady()
        ? M64ERR_SUCCESS : M64ERR_UNSUPPORTED;
}
m64p_error androidVideoVkSurface(void**, void*) { return M64ERR_UNSUPPORTED; }
m64p_error androidVideoVkExtensions(const char**[], uint32_t*) { return M64ERR_UNSUPPORTED; }
}
#endif

void* g_gfxPluginHandle = nullptr;
bool g_gfxPluginStarted = false;
void* g_rspPluginHandle = nullptr;
bool g_rspPluginStarted = false;
bool g_rspAttached = false;
bool g_romOpen = false;
bool g_gfxAttached = false;
std::atomic<bool> g_coreExecuting{false};
std::atomic<bool> g_stopCommandIssued{false};
const char* const kRequiredGfxSymbols[] = {
    "PluginGetVersion", "PluginStartup", "PluginShutdown", "ChangeWindow",
    "InitiateGFX", "MoveScreen", "ProcessDList", "ProcessRDPList",
    "RomClosed", "RomOpen", "ShowCFB", "UpdateScreen", "ViStatusChanged",
    "ViWidthChanged", "ReadScreen2", "SetRenderingCallback", "FBRead",
    "FBWrite", "FBGetFrameBufferInfo"
};
const char* const kRequiredRspSymbols[] = {
    "PluginGetVersion", "PluginStartup", "PluginShutdown",
    "InitiateRSP", "DoRspCycles", "RomClosed"
};

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
    if (g_coreRuntime.phase() == CoreRuntimeCoordinator::Phase::ShutdownComplete)
        g_coreRuntime.resetAfterShutdown();
    auto runtimeLease = g_coreRuntime.tryAcquire(CoreRuntimeCoordinator::Operation::Other);
    if (!runtimeLease)
        return makeString(env, "Inicializacao recusada: ciclo do Core em encerramento.");
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
    auto runtimeLease = g_coreRuntime.tryAcquire(CoreRuntimeCoordinator::Operation::Other);
    if (!runtimeLease)
        return makeString(env, "CoreStartup recusado: ciclo do Core em encerramento.");
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (g_coreHandle == nullptr) return makeString(env, "Carregue e verifique o Core antes de inicializar.");
    if (g_coreStarted) return makeString(env, "Core ja esta inicializado.");

    dlerror();
    auto startup = reinterpret_cast<CoreStartupSymbol>(dlsym(g_coreHandle, "CoreStartup"));
    const char* lookupError = dlerror();
    if (startup == nullptr || lookupError != nullptr) return makeString(env, "CoreStartup nao encontrado.");

    const char* configChars = env->GetStringUTFChars(configPath, nullptr);
    if (configChars == nullptr) return makeString(env, "Falha ao obter caminho de configuracao.");
    const char* dataChars = env->GetStringUTFChars(dataPath, nullptr);
    if (dataChars == nullptr) {
        env->ReleaseStringUTFChars(configPath, configChars);
        return makeString(env, "Falha ao obter caminho de dados.");
    }

    // Android nao define HOME por padrao. O Core e o GLideN64 consultam
    // esse diretorio para localizar dados do usuario e configuracoes.
    const std::string configDirectory(configChars);
    const std::string dataDirectory(dataChars);
    std::string homeDirectory = configDirectory;
    const size_t configSeparator = homeDirectory.find_last_of('/');
    if (configSeparator != std::string::npos) {
        homeDirectory.resize(configSeparator);
        const size_t mupenSeparator = homeDirectory.find_last_of('/');
        if (mupenSeparator != std::string::npos)
            homeDirectory.resize(mupenSeparator);
    }
    const bool homeReady = !homeDirectory.empty() &&
        setenv("HOME", homeDirectory.c_str(), 1) == 0;
    const bool xdgReady =
        setenv("XDG_CONFIG_HOME", configDirectory.c_str(), 1) == 0 &&
        setenv("XDG_DATA_HOME", dataDirectory.c_str(), 1) == 0 &&
        setenv("XDG_CACHE_HOME", dataDirectory.c_str(), 1) == 0;
    __android_log_print(ANDROID_LOG_INFO, "N64AndroidCore",
        "Diretorios Android: HOME=%s | XDG_CONFIG_HOME=%s | XDG_DATA_HOME=%s",
        homeReady ? homeDirectory.c_str() : "(falha)",
        configDirectory.c_str(), dataDirectory.c_str());
    env->ReleaseStringUTFChars(configPath, configChars);
    env->ReleaseStringUTFChars(dataPath, dataChars);
    if (!homeReady || !xdgReady)
        return makeString(env, "Falha ao configurar HOME/XDG para o Core e o plugin grafico.");

    // FRONTEND_API_VERSION da revisao fixada em version.h.
    const int result = startup(0x020106, configDirectory.c_str(), dataDirectory.c_str(),
                               nullptr, coreDebugCallback, nullptr, coreStateCallback);

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
    if (!g_coreRuntime.markCoreInitialized()) {
        __android_log_print(ANDROID_LOG_ERROR, "N64AndroidCore",
                            "CoreStartup concluiu, mas a transicao de lifecycle foi recusada.");
        return makeString(env, "CoreStartup concluiu, mas o encerramento comecou simultaneamente. O teardown aguardara esta chamada.");
    }
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
    auto runtimeLease = g_coreRuntime.tryAcquire(CoreRuntimeCoordinator::Operation::Other);
    if (!runtimeLease)
        return makeString(env, "Teste de Video Extension recusado durante encerramento.");
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
Java_com_jsr_n64android_MainActivity_nativeRegisterAndroidVideo(JNIEnv* env, jobject /* thiz */) {
#ifndef N64_HAS_MUPEN64PLUS_HEADERS
    return makeString(env, "Headers oficiais do Core nao estao disponiveis nesta compilacao.");
#else
    __android_log_print(ANDROID_LOG_INFO, "N64AndroidFlow", "nativeRegisterAndroidVideo: entrada");
    auto runtimeLease = g_coreRuntime.tryAcquire(CoreRuntimeCoordinator::Operation::Other);
    if (!runtimeLease)
        return makeString(env, "Registro de video recusado durante encerramento.");
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (g_coreHandle == nullptr || !g_coreStarted)
        return makeString(env, "Inicialize o Core antes de registrar o video Android.");
    if (!androidVideoContextReady())
        return makeString(env, "Registro cancelado: contexto EGL/GLES nao esta atual na thread GL.");
    auto overrideVideo = reinterpret_cast<ptr_CoreOverrideVidExt>(
        dlsym(g_coreHandle, "CoreOverrideVidExt"));
    if (overrideVideo == nullptr)
        return makeString(env, "CoreOverrideVidExt nao encontrado no Core.");
    m64p_video_extension_functions video{};
    video.Functions = 17;
    video.VidExtFuncInit = androidVideoInit;
    video.VidExtFuncQuit = androidVideoQuit;
    video.VidExtFuncListModes = androidVideoListModes;
    video.VidExtFuncListRates = androidVideoListRates;
    video.VidExtFuncSetMode = androidVideoSetMode;
    video.VidExtFuncSetModeWithRate = androidVideoSetModeRate;
    video.VidExtFuncGLGetProc = androidVideoGetProc;
    video.VidExtFuncGLSetAttr = androidVideoSetAttr;
    video.VidExtFuncGLGetAttr = androidVideoGetAttr;
    video.VidExtFuncGLSwapBuf = androidVideoSwapBuffers;
    video.VidExtFuncSetCaption = androidVideoSetCaption;
    video.VidExtFuncToggleFS = androidVideoToggleFullscreen;
    video.VidExtFuncResizeWindow = androidVideoResize;
    video.VidExtFuncGLGetDefaultFramebuffer = androidVideoDefaultFramebuffer;
    video.VidExtFuncInitWithRenderMode = androidVideoInitRenderMode;
    video.VidExtFuncVKGetSurface = androidVideoVkSurface;
    video.VidExtFuncVKGetInstanceExtensions = androidVideoVkExtensions;
    const m64p_error result = overrideVideo(&video);
    if (result != M64ERR_SUCCESS) {
        char status[192];
        std::snprintf(status, sizeof(status),
                      "CoreOverrideVidExt falhou ao registrar video Android: codigo %d.",
                      static_cast<int>(result));
        return makeString(env, status);
    }
    __android_log_print(ANDROID_LOG_INFO, "N64AndroidFlow", "nativeRegisterAndroidVideo: CoreOverrideVidExt concluido");
    __android_log_print(ANDROID_LOG_INFO, "N64AndroidVideo",
                        "Video Extension Android registrada na thread GL.");
    return makeString(env,
        "Video Extension Android registrada no Core.\n"
        "Callbacks EGL/GLES ativos na thread GL.\n"
        "O callback de video apresenta os frames com eglSwapBuffers na thread GL.\n"
        "Plugin GFX e carregamento de ROM ainda nao foram iniciados.");
#endif
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_jsr_n64android_MainActivity_nativeLoadGfxPlugin(
        JNIEnv* env, jobject /* thiz */, jstring pluginPath) {
    __android_log_print(ANDROID_LOG_INFO, "N64AndroidFlow", "nativeLoadGfxPlugin: entrada");
    auto runtimeLease = g_coreRuntime.tryAcquire(CoreRuntimeCoordinator::Operation::Other);
    if (!runtimeLease)
        return makeString(env, "Carga GFX recusada durante encerramento.");
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (g_coreHandle == nullptr || !g_coreStarted)
        return makeString(env, "Inicialize o Core antes de carregar o plugin GFX.");
    if (g_gfxPluginHandle != nullptr)
        return makeString(env, "Plugin GFX Android ja esta carregado e validado.");

    const char* path = env->GetStringUTFChars(pluginPath, nullptr);
    if (path == nullptr) return makeString(env, "Nao foi possivel obter o caminho do plugin.");
    dlerror();
    void* plugin = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    env->ReleaseStringUTFChars(pluginPath, path);
    if (plugin == nullptr) {
        const char* error = dlerror();
        char status[768];
        std::snprintf(status, sizeof(status), "Falha ao carregar plugin GFX Android: %s",
                      error != nullptr ? error : "erro desconhecido");
        return makeString(env, status);
    }

    char missing[512] = {};
    size_t used = 0;
    for (const char* symbol : kRequiredGfxSymbols) {
        dlerror();
        void* address = dlsym(plugin, symbol);
        const char* error = dlerror();
        if (address == nullptr || error != nullptr) {
            const int written = std::snprintf(missing + used, sizeof(missing) - used,
                                              "%s%s", used == 0 ? "" : ", ", symbol);
            if (written > 0) used += static_cast<size_t>(written);
            if (used >= sizeof(missing)) break;
        }
    }
    if (used != 0) {
        char status[768];
        std::snprintf(status, sizeof(status), "Plugin carregado, mas faltam simbolos GFX: %s", missing);
        dlclose(plugin);
        return makeString(env, status);
    }

    using GfxPluginGetVersion = int (*)(int*, int*, int*, const char**, int*);
    auto getVersion = reinterpret_cast<GfxPluginGetVersion>(dlsym(plugin, "PluginGetVersion"));
    int type = 0, pluginVersion = 0, apiVersion = 0, caps = 0;
    const char* name = nullptr;
    __android_log_print(ANDROID_LOG_INFO, "N64AndroidFlow", "nativeLoadGfxPlugin: dlopen concluido; validando versao");
    const int result = getVersion(&type, &pluginVersion, &apiVersion, &name, &caps);
    __android_log_print(ANDROID_LOG_INFO, "N64AndroidFlow",
                        "nativeLoadGfxPlugin: PluginGetVersion retorno=%d tipo=%d API=0x%06X",
                        result, type, apiVersion);
    if (result != 0 || type != 2 || (apiVersion & 0xffff0000) != 0x00020000) {
        char status[768];
        std::snprintf(status, sizeof(status),
                      "Plugin GFX incompatível: retorno=%d tipo=%d API=0x%06X.",
                      result, type, apiVersion);
        dlclose(plugin);
        return makeString(env, status);
    }

    // PluginStartup is reached from GLSurfaceView.Renderer.onSurfaceCreated.
    // Refuse initialization unless this thread owns a live EGL context.
    const EGLContext startupContext = eglGetCurrentContext();
    const EGLDisplay startupDisplay = eglGetCurrentDisplay();
    const EGLSurface startupSurface = eglGetCurrentSurface(EGL_DRAW);
    const GLubyte* startupGlVersion = glGetString(GL_VERSION);
    if (startupContext == EGL_NO_CONTEXT || startupDisplay == EGL_NO_DISPLAY ||
        startupSurface == EGL_NO_SURFACE || startupGlVersion == nullptr) {
        dlclose(plugin);
        return makeString(env,
            "PluginStartup cancelado: contexto EGL/GLES nao esta atual nesta thread GL.");
    }
    int actualGlesMajor = 0;
    int actualGlesMinor = 0;
    if (!androidVideoGetGlesVersion(&actualGlesMajor, &actualGlesMinor) ||
        actualGlesMajor < 3) {
        char status[256];
        std::snprintf(status, sizeof(status),
                      "PluginStartup cancelado: GLideN64 requer OpenGL ES 3.0; contexto atual=%d.%d.",
                      actualGlesMajor, actualGlesMinor);
        dlclose(plugin);
        return makeString(env, status);
    }

    using GfxPluginStartup = int (*)(void*, void*, void (*)(void*, int, const char*));
    auto startup = reinterpret_cast<GfxPluginStartup>(dlsym(plugin, "PluginStartup"));
    if (startup == nullptr) {
        dlclose(plugin);
        return makeString(env, "PluginStartup nao encontrado apos validacao dos simbolos.");
    }
    __android_log_print(ANDROID_LOG_INFO, "N64AndroidFlow",
                        "nativeLoadGfxPlugin: chamando PluginStartup; EGLContext=%p GLES=%s",
                        reinterpret_cast<void*>(startupContext),
                        reinterpret_cast<const char*>(startupGlVersion));
    const int startupResult = startup(g_coreHandle, nullptr, coreDebugCallback);
    __android_log_print(ANDROID_LOG_INFO, "N64AndroidFlow",
                        "nativeLoadGfxPlugin: PluginStartup retornou codigo=%d", startupResult);
    if (startupResult != 0) {
        char startupStatus[256];
        std::snprintf(startupStatus, sizeof(startupStatus),
                      "PluginStartup falhou: codigo %d. Plugin nao foi mantido carregado.",
                      startupResult);
        dlclose(plugin);
        return makeString(env, startupStatus);
    }

    g_gfxPluginHandle = plugin;
    g_gfxPluginStarted = true;
    __android_log_print(ANDROID_LOG_INFO, "N64AndroidGFX",
                        "PluginStartup executado com EGL atual. GLES=%s TID=%ld EGLContext=%p",
                        reinterpret_cast<const char*>(startupGlVersion),
                        static_cast<long>(syscall(SYS_gettid)),
                        reinterpret_cast<void*>(startupContext));
    char status[768];
    std::snprintf(status, sizeof(status),
                  "Plugin GFX Android carregado e validado.\n"
                  "Nome: %s\nVersao: %d.%d.%d | API: 0x%06X\n"
                  "Simbolos obrigatorios: %zu/%zu.\n"
                  "PluginStartup executado. Ainda nao anexado ao Core; nenhuma ROM foi aberta.",
                  name != nullptr ? name : "(sem nome)",
                  (pluginVersion >> 16) & 0xff, (pluginVersion >> 8) & 0xff,
                  pluginVersion & 0xff, apiVersion,
                  sizeof(kRequiredGfxSymbols) / sizeof(kRequiredGfxSymbols[0]),
                  sizeof(kRequiredGfxSymbols) / sizeof(kRequiredGfxSymbols[0]));
    __android_log_print(ANDROID_LOG_INFO, "N64AndroidGFX", "%s", status);
    return makeString(env, status);
}


extern "C"
JNIEXPORT jstring JNICALL
Java_com_jsr_n64android_MainActivity_nativeLoadRspPlugin(
        JNIEnv* env, jobject /* thiz */, jstring pluginPath) {
#ifndef N64_HAS_MUPEN64PLUS_HEADERS
    return makeString(env, "Headers oficiais do Core nao estao disponiveis nesta compilacao.");
#else
    auto runtimeLease = g_coreRuntime.tryAcquire(CoreRuntimeCoordinator::Operation::Other);
    if (!runtimeLease)
        return makeString(env, "Carga RSP recusada durante encerramento.");
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (g_coreHandle == nullptr || !g_coreStarted)
        return makeString(env, "Inicialize o Core antes de carregar o plugin RSP.");
    if (g_rspPluginHandle != nullptr)
        return makeString(env, "Plugin RSP-HLE ja esta carregado e validado.");

    const char* path = env->GetStringUTFChars(pluginPath, nullptr);
    if (path == nullptr) return makeString(env, "Nao foi possivel obter o caminho do plugin RSP.");
    dlerror();
    void* plugin = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    env->ReleaseStringUTFChars(pluginPath, path);
    if (plugin == nullptr) {
        const char* error = dlerror();
        char status[768];
        std::snprintf(status, sizeof(status), "Falha ao carregar RSP-HLE: %s",
                      error != nullptr ? error : "erro desconhecido");
        return makeString(env, status);
    }

    char missing[512] = {};
    for (const char* symbol : kRequiredRspSymbols) {
        dlerror();
        void* address = dlsym(plugin, symbol);
        const char* error = dlerror();
        if (address == nullptr || error != nullptr) {
            if (missing[0] != '\0') std::strncat(missing, ", ", sizeof(missing) - std::strlen(missing) - 1);
            std::strncat(missing, symbol, sizeof(missing) - std::strlen(missing) - 1);
        }
    }
    if (missing[0] != '\0') {
        char status[768];
        std::snprintf(status, sizeof(status), "RSP-HLE sem simbolos obrigatorios: %s", missing);
        dlclose(plugin);
        return makeString(env, status);
    }

    auto getVersion = reinterpret_cast<PluginGetVersionSymbol>(dlsym(plugin, "PluginGetVersion"));
    int type = 0, pluginVersion = 0, apiVersion = 0, caps = 0;
    const char* name = nullptr;
    const int versionResult = getVersion(&type, &pluginVersion, &apiVersion, &name, &caps);
    if (versionResult != M64ERR_SUCCESS || type != M64PLUGIN_RSP ||
        (apiVersion & 0xffff0000) != 0x00020000) {
        char status[512];
        std::snprintf(status, sizeof(status),
                      "RSP-HLE incompatível: retorno=%d tipo=%d API=0x%06X.",
                      versionResult, type, apiVersion);
        dlclose(plugin);
        return makeString(env, status);
    }

    using RspPluginStartup = int (*)(void*, void*, void (*)(void*, int, const char*));
    auto startup = reinterpret_cast<RspPluginStartup>(dlsym(plugin, "PluginStartup"));
    const int startupResult = startup(g_coreHandle, nullptr, coreDebugCallback);
    if (startupResult != M64ERR_SUCCESS) {
        char status[320];
        std::snprintf(status, sizeof(status),
                      "PluginStartup do RSP-HLE falhou: codigo %d.", startupResult);
        dlclose(plugin);
        return makeString(env, status);
    }

    g_rspPluginHandle = plugin;
    g_rspPluginStarted = true;
    char status[768];
    std::snprintf(status, sizeof(status),
                  "Plugin RSP-HLE carregado e inicializado.\nNome: %s\n"
                  "Versao: %d.%d.%d | API: 0x%06X\n"
                  "Simbolos obrigatorios: %zu/%zu.\n"
                  "Ainda nao anexado ao Core; nenhuma tarefa RSP foi executada.",
                  name != nullptr ? name : "(sem nome)",
                  (pluginVersion >> 16) & 0xff, (pluginVersion >> 8) & 0xff,
                  pluginVersion & 0xff, apiVersion,
                  sizeof(kRequiredRspSymbols) / sizeof(kRequiredRspSymbols[0]),
                  sizeof(kRequiredRspSymbols) / sizeof(kRequiredRspSymbols[0]));
    __android_log_print(ANDROID_LOG_INFO, "N64AndroidRSP", "%s", status);
    return makeString(env, status);
#endif
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_jsr_n64android_MainActivity_nativeOpenRomAndAttachGfx(
        JNIEnv* env, jobject /* thiz */, jbyteArray romBytes) {
#ifndef N64_HAS_MUPEN64PLUS_HEADERS
    return makeString(env, "Headers oficiais do Core nao estao disponiveis nesta compilacao.");
#else
    auto runtimeLease = g_coreRuntime.tryAcquire(CoreRuntimeCoordinator::Operation::Other);
    if (!runtimeLease)
        return makeString(env, "Abertura/anexo da ROM recusados durante encerramento.");
    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (g_coreHandle == nullptr || !g_coreStarted)
        return makeString(env, "Inicialize o Core antes de abrir uma ROM.");
    if (g_gfxPluginHandle == nullptr || !g_gfxPluginStarted)
        return makeString(env, "Carregue e inicialize o plugin GFX antes de abrir a ROM.");
    if (g_rspPluginHandle == nullptr || !g_rspPluginStarted)
        return makeString(env, "Carregue e inicialize o plugin RSP-HLE antes de abrir a ROM.");
    if (!androidVideoContextReady())
        return makeString(env, "Operacao cancelada: contexto EGL/GLES nao esta atual na thread GL.");

    using CoreDoCommandSymbol = m64p_error (*)(m64p_command, int, void*);
    using CoreAttachPluginSymbol = m64p_error (*)(m64p_plugin_type, m64p_dynlib_handle);
    auto doCommand = reinterpret_cast<CoreDoCommandSymbol>(dlsym(g_coreHandle, "CoreDoCommand"));
    auto attachPlugin = reinterpret_cast<CoreAttachPluginSymbol>(dlsym(g_coreHandle, "CoreAttachPlugin"));
    if (doCommand == nullptr || attachPlugin == nullptr)
        return makeString(env, "CoreDoCommand ou CoreAttachPlugin nao encontrado.");

    if (!g_romOpen) {
        if (romBytes == nullptr) return makeString(env, "Nenhuma ROM foi fornecida.");
        const jsize length = env->GetArrayLength(romBytes);
        if (length < 4096 || length > 64 * 1024 * 1024 || (length % 4) != 0)
            return makeString(env, "Tamanho da ROM invalido para o Core.");
        jbyte* bytes = env->GetByteArrayElements(romBytes, nullptr);
        if (bytes == nullptr) return makeString(env, "Nao foi possivel acessar os bytes da ROM.");
        __android_log_print(ANDROID_LOG_INFO, "N64AndroidFlow", "Abrindo ROM no Core: %d bytes.", length);
        const m64p_error openResult = doCommand(M64CMD_ROM_OPEN, static_cast<int>(length),
                                                reinterpret_cast<void*>(bytes));
        env->ReleaseByteArrayElements(romBytes, bytes, JNI_ABORT);
        if (openResult != M64ERR_SUCCESS) {
            char status[256];
            std::snprintf(status, sizeof(status), "M64CMD_ROM_OPEN falhou: codigo %d.",
                          static_cast<int>(openResult));
            return makeString(env, status);
        }
        g_romOpen = true;
        if (!g_coreRuntime.markRomOpen()) {
            __android_log_print(ANDROID_LOG_WARN, "N64AndroidFlow",
                                "ROM abriu, mas lifecycle ja esta em encerramento; teardown fechara a ROM.");
            return makeString(env, "ROM abriu, mas o encerramento comecou simultaneamente. A ROM sera fechada pelo teardown.");
        }
        __android_log_print(ANDROID_LOG_INFO, "N64AndroidFlow", "M64CMD_ROM_OPEN concluido.");
    }

    if (!g_gfxAttached) {
        const m64p_error attachResult = attachPlugin(
            M64PLUGIN_GFX, reinterpret_cast<m64p_dynlib_handle>(g_gfxPluginHandle));
        if (attachResult != M64ERR_SUCCESS) {
            char status[320];
            std::snprintf(status, sizeof(status),
                "ROM aberta no Core, mas CoreAttachPlugin(GFX) falhou: codigo %d. A ROM permanece aberta para nova tentativa.",
                static_cast<int>(attachResult));
            return makeString(env, status);
        }
        g_gfxAttached = true;
        __android_log_print(ANDROID_LOG_INFO, "N64AndroidFlow",
                            "CoreAttachPlugin(GFX) retornou M64ERR_SUCCESS; InitiateGFX concluido pelo Core.");
    }
    if (!g_rspAttached) {
        const m64p_error rspAttachResult = attachPlugin(
            M64PLUGIN_RSP, reinterpret_cast<m64p_dynlib_handle>(g_rspPluginHandle));
        if (rspAttachResult != M64ERR_SUCCESS) {
            char status[384];
            std::snprintf(status, sizeof(status),
                "ROM aberta e GFX anexado, mas CoreAttachPlugin(RSP) falhou: codigo %d. RSP permanece carregado para nova tentativa.",
                static_cast<int>(rspAttachResult));
            return makeString(env, status);
        }
        g_rspAttached = true;
        __android_log_print(ANDROID_LOG_INFO, "N64AndroidRSP",
                            "CoreAttachPlugin(RSP) retornou M64ERR_SUCCESS; InitiateRSP concluido pelo Core.");
    }
    if (g_coreRuntime.phase() == CoreRuntimeCoordinator::Phase::RomOpen &&
        !g_coreRuntime.markPluginsAttached()) {
        return makeString(env, "Plugins foram anexados, mas o encerramento comecou simultaneamente. O teardown sera concluido com seguranca.");
    }
    return makeString(env,
        "ROM aberta com sucesso no Mupen64Plus Core.\\n"
        "Plugin grafico GLideN64 anexado ao Core.\\n"
        "Plugin RSP-HLE anexado ao Core.\\n"
        "InitiateGFX e InitiateRSP concluidos pelo Core.\\n"
        "CoreAttachPlugin executado na thread GL com contexto EGL atual.\\n"
        "A emulacao ainda NAO foi iniciada.");
#endif
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_jsr_n64android_MainActivity_nativeExecuteCore(JNIEnv* env, jobject /* thiz */) {
#ifndef N64_HAS_MUPEN64PLUS_HEADERS
    return makeString(env, "Headers oficiais do Core nao estao disponiveis nesta compilacao.");
#else
    // SAFETY GATE — do not remove until all of these are implemented and
    // device-tested together:
    // 1) acquire the unique Execute lease before touching Core/plugin handles;
    // 2) markExecuting(), then set g_coreExecuting and clear stop-issued state;
    // 3) call synchronous M64CMD_EXECUTE on the GL thread with current EGL;
    // 4) on every return path clear g_coreExecuting, markExecuteReturned(),
    //    and release the Execute lease only after Core callbacks have stopped;
    // 5) prove STOP can run concurrently without g_coreMutex and shutdown
    //    drains before detach/dlclose. The previous device attempt closed the
    //    process, so the gate remains until the complete path is validated.
    __android_log_print(ANDROID_LOG_WARN, "N64AndroidFlow",
                        "M64CMD_EXECUTE temporariamente bloqueado: diagnostico da falha nativa pendente.");
    return makeString(env,
        "Execucao temporariamente bloqueada por seguranca.\\n"
        "ROM, GLideN64 e RSP-HLE foram inicializados/anexados, mas a execucao ainda nao foi validada.\\n"
        "Nenhuma tarefa RSP, lista grafica ou frame de jogo foi confirmado.\\n"
        "A protecao permanece ativa enquanto auditamos o ciclo de execucao e parada.");
#endif
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_jsr_n64android_MainActivity_nativeStopCore(JNIEnv* env, jobject /* thiz */) {
#ifndef N64_HAS_MUPEN64PLUS_HEADERS
    return makeString(env, "Headers oficiais do Core nao estao disponiveis nesta compilacao.");
#else
    auto stopLease = g_coreRuntime.tryAcquire(CoreRuntimeCoordinator::Operation::Stop);
    if (!stopLease)
        return makeString(env, "Parada recusada: nao existe Execute ativo ou o ciclo ja foi encerrado.");
    if (!g_coreExecuting.load())
        return makeString(env, "Nenhuma execucao do Core esta ativa.");
    using CoreDoCommandSymbol = m64p_error (*)(m64p_command, int, void*);
    CoreDoCommandSymbol doCommand = nullptr;
    {
        std::lock_guard<std::mutex> handleLock(g_coreMutex);
        if (g_coreHandle == nullptr || !g_coreStarted)
            return makeString(env, "Core nao esta inicializado.");
        doCommand = reinterpret_cast<CoreDoCommandSymbol>(
            dlsym(g_coreHandle, "CoreDoCommand"));
    }
    if (doCommand == nullptr)
        return makeString(env, "CoreDoCommand nao encontrado para solicitar parada.");
    // Serialize user STOP with the shutdown STOP; duplicate async commands
    // are unnecessary and can race the Core's stop callback.
    if (g_stopCommandIssued.exchange(true))
        return makeString(env, "Pedido M64CMD_STOP ja foi enviado; aguardando Execute retornar.");
    const m64p_error result = doCommand(M64CMD_STOP, 0, nullptr);
    if (result != M64ERR_SUCCESS) {
        g_stopCommandIssued.store(false);
        char status[256];
        std::snprintf(status, sizeof(status), "M64CMD_STOP falhou: codigo %d.",
                      static_cast<int>(result));
        return makeString(env, status);
    }
    return makeString(env, "Pedido de parada enviado ao Core. Aguardando M64CMD_EXECUTE retornar na thread GL.");
#endif
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_jsr_n64android_MainActivity_nativeShutdown(JNIEnv* env, jobject /* thiz */) {
    using RuntimePhase = CoreRuntimeCoordinator::Phase;
    using RuntimeOperation = CoreRuntimeCoordinator::Operation;

    RuntimePhase phase = g_coreRuntime.phase();
    if (phase == RuntimePhase::ShutdownComplete) {
        std::lock_guard<std::mutex> handleLock(g_coreMutex);
        return makeString(env, g_coreHandle == nullptr
            ? "Core ja foi encerrado e descarregado."
            : "Estado inconsistente: ShutdownComplete com handle ainda carregado.");
    }
    if (phase != RuntimePhase::Drained && phase != RuntimePhase::StopRequested) {
        if (!g_coreRuntime.beginShutdown())
            return makeString(env, "Encerramento recusado: transicao de shutdown nao foi aceita.");
    }

    if (g_coreRuntime.requiresStopCommand()) {
        auto stopLease = g_coreRuntime.tryAcquire(RuntimeOperation::Stop);
        if (!stopLease)
            return makeString(env, "STOP recusado: nao foi possivel proteger a vida util do Core.");
        if (!g_stopCommandIssued.exchange(true)) {
            using CoreDoCommandSymbol = m64p_error (*)(m64p_command, int, void*);
            CoreDoCommandSymbol doCommand = nullptr;
            {
                std::lock_guard<std::mutex> handleLock(g_coreMutex);
                if (g_coreHandle != nullptr)
                    doCommand = reinterpret_cast<CoreDoCommandSymbol>(
                        dlsym(g_coreHandle, "CoreDoCommand"));
            }
            if (doCommand == nullptr) {
                g_stopCommandIssued.store(false);
                return makeString(env, "CoreDoCommand nao encontrado; handles preservados.");
            }
            const m64p_error stopResult = doCommand(M64CMD_STOP, 0, nullptr);
            if (stopResult != M64ERR_SUCCESS) {
                g_stopCommandIssued.store(false);
                char status[256];
                std::snprintf(status, sizeof(status), "M64CMD_STOP falhou: codigo %d. Handles preservados.",
                              static_cast<int>(stopResult));
                return makeString(env, status);
            }
        }
        stopLease.release();
    }

    if (!g_coreRuntime.waitForDrainFor(std::chrono::milliseconds(1500)))
        return makeString(env, "Encerramento aguardando chamadas do Core. Timeout: todas as bibliotecas e handles foram preservados.");

    std::lock_guard<std::mutex> lock(g_coreMutex);
    if (g_coreExecuting.load())
        return makeString(env, "Encerramento recusado: flag de execucao ainda ativa apesar do drain. Handles preservados.");
    if (g_coreHandle == nullptr) {
        g_coreRuntime.markShutdownComplete();
        return makeString(env, "Biblioteca do Core nao estava carregada; lifecycle encerrado.");
    }
    // GLideN64 shutdown/detach may release GLES resources. Never run those
    // callbacks from a thread without the EGL context current. Keep every
    // library loaded so the frontend can retry later from the GL thread.
    if (g_gfxPluginStarted && !androidVideoContextReady()) {
        __android_log_print(ANDROID_LOG_ERROR, "N64AndroidGFX",
                            "Shutdown recusado: EGL context nao esta atual nesta thread. TID=%ld",
                            static_cast<long>(syscall(SYS_gettid)));
        return makeString(env,
            "Encerramento grafico recusado por seguranca.\n"
            "O contexto EGL nao esta atual nesta thread.\n"
            "Core e plugin foram mantidos carregados; tente encerrar pela thread GL.");
    }
    using CoreDetachPluginSymbol = m64p_error (*)(m64p_plugin_type);
    auto detachPlugin = reinterpret_cast<CoreDetachPluginSymbol>(dlsym(g_coreHandle, "CoreDetachPlugin"));
    if ((g_rspAttached || g_gfxAttached) && detachPlugin == nullptr)
        return makeString(env, "CoreDetachPlugin nao encontrado; bibliotecas mantidas carregadas.");
    if (g_rspAttached) {
        const m64p_error result = detachPlugin(M64PLUGIN_RSP);
        if (result != M64ERR_SUCCESS) {
            char status[256];
            std::snprintf(status, sizeof(status), "CoreDetachPlugin(RSP) falhou: codigo %d.", static_cast<int>(result));
            return makeString(env, status);
        }
        g_rspAttached = false;
    }
    if (g_gfxAttached) {
        const m64p_error result = detachPlugin(M64PLUGIN_GFX);
        if (result != M64ERR_SUCCESS) {
            char status[256];
            std::snprintf(status, sizeof(status), "CoreDetachPlugin(GFX) falhou: codigo %d.", static_cast<int>(result));
            return makeString(env, status);
        }
        g_gfxAttached = false;
    }
    if (g_romOpen) {
        using CoreDoCommandSymbol = m64p_error (*)(m64p_command, int, void*);
        auto doCommand = reinterpret_cast<CoreDoCommandSymbol>(dlsym(g_coreHandle, "CoreDoCommand"));
        if (doCommand == nullptr) return makeString(env, "CoreDoCommand nao encontrado para fechar ROM.");
        const m64p_error result = doCommand(M64CMD_ROM_CLOSE, 0, nullptr);
        if (result != M64ERR_SUCCESS) {
            char status[256]; std::snprintf(status, sizeof(status), "M64CMD_ROM_CLOSE falhou: codigo %d.", static_cast<int>(result));
            return makeString(env, status);
        }
        g_romOpen = false;
    }
    if (g_rspPluginHandle != nullptr) {
        if (g_rspPluginStarted) {
            using RspPluginShutdown = int (*)();
            auto pluginShutdown = reinterpret_cast<RspPluginShutdown>(
                dlsym(g_rspPluginHandle, "PluginShutdown"));
            if (pluginShutdown == nullptr)
                return makeString(env, "PluginShutdown do RSP-HLE nao encontrado; bibliotecas mantidas carregadas.");
            const int pluginResult = pluginShutdown();
            if (pluginResult != M64ERR_SUCCESS) {
                char status[256];
                std::snprintf(status, sizeof(status),
                              "PluginShutdown do RSP-HLE falhou: codigo %d. Bibliotecas mantidas carregadas.",
                              pluginResult);
                return makeString(env, status);
            }
            g_rspPluginStarted = false;
        }
        dlclose(g_rspPluginHandle);
        g_rspPluginHandle = nullptr;
    }
    if (g_gfxPluginHandle != nullptr) {
        if (g_gfxPluginStarted) {
            using GfxPluginShutdown = int (*)();
            auto pluginShutdown = reinterpret_cast<GfxPluginShutdown>(
                dlsym(g_gfxPluginHandle, "PluginShutdown"));
            if (pluginShutdown == nullptr)
                return makeString(env, "PluginShutdown nao encontrado; bibliotecas mantidas carregadas.");
            const int pluginResult = pluginShutdown();
            if (pluginResult != 0) {
                char status[256];
                std::snprintf(status, sizeof(status),
                              "PluginShutdown falhou: codigo %d. Bibliotecas mantidas carregadas.",
                              pluginResult);
                return makeString(env, status);
            }
            g_gfxPluginStarted = false;
        }
        dlclose(g_gfxPluginHandle);
        g_gfxPluginHandle = nullptr;
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
    g_stopCommandIssued.store(false);
    if (!g_coreRuntime.markShutdownComplete())
        return makeString(env, "Core descarregado, mas estado do coordenador nao confirmou ShutdownComplete.");
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



extern "C"
JNIEXPORT jstring JNICALL
Java_com_jsr_n64android_MainActivity_nativeTestEglSwap(JNIEnv* env, jobject /* thiz */) {
    const EGLContext context = eglGetCurrentContext();
    const EGLDisplay display = eglGetCurrentDisplay();
    const EGLSurface surface = eglGetCurrentSurface(EGL_DRAW);
    if (context == EGL_NO_CONTEXT || display == EGL_NO_DISPLAY ||
        surface == EGL_NO_SURFACE || glGetString(GL_VERSION) == nullptr) {
        const EGLint error = eglGetError();
        char status[256];
        std::snprintf(status, sizeof(status),
                      "Teste de swap cancelado: EGL nao esta atual na thread GL. Erro=0x%04X",
                      static_cast<unsigned int>(error));
        return makeString(env, status);
    }

    // GLSurfaceView owns the EGL buffer swap at the end of its render callback.
    // Calling eglSwapBuffers manually here can conflict with that lifecycle.
    glFlush();
    const GLenum glError = glGetError();
    const EGLint eglError = eglGetError();
    char status[512];
    std::snprintf(status, sizeof(status),
                  "Contexto EGL validado; nenhuma troca manual de buffers foi feita.\n"
                  "EGL error: 0x%04X | GLES error: 0x%04X | Thread Linux: %ld.\n"
                  "O GLSurfaceView continua responsavel por apresentar o frame.\n"
                  "Este teste nao inicia a emulacao nem altera a ROM.",
                  static_cast<unsigned int>(eglError),
                  static_cast<unsigned int>(glError),
                  static_cast<long>(syscall(SYS_gettid)));
    __android_log_print(ANDROID_LOG_INFO, "N64AndroidEGL", "%s", status);
    return makeString(env, status);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_jsr_n64android_MainActivity_nativeTestGlOperations(JNIEnv* env, jobject /* thiz */) {
    const EGLContext context = eglGetCurrentContext();
    const EGLDisplay display = eglGetCurrentDisplay();
    const EGLSurface surface = eglGetCurrentSurface(EGL_DRAW);
    const GLubyte* version = glGetString(GL_VERSION);
    if (context == EGL_NO_CONTEXT || display == EGL_NO_DISPLAY ||
        surface == EGL_NO_SURFACE || version == nullptr) {
        const EGLint error = eglGetError();
        char status[256];
        std::snprintf(status, sizeof(status),
                      "Operacoes GLES canceladas: contexto EGL ausente. EGL error=0x%04X",
                      static_cast<unsigned int>(error));
        return makeString(env, status);
    }

    GLint viewport[4] = {0, 0, 0, 0};
    glGetIntegerv(GL_VIEWPORT, viewport);
    const GLenum beforeError = glGetError();

    // Exercise real GLES calls only on the current GLSurfaceView render thread.
    glClearColor(0.08f, 0.18f, 0.32f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glFlush();
    const GLenum afterError = glGetError();

    char status[768];
    std::snprintf(status, sizeof(status),
                  "Operacoes GLES executadas pela JNI.\\n"
                  "OpenGL ES: %s\\n"
                  "Viewport: %d x %d (origem %d,%d)\\n"
                  "Erro GLES antes: 0x%04X | depois: 0x%04X\\n"
                  "Thread Linux: %ld\\n"
                  "Contexto EGL valido; nenhuma ROM/plugin foi iniciada.",
                  reinterpret_cast<const char*>(version),
                  viewport[2], viewport[3], viewport[0], viewport[1],
                  static_cast<unsigned int>(beforeError),
                  static_cast<unsigned int>(afterError),
                  static_cast<long>(syscall(SYS_gettid)));
    __android_log_print(ANDROID_LOG_INFO, "N64AndroidGLES", "%s", status);
    return makeString(env, status);
}
