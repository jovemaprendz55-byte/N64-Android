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
#include "global_managers_init.hpp"
#include "global_managers.hpp"
#include <memory>

namespace {
constexpr char TAG[] = "N64AndroidVulkanGFX";
std::mutex g_mutex;
GFX_INFO g_info{};
bool g_started = false;
bool g_initialized = false;
bool g_romOpen = false;
bool g_graniteInitialized = false;
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

    // paraLLEl-RDP's runtime shader path uses Granite's global filesystem
    // and worker-thread context. The Android host does not run a Granite app,
    // so initialize only the managers required by this renderer.
    if (!g_graniteInitialized) {
        Granite::Global::init(
            Granite::Global::MANAGER_FEATURE_FILESYSTEM_BIT |
            Granite::Global::MANAGER_FEATURE_THREAD_GROUP_BIT,
            2);
        if (!Granite::Global::filesystem() || !Granite::Global::thread_group()) {
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                "RDP backend: Granite global filesystem/thread group unavailable");
            Granite::Global::deinit();
            return false;
        }
        g_graniteInitialized = true;
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "Granite global filesystem and worker threads initialized");
    }

    g_rdpContext = Vulkan::ContextHandle(new Vulkan::Context());

    // Granite dereferences required_features while creating the Vulkan device.
    // Pass an explicit zero-initialized feature set when the RDP does not
    // require any mandatory legacy VkPhysicalDeviceFeatures.
    const VkPhysicalDeviceFeatures requiredFeatures{};
    if (!g_rdpContext->init_device_from_instance(
            instance, gpu, VK_NULL_HANDLE, nullptr, 0, &requiredFeatures)) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "RDP backend: Granite nao conseguiu criar seu dispositivo Vulkan");
        shutdownRdpBackend();
        return false;
    }

    g_rdpDevice = Vulkan::DeviceHandle(new Vulkan::Device());
    g_rdpDevice->set_context(*g_rdpContext);

    // N64 RDRAM has one hidden bit for each data byte. paraLLEl-RDP
    // stores those sideband bits in a separate buffer (1/8 of 8 MiB RDRAM).
    // Passing zero makes Granite's LegionAllocator request zero blocks and
    // abort during CommandProcessor construction.
    constexpr size_t rdramSize = 8u * 1024u * 1024u;
    constexpr size_t hiddenRdramSize = rdramSize / 8u;
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "RDP backend memory: RDRAM=%zu bytes; hidden RDRAM=%zu bytes; TMEM=4096 bytes",
        rdramSize, hiddenRdramSize);
    g_rdp.reset(new RDP::CommandProcessor(
        *g_rdpDevice, g_info.RDRAM, 0, rdramSize, hiddenRdramSize, 0));
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

uint32_t readRdpWord(uint32_t address);

uint32_t readN64MemoryWord(const unsigned char* memory, uint32_t address) {
    if (!memory) return 0;
    return (static_cast<uint32_t>(memory[(address + 0u) ^ 3u]) << 24) |
           (static_cast<uint32_t>(memory[(address + 1u) ^ 3u]) << 16) |
           (static_cast<uint32_t>(memory[(address + 2u) ^ 3u]) << 8) |
           static_cast<uint32_t>(memory[(address + 3u) ^ 3u]);
}

uint32_t checksumRdramBytes(uint32_t address, uint32_t size) {
    uint32_t sum = 0;
    for (uint32_t i = 0; i < size; ++i)
        sum += rdramByte(address + i);
    return sum;
}

uint32_t fnv1aRdramBytes(uint32_t address, uint32_t size) {
    uint32_t hash = 2166136261u;
    // Hash the host byte layout exactly as the HLE plugin sees hle->dram.
    // The byte sum is endian-independent, but FNV-1a is not.
    for (uint32_t i = 0; i < size; ++i) {
        hash ^= g_info.RDRAM[address + i];
        hash *= 16777619u;
    }
    return hash;
}

void logRspTaskSnapshot() {
    if (!g_info.DMEM) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "RSP task snapshot: DMEM indisponivel");
        return;
    }

    uint32_t task[16] = {};
    for (unsigned i = 0; i < 16; ++i)
        task[i] = readN64MemoryWord(g_info.DMEM, 0xFC0u + i * 4u);

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "RSP OSTask: type=%08x flags=%08x ucode_boot=%08x boot_size=%08x "
        "ucode=%08x ucode_size=%08x ucode_data=%08x ucode_data_size=%08x "
        "output_buff=%08x output_buff_size=%08x data_ptr=%08x data_size=%08x "
        "yield_ptr=%08x yield_size=%08x",
        task[0], task[1], task[2], task[3], task[4], task[5], task[6], task[7],
        task[10], task[11], task[12], task[13], task[14], task[15]);

    // Match the checksum window used by mupen64plus-rsp-hle when it identifies
    // a graphics microcode. The additional FNV-1a value distinguishes ucodes
    // that happen to share the same byte sum.
    const uint32_t ucodeAddress = task[4] & 0x00ffffffu;
    const uint32_t ucodeSize = std::min<uint32_t>(task[5], 0x0f80u);
    const uint32_t ucodeDataAddress = task[6] & 0x00ffffffu;
    const uint32_t ucodeDataSize = std::min<uint32_t>(task[7], 0x1000u);
    if (ucodeAddress < 8u * 1024u * 1024u &&
        ucodeSize <= 8u * 1024u * 1024u - ucodeAddress &&
        ucodeDataAddress < 8u * 1024u * 1024u &&
        ucodeDataSize <= 8u * 1024u * 1024u - ucodeDataAddress) {
        const uint32_t hleChecksumSize = ucodeSize >> 1;
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "RSP ucode identity: HLE_sum=%08x HLE_bytes=%u ucode_fnv1a=%08x "
            "ucode_data_sum=%08x ucode_data_fnv1a=%08x",
            checksumRdramBytes(ucodeAddress, hleChecksumSize), hleChecksumSize,
            fnv1aRdramBytes(ucodeAddress, ucodeSize),
            checksumRdramBytes(ucodeDataAddress, ucodeDataSize),
            fnv1aRdramBytes(ucodeDataAddress, ucodeDataSize));
    } else {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "RSP ucode range invalid: ucode=%08x/%08x data=%08x/%08x",
            task[4], task[5], task[6], task[7]);
    }

    // Dump the actual HLE display-list payload. OSTask fields 12/13 are
    // data_ptr/data_size; fields 10/11 are output buffer metadata.
    const uint32_t dataPtr = task[12] & 0x00ffffffu;
    const uint32_t dataSize = task[13];
    if (dataSize != 0 && dataSize <= 0x1000u &&
        dataPtr <= 8u * 1024u * 1024u &&
        dataSize <= 8u * 1024u * 1024u - dataPtr) {
        const unsigned count = std::min<unsigned>(dataSize / 8u, 16u);
        for (unsigned i = 0; i < count; ++i) {
            const uint32_t address = dataPtr + i * 8u;
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "RSP GBI[%02u] @%06x: %08x %08x",
                i, address, readRdpWord(address), readRdpWord(address + 4u));
        }
    } else {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "RSP display list range invalid: ptr=%08x size=%08x",
            task[12], task[13]);
    }

    const uint32_t dpcStart = g_info.DPC_START_REG ? *g_info.DPC_START_REG : 0;
    const uint32_t dpcEnd = g_info.DPC_END_REG ? *g_info.DPC_END_REG : 0;
    const uint32_t spStatus = g_info.SP_STATUS_REG ? *g_info.SP_STATUS_REG : 0;
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "RSP task registers: DPC_START=%08x DPC_END=%08x SP_STATUS=%08x",
        dpcStart, dpcEnd, spStatus);
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
    // RSP HLE signals a graphics task to the GFX plugin. It does not guarantee
    // that the OSTask display-list data has already become raw RDP commands.
    // Capture the task descriptor first so the GBI translation path can be
    // implemented against the actual microcode/data pointers used by GoldenEye.
    if (g_processDListCalls <= 8)
        logRspTaskSnapshot();
    const bool rendered = processRdpCommandList();
    if (!rendered && g_processDListCalls <= 10) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "ProcessDList #%llu: nenhuma imagem RDP valida; aguardando lista real (sem apresentar VI bruto)",
            static_cast<unsigned long long>(g_processDListCalls));
    }
}
EXPORT void CALL ProcessRDPList(void) {
    std::lock_guard<std::mutex> lock(g_mutex);
    ++g_processRdpCalls;
    if (!processRdpCommandList() && g_processRdpCalls <= 10) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "ProcessRDPList #%llu sem frame RDP valido; fallback VI desativado para evitar imagem corrompida",
            static_cast<unsigned long long>(g_processRdpCalls));
    }
}
EXPORT void CALL ShowCFB(void) {
    // The VI memory path is not yet validated. Do not upload arbitrary RDRAM
    // as a framebuffer: it produces colored noise instead of a real game frame.
}
EXPORT void CALL UpdateScreen(void) {
    // Frames are presented only after a successful paraLLEl-RDP scanout.
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
