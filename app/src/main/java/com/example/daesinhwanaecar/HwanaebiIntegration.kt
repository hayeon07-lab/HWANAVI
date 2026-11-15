package com.example.daesinhwanaecar

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.hwanaebi.ui.PlaybackGateway
import dev.hwanaebi.ui.RecordedMp3Player
import dev.hwanaebi.ui.Recording
import dev.hwanaebi.ui.VoiceRepository
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID

/**
 * Existing raw audio is COPIED once into the new voice catalog.
 * Does not overwrite an existing user selection or change res/raw/angry_voice.mp3.
 * Defaults to the first selectable slot (ajumma_mild); character identity is a UI demo label.
 */
internal object BundledVoiceInstaller {
    fun installIfFirstLaunch(context: Context) {
        val flags = context.getSharedPreferences("hwanaebi_original_voice_seed_v1", Context.MODE_PRIVATE)
        if (flags.getBoolean("attempted", false)) return
        runCatching {
            val repository = VoiceRepository(context)
            val key = "ajumma_mild"
            val original = repository.load().getValue(key)
            if (original.recordings.isEmpty()) {
                val uuid = UUID.randomUUID().toString()
                val filename = "$uuid.mp3"
                val folder = File(context.filesDir, "hwanaebi_recordings").apply { mkdirs() }
                val dest = File(folder, filename)
                try {
                    context.resources.openRawResource(R.raw.angry_voice).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    check(dest.length() > 0) { "기존 음성 파일이 비어 있어요." }
                    val item = Recording(uuid, "기존 분노 음성 (angry_voice.mp3)", filename)
                    repository.save(key, original.copy(recordings = listOf(item), selectedId = item.id))
                } catch (problem: Exception) {
                    dest.delete()
                    throw problem
                }
            }
            flags.edit().putBoolean("attempted", true).apply()
        }
    }
}

/**
 * One gateway used by the design's preview and automated demo.
 * It delegates MP3 playback to the DESIGN's existing RecordedMp3Player.
 * For an actual engine-controlled playback (not preview), it then calls the
 * EXISTING MainActivity weather/random Korean TTS function.
 * Sensor/safety permits are still owned by the design's StressEngine.
 */
internal class IntegratedPlaybackGateway(context: Context) : PlaybackGateway {
    private val recorded = RecordedMp3Player(context.applicationContext)
    private var boundActivity: WeakReference<MainActivity>? = null
    private var generation = 0L
    private var disposed = false

    /** This reads the design engine's active token; the preview has no token. */
    var isAutomaticPlaying: () -> Boolean = { false }

    fun bind(activity: MainActivity) { boundActivity = WeakReference(activity) }
    fun unbind(activity: MainActivity) {
        if (boundActivity?.get() === activity) boundActivity = null
    }
    fun isDisposed(): Boolean = disposed

    override fun play(
        file: File,
        volume: Float,
        stillPermitted: () -> Boolean,
        started: () -> Unit,
        ended: () -> Unit,
        failed: () -> Unit,
        interrupted: () -> Unit
    ) {
        stop()
        if (disposed) { failed(); return }
        val ticket = generation
        val playFollowup = isAutomaticPlaying()
        val host = boundActivity?.get()
        if (host == null) { failed(); return }
        if (playFollowup) host.prepareWeatherForIntegratedPlayback()

        recorded.play(
            file = file,
            volume = volume,
            stillPermitted = stillPermitted,
            started = started,
            ended = {
                if (generation == ticket) {
                    if (!playFollowup) {
                        // '미리 듣기' = selected MP3 only, no calming voice.
                        ended()
                    } else if (!stillPermitted()) {
                        interrupted()
                    } else {
                        val currentHost = boundActivity?.get()
                        if (currentHost == null) failed()
                        else currentHost.speakCalmAfterSelectedRecording(
                            complete = {
                                if (ticket == generation) {
                                    if (stillPermitted()) ended() else interrupted()
                                }
                            },
                            failed = { if (ticket == generation) failed() }
                        )
                    }
                }
            },
            failed = { if (ticket == generation) failed() },
            interrupted = { if (ticket == generation) interrupted() }
        )
    }

    override fun stop() {
        generation++
        recorded.stop()
        boundActivity?.get()?.stopExistingAudioForBridge()
    }

    override fun setVolume(volume: Float) = recorded.setVolume(volume)

    override fun release() {
        stop()
        disposed = true
        boundActivity = null
        isAutomaticPlaying = { false }
    }
}

/** VM survives a configuration change; bind to the current Activity via weak ref. */
internal object IntegratedPlaybackGatewayStore {
    private var existing: IntegratedPlaybackGateway? = null

    fun obtain(app: Application): IntegratedPlaybackGateway = synchronized(this) {
        existing?.takeUnless { it.isDisposed() }
            ?: IntegratedPlaybackGateway(app).also { existing = it }
    }
}

/** Supplements the unchanged Codex HOME screen with the original free weather feature. */
@Composable
internal fun LegacyWeatherPanel(status: String, refresh: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) refresh()
    }
    Surface(
        color = androidx.compose.ui.graphics.Color(0xFF151E26),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("기존 날씨 맞춤 진정 안내", style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.bodySmall)
            Text("무료 Open-Meteo · 현재 위치는 날씨 확인에만 사용", style = MaterialTheme.typography.labelSmall)
            OutlinedButton(onClick = {
                val permission = listOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ).any {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
                if (permission) refresh() else launcher.launch(arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ))
            }) { Text("📍 현재 위치·날씨 확인") }
        }
    }
}

/** Old persisted simulation report is NOT merged with the design engine's session counters. */
@Composable
internal fun LegacySavedReportPanel(
    day: String, brakes: Int, plays: Int, blocked: Int,
    entries: List<String>, clear: () -> Unit
) {
    Surface(
        color = androidx.compose.ui.graphics.Color(0xFF151E26),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("기존 앱 · 저장된 가상 주행 기록", style = MaterialTheme.typography.titleMedium)
            Text("$day · 급제동 ${brakes}회 / 녹음 재생 ${plays}회 / 보류 ${blocked}회")
            Text("이전 시뮬레이션 기록이며, 위의 3신호 엔진 현재 세션 기록과 합산하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            entries.take(5).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (entries.isNotEmpty()) OutlinedButton(onClick = clear) { Text("기존 시뮬레이션 기록만 초기화") }
        }
    }
}
