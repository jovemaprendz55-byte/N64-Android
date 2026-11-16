package com.jsr.n64android

import android.view.Surface

internal object VulkanNative {
    init {
        System.loadLibrary("n64vulkan")
    }

    external fun nativeInitialize(surface: Surface): String
    external fun nativePrepareRom(
        romBytes: ByteArray,
        configPath: String,
        dataPath: String,
        videoPluginId: String,
        rspPluginId: String,
        audioPluginId: String,
        inputPluginId: String
    ): String
    external fun nativeStartEmulation(): String
    external fun nativeSetControllerState(buttons: Int, axisX: Int, axisY: Int)
    external fun nativeCloseRom(): String
    external fun nativeShutdown()
}
