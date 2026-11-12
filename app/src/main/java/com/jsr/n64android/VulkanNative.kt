package com.jsr.n64android

import android.view.Surface

internal object VulkanNative {
    init {
        System.loadLibrary("n64vulkan")
    }

    external fun nativeInitialize(surface: Surface): String
    external fun nativeShutdown()
}
