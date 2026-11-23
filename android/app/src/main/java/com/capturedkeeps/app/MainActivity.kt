package com.capturedkeeps.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.capturedkeeps.app.ui.CapturedKeepsTheme
import com.capturedkeeps.app.ui.VirtualPrinterScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Until the kiosk flow exists, the app opens on the virtual printer.
        setContent { CapturedKeepsTheme { VirtualPrinterScreen() } }
    }
}
