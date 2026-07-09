package us.creativeworks.nomad.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import us.creativeworks.nomad.R
import kotlin.math.abs

/**
 * Lightweight game-audio layer: short UI cues plus a looping engine drone whose
 * pitch and volume track the throttle. Backed by [SoundPool] (low-latency,
 * fire-and-forget), safe to call from the main thread.
 *
 * All samples are original synthesized tones (see tools/synth_sfx.py) — no audio
 * is taken from the original app, so nothing here is copyright-encumbered.
 *
 * Muting is honored per-cue and stops the engine loop; it does not tear down the
 * pool, so unmuting is instant.
 */
class SoundManager(context: Context) {

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private enum class Cue(val res: Int) {
        CONNECT(R.raw.sfx_connect),
        DISCONNECT(R.raw.sfx_disconnect),
        STOP(R.raw.sfx_stop),
        LED_ON(R.raw.sfx_led_on),
        LED_OFF(R.raw.sfx_led_off),
        TRIM_UP(R.raw.sfx_trim_up),
        TRIM_DN(R.raw.sfx_trim_dn),
        PHOTO(R.raw.sfx_photo),
        REC(R.raw.sfx_rec),
    }

    private val soundIds = HashMap<Cue, Int>()
    private val loaded = HashSet<Int>()
    private var engineSoundId = 0
    private var engineStreamId = 0

    /** Gates the one-shot UI cues (connect, LED, trim, capture). */
    @Volatile var uiEnabled: Boolean = true

    /** Gates the looping engine drone. Turning it off silences the loop at once. */
    @Volatile var engineEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) stopEngine()
        }

    // Deferred engine start: the pool loads asynchronously, so if the caller asks
    // for the engine before its sample is ready, start it on load completion.
    private var engineWanted = false

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                loaded.add(sampleId)
                if (sampleId == engineSoundId && engineWanted) startEngineNow()
            }
        }
        Cue.entries.forEach { soundIds[it] = pool.load(context, it.res, 1) }
        engineSoundId = pool.load(context, R.raw.eng_loop, 1)
    }

    // --- One-shot cues -------------------------------------------------------

    fun connect() = play(Cue.CONNECT)
    fun disconnect() = play(Cue.DISCONNECT)
    fun stop() = play(Cue.STOP)
    fun ledOn() = play(Cue.LED_ON)
    fun ledOff() = play(Cue.LED_OFF)
    fun trimUp() = play(Cue.TRIM_UP)
    fun trimDown() = play(Cue.TRIM_DN)
    fun photo() = play(Cue.PHOTO)
    fun record() = play(Cue.REC)

    private fun play(cue: Cue, volume: Float = 0.9f, rate: Float = 1f) {
        if (!uiEnabled) return
        val id = soundIds[cue] ?: return
        if (id !in loaded) return  // still loading; skip rather than glitch
        pool.play(id, volume, volume, 1, 0, rate)
    }

    // --- Engine loop ---------------------------------------------------------

    /** Begin the looping engine drone (idempotent). No-op while muted. */
    fun startEngine() {
        if (!engineEnabled) return
        engineWanted = true
        if (engineStreamId == 0) startEngineNow()
    }

    private fun startEngineNow() {
        if (engineSoundId !in loaded || engineStreamId != 0) return
        // Start quiet at idle pitch; setEngineThrottle() shapes it live.
        engineStreamId = pool.play(engineSoundId, IDLE_VOL, IDLE_VOL, 1, -1, IDLE_RATE)
    }

    /** Stop the engine drone. */
    fun stopEngine() {
        engineWanted = false
        if (engineStreamId != 0) {
            pool.stop(engineStreamId)
            engineStreamId = 0
        }
    }

    /**
     * Modulate the engine drone from the current throttle magnitude (0..1,
     * absolute value of forward/reverse). Louder and higher-pitched under power.
     */
    fun setEngineThrottle(magnitude: Float) {
        if (!engineEnabled || engineStreamId == 0) return
        val m = abs(magnitude).coerceIn(0f, 1f)
        val rate = IDLE_RATE + (MAX_RATE - IDLE_RATE) * m
        val vol = IDLE_VOL + (MAX_VOL - IDLE_VOL) * m
        pool.setRate(engineStreamId, rate)
        pool.setVolume(engineStreamId, vol, vol)
    }

    fun release() {
        pool.release()
    }

    private companion object {
        const val IDLE_RATE = 0.85f
        const val MAX_RATE = 1.7f
        const val IDLE_VOL = 0.28f
        const val MAX_VOL = 0.85f
    }
}
