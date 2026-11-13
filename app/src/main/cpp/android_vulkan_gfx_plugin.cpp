#include <android/log.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

#define M64P_PLUGIN_PROTOTYPES 1
#include "m64p_plugin.h"
#include "n64-video-frame.h"

namespace {
constexpr char TAG[] = "N64AndroidVulkanGFX";
std::mutex g_mutex;
GFX_INFO g_info{};
bool g_started = false;
bool g_initialized = false;
bool g_romOpen = false;
uint64_t g_frameNumber = 0;
std::vector<uint8_t> g_rgba;

inline uint8_t rdramByte(uint32_t address) {
    // Mupen64Plus stores N64 big-endian bytes in its host-endian RDRAM layout.
    return g_info.RDRAM[(address ^ 3u) & 0x7FFFFFu];
}

bool presentViFramebuffer() {
    if (!g_initialized || !g_romOpen || g_info.RDRAM == nullptr ||
        g_info.VI_STATUS_REG == nullptr || g_info.VI_ORIGIN_REG == nullptr ||
        g_info.VI_WIDTH_REG == nullptr) return false;

    const uint32_t status = *g_info.VI_STATUS_REG;
    const uint32_t origin = *g_info.VI_ORIGIN_REG & 0x00FFFFFFu;
    const uint32_t stride = *g_info.VI_WIDTH_REG & 0x0FFFu;
    const uint32_t pixelType = status & 3u;
    const uint32_t bytesPerPixel = pixelType == 2u ? 2u : (pixelType == 3u ? 4u : 0u);
    if (bytesPerPixel == 0 || stride == 0 || stride > 1024u) return false;

    // VI scanout is a framebuffer path, not an RDP command-list renderer.
    // Keep the first implementation bounded while the software RDP is added.
    const uint32_t width = std::min(stride, 640u);
    const uint32_t height = 240u;
    const uint64_t sourceBytes = static_cast<uint64_t>(stride) * height * bytesPerPixel;
    if (sourceBytes == 0 || sourceBytes > 8u * 1024u * 1024u ||
        static_cast<uint64_t>(origin) + sourceBytes > 8u * 1024u * 1024u) return false;

    g_rgba.resize(static_cast<size_t>(width) * height * 4u);
    for (uint32_t y = 0; y < height; ++y) {
        for (uint32_t x = 0; x < width; ++x) {
            const uint32_t address = origin + (y * stride + x) * bytesPerPixel;
            uint8_t r = 0, g = 0, b = 0, a = 255;
            if (bytesPerPixel == 2u) {
                const uint16_t value = static_cast<uint16_t>(
                    (static_cast<uint16_t>(rdramByte(address)) << 8) |
                    rdramByte(address + 1u));
                r = static_cast<uint8_t>(((value >> 11) & 31u) * 255u / 31u);
                g = static_cast<uint8_t>(((value >> 6) & 31u) * 255u / 31u);
                b = static_cast<uint8_t>(((value >> 1) & 31u) * 255u / 31u);
                a = (value & 1u) ? 255u : 0u;
            } else {
                r = rdramByte(address);
                g = rdramByte(address + 1u);
                b = rdramByte(address + 2u);
                a = rdramByte(address + 3u);
            }
            const size_t out = (static_cast<size_t>(y) * width + x) * 4u;
            g_rgba[out] = r;
            g_rgba[out + 1u] = g;
            g_rgba[out + 2u] = b;
            g_rgba[out + 3u] = a;
        }
    }

    N64VideoFrame frame{};
    frame.pixels = g_rgba.data();
    frame.width = width;
    frame.height = height;
    frame.stride_bytes = width * 4u;
    frame.pixel_format = N64_VIDEO_PIXEL_FORMAT_RGBA8888;
    frame.frame_number = ++g_frameNumber;
    n64VulkanVideoFrameCallback(&frame, nullptr);
    return true;
}
} // namespace

extern "C" {
EXPORT m64p_error CALL PluginGetVersion(m64p_plugin_type* type, int* pluginVersion,
                                         int* apiVersion, const char** name, int* caps) {
    if (!type || !pluginVersion || !apiVersion || !name || !caps) return M64ERR_INPUT_ASSERT;
    *type = M64PLUGIN_GFX;
    *pluginVersion = 0x000100;
    *apiVersion = 0x020000;
    *name = "N64 Android Vulkan VI Framebuffer Plugin";
    *caps = 0;
    return M64ERR_SUCCESS;
}

EXPORT m64p_error CALL PluginStartup(m64p_dynlib_handle, void*,
                                      void (*)(void*, int, const char*)) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_started) return M64ERR_ALREADY_INIT;
    g_started = true;
    __android_log_print(ANDROID_LOG_INFO, TAG, "Vulkan GFX plugin started");
    return M64ERR_SUCCESS;
}

EXPORT m64p_error CALL PluginShutdown(void) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_romOpen = false;
    g_initialized = false;
    g_started = false;
    g_rgba.clear();
    std::memset(&g_info, 0, sizeof(g_info));
    return M64ERR_SUCCESS;
}

EXPORT void CALL ChangeWindow(void) {}
EXPORT int CALL InitiateGFX(GFX_INFO info) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_started || info.RDRAM == nullptr || info.VI_STATUS_REG == nullptr ||
        info.VI_ORIGIN_REG == nullptr || info.VI_WIDTH_REG == nullptr) return 0;
    g_info = info;
    g_initialized = true;
    __android_log_print(ANDROID_LOG_INFO, TAG, "GFX_INFO attached; Vulkan frame callback ready");
    return 1;
}
EXPORT void CALL MoveScreen(int, int) {}
EXPORT void CALL ProcessDList(void) {
    std::lock_guard<std::mutex> lock(g_mutex);
    // The RDP command processor is not implemented in this plugin yet.
    // Present the VI framebuffer when the Core exposes a completed CFB.
    presentViFramebuffer();
}
EXPORT void CALL ProcessRDPList(void) {
    std::lock_guard<std::mutex> lock(g_mutex);
    presentViFramebuffer();
}
EXPORT void CALL ShowCFB(void) {
    std::lock_guard<std::mutex> lock(g_mutex);
    presentViFramebuffer();
}
EXPORT void CALL UpdateScreen(void) {
    std::lock_guard<std::mutex> lock(g_mutex);
    presentViFramebuffer();
}
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
    if (info) std::memset(info, 0, sizeof(FrameBufferInfo) * 6u);
}
EXPORT int CALL RomOpen(void) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized) return 0;
    g_romOpen = true;
    g_frameNumber = 0;
    return 1;
}
EXPORT void CALL RomClosed(void) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_romOpen = false;
}
}
