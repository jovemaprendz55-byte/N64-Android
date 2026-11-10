#include <cstring>
#include <atomic>
#include <algorithm>
#define M64P_PLUGIN_PROTOTYPES 1
#include "m64p_plugin.h"
#include "m64p_types.h"

namespace {
std::atomic<unsigned int> g_buttons{0};
std::atomic<int> g_axisX{0};
std::atomic<int> g_axisY{0};
bool g_started = false;
}

extern "C" {
EXPORT m64p_error CALL PluginStartup(m64p_dynlib_handle, void*, void (*)(void*, int, const char*)) {
    if (g_started) return M64ERR_ALREADY_INIT;
    g_started = true;
    return M64ERR_SUCCESS;
}

EXPORT m64p_error CALL PluginShutdown(void) {
    if (!g_started) return M64ERR_NOT_INIT;
    g_buttons.store(0);
    g_axisX.store(0);
    g_axisY.store(0);
    g_started = false;
    return M64ERR_SUCCESS;
}

EXPORT m64p_error CALL PluginGetVersion(m64p_plugin_type* type, int* version,
                                         int* apiVersion, const char** name, int* caps) {
    if (type) *type = M64PLUGIN_INPUT;
    if (version) *version = 0x020600;
    if (apiVersion) *apiVersion = 0x020100;
    if (name) *name = "N64 Android Virtual Controller";
    if (caps) *caps = 0;
    return M64ERR_SUCCESS;
}

EXPORT void CALL InitiateControllers(CONTROL_INFO info) {
    if (info.Controls == nullptr) return;
    for (int i = 0; i < 4; ++i) {
        info.Controls[i].Present = (i == 0) ? 1 : 0;
        info.Controls[i].RawData = 0;
        info.Controls[i].Plugin = PLUGIN_MEMPAK;
        info.Controls[i].Type = CONT_TYPE_STANDARD;
    }
}

EXPORT void CALL GetKeys(int control, BUTTONS* keys) {
    if (keys == nullptr) return;
    keys->Value = 0;
    if (control != 0 || !g_started) return;
    const unsigned int bits = g_buttons.load();
    keys->R_DPAD = (bits >> 0) & 1U;
    keys->L_DPAD = (bits >> 1) & 1U;
    keys->D_DPAD = (bits >> 2) & 1U;
    keys->U_DPAD = (bits >> 3) & 1U;
    keys->START_BUTTON = (bits >> 4) & 1U;
    keys->Z_TRIG = (bits >> 5) & 1U;
    keys->B_BUTTON = (bits >> 6) & 1U;
    keys->A_BUTTON = (bits >> 7) & 1U;
    keys->R_CBUTTON = (bits >> 8) & 1U;
    keys->L_CBUTTON = (bits >> 9) & 1U;
    keys->D_CBUTTON = (bits >> 10) & 1U;
    keys->U_CBUTTON = (bits >> 11) & 1U;
    keys->R_TRIG = (bits >> 12) & 1U;
    keys->L_TRIG = (bits >> 13) & 1U;
    keys->X_AXIS = static_cast<signed char>(std::clamp(g_axisX.load(), -80, 80));
    keys->Y_AXIS = static_cast<signed char>(std::clamp(g_axisY.load(), -80, 80));
}

// Obrigatorios na ABI INPUT do Mupen64Plus. O frontend Android usa
// os controles virtuais via N64AndroidSetControllerState, portanto SDL fica vazio.
EXPORT void CALL SDL_KeyDown(int, int) {}
EXPORT void CALL SDL_KeyUp(int, int) {}
EXPORT void CALL ControllerCommand(int, unsigned char*) {}
EXPORT void CALL ReadController(int, unsigned char*) {}
EXPORT int CALL RomOpen(void) { return 1; }
EXPORT void CALL RomClosed(void) {}
}

extern "C" __attribute__((visibility("default")))
void N64AndroidSetControllerState(unsigned int buttons, int axisX, int axisY) {
    g_buttons.store(buttons & 0x3FFFU);
    g_axisX.store(std::clamp(axisX, -80, 80));
    g_axisY.store(std::clamp(axisY, -80, 80));
}
