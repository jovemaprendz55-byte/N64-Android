#define VK_NO_PROTOTYPES 1
#include "vulkan_headers.hpp"

#include <android/log.h>
#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdio>
#include <cmath>
#include <chrono>
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

// Native F3D Vtx layout: 16 bytes in big-endian N64 RDRAM.
struct GoldenEyeVertex {
    int16_t x = 0, y = 0, z = 0;
    uint16_t flag = 0;
    int16_t s = 0, t = 0;
    uint8_t colorOrNormal[4] = {};
    bool lightingEnabled = false;
    bool valid = false;
};

struct GoldenEyeLightState {
    float color[3]{};
    float direction[3]{};
    bool valid = false;
};

struct GoldenEyeMatrixState {
    float modelView[16] = {};
    float projection[16] = {};
    bool modelViewValid = false;
    bool projectionValid = false;
    uint32_t geometryMode = 0;
    std::array<GoldenEyeLightState, 9> lights{};
    unsigned numLights = 0;
    // Fast3D G_MTX_PUSH / G_POPMTX use a model-view matrix stack.
    std::array<std::array<float, 16>, 32> modelViewStack{};
    unsigned modelViewStackDepth = 0;
};

// Persistent state for the GoldenEye GBI interpreter. Display lists can be
// split across OSTasks; vertex cache, segment bases and matrix stack must not
// be recreated by the diagnostic pass for every task.
struct GoldenEyeGbiState {
    std::array<uint32_t, 16> segments{};
    std::array<GoldenEyeVertex, 32> vertexCache{};
    GoldenEyeMatrixState matrices{};
    uint32_t vertexCacheMask = 0;
};

void multiplyMatrix4x4(const float a[16], const float b[16], float out[16]) {
    float result[16] = {};
    for (unsigned row = 0; row < 4u; ++row) {
        for (unsigned col = 0; col < 4u; ++col) {
            for (unsigned k = 0; k < 4u; ++k)
                result[row * 4u + col] += a[row * 4u + k] * b[k * 4u + col];
        }
    }
    std::memcpy(out, result, sizeof(result));
}

void transformGoldenEyeVertex(const float matrix[16],
                              const GoldenEyeVertex& vertex,
                              float out[4]) {
    const float input[4] = {
        static_cast<float>(vertex.x),
        static_cast<float>(vertex.y),
        static_cast<float>(vertex.z),
        1.0f
    };
    // N64 Mtx values are stored as rows, with translation in row 3.
    // Transform positions as row vectors: out = input * matrix.
    // Using the column-vector form here moves the translation into W and
    // produces huge, invalid homogeneous coordinates (e.g. W ~= -988000).
    for (unsigned col = 0; col < 4u; ++col) {
        out[col] = 0.0f;
        for (unsigned row = 0; row < 4u; ++row)
            out[col] += input[row] * matrix[row * 4u + col];
    }
}

void transformGoldenEyeVector4(const float matrix[16],
                               const float input[4], float out[4]) {
    for (unsigned col = 0; col < 4u; ++col) {
        out[col] = 0.0f;
        for (unsigned row = 0; row < 4u; ++row)
            out[col] += input[row] * matrix[row * 4u + col];
    }
}
void logGoldenEyeActiveMatrixState(const GoldenEyeMatrixState& state,
                                     unsigned triangleIndex, uint32_t commandPc) {
    if (triangleIndex >= 3u) return;
    for (unsigned row = 0; row < 4u; ++row) {
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "F3DGOLDEN TRI1_MATRIX[%u] pc=%06x MODELVIEW row%u: %.5f %.5f %.5f %.5f",
            triangleIndex, commandPc, row,
            state.modelView[row * 4u + 0u], state.modelView[row * 4u + 1u],
            state.modelView[row * 4u + 2u], state.modelView[row * 4u + 3u]);
    }
    for (unsigned row = 0; row < 4u; ++row) {
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "F3DGOLDEN TRI1_MATRIX[%u] pc=%06x PROJECTION row%u: %.5f %.5f %.5f %.5f",
            triangleIndex, commandPc, row,
            state.projection[row * 4u + 0u], state.projection[row * 4u + 1u],
            state.projection[row * 4u + 2u], state.projection[row * 4u + 3u]);
    }
}

std::mutex g_mutex;
GoldenEyeGbiState g_goldenEyeGbiState{};
GFX_INFO g_info{};
bool g_started = false;
bool g_initialized = false;
bool g_romOpen = false;
uint64_t g_frameNumber = 0;
uint64_t g_processDListCalls = 0;
uint64_t g_processRdpCalls = 0;
uint64_t g_viAttempts = 0;
uint64_t g_viRejected = 0;
struct RdpCommandStats {
    uint64_t batches = 0;
    uint64_t commands = 0;
    uint64_t setColorImage = 0;
    uint64_t setTextureImage = 0;
    uint64_t setDepthImage = 0;
    uint64_t fillRect = 0;
    uint64_t textureRect = 0;
    uint64_t triangles = 0;
    uint64_t sync = 0;
    uint32_t lastOpcode = 0;
    uint32_t lastColorW0 = 0;
    uint32_t lastColorW1 = 0;
    uint32_t lastTextureW0 = 0;
    uint32_t lastTextureW1 = 0;
};
RdpCommandStats g_rdpCommandStats{};
std::vector<uint8_t> g_rgba;
// Temporary first-light path: projected F3D triangles become an RGBA frame.
std::vector<std::array<float,  9>> g_goldenEyeDebugTriangles;
std::vector<float> g_goldenEyeRenderVertices;
void getGoldenEyeVertexColor(const GoldenEyeVertex& vertex,
                              const GoldenEyeMatrixState& state,
                              float color[4]) {
    color[3] = static_cast<float>(vertex.colorOrNormal[3]) / 255.0f;
    if (!vertex.lightingEnabled) {
        color[0] = static_cast<float>(vertex.colorOrNormal[0]) / 255.0f;
        color[1] = static_cast<float>(vertex.colorOrNormal[1]) / 255.0f;
        color[2] = static_cast<float>(vertex.colorOrNormal[2]) / 255.0f;
        return;
    }

    // F3D lit vertices carry signed normal XYZ in the final four bytes.
    // Evaluate ambient + Lambert directional lights instead of forcing white.
    float normal[3] = {
        static_cast<float>(static_cast<int8_t>(vertex.colorOrNormal[0])) / 127.0f,
        static_cast<float>(static_cast<int8_t>(vertex.colorOrNormal[1])) / 127.0f,
        static_cast<float>(static_cast<int8_t>(vertex.colorOrNormal[2])) / 127.0f
    };
    float normalLength = std::sqrt(normal[0]*normal[0] +
                                   normal[1]*normal[1] +
                                   normal[2]*normal[2]);
    if (normalLength > 0.0001f) {
        for (float& component : normal) component /= normalLength;
    }

    color[0] = color[1] = color[2] = 0.0f;
    bool foundLight = false;
    const unsigned directionalCount = std::min(state.numLights, 8u);
    for (unsigned i = 0; i < directionalCount; ++i) {
        const GoldenEyeLightState& light = state.lights[i];
        if (!light.valid) continue;
        foundLight = true;
        const float diffuse = std::max(0.0f,
            -(normal[0]*light.direction[0] +
              normal[1]*light.direction[1] +
              normal[2]*light.direction[2]));
        for (unsigned channel = 0; channel < 3u; ++channel)
            color[channel] += light.color[channel] * diffuse;
    }
    // In F3D the ambient light follows the directional lights.
    if (directionalCount < state.lights.size() &&
        state.lights[directionalCount].valid) {
        const GoldenEyeLightState& ambient = state.lights[directionalCount];
        foundLight = true;
        for (unsigned channel = 0; channel < 3u; ++channel)
            color[channel] += ambient.color[channel];
    }
    if (!foundLight) color[0] = color[1] = color[2] = 1.0f;
    for (unsigned channel = 0; channel < 3u; ++channel)
        color[channel] = std::clamp(color[channel], 0.0f, 1.0f);
}

void appendGoldenEyeRenderVertex(float x, float y, float z,
                                  const GoldenEyeVertex& vertex,
                                  const GoldenEyeMatrixState& state) {
    float color[4] = {};
    getGoldenEyeVertexColor(vertex, state, color);
    g_goldenEyeRenderVertices.insert(g_goldenEyeRenderVertices.end(),
        {x, y, z, color[0], color[1], color[2], color[3]});
}

void logGoldenEyeTriangleColors(const char* source, unsigned triangleIndex,
                                unsigned v0, unsigned v1, unsigned v2,
                                const GoldenEyeVertex& a,
                                const GoldenEyeVertex& b,
                                const GoldenEyeVertex& c,
                                uint32_t geometryMode,
                                const GoldenEyeMatrixState& currentColorState) {
    if (triangleIndex >= 12u) return;
    const GoldenEyeVertex* vertices[3] = {&a, &b, &c};
    float output[3][4] = {};
    for (unsigned i = 0; i < 3u; ++i) {
        float color[4] = {};
        getGoldenEyeVertexColor(*vertices[i], currentColorState, color);
        for (unsigned channel = 0; channel < 4u; ++channel)
            output[i][channel] = color[channel];
    }
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "F3DGOLDEN TRI_COLOR[%u] src=%s idx=(%u,%u,%u) geom=%08x "
        "A{L=%u raw=%02x,%02x,%02x,%02x out=%.2f,%.2f,%.2f,%.2f} "
        "B{L=%u raw=%02x,%02x,%02x,%02x out=%.2f,%.2f,%.2f,%.2f} "
        "C{L=%u raw=%02x,%02x,%02x,%02x out=%.2f,%.2f,%.2f,%.2f}",
        triangleIndex, source, v0, v1, v2, geometryMode,
        a.lightingEnabled ? 1u : 0u,
        a.colorOrNormal[0], a.colorOrNormal[1], a.colorOrNormal[2], a.colorOrNormal[3],
        output[0][0], output[0][1], output[0][2], output[0][3],
        b.lightingEnabled ? 1u : 0u,
        b.colorOrNormal[0], b.colorOrNormal[1], b.colorOrNormal[2], b.colorOrNormal[3],
        output[1][0], output[1][1], output[1][2], output[1][3],
        c.lightingEnabled ? 1u : 0u,
        c.colorOrNormal[0], c.colorOrNormal[1], c.colorOrNormal[2], c.colorOrNormal[3],
        output[2][0], output[2][1], output[2][2], output[2][3]);
}
std::vector<uint8_t> g_goldenEyeDebugPixels;
bool g_hasGoldenEyeDebugFrame = false;
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

    // Granite global managers are thread_local. A process-wide boolean is
    // not sufficient: RomOpen / RDP construction can run on a different
    // thread from the one which first initialized the backend. Ensure this
    // calling thread owns its own filesystem and worker-group context.
    if (!Granite::Global::filesystem() || !Granite::Global::thread_group()) {
        Granite::Global::init(
            Granite::Global::MANAGER_FEATURE_FILESYSTEM_BIT |
            Granite::Global::MANAGER_FEATURE_THREAD_GROUP_BIT,
            2);
    }
    if (!Granite::Global::filesystem() || !Granite::Global::thread_group()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "RDP backend: Granite managers unavailable on RDP initialization thread");
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "Granite managers ready on current RDP thread");

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
uint32_t crc32RdramBytes(uint32_t address, uint32_t size) {
    uint32_t crc = 0xffffffffu;
    for (uint32_t i = 0; i < size; ++i) {
        crc ^= rdramByte(address + i);
        for (unsigned bit = 0; bit < 8; ++bit)
            crc = (crc >> 1) ^ (0xedb88320u & (0u - (crc & 1u)));
    }
    return ~crc;
}

void logRdramAsciiSignatures(uint32_t address, uint32_t size) {
    // Microcode identification strings commonly live in the data segment.
    // Emit only printable ASCII runs, capped to avoid flooding logcat.
    char run[96];
    uint32_t runLength = 0;
    unsigned emitted = 0;
    for (uint32_t i = 0; i < size && emitted < 8; ++i) {
        const uint8_t ch = rdramByte(address + i);
        if (ch >= 0x20 && ch <= 0x7e) {
            if (runLength < sizeof(run) - 1)
                run[runLength++] = static_cast<char>(ch);
        } else {
            if (runLength >= 8) {
                run[runLength] = '\0';
                __android_log_print(ANDROID_LOG_INFO, TAG,
                    "RSP ucode_data text @+%04x: %s", i - runLength, run);
                ++emitted;
            }
            runLength = 0;
        }
    }
    if (runLength >= 8 && emitted < 8) {
        run[runLength] = '\0';
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "RSP ucode_data text @+%04x: %s", size - runLength, run);
    }
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
            "RSP ucode identity: HLE_sum=%08x HLE_bytes=%u ucode_crc32=%08x "
            "ucode_fnv1a=%08x ucode_data_crc32=%08x ucode_data_fnv1a=%08x",
            checksumRdramBytes(ucodeAddress, hleChecksumSize), hleChecksumSize,
            crc32RdramBytes(ucodeAddress, ucodeSize),
            fnv1aRdramBytes(ucodeAddress, ucodeSize),
            crc32RdramBytes(ucodeDataAddress, ucodeDataSize),
            fnv1aRdramBytes(ucodeDataAddress, ucodeDataSize));
        const uint32_t ucodeEntry = readRdpWord(ucodeAddress);
        const uint32_t ucodeEntry1 = readRdpWord(ucodeAddress + 4u);
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "RSP ucode entry: text=%06x word0=%08x word1=%08x "
            "GoldenEye_F3DEX_signature=%s",
            ucodeAddress, ucodeEntry, ucodeEntry1,
            ucodeEntry == 0x090005eau ? "MATCH" : "no");
        logRdramAsciiSignatures(ucodeDataAddress, ucodeDataSize);
    } else {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "RSP ucode range invalid: ucode=%08x/%08x data=%08x/%08x",
            task[4], task[5], task[6], task[7]);
    }

    // Dump the actual HLE display-list payload. OSTask fields 12/13 are
    // data_ptr/data_size; fields 10/11 are output buffer metadata.
    const uint32_t dataPtr = task[12] & 0x00ffffffu;
    const uint32_t dataSize = task[13];
    if (dataSize != 0 && dataSize <= 0x100000u &&
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

bool submitViRdramFallbackFrame() {
    if (!g_info.RDRAM || !g_info.VI_STATUS_REG || !g_info.VI_ORIGIN_REG ||
        !g_info.VI_WIDTH_REG || !g_info.VI_H_START_REG || !g_info.VI_V_START_REG ||
        !g_info.VI_X_SCALE_REG || !g_info.VI_Y_SCALE_REG)
        return false;

    constexpr uint32_t outputWidth = 640u;
    constexpr uint32_t outputHeight = 240u;
    constexpr uint32_t rdramSize = 8u * 1024u * 1024u;
    const uint32_t status = *g_info.VI_STATUS_REG;
    const uint32_t origin = *g_info.VI_ORIGIN_REG & 0x00ffffffu;
    const uint32_t sourceWidth = *g_info.VI_WIDTH_REG & 0x0fffu;
    const uint32_t hStartReg = *g_info.VI_H_START_REG;
    const uint32_t vStartReg = *g_info.VI_V_START_REG;
    const uint32_t xScale = *g_info.VI_X_SCALE_REG;
    const uint32_t yScale = *g_info.VI_Y_SCALE_REG;

    const uint32_t xStart = (xScale >> 16) & 0x0fffu;
    const uint32_t xAdd = xScale & 0x0fffu;
    const uint32_t yStart = (yScale >> 16) & 0x0fffu;
    const uint32_t yAdd = yScale & 0x0fffu;
    const uint32_t hStart = (hStartReg >> 16) & 0x03ffu;
    const uint32_t hEnd = hStartReg & 0x03ffu;
    const uint32_t vStart = (vStartReg >> 16) & 0x03ffu;
    const uint32_t vEnd = vStartReg & 0x03ffu;

    const bool pal = g_info.VI_V_SYNC_REG &&
                     ((*g_info.VI_V_SYNC_REG & 0x03ffu) > 550u);
    const int hOffset = pal ? 128 : 108;
    const int vOffset = pal ? 44 : 34;
    const int activeX0 = std::max(0, static_cast<int>(hStart) - hOffset);
    const int activeX1 = std::min(static_cast<int>(outputWidth),
                                  static_cast<int>(hEnd) - hOffset);
    const int activeY0 = std::max(0, (static_cast<int>(vStart) - vOffset) / 2);
    const int activeY1 = std::min(static_cast<int>(outputHeight),
                                  (static_cast<int>(vEnd) - vOffset) / 2);
    const uint32_t pixelBytes = ((status & 3u) == 3u) ? 4u : 2u;

    if (origin == 0u || sourceWidth == 0u || xAdd == 0u || yAdd == 0u ||
        activeX1 <= activeX0 || activeY1 <= activeY0 ||
        (pixelBytes == 2u && (status & 3u) != 2u))
        return false;

    g_rgba.assign(static_cast<size_t>(outputWidth) * outputHeight * 4u, 0u);
    for (int y = activeY0; y < activeY1; ++y) {
        const uint32_t sourceY = (yStart +
            static_cast<uint32_t>(y - activeY0) * yAdd) >> 10;
        for (int x = activeX0; x < activeX1; ++x) {
            const uint32_t sourceX = (xStart +
                static_cast<uint32_t>(x - activeX0) * xAdd) >> 10;
            if (sourceX >= sourceWidth) continue;

            const uint64_t address64 = static_cast<uint64_t>(origin) +
                (static_cast<uint64_t>(sourceY) * sourceWidth + sourceX) * pixelBytes;
            if (address64 + pixelBytes > rdramSize) continue;
            const uint32_t address = static_cast<uint32_t>(address64);
            uint8_t red, green, blue;
            if (pixelBytes == 2u) {
                const uint16_t pixel = static_cast<uint16_t>(
                    (static_cast<uint16_t>(rdramByte(address)) << 8) |
                    rdramByte(address + 1u));
                const uint8_t r5 = static_cast<uint8_t>((pixel >> 11) & 31u);
                const uint8_t g5 = static_cast<uint8_t>((pixel >> 6) & 31u);
                const uint8_t b5 = static_cast<uint8_t>((pixel >> 1) & 31u);
                red = static_cast<uint8_t>((r5 << 3) | (r5 >> 2));
                green = static_cast<uint8_t>((g5 << 3) | (g5 >> 2));
                blue = static_cast<uint8_t>((b5 << 3) | (b5 >> 2));
            } else {
                red = rdramByte(address);
                green = rdramByte(address + 1u);
                blue = rdramByte(address + 2u);
            }

            const size_t out = (static_cast<size_t>(y) * outputWidth +
                                static_cast<uint32_t>(x)) * 4u;
            g_rgba[out + 0u] = red;
            g_rgba[out + 1u] = green;
            g_rgba[out + 2u] = blue;
            g_rgba[out + 3u] = 255u;
        }
    }

    N64VideoFrame frame{};
    frame.pixels = g_rgba.data();
    frame.width = outputWidth;
    frame.height = outputHeight;
    frame.stride_bytes = outputWidth * 4u;
    frame.pixel_format = N64_VIDEO_PIXEL_FORMAT_RGBA8888;
    frame.frame_number = ++g_frameNumber;
    n64VulkanVideoFrameCallback(&frame, nullptr);
    return true;
}

void logFramebufferPixelProbe(const char* label, uint32_t address,
                              uint32_t width, uint32_t status) {
    if (width == 0u || width > 4096u) return;

    constexpr uint32_t probeHeight = 240u;
    const uint32_t pixelCount = std::min(width * probeHeight, 105600u);
    const bool rgba8888 = (status & 3u) == 3u;
    uint32_t nonBlack = 0u;
    uint32_t firstNonBlack = pixelCount;
    uint32_t maxComponent = 0u;
    char samples[96] = {};
    size_t sampleOffset = 0u;

    for (uint32_t i = 0; i < pixelCount; ++i) {
        uint32_t componentMax = 0u;
        uint32_t raw = 0u;
        if (rgba8888) {
            const uint32_t pixelAddress = address + i * 4u;
            raw = readRdpWord(pixelAddress);
            componentMax = std::max((raw >> 24) & 0xffu,
                std::max((raw >> 16) & 0xffu, (raw >> 8) & 0xffu));
        } else {
            const uint32_t pixelAddress = address + i * 2u;
            raw = (static_cast<uint32_t>(rdramByte(pixelAddress)) << 8) |
                  static_cast<uint32_t>(rdramByte(pixelAddress + 1u));
            componentMax = std::max((raw >> 11) & 0x1fu,
                std::max((raw >> 6) & 0x1fu, (raw >> 1) & 0x1fu));
        }

        if (componentMax != 0u) {
            ++nonBlack;
            if (firstNonBlack == pixelCount) firstNonBlack = i;
        }
        maxComponent = std::max(maxComponent, componentMax);
        if (i < 8u) {
            const int written = std::snprintf(
                samples + sampleOffset, sizeof(samples) - sampleOffset,
                "%s%0*x", i == 0u ? "" : ",",
                rgba8888 ? 8 : 4, raw);
            if (written > 0)
                sampleOffset = std::min(sizeof(samples) - 1u,
                    sampleOffset + static_cast<size_t>(written));
        }
    }

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "RDP framebuffer pixels: %s addr=%06x width=%u format=%s "
        "nonBlackRGB=%u/%u firstRGB=%u maxRGB=%u first=%s",
        label, address & 0x00ffffffu, width,
        rgba8888 ? "RGBA8888" : "RGBA5551", nonBlack, pixelCount,
        firstNonBlack, maxComponent, samples);
}

int16_t readN64MatrixS16(uint32_t address) {
    const uint16_t value =
        (static_cast<uint16_t>(rdramByte(address)) << 8) |
        static_cast<uint16_t>(rdramByte(address + 1u));
    return static_cast<int16_t>(value);
}

bool logGoldenEyeMatrix(uint32_t address, uint32_t w0, uint32_t commandPc,
                        float matrixOut[16]) {
    constexpr uint32_t matrixBytes = 64u;
    constexpr uint32_t rdramLimit = 8u * 1024u * 1024u;
    if (address > rdramLimit - matrixBytes) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "F3DGOLDEN G_MTX invalida: endereco=%06x @%06x",
            address, commandPc);
        return false;
    }

    const unsigned param = (w0 >> 16) & 0xffu;
    const char* matrixType = (param & 0x01u) ? "PROJECTION" : "MODELVIEW";
    const char* operation = (param & 0x02u) ? "LOAD" : "MUL";
    const bool push = (param & 0x04u) != 0u;
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "F3DGOLDEN G_MTX: addr=%06x type=%s op=%s push=%u @%06x",
        address, matrixType, operation, push ? 1u : 0u, commandPc);

    for (unsigned row = 0; row < 4u; ++row) {
        float values[4] = {};
        for (unsigned col = 0; col < 4u; ++col) {
            // N64 Mtx stores 16 signed integer halfwords followed by
            // 16 fractional halfwords. rdramByte exposes logical big-endian bytes.
            const unsigned element = row * 4u + col;
            const int16_t integer = readN64MatrixS16(address + element * 2u);
            const uint16_t fraction = static_cast<uint16_t>(
                (static_cast<uint16_t>(rdramByte(address + 32u + element * 2u)) << 8) |
                static_cast<uint16_t>(rdramByte(address + 33u + element * 2u)));
            values[col] = static_cast<float>(integer) +
                           static_cast<float>(fraction) / 65536.0f;
            matrixOut[element] = values[col];
        }
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "F3DGOLDEN G_MTX row%u: %.5f %.5f %.5f %.5f",
            row, values[0], values[1], values[2], values[3]);
    }
    return true;
}

bool walkF3DDisplayList(uint32_t start, uint32_t limit, uint32_t segments[16],
                        unsigned depth, uint32_t& budget,
                        uint32_t& calls, uint32_t& rawRdpCommands,
                        uint32_t opcodeCounts[256], uint32_t& loggedGbiCommands,
                        uint32_t& vertexCacheMask, uint32_t& decodedGoldenTriangles,
                        GoldenEyeVertex vertexCache[32],
                        GoldenEyeMatrixState& matrixState, bool verbose) {
    constexpr uint32_t rdramLimit = 8u * 1024u * 1024u;
    if (depth > 10u || start >= rdramLimit || (start & 7u) != 0u) {
        if (verbose) __android_log_print(ANDROID_LOG_WARN, TAG,
            "F3DGOLDEN GBI: endereco/profundidade invalida start=%06x depth=%u",
            start, depth);
        return false;
    }

    uint32_t pc = start;
    uint32_t localCommands = 0;
    while (budget < 8192u && localCommands < 2048u) {
        if (pc > rdramLimit - 8u || pc > limit - (limit >= 8u ? 8u : limit)) {
            if (verbose) __android_log_print(ANDROID_LOG_WARN, TAG,
                "F3DGOLDEN GBI: lista saiu dos limites pc=%06x limit=%06x",
                pc, limit);
            return false;
        }

        const uint32_t w0 = readRdpWord(pc);
        const uint32_t w1 = readRdpWord(pc + 4u);
        const uint32_t opcode = w0 >> 24;
        ++opcodeCounts[opcode];
        ++budget;
        ++localCommands;
        ++calls;

        // Compact command trace across recursive display-list calls.
        if (calls <= 128u) {
            if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                "F3DGOLDEN CMD_TRACE[%u]: opcode=%02x w0=%08x w1=%08x pc=%06x depth=%u",
                calls, opcode, w0, w1, pc, depth);
        }

        if (opcode == 0xB8u) { // F3D G_ENDDL
            return true;
        }

        // F3D G_MOVEMEM stores directional lights first and the ambient
        // light immediately after them. AmbientLight is only 8 bytes: it has
        // RGB color/copy fields and no direction vector.
        if (opcode == 0x03u) {
            const unsigned moveMemType = (w0 >> 16) & 0xffu;
            if (moveMemType >= 0x86u && moveMemType <= 0x96u &&
                (moveMemType & 1u) == 0u) {
                const unsigned lightIndex = (moveMemType - 0x86u) / 2u;
                const unsigned segment = (w1 >> 24) & 0xffu;
                const uint32_t offset = w1 & 0x00ffffffu;
                const uint64_t address64 = segment < 16u
                    ? static_cast<uint64_t>(segments[segment]) + offset
                    : 8u * 1024u * 1024u;
                const bool ambient = matrixState.numLights > 0u &&
                    lightIndex == std::min(matrixState.numLights, 8u);
                const uint64_t requiredBytes = ambient ? 8u : 16u;
                if (lightIndex < matrixState.lights.size() &&
                    address64 <= 8u * 1024u * 1024u - requiredBytes) {
                    const uint32_t address = static_cast<uint32_t>(address64);
                    GoldenEyeLightState& light = matrixState.lights[lightIndex];
                    for (unsigned channel = 0; channel < 3u; ++channel) {
                        light.color[channel] =
                            static_cast<float>(rdramByte(address + channel)) / 255.0f;
                    }
                    if (ambient) {
                        // AmbientLight has no direction. Its color alone makes
                        // this slot valid for getGoldenEyeVertexColor().
                        light.direction[0] = light.direction[1] = light.direction[2] = 0.0f;
                        light.valid = true;
                    } else {
                        for (unsigned channel = 0; channel < 3u; ++channel) {
                            const int8_t direction =
                                static_cast<int8_t>(rdramByte(address + 8u + channel));
                            light.direction[channel] = static_cast<float>(direction) / 127.0f;
                        }
                        const float length = std::sqrt(
                            light.direction[0] * light.direction[0] +
                            light.direction[1] * light.direction[1] +
                            light.direction[2] * light.direction[2]);
                        if (length > 0.0001f) {
                            for (float& component : light.direction) component /= length;
                            light.valid = true;
                        } else {
                            light.valid = false;
                        }
                    }
                    if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                        "F3DGOLDEN %sLIGHT_LOAD[%u]: addr=%06x rgb=%.3f,%.3f,%.3f dir=%.3f,%.3f,%.3f valid=%u",
                        ambient ? "AMBIENT_" : "", lightIndex, address,
                        light.color[0], light.color[1], light.color[2],
                        light.direction[0], light.direction[1], light.direction[2],
                        light.valid ? 1u : 0u);
                } else if (verbose) {
                    __android_log_print(ANDROID_LOG_WARN, TAG,
                        "F3DGOLDEN LIGHT_LOAD invalido: type=%02x seg=%u offset=%06x",
                        moveMemType, segment, offset);
                }
                pc += 8u;
                continue;
            }
        }

        // F3D geometry-mode commands. In a lit vertex, the final four bytes
        // are signed normal XYZ plus alpha, not an RGBA color. The temporary
        // Vulkan path has no N64 lighting implementation yet, so render lit
        // vertices neutral white instead of incorrectly treating normals as RGB.
        if (opcode == 0xB7u || opcode == 0xB6u) {
            if (opcode == 0xB7u) { // G_SETGEOMETRYMODE
                matrixState.geometryMode |= w1;
            } else { // G_CLEARGEOMETRYMODE
                matrixState.geometryMode &= ~w1;
            }
            if (verbose) {
                __android_log_print(ANDROID_LOG_INFO, TAG,
                    "F3DGOLDEN GEOMETRY_MODE: cmd=%s mask=%08x state=%08x lighting=%u @%06x",
                    opcode == 0xB7u ? "SET" : "CLEAR", w1,
                    matrixState.geometryMode,
                    (matrixState.geometryMode & 0x00020000u) ? 1u : 0u, pc);
            }
            // These are RSP GBI commands, not RDP commands. Consume them here
            // so they cannot fall through into the generic command/RDP path.
            pc += 8u;
            continue;
        }

        // GLideN64 F3DGOLDEN_Init maps BOTH 0xBC (G_MOVEWORD) and
        // 0xBD (G_GOLDEN_MOVEWORD) to F3D_MoveWord. In this microcode,
        // 0xBD is NOT G_POPMTX; treating it as a matrix pop corrupts state.
        if (opcode == 0xBCu || opcode == 0xBDu) {
            const unsigned moveWordType = w0 & 0xffu;
            if (moveWordType == 0x06u) { // G_MW_SEGMENT
                const unsigned segment = (w0 >> 10) & 0x0fu;
                segments[segment] = w1 & 0x00ffffffu;
                if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                    "F3DGOLDEN MOVEWORD: opcode=%02x segment[%u]=%06x cmd=%08x/%08x @%06x",
                    opcode, segment, segments[segment], w0, w1, pc);
            } else if (moveWordType == 0x02u) { // G_MW_NUMLIGHT
                if (w1 >= 0x80000020u) {
                    matrixState.numLights = std::min(
                        static_cast<unsigned>((w1 - 0x80000000u) >> 5) - 1u,
                        8u);
                    if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                        "F3DGOLDEN NUM_LIGHTS: count=%u raw=%08x @%06x",
                        matrixState.numLights, w1, pc);
                } else if (verbose) {
                    __android_log_print(ANDROID_LOG_WARN, TAG,
                        "F3DGOLDEN NUM_LIGHTS invalido: %08x @%06x", w1, pc);
                }
            }
        } else if (opcode == 0x06u) { // F3D G_DL
            const unsigned mode = (w0 >> 16) & 0xffu;
            const unsigned segment = (w1 >> 24) & 0xffu;
            const uint32_t offset = w1 & 0x00ffffffu;
            if (segment >= 16u) {
                if (verbose) __android_log_print(ANDROID_LOG_WARN, TAG,
                    "F3DGOLDEN GBI: segmento invalido %u em G_DL @%06x",
                    segment, pc);
                return false;
            }
            const uint64_t resolved64 = static_cast<uint64_t>(segments[segment]) + offset;
            if (resolved64 >= rdramLimit || (resolved64 & 7u) != 0u) {
                if (verbose) __android_log_print(ANDROID_LOG_WARN, TAG,
                    "F3DGOLDEN GBI: G_DL fora da RDRAM seg=%u base=%06x offset=%06x",
                    segment, segments[segment], offset);
                return false;
            }
            const uint32_t target = static_cast<uint32_t>(resolved64);
            if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                "F3DGOLDEN GBI: G_DL mode=%u alvo=%06x depth=%u",
                mode, target, depth);

            if (mode == 0u) { // G_DL_PUSH: call and return
                if (!walkF3DDisplayList(target, rdramLimit, segments,
                                        depth + 1u, budget, calls, rawRdpCommands,
                                        opcodeCounts, loggedGbiCommands,
                                        vertexCacheMask, decodedGoldenTriangles, vertexCache,
                                        matrixState, verbose))
                    return false;
            } else if (mode == 1u) { // G_DL_NOPUSH: branch without return
                // A branch replaces the current display-list stream. Its target
                // is not required to remain inside the root OSTask data range.
                pc = target;
                limit = rdramLimit;
                if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                    "F3DGOLDEN GBI: G_DL_NOPUSH mudou o limite para RDRAM; alvo=%06x",
                    target);
                continue;
            } else {
                if (verbose) __android_log_print(ANDROID_LOG_WARN, TAG,
                    "F3DGOLDEN GBI: modo G_DL desconhecido %u", mode);
                return false;
            }
        } else if (opcode == 0x01u) { // F3D G_MTX
            const unsigned param = (w0 >> 16) & 0xffu;
            if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                "F3DGOLDEN G_MTX_CMD: w0=%08x w1=%08x param=%02x depth=%u pc=%06x",
                w0, w1, param, depth, pc);
            const unsigned segment = (w1 >> 24) & 0xffu;
            const uint32_t offset = w1 & 0x00ffffffu;
            const uint64_t matrixAddress64 = segment < 16u
                ? static_cast<uint64_t>(segments[segment]) + offset
                : 8u * 1024u * 1024u;
            if ((w0 & 0xffffu) != 0x0040u || segment >= 16u ||
                matrixAddress64 > 8u * 1024u * 1024u - 64u) {
                if (verbose) __android_log_print(ANDROID_LOG_WARN, TAG,
                    "F3DGOLDEN G_MTX range/length invalido: w0=%08x w1=%08x seg=%u @%06x",
                    w0, w1, segment, pc);
            } else {
                float loadedMatrix[16] = {};
                if (logGoldenEyeMatrix(static_cast<uint32_t>(matrixAddress64),
                                       w0, pc, loadedMatrix)) {
                    const bool isProjection = (param & 0x01u) != 0u;
                    const bool isLoad = (param & 0x02u) != 0u;
                    float* destination = isProjection
                        ? matrixState.projection : matrixState.modelView;
                    bool& destinationValid = isProjection
                        ? matrixState.projectionValid : matrixState.modelViewValid;
                    const bool push = (param & 0x04u) != 0u;
                    if (!isProjection && push && destinationValid) {
                        if (matrixState.modelViewStackDepth <
                            matrixState.modelViewStack.size()) {
                            std::memcpy(
                                matrixState.modelViewStack[matrixState.modelViewStackDepth].data(),
                                matrixState.modelView, sizeof(matrixState.modelView));
                            ++matrixState.modelViewStackDepth;
                        } else {
                            if (verbose) __android_log_print(ANDROID_LOG_WARN, TAG,
                                "F3DGOLDEN G_MTX stack cheia; push ignorado @%06x", pc);
                        }
                    }
                    if (isLoad || !destinationValid) {
                        std::memcpy(destination, loadedMatrix, sizeof(loadedMatrix));
                    } else {
                        float combined[16] = {};
                        multiplyMatrix4x4(loadedMatrix, destination, combined);
                        std::memcpy(destination, combined, sizeof(combined));
                    }
                    destinationValid = true;
                    if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                        "F3DGOLDEN MATRIX_STATE: %s %s push=%u",
                        isProjection ? "PROJECTION" : "MODELVIEW",
                        isLoad ? "LOAD" : "MUL", (param & 0x04u) ? 1u : 0u);
                }
            }
            (void)param;
        } else if (opcode == 0x04u) { // F3D G_VTX (GoldenEye base F3D)
            const unsigned vertexCount = ((w0 >> 20) & 0x0fu) + 1u;
            const unsigned firstVertex = (w0 >> 16) & 0x0fu;
            const unsigned segment = (w1 >> 24) & 0xffu;
            const uint32_t offset = w1 & 0x00ffffffu;
            const uint64_t vertexAddress64 = segment < 16u
                ? static_cast<uint64_t>(segments[segment]) + offset
                : 8u * 1024u * 1024u;
            const bool validRange = segment < 16u &&
                vertexAddress64 < 8u * 1024u * 1024u &&
                vertexCount <= (8u * 1024u * 1024u - vertexAddress64) / 16u &&
                firstVertex + vertexCount <= 32u;
            if (validRange) {
                const uint32_t vertexAddress = static_cast<uint32_t>(vertexAddress64);
                for (unsigned i = 0; i < vertexCount; ++i) {
                    const uint32_t address = vertexAddress + i * 16u;
                    const uint32_t xy = readRdpWord(address);
                    const uint32_t zFlag = readRdpWord(address + 4u);
                    const uint32_t st = readRdpWord(address + 8u);
                    const uint32_t rgba = readRdpWord(address + 12u);
                    GoldenEyeVertex& vertex = vertexCache[firstVertex + i];
                    vertex.x = static_cast<int16_t>(xy >> 16);
                    vertex.y = static_cast<int16_t>(xy & 0xffffu);
                    vertex.z = static_cast<int16_t>(zFlag >> 16);
                    vertex.flag = static_cast<uint16_t>(zFlag);
                    vertex.s = static_cast<int16_t>(st >> 16);
                    vertex.t = static_cast<int16_t>(st);
                    vertex.colorOrNormal[0] = static_cast<uint8_t>(rgba >> 24);
                    vertex.colorOrNormal[1] = static_cast<uint8_t>(rgba >> 16);
                    vertex.colorOrNormal[2] = static_cast<uint8_t>(rgba >> 8);
                    vertex.colorOrNormal[3] = static_cast<uint8_t>(rgba);
                    vertex.lightingEnabled = (matrixState.geometryMode & 0x00020000u) != 0u;
                    vertex.valid = true;
                    vertexCacheMask |= (1u << (firstVertex + i));
                    if (i < 4u && loggedGbiCommands < 100u) {
                        if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                            "F3DGOLDEN VTX_SAMPLE[%u]: slot=%u XYZ=(%d,%d,%d) ST=(%d,%d) flag=%04x lighting=%u attr=%02x,%02x,%02x,%02x",
                            i, firstVertex + i, vertex.x, vertex.y, vertex.z,
                            vertex.s, vertex.t, vertex.flag,
                            vertex.lightingEnabled ? 1u : 0u,
                            vertex.colorOrNormal[0], vertex.colorOrNormal[1],
                            vertex.colorOrNormal[2], vertex.colorOrNormal[3]);
                        ++loggedGbiCommands;
                    }
                }
                const GoldenEyeVertex& first = vertexCache[firstVertex];
                if (matrixState.modelViewValid && loggedGbiCommands < 120u) {
                    float transformed[4] = {};
                    transformGoldenEyeVertex(matrixState.modelView, first, transformed);
                    if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                        "F3DGOLDEN VTX_MODELVIEW: slot=%u XYZ=(%d,%d,%d) -> (%.2f,%.2f,%.2f,%.2f)",
                        firstVertex, first.x, first.y, first.z,
                        transformed[0], transformed[1], transformed[2], transformed[3]);
                    ++loggedGbiCommands;
                }
                const GoldenEyeVertex& firstForLog = vertexCache[firstVertex];
                if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                    "F3DGOLDEN G_VTX: seg=%u offset=%06x endereco=%06x count=%u v0=%u cache=0x%08x V0=(%d,%d,%d) ST=(%d,%d) flag=%04x lighting=%u attr=%02x,%02x,%02x,%02x",
                    segment, offset, vertexAddress, vertexCount, firstVertex,
                    vertexCacheMask, firstForLog.x, firstForLog.y, firstForLog.z,
                    firstForLog.s, firstForLog.t, firstForLog.flag,
                    firstForLog.lightingEnabled ? 1u : 0u,
                    firstForLog.colorOrNormal[0], firstForLog.colorOrNormal[1],
                    firstForLog.colorOrNormal[2], firstForLog.colorOrNormal[3]);
            } else {
                if (verbose) __android_log_print(ANDROID_LOG_WARN, TAG,
                    "F3DGOLDEN G_VTX invalido: seg=%u offset=%06x count=%u v0=%u",
                    segment, offset, vertexCount, firstVertex);
            }
            if (loggedGbiCommands < 100u) {
                if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                    "F3DGOLDEN GBI: G_VTX raw w0=%08x w1=%08x @%06x", w0, w1, pc);
                ++loggedGbiCommands;
            }
        } else if (opcode == 0xBFu) { // F3D/F3DEX G_TRI1; GoldenEye stores indices scaled by 10
            const unsigned rawV0 = (w1 >> 16) & 0xffu;
            const unsigned rawV1 = (w1 >> 8) & 0xffu;
            const unsigned rawV2 = w1 & 0xffu;
            const bool packedIndicesValid =
                (rawV0 % 10u) == 0u && (rawV1 % 10u) == 0u &&
                (rawV2 % 10u) == 0u;
            const unsigned v0 = rawV0 / 10u;
            const unsigned v1 = rawV1 / 10u;
            const unsigned v2 = rawV2 / 10u;
            if (!packedIndicesValid || v0 >= 32u || v1 >= 32u || v2 >= 32u) {
                if (verbose) __android_log_print(ANDROID_LOG_WARN, TAG,
                    "F3DGOLDEN G_TRI1 indices invalidos: raw=%02x,%02x,%02x decoded=%u,%u,%u @%06x",
                    rawV0, rawV1, rawV2, v0, v1, v2, pc);
            } else {
                const uint32_t triangleMask =
                    (1u << v0) | (1u << v1) | (1u << v2);
                const bool verticesReady = vertexCache[v0].valid &&
                    vertexCache[v1].valid && vertexCache[v2].valid &&
                    (vertexCacheMask & triangleMask) == triangleMask;
                if (!verticesReady) {
                    if (verbose) __android_log_print(ANDROID_LOG_WARN, TAG,
                        "F3DGOLDEN G_TRI1[%u]: idx=(%u,%u,%u) vertices_loaded=NO cache=0x%08x",
                        decodedGoldenTriangles, v0, v1, v2, vertexCacheMask);
                } else {
                    const GoldenEyeVertex& a = vertexCache[v0];
                    const GoldenEyeVertex& b = vertexCache[v1];
                    const GoldenEyeVertex& c = vertexCache[v2];
                    if (verbose)
                        logGoldenEyeTriangleColors("G_TRI1", decodedGoldenTriangles,
                            v0, v1, v2, a, b, c, matrixState.geometryMode, matrixState);

                    if (matrixState.modelViewValid && matrixState.projectionValid) {
                        logGoldenEyeActiveMatrixState(matrixState, decodedGoldenTriangles, pc);
                        const GoldenEyeVertex* cv[3] = {&a,&b,&c};
                        float clip[3][4] = {};
                        bool valid = true;
                        for (unsigned vi=0; vi<3u; ++vi) {
                            float mv[4] = {};
                            transformGoldenEyeVertex(matrixState.modelView,*cv[vi],mv);
                            transformGoldenEyeVector4(matrixState.projection,mv,clip[vi]);
                            if (clip[vi][3] <= 0.00001f) valid=false;
                        }
                        if (valid) {
                            const float ax=clip[0][0]/clip[0][3], ay=clip[0][1]/clip[0][3], az=clip[0][2]/clip[0][3];
                            const float bx=clip[1][0]/clip[1][3], by=clip[1][1]/clip[1][3], bz=clip[1][2]/clip[1][3];
                            const float cx=clip[2][0]/clip[2][3], cy=clip[2][1]/clip[2][3], cz=clip[2][2]/clip[2][3];
                            g_goldenEyeDebugTriangles.push_back({ax,ay,az,bx,by,bz,cx,cy,cz});
                            appendGoldenEyeRenderVertex(ax,ay,az,a,matrixState);
                            appendGoldenEyeRenderVertex(bx,by,bz,b,matrixState);
                            appendGoldenEyeRenderVertex(cx,cy,cz,c,matrixState);
                        }
                    }
                    if (decodedGoldenTriangles < 32u) {
                        if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                            "F3DGOLDEN G_TRI1[%u]: raw=(%02x,%02x,%02x) idx=(%u,%u,%u) "
                            "A=(%d,%d,%d) B=(%d,%d,%d) C=(%d,%d,%d)",
                            decodedGoldenTriangles, rawV0, rawV1, rawV2,
                            v0, v1, v2, a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z);
                    }
                    if (decodedGoldenTriangles < 12u &&
                        matrixState.modelViewValid && matrixState.projectionValid) {
                        const GoldenEyeVertex* vertices[3] = {&a, &b, &c};
                        float mv[3][4] = {};
                        float clip[3][4] = {};
                        for (unsigned vertex = 0; vertex < 3u; ++vertex) {
                            transformGoldenEyeVertex(
                                matrixState.modelView, *vertices[vertex], mv[vertex]);
                            transformGoldenEyeVector4(
                                matrixState.projection, mv[vertex], clip[vertex]);
                        }
                        if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                            "F3DGOLDEN TRI1_PROJECTED[%u]: "
                            "Aclip=(%.2f,%.2f,%.2f,%.2f) "
                            "Bclip=(%.2f,%.2f,%.2f,%.2f) "
                            "Cclip=(%.2f,%.2f,%.2f,%.2f)",
                            decodedGoldenTriangles,
                            clip[0][0], clip[0][1], clip[0][2], clip[0][3],
                            clip[1][0], clip[1][1], clip[1][2], clip[1][3],
                            clip[2][0], clip[2][1], clip[2][2], clip[2][3]);
                        if (std::abs(clip[0][3]) > 0.00001f &&
                            std::abs(clip[1][3]) > 0.00001f &&
                            std::abs(clip[2][3]) > 0.00001f) {
                            if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                                "F3DGOLDEN TRI1_NDC[%u]: "
                                "A=(%.5f,%.5f,%.5f) "
                                "B=(%.5f,%.5f,%.5f) "
                                "C=(%.5f,%.5f,%.5f)",
                                decodedGoldenTriangles,
                                clip[0][0] / clip[0][3],
                                clip[0][1] / clip[0][3],
                                clip[0][2] / clip[0][3],
                                clip[1][0] / clip[1][3],
                                clip[1][1] / clip[1][3],
                                clip[1][2] / clip[1][3],
                                clip[2][0] / clip[2][3],
                                clip[2][1] / clip[2][3],
                                clip[2][2] / clip[2][3]);
                        } else {
                            if (verbose) __android_log_print(ANDROID_LOG_WARN, TAG,
                                "F3DGOLDEN TRI1_NDC[%u]: W invalido A=%.6f B=%.6f C=%.6f",
                                decodedGoldenTriangles,
                                clip[0][3], clip[1][3], clip[2][3]);
                        }
                    } else if (decodedGoldenTriangles < 12u &&
                               matrixState.modelViewValid &&
                               !matrixState.projectionValid) {
                        if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                            "F3DGOLDEN TRI1_MODELVIEW[%u]: projection=NOT_LOADED",
                            decodedGoldenTriangles);
                    }
                    ++decodedGoldenTriangles;
                }
            }
        } else if (opcode == 0xB1u) { // F3DGOLDEN G_TRIX: packed nibble stream
            // GLideN64 F3DGOLDEN.cpp (F3D_TriX) decodes two indices from
            // the low nibbles of w1 and the third from the low nibble of w0.
            // It shifts w1 by 8 and w0 by 4 after each triangle, stopping
            // when w1 becomes zero. This is NOT the generic TRI4 interleave.
            uint32_t triW0 = w0;
            uint32_t triW1 = w1;
            unsigned triangleInCommand = 0u;
            while (triW1 != 0u && triangleInCommand < 4u) {
                const unsigned v0 = triW1 & 0x0fu;
                triW1 >>= 4u;
                const unsigned v1 = triW1 & 0x0fu;
                triW1 >>= 4u;
                const unsigned v2 = triW0 & 0x0fu;
                triW0 >>= 4u;

                const uint32_t triangleMask =
                    (1u << v0) | (1u << v1) | (1u << v2);
                const bool verticesReady = vertexCache[v0].valid &&
                    vertexCache[v1].valid && vertexCache[v2].valid &&
                    (vertexCacheMask & triangleMask) == triangleMask;
                if (!verticesReady) {
                    if (verbose) __android_log_print(ANDROID_LOG_WARN, TAG,
                        "F3DGOLDEN G_TRIX[%u]: (%u,%u,%u) vertices_loaded=NO cache=0x%08x",
                        triangleInCommand, v0, v1, v2, vertexCacheMask);
                } else {
                    const GoldenEyeVertex& a = vertexCache[v0];
                    const GoldenEyeVertex& b = vertexCache[v1];
                    const GoldenEyeVertex& c = vertexCache[v2];
                    if (verbose)
                        logGoldenEyeTriangleColors("G_TRIX", decodedGoldenTriangles,
                            v0, v1, v2, a, b, c, matrixState.geometryMode,
                            matrixState);
                    if (decodedGoldenTriangles < 32u) {
                        if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                            "F3DGOLDEN TRIANGLE[%u]: idx=(%u,%u,%u) A=(%d,%d,%d) B=(%d,%d,%d) C=(%d,%d,%d)",
                            decodedGoldenTriangles, v0, v1, v2,
                            a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z);
                    }
                    if (matrixState.modelViewValid && matrixState.projectionValid) {
                        const GoldenEyeVertex* triVertices[3] = {&a, &b, &c};
                        float ndc[3][3] = {};
                        bool clipValid = true;
                        for (unsigned vi = 0; vi < 3u; ++vi) {
                            float mv[4] = {};
                            float clip[4] = {};
                            transformGoldenEyeVertex(
                                matrixState.modelView, *triVertices[vi], mv);
                            transformGoldenEyeVector4(
                                matrixState.projection, mv, clip);
                            if (clip[3] <= 0.00001f) {
                                clipValid = false;
                                break;
                            }
                            ndc[vi][0] = clip[0] / clip[3];
                            ndc[vi][1] = clip[1] / clip[3];
                            ndc[vi][2] = clip[2] / clip[3];
                        }
                        if (clipValid) {
                            g_goldenEyeDebugTriangles.push_back({
                                ndc[0][0], ndc[0][1], ndc[0][2],
                                ndc[1][0], ndc[1][1], ndc[1][2],
                                ndc[2][0], ndc[2][1], ndc[2][2]});
                            appendGoldenEyeRenderVertex(ndc[0][0],ndc[0][1],ndc[0][2],a,matrixState);
                            appendGoldenEyeRenderVertex(ndc[1][0],ndc[1][1],ndc[1][2],b,matrixState);
                            appendGoldenEyeRenderVertex(ndc[2][0],ndc[2][1],ndc[2][2],c,matrixState);
                        }
                    }
                    if (decodedGoldenTriangles < 12u &&
                        matrixState.modelViewValid) {
                        const GoldenEyeVertex* vertices[3] = {&a, &b, &c};
                        float mv[3][4] = {};
                        float clip[3][4] = {};
                        for (unsigned vertex = 0; vertex < 3u; ++vertex) {
                            transformGoldenEyeVertex(
                                matrixState.modelView, *vertices[vertex], mv[vertex]);
                            if (matrixState.projectionValid) {
                                transformGoldenEyeVector4(
                                    matrixState.projection, mv[vertex], clip[vertex]);
                            }
                        }
                        if (matrixState.projectionValid) {
                            if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                                "F3DGOLDEN TRI_PROJECTED[%u]: "
                                "Aclip=(%.2f,%.2f,%.2f,%.2f) "
                                "Bclip=(%.2f,%.2f,%.2f,%.2f) "
                                "Cclip=(%.2f,%.2f,%.2f,%.2f)",
                                decodedGoldenTriangles,
                                clip[0][0], clip[0][1], clip[0][2], clip[0][3],
                                clip[1][0], clip[1][1], clip[1][2], clip[1][3],
                                clip[2][0], clip[2][1], clip[2][2], clip[2][3]);
                            if (std::abs(clip[0][3]) > 0.00001f &&
                                std::abs(clip[1][3]) > 0.00001f &&
                                std::abs(clip[2][3]) > 0.00001f) {
                                if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                                    "F3DGOLDEN TRI_NDC[%u]: "
                                    "A=(%.5f,%.5f,%.5f) "
                                    "B=(%.5f,%.5f,%.5f) "
                                    "C=(%.5f,%.5f,%.5f)",
                                    decodedGoldenTriangles,
                                    clip[0][0] / clip[0][3],
                                    clip[0][1] / clip[0][3],
                                    clip[0][2] / clip[0][3],
                                    clip[1][0] / clip[1][3],
                                    clip[1][1] / clip[1][3],
                                    clip[1][2] / clip[1][3],
                                    clip[2][0] / clip[2][3],
                                    clip[2][1] / clip[2][3],
                                    clip[2][2] / clip[2][3]);
                            } else {
                                if (verbose) __android_log_print(ANDROID_LOG_WARN, TAG,
                                    "F3DGOLDEN TRI_NDC[%u]: W invalido A=%.6f B=%.6f C=%.6f",
                                    decodedGoldenTriangles,
                                    clip[0][3], clip[1][3], clip[2][3]);
                            }
                        } else {
                            if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                                "F3DGOLDEN TRI_MODELVIEW[%u]: "
                                "A=(%.2f,%.2f,%.2f,%.2f) "
                                "B=(%.2f,%.2f,%.2f,%.2f) "
                                "C=(%.2f,%.2f,%.2f,%.2f) projection=NOT_LOADED",
                                decodedGoldenTriangles,
                                mv[0][0], mv[0][1], mv[0][2], mv[0][3],
                                mv[1][0], mv[1][1], mv[1][2], mv[1][3],
                                mv[2][0], mv[2][1], mv[2][2], mv[2][3]);
                        }
                    }
                }
                ++decodedGoldenTriangles;
                ++triangleInCommand;
            }
            if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                "F3DGOLDEN G_TRIX_CMD: w0=%08x w1=%08x triangles=%u remaining_w1=%08x @%06x",
                w0, w1, triangleInCommand, triW1, pc);
        } else if (opcode >= 0xC8u) {
            // Raw RDP commands may be embedded in a GBI list. Count them,
            // but do not enqueue them until the RSP geometry path is implemented.
            ++rawRdpCommands;
            if (rawRdpCommands <= 12u) {
                if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                    "F3DGOLDEN GBI: comando RDP bruto opcode=%02x @%06x",
                    opcode, pc);
            }
        } else if (loggedGbiCommands < 100u) {
            // Capture unknown/non-RDP F3D commands (matrix, vertex, triangles,
            // texture and GoldenEye extensions) to map the real geometry path.
            if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                "F3DGOLDEN GBI: GBI opcode=%02x w0=%08x w1=%08x @%06x depth=%u",
                opcode, w0, w1, pc, depth);
            ++loggedGbiCommands;
        }

        pc += 8u;
    }

    if (verbose) __android_log_print(ANDROID_LOG_WARN, TAG,
        "F3DGOLDEN GBI: limite de comandos atingido start=%06x depth=%u",
        start, depth);
    return false;
}


[[maybe_unused]] void presentGoldenEyeDebugGeometry() {
    constexpr uint32_t width = 320u, height = 240u;
    if (g_goldenEyeDebugTriangles.empty()) return;
    g_goldenEyeDebugPixels.assign(static_cast<size_t>(width) * height * 4u, 0u);
    std::vector<float> depth(static_cast<size_t>(width) * height, 1.0f);
    for (size_t i = 0; i < static_cast<size_t>(width) * height; ++i) {
        g_goldenEyeDebugPixels[i * 4u + 0u] = 8u;
        g_goldenEyeDebugPixels[i * 4u + 1u] = 12u;
        g_goldenEyeDebugPixels[i * 4u + 2u] = 20u;
        g_goldenEyeDebugPixels[i * 4u + 3u] = 255u;
    }
    auto edge = [](float ax, float ay, float bx, float by, float px, float py) {
        return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
    };
    unsigned drawn = 0;
    for (const auto& tri : g_goldenEyeDebugTriangles) {
        const float x0 = (tri[0] * 0.5f + 0.5f) * (width - 1u);
        const float y0 = (0.5f - tri[1] * 0.5f) * (height - 1u);
        const float x1 = (tri[3] * 0.5f + 0.5f) * (width - 1u);
        const float y1 = (0.5f - tri[4] * 0.5f) * (height - 1u);
        const float x2 = (tri[6] * 0.5f + 0.5f) * (width - 1u);
        const float y2 = (0.5f - tri[7] * 0.5f) * (height - 1u);
        const float area = edge(x0, y0, x1, y1, x2, y2);
        if (std::abs(area) < 0.0001f) continue;
        const int minX = std::max(0, static_cast<int>(std::floor(std::min({x0,x1,x2}))));
        const int maxX = std::min(static_cast<int>(width)-1, static_cast<int>(std::ceil(std::max({x0,x1,x2}))));
        const int minY = std::max(0, static_cast<int>(std::floor(std::min({y0,y1,y2}))));
        const int maxY = std::min(static_cast<int>(height)-1, static_cast<int>(std::ceil(std::max({y0,y1,y2}))));
        if (minX > maxX || minY > maxY) continue;
        for (int y=minY; y<=maxY; ++y) for (int x=minX; x<=maxX; ++x) {
            const float px=x+0.5f, py=y+0.5f;
            const float w0=edge(x1,y1,x2,y2,px,py)/area;
            const float w1=edge(x2,y2,x0,y0,px,py)/area;
            const float w2=1.0f-w0-w1;
            if (w0<0.0f || w1<0.0f || w2<0.0f) continue;
            const float z=w0*tri[2]+w1*tri[5]+w2*tri[8];
            const size_t p=static_cast<size_t>(y)*width+static_cast<unsigned>(x);
            if (z<0.0f || z>1.0f || z>depth[p]) continue;
            depth[p]=z;
            g_goldenEyeDebugPixels[p*4u+0u]=235u;
            g_goldenEyeDebugPixels[p*4u+1u]=242u;
            g_goldenEyeDebugPixels[p*4u+2u]=255u;
            g_goldenEyeDebugPixels[p*4u+3u]=255u;
        }
        ++drawn;
    }
    N64VideoFrame frame{};
    frame.pixels=g_goldenEyeDebugPixels.data();
    frame.width=width; frame.height=height; frame.stride_bytes=width*4u;
    frame.pixel_format=N64_VIDEO_PIXEL_FORMAT_RGBA8888;
    frame.frame_number=++g_frameNumber;
    n64VulkanVideoFrameCallback(&frame,nullptr);
    g_hasGoldenEyeDebugFrame = true;
    __android_log_print(ANDROID_LOG_INFO,TAG,
        "F3DGOLDEN DEBUG_GEOMETRY: triangulos=%zu rasterizados=%u frame=%ux%u",
        g_goldenEyeDebugTriangles.size(),drawn,width,height);
}

// Re-submit the last valid diagnostic image on every display-list callback.
// The GBI walker is intentionally sampled for logging, but presentation must
// not be sampled: otherwise a single frame flashes and is replaced by blank.
[[maybe_unused]] void replayGoldenEyeDebugFrame() {
    constexpr uint32_t width = 320u, height = 240u;
    if (!g_hasGoldenEyeDebugFrame ||
        g_goldenEyeDebugPixels.size() != static_cast<size_t>(width) * height * 4u)
        return;
    N64VideoFrame frame{};
    frame.pixels = g_goldenEyeDebugPixels.data();
    frame.width = width;
    frame.height = height;
    frame.stride_bytes = width * 4u;
    frame.pixel_format = N64_VIDEO_PIXEL_FORMAT_RGBA8888;
    frame.frame_number = ++g_frameNumber;
    n64VulkanVideoFrameCallback(&frame, nullptr);
}

void processF3DDisplayList(bool verbose) {
    if (!g_info.DMEM || !g_info.RDRAM) return;
    // Clear the previous task's packet first. Non-GoldenEye tasks and invalid
    // payloads must never leave stale geometry queued for a future renderer.
    n64VulkanSubmitGoldenEyeGeometry(nullptr, 0u, g_processDListCalls);
    uint32_t task[16] = {};
    for (unsigned i = 0; i < 16; ++i)
        task[i] = readN64MemoryWord(g_info.DMEM, 0xFC0u + i * 4u);

    const uint32_t ucodeDataAddress = task[6] & 0x00ffffffu;
    const uint32_t ucodeDataSize = std::min<uint32_t>(task[7], 0x1000u);
    if (ucodeDataAddress >= 8u * 1024u * 1024u ||
        ucodeDataSize > 8u * 1024u * 1024u - ucodeDataAddress ||
        crc32RdramBytes(ucodeDataAddress, ucodeDataSize) != 0x2a9b4b97u)
        return; // This diagnostic walker is intentionally limited to F3DGOLDEN.

    const uint32_t dataPtr = task[12] & 0x00ffffffu;
    const uint32_t dataSize = task[13];
    if (dataSize < 8u || dataSize > 0x100000u ||
        dataPtr >= 8u * 1024u * 1024u ||
        dataSize > 8u * 1024u * 1024u - dataPtr) {
        if (verbose) __android_log_print(ANDROID_LOG_WARN, TAG,
            "F3DGOLDEN GBI: payload raiz invalido ptr=%06x size=%08x",
            dataPtr, dataSize);
        return;
    }

    // OSTask owns this interpreter state; nested G_DL calls share it,
    // but a new graphics task starts with a fresh GBI state.
    // Preserve Fast3D segment bases, vertex cache and matrix stack across OSTasks.
    GoldenEyeGbiState& taskState = g_goldenEyeGbiState;
    g_goldenEyeDebugTriangles.clear();
    g_goldenEyeRenderVertices.clear();
    uint32_t budget = 0;
    uint32_t calls = 0;
    uint32_t rawRdpCommands = 0;
    uint32_t opcodeCounts[256] = {};
    uint32_t loggedGbiCommands = 0;
    uint32_t decodedGoldenTriangles = 0;
    const bool complete = walkF3DDisplayList(
        dataPtr, dataPtr + dataSize, taskState.segments.data(), 0u,
        budget, calls, rawRdpCommands, opcodeCounts, loggedGbiCommands,
        taskState.vertexCacheMask, decodedGoldenTriangles,
        taskState.vertexCache.data(), taskState.matrices, verbose);
    if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
        "F3DGOLDEN GBI traversal: %s comandos=%u listas/chamadas=%u RDP_brutos=%u GBI_detalhados=%u G_TRIX_triangulos=%u vertex_cache=0x%08x",
        complete ? "fim/retorno alcancado" : "incompleto/limite",
        budget, calls, rawRdpCommands, loggedGbiCommands,
        decodedGoldenTriangles, taskState.vertexCacheMask);
    // Emit the complete opcode histogram in compact chunks so logcat
    // cannot make a multi-line summary look as if only one opcode existed.
    char histogram[768] = {};
    size_t used = 0;
    for (unsigned opcode = 0; opcode < 256u; ++opcode) {
        if (opcodeCounts[opcode] == 0u) continue;
        const int written = std::snprintf(histogram + used, sizeof(histogram) - used,
            "%s%02x:%u", used == 0u ? "" : " ", opcode, opcodeCounts[opcode]);
        if (written < 0) break;
        if (static_cast<size_t>(written) >= sizeof(histogram) - used) {
            if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
                "F3DGOLDEN OPCODE_HIST: %s", histogram);
            used = 0;
            histogram[0] = '\0';
            const int retry = std::snprintf(histogram, sizeof(histogram),
                "%02x:%u", opcode, opcodeCounts[opcode]);
            if (retry < 0 || static_cast<size_t>(retry) >= sizeof(histogram)) break;
            used = static_cast<size_t>(retry);
        } else {
            used += static_cast<size_t>(written);
        }
    }
    if (used != 0u) {
        if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
            "F3DGOLDEN OPCODE_HIST: %s", histogram);
    }
    if (verbose) __android_log_print(ANDROID_LOG_INFO, TAG,
        "F3DGOLDEN OPCODE_KEY: BF_G_TRI1=%u B1_G_TRIX=%u 04_G_VTX=%u 06_G_DL=%u 01_G_MTX=%u BA=%u B8_ENDDL=%u",
        opcodeCounts[0xbfu], opcodeCounts[0xb1u], opcodeCounts[0x04u], opcodeCounts[0x06u],
        opcodeCounts[0x01u], opcodeCounts[0xbau], opcodeCounts[0xb8u]);
    // Transfer NDC positions and decoded per-vertex RGBA attributes.
    const uint32_t geometryVertexCount =
        static_cast<uint32_t>(g_goldenEyeRenderVertices.size() / 7u);
    if (!n64VulkanSubmitGoldenEyeGeometry(
            g_goldenEyeRenderVertices.empty() ? nullptr : g_goldenEyeRenderVertices.data(),
            geometryVertexCount, g_processDListCalls)) {
        if (verbose) __android_log_print(ANDROID_LOG_WARN, TAG,
            "F3DGOLDEN: falha ao transferir geometria para o modulo Vulkan");
    }
    // Keep collecting diagnostic triangles, but do not present the CPU debug
    // rasterizer: its white fill is not a real GoldenEye frame and can mask RDP output.
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
    case 0x25: return 4;   // Texture rectangle: 2 x 64-bit words (paraLLEl cmd_len_lut)
    default: return 2;
    }
}


bool collectGoldenEyeRdpCommands(
        uint32_t start, uint32_t limit, uint32_t segments[16],
        unsigned depth, uint32_t& budget,
        std::vector<std::vector<uint32_t>>& commands) {
    constexpr uint32_t rdramLimit = 8u * 1024u * 1024u;
    if (depth > 10u || start >= rdramLimit || (start & 7u) != 0u)
        return false;

    uint32_t pc = start;
    while (budget < 8192u) {
        if (pc > rdramLimit - 8u || limit < 8u || pc > limit - 8u)
            return false;

        const uint32_t w0 = readRdpWord(pc);
        const uint32_t w1 = readRdpWord(pc + 4u);
        const uint32_t opcode = w0 >> 24;
        ++budget;

        if (opcode == 0xB8u)
            return true;

        if (opcode == 0xBCu || opcode == 0xBDu) {
            if ((w0 & 0xffu) == 0x06u) {
                const unsigned segment = (w0 >> 10) & 0x0fu;
                segments[segment] = w1 & 0x00ffffffu;
            }
        } else if (opcode == 0x06u) {
            const unsigned mode = (w0 >> 16) & 0xffu;
            const unsigned segment = (w1 >> 24) & 0xffu;
            const uint32_t offset = w1 & 0x00ffffffu;
            if (segment >= 16u) return false;
            const uint64_t target64 =
                static_cast<uint64_t>(segments[segment]) + offset;
            if (target64 >= rdramLimit || (target64 & 7u) != 0u)
                return false;
            const uint32_t target = static_cast<uint32_t>(target64);
            if (mode == 0u) {
                if (!collectGoldenEyeRdpCommands(
                        target, rdramLimit, segments, depth + 1u,
                        budget, commands))
                    return false;
            } else if (mode == 1u) {
                // G_DL_NOPUSH is a tail branch: continue at the target even
                // when it lives outside the root OSTask payload range.
                pc = target;
                limit = rdramLimit;
                continue;
            } else {
                return false;
            }
        } else if (opcode == 0xE4u || opcode == 0xE5u) {
            // GoldenEye sends texture-rectangle attributes through the
            // following G_RDPHALF_1 (B4) and G_RDPHALF_2 (B3) GBI commands.
            // paraLLEl-RDP expects a four-word RDP packet:
            //   E4/E5.w0, E4/E5.w1, B4.w1, B3.w1
            // Do not copy the B4/B3 opcode headers into the RDP payload.
            constexpr unsigned halfCommandBytes = 8u;
            constexpr unsigned packetBytes = 3u * halfCommandBytes;
            if (static_cast<uint64_t>(pc) + packetBytes > rdramLimit ||
                static_cast<uint64_t>(pc) + packetBytes > limit)
                return false;

            const uint32_t half1w0 = readRdpWord(pc + 8u);
            const uint32_t half1w1 = readRdpWord(pc + 12u);
            const uint32_t half2w0 = readRdpWord(pc + 16u);
            const uint32_t half2w1 = readRdpWord(pc + 20u);
            if ((half1w0 >> 24) != 0xB4u || (half2w0 >> 24) != 0xB3u) {
                __android_log_print(ANDROID_LOG_WARN, TAG,
                    "GoldenEye TEXRECT sem RDPHALF esperado: pc=%06x "
                    "next=%08x/%08x", pc, half1w0, half2w0);
                return false;
            }

            std::vector<uint32_t> command(4u);
            command[0] = w0;
            command[1] = w1;
            command[2] = half1w1;
            command[3] = half2w1;
            commands.emplace_back(std::move(command));
            pc += packetBytes;
            continue;
        } else if ((opcode >= 0xC8u && opcode <= 0xCFu) ||
                   opcode >= 0xE6u) {
            // Actual RDP opcodes are C8-CF (triangles) and E6-FF (state,
            // sync, rectangles and other raster commands). The D0-DF range
            // belongs to Fast3D/GoldenEye GBI; forwarding commands such as
            // G_TEXTURE (D7) to paraLLEl-RDP corrupts the RDP command stream.
            // Triangle commands carry extra payload words, so capture each
            // accepted RDP packet at its full hardware-defined length.
            const unsigned wordCount = rdpCommandWordCount(opcode);
            const uint64_t commandEnd =
                static_cast<uint64_t>(pc) + wordCount * sizeof(uint32_t);
            if (wordCount < 2u || wordCount > 44u ||
                commandEnd > rdramLimit || commandEnd > limit)
                return false;

            std::vector<uint32_t> command(wordCount);
            for (unsigned i = 0; i < wordCount; ++i)
                command[i] = readRdpWord(pc + i * sizeof(uint32_t));
            commands.emplace_back(std::move(command));
            pc += wordCount * sizeof(uint32_t);
            continue;
        }

        pc += 8u;
    }
    return false;
}

void logRdpFramebufferState(const char* source,
                             const std::vector<std::vector<uint32_t>>& commands,
                             uint64_t call) {
    RdpCommandStats& stats = g_rdpCommandStats;
    ++stats.batches;
    for (const auto& command : commands) {
        if (command.empty()) continue;
        const uint32_t opcode = command[0] >> 24;
        const uint32_t w0 = command[0];
        const uint32_t w1 = command.size() > 1u ? command[1] : 0u;
        ++stats.commands;
        stats.lastOpcode = opcode;
        switch (opcode) {
        case 0xFFu: // SetColorImage
            ++stats.setColorImage;
            stats.lastColorW0 = w0;
            stats.lastColorW1 = w1;
            break;
        case 0xFDu: // SetTextureImage
            ++stats.setTextureImage;
            stats.lastTextureW0 = w0;
            stats.lastTextureW1 = w1;
            break;
        case 0xFEu: ++stats.setDepthImage; break;
        case 0xF6u: ++stats.fillRect; break;
        case 0xE4u:
        case 0xE5u: ++stats.textureRect; break;
        case 0xC8u: case 0xC9u: case 0xCAu: case 0xCBu:
        case 0xCCu: case 0xCDu: case 0xCEu: case 0xCFu:
            ++stats.triangles;
            break;
        case 0xE6u: case 0xE7u: case 0xE8u: case 0xE9u:
            ++stats.sync;
            break;
        default: break;
        }
    }

    if (call > 12u && (call % 120u) != 0u) return;
    const uint32_t viStatus = g_info.VI_STATUS_REG ? *g_info.VI_STATUS_REG : 0u;
    const uint32_t viOrigin = g_info.VI_ORIGIN_REG ? *g_info.VI_ORIGIN_REG : 0u;
    const uint32_t viWidth = g_info.VI_WIDTH_REG ? *g_info.VI_WIDTH_REG : 0u;
    const uint32_t colorW0 = stats.lastColorW0;
    const uint32_t colorW1 = stats.lastColorW1;
    const uint32_t colorAddress = colorW1 & 0x00ffffffu;
    const uint32_t viAddress = viOrigin & 0x00ffffffu;
    // SetColorImage size: 0=4bpp, 1=8bpp, 2=16bpp, 3=32bpp.
    // Twice-the-byte count avoids truncating the 4bpp case.
    const uint32_t colorSize = (colorW0 >> 19u) & 3u;
    const uint32_t bytesPerPixelTimesTwo = 1u << colorSize;
    const uint32_t colorWidth = (colorW0 & 1023u) + 1u;
    const uint32_t exactFramebufferBytes =
        (colorWidth * 240u * bytesPerPixelTimesTwo) / 2u;
    const uint32_t probeBytes = std::min<uint32_t>(
        exactFramebufferBytes, 256u * 1024u);
    auto logMemoryWindow = [&](const char* label, uint32_t address) {
        if (address >= 8u * 1024u * 1024u ||
            probeBytes > 8u * 1024u * 1024u - address) {
            __android_log_print(ANDROID_LOG_WARN, TAG,
                "%s RDRAM probe invalido: addr=%06x bytes=%u",
                label, address, probeBytes);
            return;
        }
        uint32_t nonZero = 0;
        uint32_t firstNonZero = probeBytes;
        uint32_t lastNonZero = 0;
        uint8_t minByte = 255u;
        uint8_t maxByte = 0u;
        uint32_t hash = 2166136261u;
        for (uint32_t i = 0; i < probeBytes; ++i) {
            const uint8_t value = rdramByte(address + i);
            if (value != 0u) {
                if (firstNonZero == probeBytes) firstNonZero = i;
                lastNonZero = i;
                ++nonZero;
            }
            minByte = std::min(minByte, value);
            maxByte = std::max(maxByte, value);
            hash = (hash ^ value) * 16777619u;
        }
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "%s RDRAM probe: addr=%06x bytes=%u nonZero=%u firstNZ=%u "
            "lastNZ=%u min=%u max=%u hash=%08x first=%08x/%08x/%08x/%08x",
            label, address, probeBytes, nonZero, firstNonZero, lastNonZero,
            minByte, maxByte, hash, readRdpWord(address),
            readRdpWord(address + 4u), readRdpWord(address + 8u),
            readRdpWord(address + 12u));
    };
    logMemoryWindow("RDP_COLOR", colorAddress);
    if (viAddress != colorAddress)
        logMemoryWindow("VI_ORIGIN", viAddress);
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "%s framebuffer cumulative: call=%llu batches=%llu commands=%llu "
        "FF=%llu FD=%llu FE=%llu F6=%llu TEXRECT=%llu TRI=%llu SYNC=%llu "
        "lastOpcode=%02x lastFF=%08x/%08x fmt=%u size=%u width=%u addr=%06x "
        "lastFD=%08x/%08x VI_STATUS=%08x VI_ORIGIN=%06x VI_WIDTH=%u",
        source, static_cast<unsigned long long>(call),
        static_cast<unsigned long long>(stats.batches),
        static_cast<unsigned long long>(stats.commands),
        static_cast<unsigned long long>(stats.setColorImage),
        static_cast<unsigned long long>(stats.setTextureImage),
        static_cast<unsigned long long>(stats.setDepthImage),
        static_cast<unsigned long long>(stats.fillRect),
        static_cast<unsigned long long>(stats.textureRect),
        static_cast<unsigned long long>(stats.triangles),
        static_cast<unsigned long long>(stats.sync), stats.lastOpcode,
        colorW0, colorW1, (colorW0 >> 21) & 7u, (colorW0 >> 19) & 3u,
        (colorW0 & 1023u) + 1u, colorW1 & 0x00ffffffu,
        stats.lastTextureW0, stats.lastTextureW1,
        viStatus, viOrigin & 0x00ffffffu, viWidth & 0xfffu);
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
    if (!g_rdp || !g_info.RDRAM || !g_info.DPC_START_REG ||
        !g_info.DPC_CURRENT_REG || !g_info.DPC_END_REG)
        return false;

    // CXD4's MT_CMD_END calls ProcessRDPList repeatedly as DPC_END grows.
    // DPC_CURRENT is the unconsumed cursor; DPC_START is advanced alongside
    // it after each complete batch, matching established Mupen64Plus GFX
    // plugin behavior. Reading DPC_START here would replay old commands.
    const uint32_t start = *g_info.DPC_CURRENT_REG & 0x00fffff8u;
    const uint32_t end = *g_info.DPC_END_REG & 0x00fffff8u;
    if (end <= start || end > 8u * 1024u * 1024u) {
        if (g_processRdpCalls <= 5)
            __android_log_print(ANDROID_LOG_WARN, TAG,
                "RDP lista vazia/invalida: CURRENT=0x%06x END=0x%06x",
                start, end);
        return false;
    }

    uint32_t cursor = start;
    unsigned commands = 0;
    bool incompleteTail = false;
    std::vector<std::vector<uint32_t>> capturedCommands;
    while (cursor < end && commands < 65536u) {
        if (end - cursor < 8u) {
            incompleteTail = true;
            __android_log_print(ANDROID_LOG_WARN, TAG,
                "RDP aguardando palavras do comando: endereco=0x%06x bytes=%u END=0x%06x",
                cursor, end - cursor, end);
            break;
        }

        uint32_t words[44] = {};
        words[0] = readRdpWord(cursor);
        const unsigned count = rdpCommandWordCount(words[0] >> 24);
        const uint64_t commandEnd = static_cast<uint64_t>(cursor) +
                                    static_cast<uint64_t>(count) * 4u;
        if (commandEnd > end) {
            incompleteTail = true;
            __android_log_print(ANDROID_LOG_WARN, TAG,
                "RDP comando parcial: opcode=0x%02x endereco=0x%06x palavras=%u disponiveis=%u END=0x%06x; aguardando proxima chamada",
                (words[0] >> 24) & 0x3fu, cursor, count,
                (end - cursor) / 4u, end);
            break;
        }
        if (commandEnd > 8u * 1024u * 1024u) {
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                "RDP comando fora da RDRAM: endereco=0x%06x palavras=%u",
                cursor, count);
            break;
        }
        for (unsigned i = 1; i < count; ++i)
            words[i] = readRdpWord(cursor + i * 4u);

        g_rdp->enqueue_command(count, words);
        capturedCommands.emplace_back(words, words + count);
        cursor += count * 4u;
        ++commands;
    }

    if (commands == 65536u && cursor < end) {
        incompleteTail = true;
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "RDP lote limitado: cursor=0x%06x END=0x%06x; restante sera processado na proxima chamada",
            cursor, end);
    }

    // Do not consume an incomplete command. The next CXD4 callback will
    // resume from this exact address after DPC_END exposes more RDRAM bytes.
    // Complete commands before it are safe to commit and will not be replayed.
    if (commands == 0u)
        return false;

    *g_info.DPC_CURRENT_REG = cursor;
    *g_info.DPC_START_REG = cursor;
    if (incompleteTail)
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "RDP progresso parcial: comandos=%u consumidos_ate=0x%06x pendente_ate=0x%06x",
            commands, cursor, end);

    setRdpViRegisters();
    logRdpFramebufferState("DPC", capturedCommands, g_processRdpCalls);
    if (g_processRdpCalls <= 5u || (g_processRdpCalls % 120u) == 0u) {
        const uint32_t viStatus = g_info.VI_STATUS_REG ? *g_info.VI_STATUS_REG : 0u;
        const uint32_t viOrigin = g_info.VI_ORIGIN_REG ? *g_info.VI_ORIGIN_REG : 0u;
        const uint32_t viWidth = g_info.VI_WIDTH_REG ? *g_info.VI_WIDTH_REG : 0u;
        const uint32_t viHStart = g_info.VI_H_START_REG ? *g_info.VI_H_START_REG : 0u;
        const uint32_t viVStart = g_info.VI_V_START_REG ? *g_info.VI_V_START_REG : 0u;
        const uint32_t viXScale = g_info.VI_X_SCALE_REG ? *g_info.VI_X_SCALE_REG : 0u;
        const uint32_t viYScale = g_info.VI_Y_SCALE_REG ? *g_info.VI_Y_SCALE_REG : 0u;
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "DPC VI input: call=%llu status=%08x origin=%08x width=%08x "
            "h_start=%08x v_start=%08x x_scale=%08x y_scale=%08x",
            static_cast<unsigned long long>(g_processRdpCalls),
            viStatus, viOrigin, viWidth, viHStart, viVStart, viXScale, viYScale);
        if (viOrigin == 0u || (viWidth & 0xfffu) == 0u ||
            (viXScale & 0xfffu) == 0u || (viYScale & 0xfffu) == 0u) {
            __android_log_print(ANDROID_LOG_WARN, TAG,
                "DPC VI suspeito: origin=%06x width=%u x_add=%u y_add=%u",
                viOrigin & 0x00ffffffu, viWidth & 0xfffu,
                viXScale & 0xfffu, viYScale & 0xfffu);
        }
        logFramebufferPixelProbe(
            "VI_ORIGIN", viOrigin & 0x00ffffffu, viWidth & 0xfffu, viStatus);
        if (g_rdpCommandStats.lastColorW1 != 0u) {
            logFramebufferPixelProbe(
                "SET_COLOR_IMAGE", g_rdpCommandStats.lastColorW1 & 0x00ffffffu,
                (g_rdpCommandStats.lastColorW0 & 1023u) + 1u, viStatus);
        }
        for (size_t i = 0; i < capturedCommands.size(); ++i) {
            const auto& c = capturedCommands[i];
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "DPC RDP[%zu]: opcode=%02x w0=%08x w1=%08x words=%zu",
                i, (c[0] >> 24) & 0xffu, c[0],
                c.size() > 1 ? c[1] : 0u, c.size());
        }
    }
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
    uint64_t nonBlackPixels = 0;
    uint8_t minChannel = 255u;
    uint8_t maxChannel = 0u;
    uint64_t pixelChecksum = 1469598103934665603ull;
    for (size_t i = 0; i < static_cast<size_t>(width) * height; ++i) {
        const uint8_t r = colors[i].r;
        const uint8_t g = colors[i].g;
        const uint8_t b = colors[i].b;
        const uint8_t a = colors[i].a;
        g_rgba[i * 4u + 0u] = r;
        g_rgba[i * 4u + 1u] = g;
        g_rgba[i * 4u + 2u] = b;
        g_rgba[i * 4u + 3u] = a;
        if (r != 0u || g != 0u || b != 0u) ++nonBlackPixels;
        minChannel = std::min(minChannel, std::min(r, std::min(g, b)));
        maxChannel = std::max(maxChannel, std::max(r, std::max(g, b)));
        pixelChecksum ^= static_cast<uint64_t>(r) |
                         (static_cast<uint64_t>(g) << 8u) |
                         (static_cast<uint64_t>(b) << 16u) |
                         (static_cast<uint64_t>(a) << 24u);
        pixelChecksum *= 1099511628211ull;
    }
    if (g_processRdpCalls <= 10u || (g_processRdpCalls % 120u) == 0u) {
        const uint64_t totalPixels = static_cast<uint64_t>(width) * height;
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "DPC RDP pixels: call=%llu size=%ux%u total=%llu nonBlack=%llu "
            "minRGB=%u maxRGB=%u checksum=%016llx",
            static_cast<unsigned long long>(g_processRdpCalls), width, height,
            static_cast<unsigned long long>(totalPixels),
            static_cast<unsigned long long>(nonBlackPixels),
            static_cast<unsigned>(minChannel), static_cast<unsigned>(maxChannel),
            static_cast<unsigned long long>(pixelChecksum));
    }

    if (nonBlackPixels == 0u && submitViRdramFallbackFrame())
        return true;

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


bool processGoldenEyeEmbeddedRdp() {
    if (!g_rdp || !g_info.DMEM || !g_info.RDRAM)
        return false;

    uint32_t task[16] = {};
    for (unsigned i = 0; i < 16; ++i)
        task[i] = readN64MemoryWord(g_info.DMEM, 0xFC0u + i * 4u);

    const uint32_t ucodeDataAddress = task[6] & 0x00ffffffu;
    const uint32_t ucodeDataSize = std::min<uint32_t>(task[7], 0x1000u);
    if (ucodeDataAddress >= 8u * 1024u * 1024u ||
        ucodeDataSize > 8u * 1024u * 1024u - ucodeDataAddress ||
        crc32RdramBytes(ucodeDataAddress, ucodeDataSize) != 0x2a9b4b97u)
        return false;

    const uint32_t dataPtr = task[12] & 0x00ffffffu;
    const uint32_t dataSize = task[13];
    if (dataSize < 8u || dataSize > 0x100000u ||
        dataPtr >= 8u * 1024u * 1024u ||
        dataSize > 8u * 1024u * 1024u - dataPtr)
        return false;

    // The diagnostic F3D walker runs immediately before this collector and
    // resolves G_MW_SEGMENT commands into the persistent GoldenEye state.
    // Reuse that segment table here so nested display lists and texture data
    // referenced through non-zero segments are not silently resolved from 0.
    uint32_t* segments = g_goldenEyeGbiState.segments.data();
    uint32_t budget = 0;
    std::vector<std::vector<uint32_t>> commands;
    const bool collectionOk = collectGoldenEyeRdpCommands(
        dataPtr, dataPtr + dataSize, segments, 0u, budget, commands);
    const uint32_t nonRdpOrControlCommands =
        budget > commands.size()
            ? budget - static_cast<uint32_t>(commands.size())
            : 0u;
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "GoldenEye embedded collector: ok=%u scanned=%u raw_RDP=%zu GBI/control=%u data=%06x+%04x",
        collectionOk ? 1u : 0u, budget, commands.size(),
        nonRdpOrControlCommands, dataPtr, dataSize);
    if (!collectionOk || commands.empty())
        return false;

    const bool traceRdp = g_processDListCalls <= 10u ||
                          (g_processDListCalls % 120u) == 0u;
    if (traceRdp) {
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "GoldenEye RDP enqueue BEGIN: task=%llu commands=%zu",
            static_cast<unsigned long long>(g_processDListCalls),
            commands.size());
    }
    unsigned submitted = 0;
    for (const auto& command : commands) {
        if (command.size() < 2u || command.size() > 44u)
            return false;
        g_rdp->enqueue_command(
            static_cast<unsigned>(command.size()), command.data());
        ++submitted;
    }
    if (traceRdp) {
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "GoldenEye RDP enqueue END: task=%llu submitted=%u",
            static_cast<unsigned long long>(g_processDListCalls), submitted);
    }

    setRdpViRegisters();
    logRdpFramebufferState("GoldenEye embedded", commands, g_processDListCalls);
    if (g_processDListCalls <= 12u || (g_processDListCalls % 120u) == 0u) {
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "GoldenEye embedded VI: status=%08x origin=%08x width=%08x "
            "h_start=%08x v_start=%08x x_scale=%08x y_scale=%08x data=%06x+%04x",
            g_info.VI_STATUS_REG ? *g_info.VI_STATUS_REG : 0u,
            g_info.VI_ORIGIN_REG ? *g_info.VI_ORIGIN_REG : 0u,
            g_info.VI_WIDTH_REG ? *g_info.VI_WIDTH_REG : 0u,
            g_info.VI_H_START_REG ? *g_info.VI_H_START_REG : 0u,
            g_info.VI_V_START_REG ? *g_info.VI_V_START_REG : 0u,
            g_info.VI_X_SCALE_REG ? *g_info.VI_X_SCALE_REG : 0u,
            g_info.VI_Y_SCALE_REG ? *g_info.VI_Y_SCALE_REG : 0u,
            dataPtr, dataSize);
        for (size_t i = 0; i < commands.size(); ++i) {
            const auto& c = commands[i];
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "GoldenEye embedded RDP[%zu]: opcode=%02x w0=%08x w1=%08x words=%zu",
                i, (c[0] >> 24) & 0xffu, c[0],
                c.size() > 1u ? c[1] : 0u, c.size());
        }
    }
    const auto flushStart = std::chrono::steady_clock::now();
    if (traceRdp) {
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "GoldenEye RDP flush BEGIN: task=%llu",
            static_cast<unsigned long long>(g_processDListCalls));
    }
    g_rdp->flush();
    const auto flushUs = std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now() - flushStart).count();
    if (traceRdp) {
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "GoldenEye RDP flush END: task=%llu elapsed_us=%lld",
            static_cast<unsigned long long>(g_processDListCalls),
            static_cast<long long>(flushUs));
    }

    // This GoldenEye path is entered through RSP HLE's ProcessDList callback,
    // not the core's DPC_END write path. Therefore the core will not generate
    // the DP interrupt for an embedded RDP FullSync automatically. Set the
    // MI interrupt bit before returning; rsp_core::do_SP_Task() observes it
    // after DoRspCycles() and schedules the normal DP_INT event.
    const bool hasFullSync = std::any_of(
        commands.begin(), commands.end(),
        [](const std::vector<uint32_t>& command) {
            return !command.empty() && (command[0] >> 24) == 0xE9u;
        });
    if (hasFullSync && g_info.MI_INTR_REG) {
        *g_info.MI_INTR_REG |= 0x20u; // MI_INTR_DP
        if (g_processDListCalls <= 10u) {
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "GoldenEye embedded RDP: FullSync detectado; MI_INTR_DP sinalizado ao Core");
        }
    }

    std::vector<RDP::RGBA> colors;
    unsigned width = 0;
    unsigned height = 0;
    RDP::ScanoutOptions options = {};
    const auto scanoutStart = std::chrono::steady_clock::now();
    if (traceRdp) {
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "GoldenEye RDP scanout BEGIN: task=%llu",
            static_cast<unsigned long long>(g_processDListCalls));
    }
    g_rdp->scanout_sync(colors, width, height, options);
    const auto scanoutUs = std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now() - scanoutStart).count();
    if (traceRdp) {
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "GoldenEye RDP scanout END: task=%llu elapsed_us=%lld size=%ux%u pixels=%zu",
            static_cast<unsigned long long>(g_processDListCalls),
            static_cast<long long>(scanoutUs), width, height, colors.size());
    }
    if (width == 0 || height == 0 ||
        colors.size() < static_cast<size_t>(width) * height) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "GoldenEye raw-RDP scanout invalido: %ux%u pixels=%zu comandos=%u",
            width, height, colors.size(), submitted);
        return false;
    }

    g_rgba.resize(static_cast<size_t>(width) * height * 4u);
    uint64_t nonBlackPixels = 0;
    uint8_t minChannel = 255u;
    uint8_t maxChannel = 0u;
    uint64_t pixelChecksum = 1469598103934665603ull;
    for (size_t i = 0; i < static_cast<size_t>(width) * height; ++i) {
        const uint8_t r = colors[i].r;
        const uint8_t g = colors[i].g;
        const uint8_t b = colors[i].b;
        const uint8_t a = colors[i].a;
        g_rgba[i * 4u + 0u] = r;
        g_rgba[i * 4u + 1u] = g;
        g_rgba[i * 4u + 2u] = b;
        g_rgba[i * 4u + 3u] = a;
        if (r != 0u || g != 0u || b != 0u) ++nonBlackPixels;
        minChannel = std::min(minChannel, std::min(r, std::min(g, b)));
        maxChannel = std::max(maxChannel, std::max(r, std::max(g, b)));
        pixelChecksum ^= static_cast<uint64_t>(r) |
                         (static_cast<uint64_t>(g) << 8u) |
                         (static_cast<uint64_t>(b) << 16u) |
                         (static_cast<uint64_t>(a) << 24u);
        pixelChecksum *= 1099511628211ull;
    }

    if (nonBlackPixels == 0u && submitViRdramFallbackFrame())
        return true;

    N64VideoFrame frame{};
    frame.pixels = g_rgba.data();
    frame.width = width;
    frame.height = height;
    frame.stride_bytes = width * 4u;
    frame.pixel_format = N64_VIDEO_PIXEL_FORMAT_RGBA8888;
    frame.frame_number = ++g_frameNumber;
    if (g_processDListCalls <= 10u || (g_processDListCalls % 120u) == 0u) {
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "GoldenEye RDP pixels: frame=%llu size=%ux%u total=%llu nonBlack=%llu "
            "minRGB=%u maxRGB=%u checksum=%016llx",
            static_cast<unsigned long long>(frame.frame_number), width, height,
            static_cast<unsigned long long>(static_cast<uint64_t>(width) * height),
            static_cast<unsigned long long>(nonBlackPixels),
            static_cast<unsigned>(minChannel), static_cast<unsigned>(maxChannel),
            static_cast<unsigned long long>(pixelChecksum));
    }
    n64VulkanVideoFrameCallback(&frame, nullptr);

    if (g_processDListCalls <= 10u)
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "GoldenEye raw-RDP encaminhado: comandos=%u frame=%ux%u",
            submitted, width, height);
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
    // Advance paraLLEl-RDP's Granite frame context before recording this
    // graphics task. This flushes/drains the previous task before recycling
    // per-frame Vulkan resources.
    if (g_rdp) g_rdp->begin_frame_context();
    ++g_processDListCalls;
    // RSP HLE signals a graphics task to the GFX plugin. It does not guarantee
    // that the OSTask display-list data has already become raw RDP commands.
    // Capture the task descriptor first so the GBI translation path can be
    // implemented against the actual microcode/data pointers used by GoldenEye.
    // Trace startup tasks and then sample periodically so geometry tasks that
    // appear later in gameplay are not missed by the initial diagnostic window.
    const bool traceTask = g_processDListCalls <= 12u ||
                          (g_processDListCalls % 120u) == 0u;
    if (traceTask) logRspTaskSnapshot();
    // Execute the GoldenEye GBI interpreter on every graphics task; verbose
    // command logging remains sampled to avoid flooding logcat.
    processF3DDisplayList(traceTask);
    // GoldenEye stores hardware RDP commands inside its F3D display lists.
    // DPC_START/END can therefore be zero even though the OSTask contains
    // valid embedded RDP commands. Forward those commands before the DPC path.
    const bool rendered = processGoldenEyeEmbeddedRdp() || processRdpCommandList();
    // No diagnostic-frame fallback here. If RDP has no valid frame, keep the
    // previous real output rather than replaying a synthetic white debug image.
    if (!rendered && g_processDListCalls <= 10) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "ProcessDList #%llu: nenhuma imagem RDP valida; aguardando lista real (sem apresentar VI bruto)",
            static_cast<unsigned long long>(g_processDListCalls));
    }
}
EXPORT void CALL ProcessRDPList(void) {
    std::lock_guard<std::mutex> lock(g_mutex);
    // DPC-driven RDP tasks can arrive without ProcessDList; give that path
    // the same frame-context lifetime handling.
    if (g_rdp) g_rdp->begin_frame_context();
    ++g_processRdpCalls;
    const bool rendered = processRdpCommandList();
    // CXD4 LLE calls this callback directly after writing DPC_END through the
    // RSP_INFO register pointer. That bypasses the Core's MMIO DPC_END handler,
    // which normally raises MI_INTR_DP after ProcessRDPList returns.
    if (g_info.MI_INTR_REG)
        *g_info.MI_INTR_REG |= 0x20u; // MI_INTR_DP
    if (!rendered && g_processRdpCalls <= 10) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "ProcessRDPList #%llu sem frame RDP valido; MI_INTR_DP sinalizado",
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
    g_goldenEyeGbiState = {};
    g_processRdpCalls = 0;
    g_rdpCommandStats = {};
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