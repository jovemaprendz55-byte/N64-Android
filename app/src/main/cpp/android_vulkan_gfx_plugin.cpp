#define VK_NO_PROTOTYPES 1
#include "vulkan_headers.hpp"

#include <android/log.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

#define M64P_PLUGIN_PROTOTYPES 1
#include "m64p_plugin.h"
#include "n64-video-frame.h"
#include "n64-vulkan-context.h"
#include "context.hpp"
#include "device.hpp"
#include "rdp_device.hpp"
#include <memory>

namespace {
constexpr char TAG[] = "N64AndroidVulkanGFX";
std::mutex g_mutex;
GFX_INFO g_info{};
bool g_started = false;
bool g_initialized = false;
bool g_romOpen = false;
uint64_t g_frameNumber = 0;
uint64_t g_processDListCalls = 0;
uint64_t g_processRdpCalls = 0;
uint64_t g_viAttempts = 0;
uint64_t g_viRejected = 0;
std::vector<uint8_t> g_rgba;
Vulkan::ContextHandle g_rdpContext;
Vulkan::DeviceHandle g_rdpDevice;
std::unique_ptr<RDP::CommandProcessor> g_rdp;

inline uint8_t rdramByte(uint32_t address) {
    // Mupen64Plus stores N64 big-endian bytes in its host-endian RDRAM layout.
    return g_info.RDRAM[(address ^ 3u) & 0x7FFFFFu];
}

void shutdownRdpBackend() {
    if (g_rdp) {
        g_rdp->idle();
        g_rdp.reset();
    }
    if (g_rdpDevice) {
        g_rdpDevice->wait_idle();
        g_rdpDevice = {};
    }
    g_rdpContext = {};
}

bool initializeRdpBackend() {
    if (g_rdp && g_rdp->device_is_supported()) return true;
    shutdownRdpBackend();

    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice gpu = VK_NULL_HANDLE;
    if (!n64VulkanGetRdpContext(&instance, &gpu)) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "RDP backend: Vulkan instance/GPU ainda nao estao disponiveis");
        return false;
    }
    if (!Vulkan::Context::init_loader(vkGetInstanceProcAddr)) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "RDP backend: Granite/Volk nao conseguiu inicializar o loader Vulkan");
        return false;
    }

    g_rdpContext = Vulkan::ContextHandle(new Vulkan::Context());
    if (!g_rdpContext->init_device_from_instance(
            instance, gpu, VK_NULL_HANDLE, nullptr, 0, nullptr)) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "RDP backend: Granite nao conseguiu criar seu dispositivo Vulkan");
        shutdownRdpBackend();
        return false;
    }

    g_rdpDevice = Vulkan::DeviceHandle(new Vulkan::Device());
    g_rdpDevice->set_context(*g_rdpContext);

    g_rdp.reset(new RDP::CommandProcessor(
        *g_rdpDevice, g_info.RDRAM, 0, 8u * 1024u * 1024u, 0, 0));
    if (!g_rdp->device_is_supported()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "RDP backend: CommandProcessor inicializou, mas GPU/driver nao suportou o renderer");
        shutdownRdpBackend();
        return false;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "RDP backend inicializado: Granite + paraLLEl-RDP; RDRAM=8 MiB; device Vulkan separado");
    return true;
}

bool presentViFramebuffer() {
    ++g_viAttempts;
    if (!g_initialized || !g_romOpen || g_info.RDRAM == nullptr ||
        g_info.VI_STATUS_REG == nullptr || g_info.VI_ORIGIN_REG == nullptr ||
        g_info.VI_WIDTH_REG == nullptr) {
        if (g_viAttempts <= 5)
            __android_log_print(ANDROID_LOG_WARN, TAG,
                "VI sem dados: initialized=%d romOpen=%d RDRAM=%p STATUS=%p ORIGIN=%p WIDTH=%p",
                g_initialized, g_romOpen, g_info.RDRAM, g_info.VI_STATUS_REG,
                g_info.VI_ORIGIN_REG, g_info.VI_WIDTH_REG);
        ++g_viRejected;
        return false;
    }

    const uint32_t status = *g_info.VI_STATUS_REG;
    const uint32_t origin = *g_info.VI_ORIGIN_REG & 0x00FFFFFFu;
    const uint32_t stride = *g_info.VI_WIDTH_REG & 0x0FFFu;
    const uint32_t pixelType = status & 3u;
    const uint32_t bytesPerPixel = pixelType == 2u ? 2u : (pixelType == 3u ? 4u : 0u);
    if (bytesPerPixel == 0 || stride == 0 || stride > 1024u) {
        if (g_viRejected < 10)
            __android_log_print(ANDROID_LOG_WARN, TAG,
                "VI ainda sem framebuffer válido: status=0x%08x origin=0x%06x width=%u pixelType=%u",
                status, origin, stride, pixelType);
        ++g_viRejected;
        return false;
    }

    // VI scanout is a framebuffer path, not an RDP command-list renderer.
    // Keep the first implementation bounded while the software RDP is added.
    const uint32_t width = std::min(stride, 640u);
    const uint32_t height = 240u;
    const uint64_t sourceBytes = static_cast<uint64_t>(stride) * height * bytesPerPixel;
    if (sourceBytes == 0 || sourceBytes > 8u * 1024u * 1024u ||
        static_cast<uint64_t>(origin) + sourceBytes > 8u * 1024u * 1024u) {
        if (g_viRejected < 10)
            __android_log_print(ANDROID_LOG_WARN, TAG,
                "VI framebuffer fora dos limites: origin=0x%06x stride=%u bytesPerPixel=%u",
                origin, stride, bytesPerPixel);
        ++g_viRejected;
        return false;
    }

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
    if (g_frameNumber <= 5 || (g_frameNumber % 120u) == 0u)
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "VI frame #%llu enviado: %ux%u origin=0x%06x stride=%u status=0x%08x",
            static_cast<unsigned long long>(g_frameNumber), width, height,
            origin, stride, status);
    return true;
}

uint32_t readRdpWord(uint32_t address) {
    return (static_cast<uint32_t>(rdramByte(address)) << 24) |
           (static_cast<uint32_t>(rdramByte(address + 1u)) << 16) |
           (static_cast<uint32_t>(rdramByte(address + 2u)) << 8) |
           static_cast<uint32_t>(rdramByte(address + 3u));
}

unsigned rdpCommandWordCount(uint32_t opcode) {
    // paraLLEl-RDP consumes complete RDP commands. Triangle commands have
    // optional shade/texture/Z payloads; all other hardware commands are
    // two 32-bit words, except texture rectangles (four words).
    switch (opcode & 0x3fu) {
    case 0x08: return 8;   // Fill triangle
    case 0x09: return 12;  // Fill + Z
    case 0x0a: return 24;  // Texture
    case 0x0b: return 28;  // Texture + Z
    case 0x0c: return 24;  // Shade
    case 0x0d: return 28;  // Shade + Z
    case 0x0e: return 40;  // Shade + texture
    case 0x0f: return 44;  // Shade + texture + Z
    case 0x24:
    case 0x25: return 4;   // Texture rectangle / flipped
    default: return 2;
    }
}

void setRdpViRegisters() {
    if (!g_rdp) return;
#define SET_VI(reg, field) \
    do { if (g_info.field) g_rdp->set_vi_register(RDP::VIRegister::reg, *g_info.field); } while (0)
    SET_VI(Control, VI_STATUS_REG);
    SET_VI(Origin, VI_ORIGIN_REG);
    SET_VI(Width, VI_WIDTH_REG);
    SET_VI(Intr, VI_INTR_REG);
    SET_VI(VCurrentLine, VI_V_CURRENT_LINE_REG);
    SET_VI(Timing, VI_TIMING_REG);
    SET_VI(VSync, VI_V_SYNC_REG);
    SET_VI(HSync, VI_H_SYNC_REG);
    SET_VI(Leap, VI_LEAP_REG);
    SET_VI(HStart, VI_H_START_REG);
    SET_VI(VStart, VI_V_START_REG);
    SET_VI(VBurst, VI_V_BURST_REG);
    SET_VI(XScale, VI_X_SCALE_REG);
    SET_VI(YScale, VI_Y_SCALE_REG);
#undef SET_VI
}

bool processRdpCommandList() {
    if (!g_rdp || !g_info.RDRAM || !g_info.DPC_START_REG || !g_info.DPC_END_REG)
        return false;

    const uint32_t start = *g_info.DPC_START_REG & 0x00fffff8u;
    const uint32_t end = *g_info.DPC_END_REG & 0x00fffff8u;
    if (end <= start || end > 8u * 1024u * 1024u) {
        if (g_processRdpCalls <= 5)
            __android_log_print(ANDROID_LOG_WARN, TAG,
                "RDP lista vazia/invalida: START=0x%06x END=0x%06x",
                start, end);
        return false;
    }

    uint32_t cursor = start;
    unsigned commands = 0;
    while (cursor < end && commands < 65536u) {
        if (cursor > end - 8u) {
            __android_log_print(ANDROID_LOG_WARN, TAG,
                "RDP lista truncada no endereco 0x%06x", cursor);
            return false;
        }

        uint32_t words[44] = {};
        words[0] = readRdpWord(cursor);
        const unsigned count = rdpCommandWordCount(words[0] >> 24);
        const uint64_t commandEnd = static_cast<uint64_t>(cursor) +
                                    static_cast<uint64_t>(count) * 4u;
        if (commandEnd > end || commandEnd > 8u * 1024u * 1024u) {
            __android_log_print(ANDROID_LOG_WARN, TAG,
                "RDP comando truncado: opcode=0x%02x endereco=0x%06x palavras=%u END=0x%06x",
                (words[0] >> 24) & 0x3fu, cursor, count, end);
            return false;
        }
        for (unsigned i = 1; i < count; ++i)
            words[i] = readRdpWord(cursor + i * 4u);

        g_rdp->enqueue_command(count, words);
        cursor += count * 4u;
        ++commands;
    }

    if (cursor != end) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "RDP lista excedeu limite de comandos: cursor=0x%06x END=0x%06x",
            cursor, end);
        return false;
    }

    setRdpViRegisters();
    g_rdp->flush();

    std::vector<RDP::RGBA> colors;
    unsigned width = 0;
    unsigned height = 0;
    RDP::ScanoutOptions options = {};
    g_rdp->scanout_sync(colors, width, height, options);
    if (width == 0 || height == 0 ||
        colors.size() < static_cast<size_t>(width) * height) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "RDP scanout sem imagem valida: %ux%u pixels=%zu comandos=%u",
            width, height, colors.size(), commands);
        return false;
    }

    g_rgba.resize(static_cast<size_t>(width) * height * 4u);
    for (size_t i = 0; i < static_cast<size_t>(width) * height; ++i) {
        g_rgba[i * 4u + 0u] = colors[i].r;
        g_rgba[i * 4u + 1u] = colors[i].g;
        g_rgba[i * 4u + 2u] = colors[i].b;
        g_rgba[i * 4u + 3u] = colors[i].a;
    }

    N64VideoFrame frame{};
    frame.pixels = g_rgba.data();
    frame.width = width;
    frame.height = height;
    frame.stride_bytes = width * 4u;
    frame.pixel_format = N64_VIDEO_PIXEL_FORMAT_RGBA8888;
    frame.frame_number = ++g_frameNumber;
    n64VulkanVideoFrameCallback(&frame, nullptr);

    if (g_processRdpCalls <= 5 || (g_processRdpCalls % 120u) == 0u)
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "RDP real processado: comandos=%u lista=0x%06x-0x%06x frame=%ux%u",
            commands, start, end, width, height);
    return true;
}

} // namespace

extern "C" {
EXPORT m64p_error CALL PluginGetVersion(m64p_plugin_type* type, int* pluginVersion,
                                         int* apiVersion, const char** name, int* caps) {
    // Mupen64Plus calls PluginGetVersion with name and caps set to nullptr
    // during compatibility checks. Only the three version outputs are required.
    if (!type || !pluginVersion || !apiVersion) return M64ERR_INPUT_ASSERT;
    *type = M64PLUGIN_GFX;
    *pluginVersion = 0x000100;
    *apiVersion = 0x020200;
    if (name) *name = "N64 Android Vulkan VI Framebuffer Plugin";
    if (caps) *caps = 0;
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "PluginGetVersion: type=%d plugin=0x%06x api=0x%06x",
        static_cast<int>(*type), *pluginVersion, *apiVersion);
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
    shutdownRdpBackend();
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
    ++g_processDListCalls;
    if (g_processDListCalls <= 5)
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "ProcessDList #%llu (HLE display list); RDP ainda nao implementado",
            static_cast<unsigned long long>(g_processDListCalls));
    // The RDP command processor is not implemented in this plugin yet.
    // Present the VI framebuffer when the Core exposes a completed CFB.
    presentViFramebuffer();
}
EXPORT void CALL ProcessRDPList(void) {
    std::lock_guard<std::mutex> lock(g_mutex);
    ++g_processRdpCalls;
    if (!processRdpCommandList()) {
        if (g_processRdpCalls <= 5)
            __android_log_print(ANDROID_LOG_WARN, TAG,
                "ProcessRDPList #%llu sem frame RDP; usando fallback VI",
                static_cast<unsigned long long>(g_processRdpCalls));
        presentViFramebuffer();
    }
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
    if (!initializeRdpBackend()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "ROM aberta, mas backend RDP indisponivel; framebuffer VI permanece como fallback");
    }
    g_frameNumber = 0;
    g_processDListCalls = 0;
    g_processRdpCalls = 0;
    g_viAttempts = 0;
    g_viRejected = 0;
    __android_log_print(ANDROID_LOG_INFO, TAG, "ROM aberta no plugin GFX Vulkan");
    return 1;
}
EXPORT void CALL RomClosed(void) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_romOpen = false;
    shutdownRdpBackend();
}
}
