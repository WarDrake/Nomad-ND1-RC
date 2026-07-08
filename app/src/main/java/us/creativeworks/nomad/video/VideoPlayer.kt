package us.creativeworks.nomad.video

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import us.creativeworks.nomad.control.Protocol

/**
 * Renders the car's RTSP camera feed with Media3/ExoPlayer.
 *
 * NOTE: whether this works out of the box is the project's main open question.
 * The legacy app shipped its own FFmpeg decoder, which hints the stream MAY be
 * a nonstandard RTSP/RTP dialect. If ExoPlayer errors on rtsp://192.168.0.1/vs1,
 * fall back to libVLC or a bundled modern FFmpeg. Validate the stream first with
 * ffplay/VLC from a laptop joined to the car's Wi-Fi.
 *
 * [forceTcp] switches RTP to TCP interleaving, which fixes many cheap-camera
 * streams that don't do UDP RTP cleanly.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    modifier: Modifier = Modifier,
    url: String = Protocol.RTSP_URL,
    forceTcp: Boolean = true,
) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            val source = RtspMediaSource.Factory()
                .setForceUseRtpTcp(forceTcp)
                .createMediaSource(MediaItem.fromUri(url))
            setMediaSource(source)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
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
            }
        },
    )
}
