package com.jsr.n64android

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View

/**
 * Empty launcher for the clean HLE reboot.
 * No ROM picker, legacy emulator, diagnostic worker or game runtime is wired.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.setBackgroundColor(Color.BLACK)
        setContentView(View(this).apply {
            setBackgroundColor(Color.BLACK)
            contentDescription = "N64 Android — base limpa"
        })
    }
}