package dev.hwanaebi.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File

/** Implement this using the existing, independently computed vehicle safety evaluator. */
interface ExistingSafetyGate {
    fun isAudioSafeNow(): Boolean
    fun isSafelyParkedNow(): Boolean
}
object UnconnectedSafetyGate : ExistingSafetyGate {
    override fun isAudioSafeNow() = false
    override fun isSafelyParkedNow() = false
}
/** Host may inject a gateway that calls the EXISTING player. Never run two competing players. */
interface PlaybackGateway {
    fun play(file: File, volume: Float, stillPermitted: () -> Boolean, started: () -> Unit,
        ended: () -> Unit, failed: () -> Unit, interrupted: () -> Unit)
    fun stop()
    fun setVolume(volume: Float)
    fun release() = stop()
}
/** All calls, including completion/focus listeners, must be on the main thread. */
class RecordedMp3Player(context: Context) : PlaybackGateway {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var request: AudioFocusRequest? = null
    private var generation = 0L
    private var prepareTimeout: Runnable? = null
    override fun stop() {
        generation++
        prepareTimeout?.let(handler::removeCallbacks); prepareTimeout = null
        val old = player; player = null
        old?.setOnPreparedListener(null); old?.setOnCompletionListener(null); old?.setOnErrorListener(null)
        runCatching { old?.release() }
        request?.let(manager::abandonAudioFocusRequest); request = null
    }
    override fun setVolume(volume: Float) { val capped = volume.coerceIn(0f, .65f); runCatching { player?.setVolume(capped, capped) } }
    override fun play(file: File, volume: Float, stillPermitted: () -> Boolean, started: () -> Unit,
        ended: () -> Unit, failed: () -> Unit, interrupted: () -> Unit) {
        stop()
        if (!file.isFile || !stillPermitted()) { failed(); return }
        val token = generation
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false).setOnAudioFocusChangeListener({ change ->
                if (change < 0 && generation == token) { stop(); interrupted() }
            }, handler).build()
        request = focus
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { stop(); failed(); return }
        try {
            val media = MediaPlayer(); player = media
            media.setAudioAttributes(attrs); media.setDataSource(file.absolutePath); setVolume(volume)
            media.setOnPreparedListener {
                if (generation != token) return@setOnPreparedListener
                prepareTimeout?.let(handler::removeCallbacks); prepareTimeout = null
                if (!stillPermitted()) { stop(); interrupted() }
                else runCatching { media.start(); started() }.onFailure { stop(); failed() }
            }
            media.setOnCompletionListener { if (generation == token) { stop(); ended() } }
            media.setOnErrorListener { _, _, _ -> if (generation == token) { stop(); failed() }; true }
            prepareTimeout = Runnable { if (generation == token) { stop(); failed() } }.also { handler.postDelayed(it, 10_000) }
            media.prepareAsync()
        } catch (_: Exception) { stop(); failed() }
    }
}
