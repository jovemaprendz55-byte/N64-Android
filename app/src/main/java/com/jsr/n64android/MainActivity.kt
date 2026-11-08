package com.jsr.n64android

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {

    private external fun nativeGetStatus(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = try {
            nativeGetStatus()
        } catch (error: UnsatisfiedLinkError) {
            "Biblioteca nativa não carregada: ${error.message}"
        }

        val message = TextView(this).apply {
            text = "N64 Android\n\n$status"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        setContentView(message)
    }

    companion object {
        init {
            System.loadLibrary("n64bridge")
        }
    }
}
