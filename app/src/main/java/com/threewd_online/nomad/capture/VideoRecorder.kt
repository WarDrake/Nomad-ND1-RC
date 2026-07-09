package com.threewd_online.nomad.capture

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Minimal H.264 -> MP4 recorder. Frames are pushed in as ARGB bitmaps (captured
 * from the video surface), converted to YUV420 and encoded via MediaCodec in
 * ByteBuffer/Image mode (no EGL), then muxed to an MP4 file.
 *
 * Encodes with real elapsed timestamps, so playback speed is correct even if the
 * capture loop can't hit the target frame rate.
 */
class VideoRecorder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int = 25,
    private val bitRate: Int = 4_000_000,
) {
    private lateinit var codec: MediaCodec
    private lateinit var muxer: MediaMuxer
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxerStarted = false
    private val pixels = IntArray(width * height)

    fun start(output: File) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    /** Encode one frame ([bitmap] must be [width]x[height]) at [ptsUs]. */
    fun encode(bitmap: Bitmap, ptsUs: Long) {
        val index = codec.dequeueInputBuffer(10_000)
        if (index >= 0) {
            val image = codec.getInputImage(index)
            if (image != null) {
                fillYuv420(image, bitmap)
                codec.queueInputBuffer(index, 0, width * height * 3 / 2, ptsUs, 0)
            } else {
                codec.queueInputBuffer(index, 0, 0, ptsUs, 0)
            }
        }
        drain(endOfStream = false)
    }

    fun finish() {
        // Signal end of stream and flush the encoder.
        val index = codec.dequeueInputBuffer(10_000)
        if (index >= 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drain(endOfStream = true)
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (muxerStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    val encoded: ByteBuffer = codec.getOutputBuffer(outIndex) ?: continue
                    // Codec config bytes are folded into the track format, skip them.
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && endOfStream) {
                // Keep waiting for EOS output.
            }
        }
    }

    /** Convert an ARGB_8888 bitmap into the encoder's YUV420 input image (BT.601). */
    private fun fillYuv420(image: android.media.Image, bitmap: Bitmap) {
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val yBuf = y.buffer; val uBuf = u.buffer; val vBuf = v.buffer
        val yRow = y.rowStride; val yPix = y.pixelStride
        val uRow = u.rowStride; val uPix = u.pixelStride
        val vRow = v.rowStride; val vPix = v.pixelStride

        for (j in 0 until height) {
            for (i in 0 until width) {
                val p = pixels[j * width + i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val yy = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuf.put(j * yRow + i * yPix, yy.coerceIn(0, 255).toByte())
            }
        }
        for (j in 0 until height / 2) {
            for (i in 0 until width / 2) {
                val p = pixels[(j * 2) * width + (i * 2)]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val uu = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val vv = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                uBuf.put(j * uRow + i * uPix, uu.coerceIn(0, 255).toByte())
                vBuf.put(j * vRow + i * vPix, vv.coerceIn(0, 255).toByte())
            }
        }
    }

    companion object {
        private const val TAG = "VideoRecorder"
    }
}
