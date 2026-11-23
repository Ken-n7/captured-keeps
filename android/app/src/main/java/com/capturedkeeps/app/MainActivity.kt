package com.capturedkeeps.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.capturedkeeps.app.ui.CapturedKeepsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CapturedKeepsTheme { Wordmark() } }
    }
}

// Placeholder until the kiosk idle screen exists.
@Composable
fun Wordmark() {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("CAPTURED KEEPS", style = MaterialTheme.typography.displayLarge)
            Text("RECEIPT PHOTO STUDIO", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
            Text("Little moments. Lifelong keeps.", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun WordmarkPreview() = CapturedKeepsTheme { Wordmark() }
