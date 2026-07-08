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
 * Stream verified against a live car (see NOMAD-ND1-PROTOCOL.md §6): a standard
 * LIVE555 server delivering H.264 Main / yuv420p / 640x480 / 25fps — trivially
 * hardware-decodable. No custom decoder needed.
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
