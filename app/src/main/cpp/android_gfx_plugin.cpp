#include <android/log.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <cstring>
#include "m64p_common.h"
#include "m64p_plugin.h"

#define LOG_TAG "N64AndroidGFX"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {
GFX_INFO g_gfxInfo{};
bool g_initialized = false;
bool g_romOpen = false;
unsigned long long g_displayLists = 0;
}

extern "C" {
EXPORT m64p_error CALL PluginGetVersion(m64p_plugin_type* type, int* pluginVersion,
                                         int* apiVersion, const char** name, int* caps) {
    if (!type || !pluginVersion || !apiVersion || !name || !caps) return M64ERR_INPUT_ASSERT;
    *type = M64PLUGIN_GFX;
    *pluginVersion = 0x000100;
    *apiVersion = 0x020000;
    *name = "N64 Android GLES Graphics Plugin (initial ABI)";
    *caps = 0;
    return M64ERR_SUCCESS;
}
EXPORT m64p_error CALL PluginStartup(m64p_dynlib_handle, void*, void (*)(void*, int, const char*)) {
    LOGI("PluginStartup");
    return M64ERR_SUCCESS;
}
EXPORT m64p_error CALL PluginShutdown(void) {
    g_initialized = false;
    g_romOpen = false;
    std::memset(&g_gfxInfo, 0, sizeof(g_gfxInfo));
    LOGI("PluginShutdown");
    return M64ERR_SUCCESS;
}
EXPORT void CALL ChangeWindow(void) {}
EXPORT int CALL InitiateGFX(GFX_INFO info) {
    if (eglGetCurrentContext() == EGL_NO_CONTEXT ||
        eglGetCurrentSurface(EGL_DRAW) == EGL_NO_SURFACE || glGetString(GL_VERSION) == nullptr) {
        LOGI("InitiateGFX recusado: contexto EGL/GLES ausente");
        return 0;
    }
    g_gfxInfo = info;
    g_initialized = true;
    LOGI("InitiateGFX: GFX_INFO recebido; GLES=%s", glGetString(GL_VERSION));
    return 1;
}
EXPORT void CALL MoveScreen(int, int) {}
EXPORT void CALL ProcessDList(void) {
    if (!g_initialized || !g_romOpen) return;
    ++g_displayLists;
    if ((g_displayLists % 1000ULL) == 1ULL)
        LOGI("ProcessDList recebido (%llu); interpretador RDP ainda em desenvolvimento", g_displayLists);
}
EXPORT void CALL ProcessRDPList(void) { if (!g_initialized || !g_romOpen) return; }
EXPORT void CALL ShowCFB(void) {}
EXPORT void CALL UpdateScreen(void) {}
EXPORT void CALL ViStatusChanged(void) {}
EXPORT void CALL ViWidthChanged(void) {}
EXPORT void CALL ReadScreen2(void* dest, int* width, int* height, int) {
    if (width) *width = 0;
    if (height) *height = 0;
    (void) dest;
}
EXPORT void CALL SetRenderingCallback(void (*)(int)) {}
EXPORT void CALL ResizeVideoOutput(int, int) {}
EXPORT void CALL FBRead(unsigned int) {}
EXPORT void CALL FBWrite(unsigned int, unsigned int) {}
EXPORT void CALL FBGetFrameBufferInfo(void* info) {
    if (info) std::memset(info, 0, sizeof(FrameBufferInfo) * 6);
}
EXPORT int CALL RomOpen(void) {
    if (!g_initialized) return 0;
    g_romOpen = true;
    g_displayLists = 0;
    LOGI("RomOpen");
    return 1;
}
EXPORT void CALL RomClosed(void) { g_romOpen = false; LOGI("RomClosed"); }
}
