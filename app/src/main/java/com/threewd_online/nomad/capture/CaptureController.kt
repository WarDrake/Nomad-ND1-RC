package com.threewd_online.nomad.capture

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Captures stills and records video from the video [SurfaceView] via PixelCopy.
 * PixelCopy grabs only the video layer, so captures exclude the HUD overlay.
 *
 * The composable registers the surface (and its native size) here; the UI calls
 * [capturePhoto] / [toggleRecording].
 */
class CaptureController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Set by the video composable. */
    var surfaceView: SurfaceView? = null
    var videoWidth: Int = 640
    var videoHeight: Int = 480

    var isRecording by mutableStateOf(false)
        private set

    private var recordJob: Job? = null

    fun capturePhoto(context: Context, onResult: (String?) -> Unit) {
        scope.launch {
            val bmp = grab()
            if (bmp == null) {
                postResult(onResult, null); return@launch
            }
            val name = "IMG_${MediaStoreOutput.timestamp(System.currentTimeMillis())}.jpg"
            val saved = MediaStoreOutput.saveImage(context, bmp, name)
            postResult(onResult, saved)
        }
    }

    fun toggleRecording(context: Context, onResult: (String?) -> Unit) {
        if (isRecording) stopRecording() else startRecording(context, onResult)
    }

    private fun startRecording(context: Context, onResult: (String?) -> Unit) {
        val w = (videoWidth and 1.inv()).coerceAtLeast(2)   // even dimensions for the encoder
        val h = (videoHeight and 1.inv()).coerceAtLeast(2)
        val recorder = VideoRecorder(w, h)
        val temp = File(context.cacheDir, "nomad_rec.mp4")
        try {
            recorder.start(temp)
        } catch (e: Exception) {
            Log.e(TAG, "recorder start failed", e)
            postResult(onResult, null); return
        }
        isRecording = true
        val startNs = System.nanoTime()
        val frameIntervalMs = 1000L / 25
        recordJob = scope.launch {
            try {
                while (isRecording) {
                    val t0 = System.nanoTime()
                    val frame = grab()
                    if (frame != null) {
                        val scaled = if (frame.width == w && frame.height == h) frame
                        else Bitmap.createScaledBitmap(frame, w, h, true)
                        recorder.encode(scaled, (System.nanoTime() - startNs) / 1000)
                        if (scaled !== frame) scaled.recycle()
                        frame.recycle()
                    }
                    val elapsed = (System.nanoTime() - t0) / 1_000_000
                    if (elapsed < frameIntervalMs) delay(frameIntervalMs - elapsed)
                }
            } finally {
                recorder.finish()
                val name = "VID_${MediaStoreOutput.timestamp(System.currentTimeMillis())}.mp4"
                val saved = MediaStoreOutput.saveVideoFile(context, temp, name)
                runCatching { temp.delete() }
                postResult(onResult, saved)
            }
        }
    }

    private fun stopRecording() {
        isRecording = false // the record loop observes this and finalizes in its finally block
    }

    private suspend fun grab(): Bitmap? {
        val sv = surfaceView ?: return null
        val w = sv.width
        val h = sv.height
        if (w <= 0 || h <= 0 || !sv.holder.surface.isValid) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(sv, bmp, { result ->
                    if (result == PixelCopy.SUCCESS) cont.resume(bmp) else cont.resume(null)
                }, mainHandler)
            } catch (e: Exception) {
                Log.e(TAG, "PixelCopy failed", e)
                cont.resume(null)
            }
        }
    }

    private fun postResult(cb: (String?) -> Unit, value: String?) {
        mainHandler.post { cb(value) }
    }

    fun release() {
        isRecording = false
        recordJob = null
        scope.cancel()
    }

    companion object {
        private const val TAG = "CaptureController"
    }
}
