package com.jsr.n64android

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View

/**
 * Clean application shell.
 *
 * The previous ROM picker, diagnostics, experimental IPL3/HLE launch path,
 * CPU worker and video session are intentionally disconnected from the app
 * entry point. The new high-level HLE runtime will be introduced separately
 * behind tested interfaces before any game-launch UI is restored.
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