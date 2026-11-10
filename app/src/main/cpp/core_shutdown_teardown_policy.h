#pragma once

// Detaching an attached graphics plugin may touch GLES resources even when
// PluginStartup has not completed. EGL must therefore be current whenever
// the graphics plugin is attached OR started.
constexpr bool coreShutdownRequiresGraphicsContext(bool gfxAttached,
                                                    bool gfxStarted) {
    return gfxAttached || gfxStarted;
}
