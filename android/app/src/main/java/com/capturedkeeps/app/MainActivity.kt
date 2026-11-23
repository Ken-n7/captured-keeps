package com.capturedkeeps.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.capturedkeeps.app.ui.CapturedKeepsTheme
import com.capturedkeeps.app.ui.KioskScreen
import com.capturedkeeps.app.ui.VirtualPrinterScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // booth screen never sleeps
        val app = application as App
        setContent {
            CapturedKeepsTheme {
                var staffTools by remember { mutableStateOf(false) }
                if (staffTools) {
                    BackHandler { staffTools = false }
                    VirtualPrinterScreen()
                } else {
                    KioskScreen(app.kiosk, app.scope, filesDir, onStaff = { staffTools = true })
                }
            }
        }
    }
}
