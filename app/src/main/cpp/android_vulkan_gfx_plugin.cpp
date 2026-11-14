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

// Native F3D Vtx layout: 16 bytes in big-endian N64 RDRAM.
struct GoldenEyeVertex {
    int16_t x = 0, y = 0, z = 0;
    uint16_t flag = 0;
    int16_t s = 0, t = 0;
    uint8_t colorOrNormal[4] = {};
    bool valid = false;
};

struct GoldenEyeMatrixState {
    float modelView[16] = {};
    float projection[16] = {};
    bool modelViewValid = false;
    bool projectionValid = false;
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
                        GoldenEyeMatrixState& matrixState) {
    constexpr uint32_t rdramLimit = 8u * 1024u * 1024u;
    if (depth > 10u || start >= rdramLimit || (start & 7u) != 0u) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "F3DGOLDEN GBI: endereco/profundidade invalida start=%06x depth=%u",
            start, depth);
        return false;
    }

    uint32_t pc = start;
    uint32_t localCommands = 0;
    while (budget < 8192u && localCommands < 2048u) {
        if (pc > rdramLimit - 8u || pc > limit - (limit >= 8u ? 8u : limit)) {
            __android_log_print(ANDROID_LOG_WARN, TAG,
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

        if (opcode == 0xB8u) { // F3D G_ENDDL
            return true;
        }

        if (opcode == 0xBCu || opcode == 0xBDu) { // G_MOVEWORD / GoldenEye variant
            if ((w0 & 0xffu) == 0x06u) { // G_MW_SEGMENT
                const unsigned segment = (w0 >> 10) & 0x0fu;
                segments[segment] = w1 & 0x00ffffffu;
                __android_log_print(ANDROID_LOG_INFO, TAG,
                    "F3DGOLDEN GBI: segmento[%u]=%06x (cmd @%06x)",
                    segment, segments[segment], pc);
            }
        } else if (opcode == 0x06u) { // F3D G_DL
            const unsigned mode = (w0 >> 16) & 0xffu;
            const unsigned segment = (w1 >> 24) & 0xffu;
            const uint32_t offset = w1 & 0x00ffffffu;
            if (segment >= 16u) {
                __android_log_print(ANDROID_LOG_WARN, TAG,
                    "F3DGOLDEN GBI: segmento invalido %u em G_DL @%06x",
                    segment, pc);
                return false;
            }
            const uint64_t resolved64 = static_cast<uint64_t>(segments[segment]) + offset;
            if (resolved64 >= rdramLimit || (resolved64 & 7u) != 0u) {
                __android_log_print(ANDROID_LOG_WARN, TAG,
                    "F3DGOLDEN GBI: G_DL fora da RDRAM seg=%u base=%06x offset=%06x",
                    segment, segments[segment], offset);
                return false;
            }
            const uint32_t target = static_cast<uint32_t>(resolved64);
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "F3DGOLDEN GBI: G_DL mode=%u alvo=%06x depth=%u",
                mode, target, depth);

            if (mode == 0u) { // G_DL_PUSH: call and return
                if (!walkF3DDisplayList(target, rdramLimit, segments,
                                        depth + 1u, budget, calls, rawRdpCommands,
                                        opcodeCounts, loggedGbiCommands,
                                        vertexCacheMask, decodedGoldenTriangles, vertexCache,
                                        matrixState))
                    return false;
            } else if (mode == 1u) { // G_DL_NOPUSH: branch without return
                pc = target;
                continue;
            } else {
                __android_log_print(ANDROID_LOG_WARN, TAG,
                    "F3DGOLDEN GBI: modo G_DL desconhecido %u", mode);
                return false;
            }
        } else if (opcode == 0x01u) { // F3D G_MTX
            const unsigned param = (w0 >> 16) & 0xffu;
            const unsigned segment = (w1 >> 24) & 0xffu;
            const uint32_t offset = w1 & 0x00ffffffu;
            const uint64_t matrixAddress64 = segment < 16u
                ? static_cast<uint64_t>(segments[segment]) + offset
                : 8u * 1024u * 1024u;
            if ((w0 & 0xffffu) != 0x0040u || segment >= 16u ||
                matrixAddress64 > 8u * 1024u * 1024u - 64u) {
                __android_log_print(ANDROID_LOG_WARN, TAG,
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
                    if (isLoad || !destinationValid) {
                        std::memcpy(destination, loadedMatrix, sizeof(loadedMatrix));
                    } else {
                        float combined[16] = {};
                        multiplyMatrix4x4(destination, loadedMatrix, combined);
                        std::memcpy(destination, combined, sizeof(combined));
                    }
                    destinationValid = true;
                    __android_log_print(ANDROID_LOG_INFO, TAG,
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
                    vertex.valid = true;
                    vertexCacheMask |= (1u << (firstVertex + i));
                    if (i < 4u && loggedGbiCommands < 100u) {
                        __android_log_print(ANDROID_LOG_INFO, TAG,
                            "F3DGOLDEN VTX_SAMPLE[%u]: slot=%u XYZ=(%d,%d,%d) ST=(%d,%d) flag=%04x attr=%02x,%02x,%02x,%02x",
                            i, firstVertex + i, vertex.x, vertex.y, vertex.z,
                            vertex.s, vertex.t, vertex.flag,
                            vertex.colorOrNormal[0], vertex.colorOrNormal[1],
                            vertex.colorOrNormal[2], vertex.colorOrNormal[3]);
                        ++loggedGbiCommands;
                    }
                }
                const GoldenEyeVertex& first = vertexCache[firstVertex];
                if (matrixState.modelViewValid && loggedGbiCommands < 120u) {
                    float transformed[4] = {};
                    transformGoldenEyeVertex(matrixState.modelView, first, transformed);
                    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "F3DGOLDEN VTX_MODELVIEW: slot=%u XYZ=(%d,%d,%d) -> (%.2f,%.2f,%.2f,%.2f)",
                        firstVertex, first.x, first.y, first.z,
                        transformed[0], transformed[1], transformed[2], transformed[3]);
                    ++loggedGbiCommands;
                }
                const GoldenEyeVertex& firstForLog = vertexCache[firstVertex];
                __android_log_print(ANDROID_LOG_INFO, TAG,
                    "F3DGOLDEN G_VTX: seg=%u offset=%06x endereco=%06x count=%u v0=%u cache=0x%08x V0=(%d,%d,%d) ST=(%d,%d) flag=%04x attr=%02x,%02x,%02x,%02x",
                    segment, offset, vertexAddress, vertexCount, firstVertex,
                    vertexCacheMask, firstForLog.x, firstForLog.y, firstForLog.z,
                    firstForLog.s, firstForLog.t, firstForLog.flag,
                    firstForLog.colorOrNormal[0], firstForLog.colorOrNormal[1],
                    first.colorOrNormal[2], first.colorOrNormal[3]);
            } else {
                __android_log_print(ANDROID_LOG_WARN, TAG,
                    "F3DGOLDEN G_VTX invalido: seg=%u offset=%06x count=%u v0=%u",
                    segment, offset, vertexCount, firstVertex);
            }
            if (loggedGbiCommands < 100u) {
                __android_log_print(ANDROID_LOG_INFO, TAG,
                    "F3DGOLDEN GBI: G_VTX raw w0=%08x w1=%08x @%06x", w0, w1, pc);
                ++loggedGbiCommands;
            }
        } else if (opcode == 0xB1u) { // F3DGOLDEN G_TRIX: packed triples, up to four
            uint32_t packedW0 = w0;
            uint32_t packedW1 = w1;
            unsigned triangles = 0;
            while (packedW1 != 0u && triangles < 4u) {
                const unsigned v0 = packedW1 & 0x0fu;
                packedW1 >>= 4;
                const unsigned v1 = packedW1 & 0x0fu;
                packedW1 >>= 4;
                const unsigned v2 = packedW0 & 0x0fu;
                packedW0 >>= 4;
                const uint32_t triangleMask = (1u << v0) | (1u << v1) | (1u << v2);
                const bool verticesReady = vertexCache[v0].valid &&
                    vertexCache[v1].valid && vertexCache[v2].valid &&
                    (vertexCacheMask & triangleMask) == triangleMask;
                if (!verticesReady) {
                    __android_log_print(ANDROID_LOG_WARN, TAG,
                        "F3DGOLDEN G_TRIX[%u]: (%u,%u,%u) vertices_loaded=NO cache=0x%08x",
                        triangles, v0, v1, v2, vertexCacheMask);
                } else if (decodedGoldenTriangles < 32u) {
                    const GoldenEyeVertex& a = vertexCache[v0];
                    const GoldenEyeVertex& b = vertexCache[v1];
                    const GoldenEyeVertex& c = vertexCache[v2];
                    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "F3DGOLDEN TRIANGLE[%u]: idx=(%u,%u,%u) A=(%d,%d,%d) B=(%d,%d,%d) C=(%d,%d,%d)",
                        decodedGoldenTriangles, v0, v1, v2,
                        a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z);
                }
                ++triangles;
                ++decodedGoldenTriangles;
            }
            if (triangles == 0u) {
                __android_log_print(ANDROID_LOG_WARN, TAG,
                    "F3DGOLDEN G_TRIX vazio/invalid: w0=%08x w1=%08x @%06x", w0, w1, pc);
            }
        } else if (opcode >= 0xC8u) {
            // Raw RDP commands may be embedded in a GBI list. Count them,
            // but do not enqueue them until the RSP geometry path is implemented.
            ++rawRdpCommands;
            if (rawRdpCommands <= 12u) {
                __android_log_print(ANDROID_LOG_INFO, TAG,
                    "F3DGOLDEN GBI: comando RDP bruto opcode=%02x @%06x",
                    opcode, pc);
            }
        } else if (loggedGbiCommands < 100u) {
            // Capture unknown/non-RDP F3D commands (matrix, vertex, triangles,
            // texture and GoldenEye extensions) to map the real geometry path.
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "F3DGOLDEN GBI: GBI opcode=%02x w0=%08x w1=%08x @%06x depth=%u",
                opcode, w0, w1, pc, depth);
            ++loggedGbiCommands;
        }

        pc += 8u;
    }

    __android_log_print(ANDROID_LOG_WARN, TAG,
        "F3DGOLDEN GBI: limite de comandos atingido start=%06x depth=%u",
        start, depth);
    return false;
}

void logF3DDisplayListTraversal() {
    if (!g_info.DMEM || !g_info.RDRAM) return;
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
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "F3DGOLDEN GBI: payload raiz invalido ptr=%06x size=%08x",
            dataPtr, dataSize);
        return;
    }

    uint32_t segments[16] = {};
    uint32_t budget = 0;
    uint32_t calls = 0;
    uint32_t rawRdpCommands = 0;
    uint32_t opcodeCounts[256] = {};
    uint32_t loggedGbiCommands = 0;
    uint32_t vertexCacheMask = 0;
    uint32_t decodedGoldenTriangles = 0;
    GoldenEyeVertex vertexCache[32] = {};
    GoldenEyeMatrixState matrixState{};
    const bool complete = walkF3DDisplayList(
        dataPtr, dataPtr + dataSize, segments, 0u,
        budget, calls, rawRdpCommands, opcodeCounts, loggedGbiCommands,
        vertexCacheMask, decodedGoldenTriangles, vertexCache, matrixState);
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "F3DGOLDEN GBI traversal: %s comandos=%u listas/chamadas=%u RDP_brutos=%u GBI_detalhados=%u G_TRIX_triangulos=%u vertex_cache=0x%08x",
        complete ? "fim/retorno alcancado" : "incompleto/limite",
        budget, calls, rawRdpCommands, loggedGbiCommands,
        decodedGoldenTriangles, vertexCacheMask);
    for (unsigned opcode = 0; opcode < 0xe4u; ++opcode) {
        if (opcodeCounts[opcode] != 0u) {
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "F3DGOLDEN GBI resumo: opcode=%02x quantidade=%u",
                opcode, opcodeCounts[opcode]);
        }
    }
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
                pc = target;
                continue;
            } else {
                return false;
            }
        } else if (opcode >= 0xC8u) {
            // RDP triangle commands (0xC8-0xCF) carry extra payload words.
            // Capture them as complete commands instead of stepping 8 bytes.
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
    std::vector<std::vector<uint32_t>> capturedCommands;
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
        capturedCommands.emplace_back(words, words + count);
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
    if (g_processDListCalls <= 5) {
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "GoldenEye VI: status=%08x origin=%08x width=%08x "
            "h_start=%08x v_start=%08x x_scale=%08x y_scale=%08x",
            g_info.VI_STATUS_REG ? *g_info.VI_STATUS_REG : 0u,
            g_info.VI_ORIGIN_REG ? *g_info.VI_ORIGIN_REG : 0u,
            g_info.VI_WIDTH_REG ? *g_info.VI_WIDTH_REG : 0u,
            g_info.VI_H_START_REG ? *g_info.VI_H_START_REG : 0u,
            g_info.VI_V_START_REG ? *g_info.VI_V_START_REG : 0u,
            g_info.VI_X_SCALE_REG ? *g_info.VI_X_SCALE_REG : 0u,
            g_info.VI_Y_SCALE_REG ? *g_info.VI_Y_SCALE_REG : 0u);
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

    uint32_t segments[16] = {};
    uint32_t budget = 0;
    std::vector<std::vector<uint32_t>> commands;
    if (!collectGoldenEyeRdpCommands(
            dataPtr, dataPtr + dataSize, segments, 0u, budget, commands) ||
        commands.empty())
        return false;

    unsigned submitted = 0;
    for (const auto& command : commands) {
        if (command.size() < 2u || command.size() > 44u)
            return false;
        g_rdp->enqueue_command(
            static_cast<unsigned>(command.size()), command.data());
        ++submitted;
    }

    setRdpViRegisters();
    if (g_processDListCalls <= 5u) {
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
    g_rdp->flush();

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
    g_rdp->scanout_sync(colors, width, height, options);
    if (width == 0 || height == 0 ||
        colors.size() < static_cast<size_t>(width) * height) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "GoldenEye raw-RDP scanout invalido: %ux%u pixels=%zu comandos=%u",
            width, height, colors.size(), submitted);
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
    ++g_processDListCalls;
    // RSP HLE signals a graphics task to the GFX plugin. It does not guarantee
    // that the OSTask display-list data has already become raw RDP commands.
    // Capture the task descriptor first so the GBI translation path can be
    // implemented against the actual microcode/data pointers used by GoldenEye.
    // Trace startup tasks and then sample periodically so geometry tasks that
    // appear later in gameplay are not missed by the initial diagnostic window.
    const bool traceTask = g_processDListCalls <= 12u ||
                          (g_processDListCalls % 120u) == 0u;
    if (traceTask) {
        logRspTaskSnapshot();
        logF3DDisplayListTraversal();
    }
    // GoldenEye stores hardware RDP commands inside its F3D display lists.
    // DPC_START/END can therefore be zero even though the OSTask contains
    // valid embedded RDP commands. Forward those commands before the DPC path.
    const bool rendered = processGoldenEyeEmbeddedRdp() || processRdpCommandList();
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
