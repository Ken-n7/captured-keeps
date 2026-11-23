package com.capturedkeeps.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.capturedkeeps.app.print.Photos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Live front-camera preview, 3-2-1 countdown, flash, saves one photo per [shot]. */
@Composable
fun CaptureScreen(
    shot: Int,
    of: Int,
    photoFile: (Int) -> File,
    onPhoto: (String) -> Unit,
    onError: (String) -> Unit,
    onCancel: (() -> Unit)?,
) {
    val context = LocalContext.current
    var allowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it; asked = true }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(Manifest.permission.CAMERA) }

    if (!allowed) {
        CameraPermissionNeeded(
            asked = asked,
            onAskAgain = { ask.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            },
            onCancel = onCancel,
        )
        return
    }

    val owner = LocalLifecycleOwner.current
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var ready by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(0) }
    var flash by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val provider = ProcessCameraProvider.getInstance(context).let { f ->
                suspendCancellableCoroutine { c -> f.addListener({ c.resume(f.get()) }, ContextCompat.getMainExecutor(context)) }
            }
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA,
                Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }, capture)
            ready = true
        } catch (e: Exception) {
            onError(e.message ?: "camera unavailable")
        }
    }

    LaunchedEffect(shot, ready) {
        if (!ready) return@LaunchedEffect
        delay(800) // a breath between shots
        for (n in COUNTDOWN downTo 1) { count = n; delay(1000) }
        count = 0
        flash = true
        try {
            val image = capture.take(context)
            flash = false
            val file = photoFile(shot)
            withContext(Dispatchers.IO) { image.use { Photos.save(it.toBitmap(), it.imageInfo.rotationDegrees, file) } }
            onPhoto(file.path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            flash = false
            onError(e.message ?: "photo failed")
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ preview }, Modifier.fillMaxSize())
        Text("Photo $shot of $of", color = Color.White, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.align(Alignment.TopCenter).padding(24.dp))
        if (count > 0) Text("$count", color = Color.White, fontSize = 160.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center))
        if (onCancel != null) TextButton(onCancel, Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Text("Cancel", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
        if (flash) Box(Modifier.fillMaxSize().background(Color.White))
    }
}

private const val COUNTDOWN = 3

private suspend fun ImageCapture.take(context: android.content.Context): ImageProxy =
    suspendCancellableCoroutine { cont ->
        takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) = cont.resume(image)
            override fun onError(e: ImageCaptureException) = cont.resumeWithException(e)
        })
    }

@Composable
fun CameraPermissionNeeded(asked: Boolean, onAskAgain: () -> Unit, onOpenSettings: () -> Unit, onCancel: (() -> Unit)?) =
    Message(
        title = "Camera access needed",
        body = "The booth needs the camera to take your photos." +
            if (asked) " Staff: allow Camera for Captured Keeps, or open Settings → Permissions." else "",
    ) {
        Button(onAskAgain) { Text("Allow camera") }
        if (asked) OutlinedButton(onOpenSettings) { Text("Open settings") }
        if (onCancel != null) TextButton(onCancel) { Text("Cancel") }
    }
