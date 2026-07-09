package us.creativeworks.nomad.video

import android.view.SurfaceView
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import us.creativeworks.nomad.capture.CaptureController
import us.creativeworks.nomad.control.Protocol

// --- Low-latency buffer tuning (see KDoc below) ------------------------------
// For FPV driving, latency matters far more than smoothness, so we buffer the
// bare minimum. These are aggressive; raise them if you see stutter on weak Wi-Fi.
private const val MIN_BUFFER_MS = 150          // keep only ~150ms of look-ahead
private const val MAX_BUFFER_MS = 600          // never hoard more than 0.6s
private const val BUFFER_FOR_PLAYBACK_MS = 30  // start rendering almost immediately
private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 60

/**
 * Renders the car's RTSP camera feed with Media3/ExoPlayer, tuned for low latency.
 *
 * Stream verified against a live car (see NOMAD-ND1-PROTOCOL.md §6): a standard
 * LIVE555 server delivering H.264 Main / yuv420p / 640x480 / 25fps — trivially
 * hardware-decodable. No custom decoder needed.
 *
 * LATENCY: ExoPlayer's default LoadControl pre-buffers ~2.5s before playback and
 * then plays at real-time rate, so it stays permanently that far behind live. For
 * an RC-car FPV feed that's unacceptable. The custom [DefaultLoadControl] below
 * starts rendering after ~30ms and caps look-ahead at ~0.6s, which removes the
 * bulk of the glass-to-glass delay. The residual floor is the car's own
 * capture/encode pipeline, which we can't change.
 *
 * [forceTcp] MUST stay true: the car does not deliver UDP RTP reliably (VLC's
 * UDP default fails to open it), so we force RTP-over-TCP interleaving — the same
 * transport that works with `ffplay -rtsp_transport tcp`.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    modifier: Modifier = Modifier,
    url: String = Protocol.RTSP_URL,
    forceTcp: Boolean = true,
    captureController: CaptureController? = null,
) {
    val context = LocalContext.current
    val player = remember {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            // Render as soon as we have a few frames rather than waiting on a byte target.
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                val source = RtspMediaSource.Factory()
                    .setForceUseRtpTcp(forceTcp)
                    .createMediaSource(MediaItem.fromUri(url))
                setMediaSource(source)
                // Keep the capture recorder's frame size matched to the real stream.
                addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        captureController?.let {
                            if (videoSize.width > 0 && videoSize.height > 0) {
                                it.videoWidth = videoSize.width
                                it.videoHeight = videoSize.height
                            }
                        }
                    }
                })
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(Unit) {
        onDispose {
            captureController?.surfaceView = null
            player.release()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = false
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                captureController?.surfaceView = videoSurfaceView as? SurfaceView
            }
        },
    )
}
