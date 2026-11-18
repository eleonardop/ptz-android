package com.ptzstudio.android

import android.app.Application
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ptzstudio.android.ui.PtzApp

/** Guarda um único Engine para o app inteiro (sobrevive a girar a tela). */
class PtzApplication : Application() {
    val engine: Engine by lazy { Engine(this) }
}

class MainActivity : ComponentActivity() {
    private val engine: Engine get() = (application as PtzApplication).engine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Tela sempre acesa enquanto o app está aberto (transmissão ao vivo).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { PtzApp(engine) }
    }

    override fun onStart() {
        super.onStart()
        engine.onForeground()
    }

    override fun onStop() {
        engine.onBackground()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (engine.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (engine.onMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }
}
