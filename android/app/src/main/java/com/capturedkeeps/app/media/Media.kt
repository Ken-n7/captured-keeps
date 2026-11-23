package com.capturedkeeps.app.media

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Owner-picked files (idle videos, payment QR) copied into app storage so they work offline and survive the original being deleted. */
object Media {
    private fun dir(context: Context) = File(context.filesDir, "media").apply { mkdirs() }

    /** The business wallet QR shown on the payment screen. One for the whole business. */
    fun paymentQr(context: Context) = File(dir(context), "payment-qr")

    // ponytail: replaced idle videos aren't deleted yet; add cleanup if storage gets tight.
    suspend fun copyIn(context: Context, uri: Uri, target: File = File(dir(context), "idle-${UUID.randomUUID()}.mp4")): File =
        withContext(Dispatchers.IO) {
            val tmp = File(target.path + ".tmp")
            (context.contentResolver.openInputStream(uri) ?: error("Couldn't open the file")).use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            if (!tmp.renameTo(target)) error("Couldn't save the file")
            target
        }

    /** True if Android can read a video track — used for the go-live warning and the idle-screen fallback. */
    fun isPlayableVideo(path: String?): Boolean {
        if (path == null || !File(path).exists()) return false
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
        } catch (e: Exception) {
            false
        } finally {
            r.release()
        }
    }

    fun image(file: File): ImageBitmap? = if (file.exists()) BitmapFactory.decodeFile(file.path)?.asImageBitmap() else null
}
