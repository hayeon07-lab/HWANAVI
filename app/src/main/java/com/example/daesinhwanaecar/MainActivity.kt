
package com.example.daesinhwanaecar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.net.Uri

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hwanaebi.ui.HwanaebiRoute
import dev.hwanaebi.ui.HwanaebiViewModel
import dev.hwanaebi.ui.RuntimeMode
import dev.hwanaebi.ui.UnconnectedSafetyGate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs

import android.media.AudioAttributes
import dev.hwanaebi.ui.VoiceRepository

import android.os.SystemClock
import android.widget.Toast
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

import dev.hwanaebi.ui.Recording
import java.io.File
import java.util.UUID


import kotlinx.coroutines.flow.collect




// ========================================
// 대신화내CAR / 화내비
// 무료 시뮬레이션 + 음성 인식 시험
// + 날씨별 진정 대사 + 리포트
//
// 유료 생성형 AI 연결 없음
// 실제 주행 센서 / 심박 / 감정 분석 없음
// ========================================

private enum class SafetyEvent {
    NONE,
    BRAKING,
    WAITING,
    SAFE_STOP,
    SAFE_CRUISE
}

private enum class DemoMode {
    STOP,
    CRUISE,
    NO_RECOVERY
}

private enum class ReportEvent {
    BRAKING,
    PLAYED,
    BLOCKED
}

private enum class WeatherType {
    SUNNY,
    CLOUDY,
    RAINY,
    SNOWY,
    FOGGY,
    STORMY
}

private data class WeatherContext(
    val region: String?,
    val type: WeatherType?
)

// ========================================
// 급제동 이후 회복 상태 판단
// 입력 데이터는 가상 속도
// ========================================

private class SafePlaybackDetector {

    private val requiredSamples = 3

    var waitingForRecovery = false
        private set

    var stopCount = 0
        private set

    var cruiseCount = 0
        private set

    fun onSample(
        speed: Float,
        acceleration: Float
    ): SafetyEvent {

        if (acceleration <= -4.0f) {

            waitingForRecovery = true
            stopCount = 0
            cruiseCount = 0

            return SafetyEvent.BRAKING
        }

        if (!waitingForRecovery) {
            return SafetyEvent.NONE
        }

        stopCount =
            if (speed <= 2.0f) {
                stopCount + 1
            } else {
                0
            }

        cruiseCount =
            if (
                speed > 5.0f &&
                abs(acceleration) <= 0.8f
            ) {
                cruiseCount + 1
            } else {
                0
            }

        if (stopCount >= requiredSamples) {

            waitingForRecovery = false

            return SafetyEvent.SAFE_STOP
        }

        if (cruiseCount >= requiredSamples) {

            waitingForRecovery = false

            return SafetyEvent.SAFE_CRUISE
        }

        return SafetyEvent.WAITING
    }
}

// ========================================
// 오늘의 가상 주행 기록
// ========================================

private data class DemoReport(
    val day: String = "",
    val brakes: Int = 0,
    val plays: Int = 0,
    val blocked: Int = 0,
    val history: List<String> = emptyList()
)

private class DemoReportStore(
    context: Context
) {

    private val prefs =
        context.getSharedPreferences(
            "hwanaebi_demo_report",
            Context.MODE_PRIVATE
        )

    private fun today(): String =
        SimpleDateFormat(
            "yyyy-MM-dd",
            Locale.KOREA
        ).format(Date())

    private fun clock(): String =
        SimpleDateFormat(
            "HH:mm:ss",
            Locale.KOREA
        ).format(Date())

    fun read(): DemoReport {

        val day = today()

        if (prefs.getString("day", "") != day) {
            return DemoReport(day = day)
        }

        val items = mutableListOf<String>()

        val array = runCatching {

            JSONArray(
                prefs.getString(
                    "history",
                    "[]"
                ) ?: "[]"
            )

        }.getOrDefault(JSONArray())

        for (i in 0 until array.length()) {
            items.add(array.optString(i))
        }

        return DemoReport(
            day = day,
            brakes = prefs.getInt("brakes", 0),
            plays = prefs.getInt("plays", 0),
            blocked = prefs.getInt("blocked", 0),
            history = items
        )
    }

    fun record(
        event: ReportEvent
    ): DemoReport {

        val old = read()

        val detail = when (event) {

            ReportEvent.BRAKING ->
                "🚨 급제동 감지 (가상 데이터)"

            ReportEvent.PLAYED ->
                "🔊 분노 음성 재생 시작 (시연)"

            ReportEvent.BLOCKED ->
                "🛑 회복 조건 미충족 · 재생 보류"
        }

        val next = old.copy(

            brakes =
                old.brakes +
                        if (event == ReportEvent.BRAKING) 1 else 0,

            plays =
                old.plays +
                        if (event == ReportEvent.PLAYED) 1 else 0,

            blocked =
                old.blocked +
                        if (event == ReportEvent.BLOCKED) 1 else 0,

            history = (
                    listOf("${clock()}  $detail") +
                            old.history
                    ).take(30)
        )

        save(next)

        return next
    }

    fun clear(): DemoReport {

        val empty = DemoReport(
            day = today()
        )

        save(empty)

        return empty
    }

    private fun save(
        report: DemoReport
    ) {

        prefs.edit()
            .putString("day", report.day)
            .putInt("brakes", report.brakes)
            .putInt("plays", report.plays)
            .putInt("blocked", report.blocked)
            .putString(
                "history",
                JSONArray(report.history).toString()
            )
            .apply()
    }
}

// ========================================
// MainActivity
// ========================================

class MainActivity : ComponentActivity(),
    TextToSpeech.OnInitListener {

    private var player: MediaPlayer? = null

    private var tts: TextToSpeech? = null

    private var ttsReady = false

    // 음성 인식 서비스
    private var recognizer: SpeechRecognizer? = null

    private var listening by mutableStateOf(false)

    // 진정 대사 재생 작업
    private var calmJob: Job? = null

    // 날씨 조회 작업
    private var weatherJob: Job? = null

    private var latestWeather: WeatherContext? = null

    private var latestWeatherTime = 0L

    private var lastLine = ""

    private var playVersion = 0

    private var weatherInfo by mutableStateOf("날씨: 확인 전")
    private var integratedBridge: IntegratedPlaybackGateway? = null

    private lateinit var reportStore: DemoReportStore

    private var report by mutableStateOf(
        DemoReport()
    )

    // 기존 안전운전 마무리 대사
    private val safetyEnding =
        "화는 제가 대신 냈으니까, 이제 안전운전에 집중하세요."


    private val vehicleSpeedPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                startPleosVehicleMonitor()
            } else {
                Toast.makeText(
                    this,
                    "차량 속도 권한이 없어 모니터링을 시작하지 못했어요.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }


    private fun installNineVoicePacks() {

        // 설정 키, 실제 MP3 리소스, 화면에 표시할 이름
        val packs = listOf(
            Triple("ajumma_mild", R.raw.ajimae_mild, "아지매 · 순한맛"),
            Triple("ajumma_medium", R.raw.ajimae_medium, "아지매 · 중간맛"),
            Triple("ajumma_spicy", R.raw.ajimae_spicy, "아지매 · 매운맛"),

            Triple("bestie_mild", R.raw.friend_mild, "절친 · 순한맛"),
            Triple("bestie_medium", R.raw.friend_medium, "절친 · 중간맛"),
            Triple("bestie_spicy", R.raw.friend_spicy, "절친 · 매운맛"),

            Triple("gentle_mild", R.raw.kind_mild, "다정한 친구 · 순한맛"),
            Triple("gentle_medium", R.raw.kind_medium, "다정한 친구 · 중간맛"),
            Triple("gentle_spicy", R.raw.kind_spicy, "다정한 친구 · 매운맛")
        )

        val repository = VoiceRepository(this)

        val folder = File(
            filesDir,
            "hwanaebi_recordings"
        ).apply {
            mkdirs()
        }

        for ((key, resourceId, displayName) in packs) {

            val id = UUID.nameUUIDFromBytes(
                "hwanaebi-built-in-v4:$key".toByteArray(Charsets.UTF_8)
            ).toString()

            val filename = "$id.mp3"
            val destination = File(folder, filename)
            val temporary = File(folder, "$id.tmp")

            try {
                // res/raw의 실제 MP3를 앱 내부 저장소로 복사
                resources.openRawResource(resourceId).use { input ->
                    temporary.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                check(temporary.length() > 0L) {
                    "비어 있는 MP3 파일: $displayName"
                }

                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(
                        destination,
                        overwrite = true
                    )
                }

                val oldSlot = repository.load().getValue(key)

                // 이전 기본 분노 음성을 목록에서 제외
                // 사용자가 직접 가져온 MP3는 유지
                val remaining = oldSlot.recordings.filterNot {
                    it.id == id ||
                            it.name == "기존 분노 음성 (angry_voice.mp3)"
                }

                val newRecording = Recording(
                    id = id,
                    name = "$displayName (기본 음성팩)",
                    privateFilename = filename
                )

                // 사용자가 직접 선택한 다른 MP3가 있으면 유지
                val selected = oldSlot.selectedId
                    ?.takeIf { selectedId ->
                        remaining.any { it.id == selectedId }
                    }
                    ?: id

                repository.save(
                    key,
                    oldSlot.copy(
                        recordings = remaining + newRecording,
                        selectedId = selected
                    )
                )

                android.util.Log.i(
                    "HwanaebiVoice",
                    "음성팩 등록 성공: $displayName"
                )

            } catch (error: Exception) {
                android.util.Log.e(
                    "HwanaebiVoice",
                    "음성팩 등록 실패: $displayName",
                    error
                )
            } finally {
                temporary.delete()
            }
        }
    }


    private fun startPleosVehicleMonitor() {

        val isAutomotive =
            packageManager.hasSystemFeature(
                PackageManager.FEATURE_AUTOMOTIVE
            )

        if (!isAutomotive) {
            return
        }

        val permission =
            "android.car.permission.CAR_SPEED"

        val granted =
            ContextCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            vehicleSpeedPermissionLauncher.launch(
                permission
            )
            return
        }

        try {
            ContextCompat.startForegroundService(
                this,
                Intent(
                    this,
                    PleosVehicleMonitorService::class.java
                )
            )

        } catch (error: Exception) {
            android.util.Log.e(
                "HwanaebiVehicle",
                "모니터 시작 실패",
                error
            )
        }
    }


    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        reportStore = DemoReportStore(this)

        report = reportStore.read()

        // 기존 분노 음성
        player = MediaPlayer.create(
            this,
            R.raw.angry_voice
        )?.apply {

            setVolume(0.40f, 0.40f)
        }

        // 진정 대사 TTS
        tts = TextToSpeech(this, this)

        // 새 디자인을 추가하기 전, 원본 MP3를 음성팩의 최초 기본 파일로 등록합니다.
        // 원본 res/raw/angry_voice.mp3와 사용자 녹음은 삭제하지 않습니다.


        installNineVoicePacks()



        // 하나의 Gateway를 미리 듣기·3신호 엔진 양쪽에서 재사용합니다.
        // VM은 Activity 재생성에도 유지되므로 Activity 인스턴스는 약한 참조만 사용합니다.
        val bridge = IntegratedPlaybackGatewayStore.obtain(application)
        integratedBridge = bridge
        bridge.bind(this)

        val vm = ViewModelProvider(
            this,
            HwanaebiViewModel.factory(
                app = application,
                mode = RuntimeMode.PREVIEW,
                safety = UnconnectedSafetyGate,
                developer = BuildConfig.DEBUG,
                player = { bridge }
            )
        )[HwanaebiViewModel::class.java]
        bridge.isAutomaticPlaying = { vm.ui.value.engine.activeToken != null }

// 사용자가 화내비의 서비스 ON/OFF를 바꾸면
// 차량 모니터 서비스를 시작하거나 종료한다.
        lifecycleScope.launch {

            var previousEnabled =
                vm.ui.value.engine.serviceOn

            vm.ui
                .map { state ->
                    state.engine.serviceOn
                }
                .distinctUntilChanged()
                .collect { enabled ->

                    if (enabled == previousEnabled) {
                        return@collect
                    }

                    previousEnabled = enabled

                    if (enabled) {
                        startPleosVehicleMonitor()
                    } else {
                        stopService(
                            Intent(
                                this@MainActivity,
                                PleosVehicleMonitorService::class.java
                            )
                        )
                    }
                }
        }

// 차량 서비스가 저장한 실제 연결 상태를
// 기존 화내비 화면의 센서 상태에 반영한다.
        lifecycleScope.launch {

            while (true) {

                val vehiclePrefs = getSharedPreferences(
                    PleosVehicleMonitorService.PREFS_NAME,
                    Context.MODE_PRIVATE
                )

                val lastSample = vehiclePrefs.getLong(
                    "lastSampleElapsedMs",
                    -1L
                )

                val now = SystemClock.elapsedRealtime()

                val fresh =
                    lastSample >= 0L &&
                            now - lastSample in 0L..3_000L

                val connected =
                    vehiclePrefs.getBoolean(
                        "connected",
                        false
                    ) && fresh

                vm.updateVehicleConnected(connected)

                delay(1_000L)
            }
        }


        // 위치 권한이 이미 있는 사용자만 사전 날씨 조회. 권한은 화면 버튼에서 요청합니다.
        prepareWeather { weatherInfo = it }

        setContent {
            HwanaebiRoute(vm, developerContent = {
                val uiState by vm.ui.collectAsStateWithLifecycle()
                if (!uiState.driveMode) {
                    when (uiState.page) {
                        0 -> LegacyWeatherPanel(
                            status = weatherInfo,
                            refresh = { prepareWeather { weatherInfo = it } }
                        )
                        1 -> LegacySavedReportPanel(
                            report.day, report.brakes, report.plays,
                            report.blocked, report.history,
                            clear = { report = reportStore.clear() }
                        )
                    }
                    if (BuildConfig.DEBUG) {
                        IntegratedDeveloperTools(vm, stopLegacyAudio = { stopAudio() }) {
                            // 이전 MainActivity의 화면과 시뮬레이션 로직을
                            // 삭제하지 않고 DEBUG 전용 전체화면으로 보존합니다.
                            HwanaebiScreen(
                                report = report,
                                onRecord = { report = reportStore.record(it) },
                                onClearReport = { report = reportStore.clear() },
                                onPrepareWeather = { prepareWeather(it) },
                                onSpeak = { callback ->
                                    bridge.stop()
                                    playAngryVoice(callback)
                                },
                                listening = listening,
                                onStartSpeech = { update, recognized ->
                                    startSpeech(update, recognized)
                                },
                                onStopAudio = { stopAudio() }
                            )
                        }
                    }
                }
            })
        }
    }

    // Free weather lookup starts while the selected MP3 plays; playback never waits for HTTP.
    internal fun prepareWeatherForIntegratedPlayback() =
        prepareWeather { weatherInfo = it }

    // Shared audio gateway stops the *original* MP3, TTS, and microphone here.
    internal fun stopExistingAudioForBridge() = stopAudio()

    // ====================================
    // 재생 중인 음성 중지
    // ====================================

    private fun stopAudio() {

        stopSpeech()

        playVersion++

        calmJob?.cancel()
        calmJob = null

        runCatching {

            player?.let {

                if (it.isPlaying) {
                    it.pause()
                }

                it.seekTo(0)
            }
        }

        tts?.stop()
    }

    // ====================================
    // 마이크 인식 종료
    // ====================================

    private fun stopSpeech() {

        val old = recognizer

        recognizer = null

        listening = false

        runCatching {
            old?.cancel()
        }

        runCatching {
            old?.destroy()
        }
    }

    // 정상 결과 및 오류 콜백에서 해제
    private fun finishSpeech() {

        val old = recognizer

        recognizer = null

        listening = false

        runCatching {
            old?.destroy()
        }
    }

    // ====================================
    // 개선된 음성 인식
    //
    // 버튼을 눌렀을 때만 실행
    // 음성의 감정 자체를 분석하지 않음
    // ====================================

    private fun startSpeech(
        update: (String) -> Unit,
        recognized: (String) -> Unit
    ) {

        val permission =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            )

        if (
            permission !=
            PackageManager.PERMISSION_GRANTED
        ) {

            update(
                "마이크 권한이 없어요. 앱 설정에서 마이크를 허용해 주세요."
            )

            return
        }

        stopSpeech()

        // 음성 인식 서비스가 설치되어 있는지 확인
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {

            update(
                "음성 인식 서비스가 없어요. " +
                        "Google 음성 인식 서비스를 확인하고 " +
                        "에뮬레이터의 마이크 입력을 켜 주세요."
            )

            return
        }

        try {

            val service =
                SpeechRecognizer.createSpeechRecognizer(
                    this
                )

            recognizer = service

            listening = true

            service.setRecognitionListener(

                object : RecognitionListener {

                    override fun onReadyForSpeech(
                        params: Bundle?
                    ) {

                        update(
                            "🎤 듣고 있어요. '저 차 때문에 짜증나'라고 말해 보세요."
                        )
                    }

                    override fun onBeginningOfSpeech() {

                        update(
                            "🎙 목소리를 듣는 중..."
                        )
                    }

                    override fun onRmsChanged(
                        rmsdB: Float
                    ) = Unit

                    override fun onBufferReceived(
                        buffer: ByteArray?
                    ) = Unit

                    override fun onEndOfSpeech() {

                        update(
                            "⏳ 말하기 완료 · 결과 확인 중..."
                        )
                    }

                    override fun onPartialResults(
                        partialResults: Bundle?
                    ) = Unit

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?
                    ) = Unit

                    override fun onResults(
                        results: Bundle?
                    ) {

                        val text = results

                            ?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )

                            ?.firstOrNull()
                            .orEmpty()

                        finishSpeech()

                        if (text.isBlank()) {

                            update(
                                "음성 인식 결과가 비어 있어요. 다시 시도해 주세요."
                            )

                        } else {

                            recognized(text)
                        }
                    }

                    override fun onError(
                        error: Int
                    ) {

                        finishSpeech()

                        val detail = when (error) {

                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->

                                "말소리를 인식하지 못했어요. 에뮬레이터의 마이크 설정을 확인해 주세요."

                            SpeechRecognizer.ERROR_AUDIO ->

                                "마이크 입력 오류예요. Windows 마이크 접근 허용을 확인해 주세요."

                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->

                                "앱 마이크 권한이 부족해요."

                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->

                                "음성 인식 서비스의 인터넷 연결이 원활하지 않아요."

                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->

                                "음성 인식기가 사용 중이에요. 잠시 후 다시 눌러 주세요."

                            SpeechRecognizer.ERROR_SERVER,
                            SpeechRecognizer.ERROR_CLIENT ->

                                "음성 인식 서비스 오류예요. Google 음성 인식 서비스 설정을 확인해 주세요."

                            else ->

                                "음성 인식 오류 코드 $error (인식 서비스 상태 확인 필요)"
                        }

                        update(
                            "⚠️ $detail (코드: $error)"
                        )
                    }
                }
            )

            val intent = Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    "ko-KR"
                )

                putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    false
                )
            }

            update(
                "🎤 마이크 연결 중..."
            )

            service.startListening(intent)

        } catch (e: Exception) {

            stopSpeech()

            update(
                "음성 인식 시작 실패: ${e.javaClass.simpleName}"
            )
        }
    }

    // ====================================
    // 분노 음성 재생
    // ====================================

    private fun playAngryVoice(
        update: (String) -> Unit
    ): Boolean {

        stopAudio()


        // 사용자가 설정한 캐릭터 및 강도 확인
        val voicePreferences = getSharedPreferences(
            "hwanaebi_choices",
            Context.MODE_PRIVATE
        )

        val voiceKey = voicePreferences.getString(
            "voice",
            "ajumma_mild"
        ) ?: "ajumma_mild"

        val repository = VoiceRepository(this)

        // 등록된 MP3 중 매번 하나를 랜덤 선택
        val selectedMp3 = repository.load()[voiceKey]
            ?.let(repository::randomFile)
            ?: run {
                update(
                    "선택한 캐릭터의 분노 MP3가 없습니다. " +
                            "res/raw 폴더를 확인해 주세요."
                )
                return false
            }

        val mp3 = try {

            player?.release()
            player = null

            val fresh = MediaPlayer()

            try {
                fresh.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(
                            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                        )
                        .setContentType(
                            AudioAttributes.CONTENT_TYPE_SPEECH
                        )
                        .build()
                )

                fresh.setDataSource(
                    selectedMp3.absolutePath
                )

                fresh.prepare()

                val volume = voicePreferences.getFloat(
                    "volume",
                    0.45f
                ).coerceIn(0f, 0.65f)

                fresh.setVolume(volume, volume)

                player = fresh
                fresh

            } catch (error: Exception) {
                fresh.release()
                throw error
            }

        } catch (error: Exception) {
            update(
                "선택한 분노 MP3를 열지 못했습니다: ${error.message}"
            )
            return false
        }


        val version = playVersion

        mp3.setOnCompletionListener {

            if (version != playVersion) {
                return@setOnCompletionListener
            }

            calmJob = lifecycleScope.launch {

                // AI 서버 대기 없음
                delay(200L)

                if (version != playVersion) {
                    return@launch
                }

                val line =
                    selectCalmLine(
                        latestWeather
                    )

                val fullLine =
                    "$line $safetyEnding"

                update(
                    "💚 오늘의 진정 대사\n\n$fullLine"
                )

                if (ttsReady) {

                    val options =
                        Bundle().apply {

                            putFloat(
                                TextToSpeech.Engine.KEY_PARAM_VOLUME,
                                0.40f
                            )
                        }

                    val result = tts?.speak(

                        fullLine,

                        TextToSpeech.QUEUE_FLUSH,

                        options,

                        "calm_$version"

                    ) ?: TextToSpeech.ERROR

                    if (
                        result == TextToSpeech.ERROR
                    ) {

                        update(
                            "음성 출력에 실패했습니다.\n\n$fullLine"
                        )
                    }

                } else {

                    update(
                        "한국어 TTS가 준비되지 않았습니다.\n\n$fullLine"
                    )
                }
            }
        }

        mp3.setOnErrorListener {
                _, _, _ ->

            if (version == playVersion) {

                update(
                    "분노 음성 재생 오류"
                )
            }

            true
        }

        return try {

            mp3.seekTo(0)

            mp3.start()

            update(
                "🔊 분노 음성 재생 중..."
            )

            true

        } catch (_: Exception) {

            update(
                "분노 음성을 재생하지 못했습니다."
            )

            false
        }
    }

    // ====================================
    // 랜덤 진정 대사
    // ====================================

    private fun selectCalmLine(
        context: WeatherContext?
    ): String {

        val weatherLines = when (
            context?.type
        ) {

            WeatherType.SUNNY -> listOf(
                "하늘이 맑네요. 기분도 조금씩 풀어봐요.",
                "날씨가 좋아요. 마음도 가볍게 가져봐요.",
                "햇살처럼 기분도 환해지길 바라요."
            )

            WeatherType.CLOUDY -> listOf(
                "하늘이 흐려도 마음까지 흐릴 필요는 없죠.",
                "구름 낀 날이네요. 마음은 가볍게요.",
                "날씨는 흐려도 오늘 하루는 괜찮을 거예요."
            )

            WeatherType.RAINY -> listOf(
                "비 오는 날엔 조금 더 여유롭게 가요.",
                "비까지 오니 더 놀라셨겠어요.",
                "비가 오네요. 마음만은 차분하게요."
            )

            WeatherType.SNOWY -> listOf(
                "눈길에는 여유가 최고죠.",
                "눈 오는 날이니 서두르지 않아도 괜찮아요.",
                "눈이 오네요. 마음도 천천히 풀어요."
            )

            WeatherType.FOGGY -> listOf(
                "안개가 짙은 날엔 서두르지 말아요.",
                "시야가 흐리니 마음은 더 차분하게요."
            )

            WeatherType.STORMY -> listOf(
                "날씨가 요란하네요. 마음은 차분하게요.",
                "천둥번개가 치는 날엔 더 조심해요."
            )

            null -> emptyList()
        }

        val generalLines = listOf(

            "놀라셨죠? 마음을 조금 가라앉혀봐요.",

            "오늘의 화는 저한테 맡겨두세요.",

            "아이고, 정말 당황스러우셨겠어요.",

            "속상하셨죠? 그래도 잘 넘기셨어요.",

            "도착하면 달달한 간식 하나 어떠세요?",

            "오늘 하루까지 망칠 필요는 없잖아요.",

            "기분 나쁜 일은 오래 붙잡지 말아요.",

            "화나는 마음, 제가 잘 받아뒀어요.",

            "이제 조금 가벼운 마음으로 돌아와요.",

            "그런 일로 좋은 하루를 놓치긴 아깝죠.",

            "놀란 마음은 잠깐 쉬어가도 괜찮아요.",

            "한숨 대신 편안한 호흡을 해봐요.",

            "지금은 침착한 마음이 제일 멋져요.",

            "마음속 짜증은 제가 가져갈게요.",

            "자, 이제 기분 전환해 볼까요?",

            "화난 마음도 조금씩 가라앉을 거예요.",

            "천천히 마음을 추슬러봐요.",

            "좋은 하루를 계속 이어가 봐요."
        )

        val weatherChoice =
            weatherLines
                .filterNot {
                    it == lastLine
                }
                .randomOrNull()

        val generalChoice =
            generalLines
                .filterNot {
                    it == lastLine
                }
                .randomOrNull()

        // 날씨 대사 또는 일반 대사 하나만
        val chosen =
            listOfNotNull(
                weatherChoice,
                generalChoice
            ).random()

        lastLine = chosen

        return chosen
    }

    // ====================================
    // 위치 권한 확인
    // ====================================

    private fun hasLocationPermission(): Boolean =

        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).any {

            ContextCompat.checkSelfPermission(
                this,
                it
            ) == PackageManager.PERMISSION_GRANTED
        }

    // ====================================
    // 날씨 미리 조회
    // ====================================

    private fun prepareWeather(
        update: (String) -> Unit
    ) {

        if (!hasLocationPermission()) {

            latestWeather = null

            update(
                "위치 미허용 · 일반 랜덤 대사 사용"
            )

            return
        }

        val fresh =
            System.currentTimeMillis() -
                    latestWeatherTime <
                    10 * 60 * 1000L

        if (
            fresh &&
            latestWeather?.type != null
        ) {

            update(
                weatherDescription(
                    latestWeather
                )
            )

            return
        }

        if (weatherJob?.isActive == true) {
            return
        }

        update(
            "📍 지역·날씨 확인 중..."
        )

        weatherJob = lifecycleScope.launch {

            val result =
                withContext(Dispatchers.IO) {

                    loadWeather()
                }

            latestWeather = result

            latestWeatherTime =
                System.currentTimeMillis()

            update(
                weatherDescription(result)
            )
        }
    }

    private fun weatherDescription(
        w: WeatherContext?
    ): String {

        val label = when (w?.type) {

            WeatherType.SUNNY -> "맑음"

            WeatherType.CLOUDY -> "흐림"

            WeatherType.RAINY -> "비"

            WeatherType.SNOWY -> "눈"

            WeatherType.FOGGY -> "안개"

            WeatherType.STORMY -> "천둥번개"

            null ->

                return "날씨 정보 없음 · 일반 랜덤 대사 사용"
        }

        return "📍 ${w?.region ?: "현재 지역"} · $label"
    }

    // ====================================
    // 현재 위치 조회
    // ====================================

    @Suppress("MissingPermission")
    private suspend fun currentLocation():
            Location? {

        if (!hasLocationPermission()) {
            return null
        }

        val fine =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val manager =
            getSystemService(
                Context.LOCATION_SERVICE
            ) as LocationManager

        val providers = listOf(

            LocationManager.NETWORK_PROVIDER,

            LocationManager.GPS_PROVIDER

        ).filter { provider ->

            (
                    provider !=
                            LocationManager.GPS_PROVIDER ||
                            fine
                    ) &&

                    runCatching {
                        manager.isProviderEnabled(provider)
                    }.getOrDefault(false)
        }

        val cached =
            providers.mapNotNull { provider ->

                runCatching {
                    manager.getLastKnownLocation(
                        provider
                    )
                }.getOrNull()

            }.maxByOrNull {
                it.time
            }

        if (
            cached != null &&
            System.currentTimeMillis() -
            cached.time < 10 * 60 * 1000L
        ) {
            return cached
        }

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.R &&
            providers.isNotEmpty()
        ) {

            val current =
                withTimeoutOrNull(4000L) {

                    suspendCancellableCoroutine<Location?> {
                            continuation ->

                        val signal =
                            CancellationSignal()

                        continuation.invokeOnCancellation {
                            signal.cancel()
                        }

                        try {

                            manager.getCurrentLocation(

                                providers.first(),

                                signal,

                                ContextCompat.getMainExecutor(
                                    this@MainActivity
                                )

                            ) { location ->

                                if (continuation.isActive) {

                                    continuation.resume(
                                        location
                                    )
                                }
                            }

                        } catch (_: Exception) {

                            if (continuation.isActive) {

                                continuation.resume(
                                    null
                                )
                            }
                        }
                    }
                }

            if (current != null) {
                return current
            }
        }

        return cached?.takeIf {

            System.currentTimeMillis() -
                    it.time < 30 * 60 * 1000L
        }
    }

    // ====================================
    // 위치를 지역명으로 변경
    // ====================================

    @Suppress("DEPRECATION")
    private fun regionName(
        location: Location
    ): String? = runCatching {

        if (!Geocoder.isPresent()) {
            return@runCatching null
        }

        val address =
            Geocoder(
                this,
                Locale.KOREAN
            ).getFromLocation(

                location.latitude,

                location.longitude,

                1

            )?.firstOrNull()

        listOfNotNull(

            address?.adminArea,

            address?.locality
                ?: address?.subLocality

        ).distinct()
            .joinToString(" ")
            .ifBlank { null }

    }.getOrNull()

    // ====================================
    // 무료 Open-Meteo 날씨 조회
    // ====================================

    private suspend fun loadWeather():
            WeatherContext? {

        val location =
            currentLocation()
                ?: return null

        val region =
            regionName(location)

        val lat = String.format(
            Locale.US,
            "%.4f",
            location.latitude
        )

        val lon = String.format(
            Locale.US,
            "%.4f",
            location.longitude
        )

        val weather = runCatching {

            val url =
                "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=$lat" +
                        "&longitude=$lon" +
                        "&current=weather_code"

            val connection =
                URL(url).openConnection()
                        as HttpURLConnection

            try {

                connection.connectTimeout = 3500

                connection.readTimeout = 3500

                if (
                    connection.responseCode !in 200..299
                ) {

                    null

                } else {

                    val json =
                        connection.inputStream
                            .bufferedReader()
                            .use {
                                it.readText()
                            }

                    val code =
                        JSONObject(json)
                            .getJSONObject("current")
                            .optInt(
                                "weather_code",
                                -1
                            )

                    when (code) {

                        0 -> WeatherType.SUNNY

                        1, 2, 3 ->
                            WeatherType.CLOUDY

                        45, 48 ->
                            WeatherType.FOGGY

                        in 51..67,
                        in 80..82 ->
                            WeatherType.RAINY

                        in 71..77,
                        85, 86 ->
                            WeatherType.SNOWY

                        95, 96, 99 ->
                            WeatherType.STORMY

                        else -> null
                    }
                }

            } finally {

                connection.disconnect()
            }

        }.getOrNull()

        return WeatherContext(
            region,
            weather
        )
    }

    // ====================================
    // 디자인 음성팩 MP3 종료 -> 기존 날씨 대사 + 기존 한국어 TTS
    // 기존 분노 MP3 시뮬레이션과 진정 문장 선택 함수를 그대로 재사용.
    // ====================================

    internal fun speakCalmAfterSelectedRecording(
        complete: () -> Unit,
        failed: () -> Unit
    ) {
        val version = playVersion
        calmJob?.cancel()
        calmJob = lifecycleScope.launch {
            delay(200L)
            if (version != playVersion) return@launch
            val spoken = "${selectCalmLine(latestWeather)} $safetyEnding"
            val engine = tts
            if (!ttsReady || engine == null) {
                failed()
                return@launch
            }
            val utteranceId = "selected_pack_$version"
            engine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(id: String?) {
                        if (id == utteranceId) runOnUiThread {
                            if (version == playVersion) complete()
                        }
                    }
                    @Deprecated("Compatibility with old TTS implementations")
                    override fun onError(id: String?) {
                        if (id == utteranceId) runOnUiThread {
                            if (version == playVersion) failed()
                        }
                    }
                    override fun onError(id: String?, errorCode: Int) {
                        onError(id)
                    }
                }
            )
            val parameters = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.40f)
            }
            val result = engine.speak(
                spoken, TextToSpeech.QUEUE_FLUSH,
                parameters, utteranceId
            )
            if (result == TextToSpeech.ERROR && version == playVersion) failed()
        }
    }

    // ====================================
    // TTS 초기화
    // ====================================

    override fun onInit(
        status: Int
    ) {

        if (
            status == TextToSpeech.SUCCESS
        ) {

            val language =
                tts?.setLanguage(
                    Locale.KOREAN
                )

            ttsReady =
                language != null &&
                        language !=
                        TextToSpeech.LANG_MISSING_DATA &&
                        language !=
                        TextToSpeech.LANG_NOT_SUPPORTED

            tts?.setSpeechRate(0.92f)

        } else {

            ttsReady = false
        }
    }

    override fun onStop() {
        // Original debug MP3 and TTS must not continue after the app moves to background.
        stopAudio()
        super.onStop()
    }

    override fun onDestroy() {

        integratedBridge?.unbind(this)
        stopAudio()

        weatherJob?.cancel()

        player?.release()

        player = null

        tts?.shutdown()

        tts = null

        super.onDestroy()
    }
}

// ========================================
// Compose 화면
// ========================================

@Composable
private fun HwanaebiScreen(

    report: DemoReport,

    onRecord: (ReportEvent) -> Unit,

    onClearReport: () -> Unit,

    onPrepareWeather:
        ((String) -> Unit) -> Unit,

    onSpeak:
        ((String) -> Unit) -> Boolean,

    listening: Boolean,

    onStartSpeech:
        ((String) -> Unit, (String) -> Unit) -> Unit,

    onStopAudio: () -> Unit

) {

    val context = LocalContext.current

    var tab by remember {
        mutableIntStateOf(0)
    }

    var enabled by remember {
        mutableStateOf(true)
    }

    var running by remember {
        mutableStateOf(false)
    }

    var runId by remember {
        mutableIntStateOf(0)
    }

    var mode by remember {
        mutableStateOf(DemoMode.STOP)
    }

    var speed by remember {
        mutableFloatStateOf(60f)
    }

    var acceleration by remember {
        mutableFloatStateOf(0f)
    }

    var message by remember {
        mutableStateOf("주행 대기 중입니다.")
    }

    var weatherStatus by remember {
        mutableStateOf(
            "지역·날씨: 아직 조회하지 않았어요."
        )
    }

    var recognizedText by remember {
        mutableStateOf("")
    }

    val stopSpeeds = remember {
        listOf(
            60f, 58f, 55f, 25f,
            10f, 2f, 0f, 0f
        )
    }

    val cruiseSpeeds = remember {
        listOf(
            60f, 58f, 55f, 25f,
            25f, 25f, 25f
        )
    }

    val unsafeSpeeds = remember {
        listOf(
            60f, 58f, 55f, 25f,
            15f, 30f, 15f, 30f
        )
    }

    // ====================================
    // 음성 인식 결과 처리
    // ====================================

    val acceptSpeech: (String) -> Unit = {
            text ->

        recognizedText = text

        val keywords = listOf(

            "짜증",
            "끼어들",
            "깜빡이",
            "화나",
            "열받",
            "저 차",
            "뭐야",
            "진짜"
        )

        message =
            if (
                keywords.any {
                    text.contains(it)
                }
            ) {

                "🎤 인식한 말: $text\n" +
                        "분노 관련 단어 감지 " +
                        "(시연용 · 자동 음성 재생 없음)"

            } else {

                "🎤 인식한 말: $text\n" +
                        "분노 관련 단어 없음"
            }
    }

    val launchSpeech: () -> Unit = {

        onStartSpeech(

            { message = it },

            acceptSpeech
        )
    }

    // ====================================
    // 마이크 권한 요청
    // ====================================

    val micPermission =
        rememberLauncherForActivityResult(

            ActivityResultContracts.RequestPermission()

        ) { granted ->

            if (granted && enabled) {

                launchSpeech()

            } else {

                message =
                    "마이크 권한을 허용해야 음성 인식 시험을 할 수 있어요."
            }
        }

    // ====================================
    // 위치 권한 요청
    // ====================================

    val locationPermission =
        rememberLauncherForActivityResult(

            ActivityResultContracts.RequestMultiplePermissions()

        ) { permissions ->

            if (
                permissions.values.any {
                    it
                }
            ) {

                onPrepareWeather {
                    weatherStatus = it
                }

            } else {

                weatherStatus =
                    "위치 미허용 · 일반 랜덤 대사를 사용해요."
            }
        }

    // ====================================
    // 가상 주행 시작
    // ====================================

    val startDemo: (DemoMode) -> Unit = {
            selected ->

        onStopAudio()

        onPrepareWeather {
            weatherStatus = it
        }

        mode = selected

        speed = 60f

        acceleration = 0f

        message =
            "🚗 가상 주행 시뮬레이션 중..."

        running = true

        runId++

        tab = 0
    }

    // ====================================
    // 가상 주행 데이터 처리
    // ====================================

    LaunchedEffect(
        runId,
        enabled
    ) {

        if (!running || !enabled) {
            return@LaunchedEffect
        }

        val speeds = when (mode) {

            DemoMode.STOP ->
                stopSpeeds

            DemoMode.CRUISE ->
                cruiseSpeeds

            DemoMode.NO_RECOVERY ->
                unsafeSpeeds
        }

        val detector =
            SafePlaybackDetector()

        var previous =
            speeds.first()

        var played = false

        for (
        next in speeds.drop(1)
        ) {

            delay(1000L)

            speed = next

            acceleration =
                (next - previous) / 3.6f

            when (
                detector.onSample(
                    next,
                    acceleration
                )
            ) {

                SafetyEvent.BRAKING -> {

                    message =
                        "🚨 급제동 감지 · 재생 보류"

                    onRecord(
                        ReportEvent.BRAKING
                    )
                }

                SafetyEvent.WAITING -> {

                    message =
                        "⏳ 확인 중 · " +
                                "정차 ${detector.stopCount}/3 · " +
                                "안정 ${detector.cruiseCount}/3"
                }

                SafetyEvent.SAFE_STOP,
                SafetyEvent.SAFE_CRUISE -> {

                    // 안정 주행 중 재생은 시연 전용
                    val started =
                        onSpeak {
                            message = it
                        }

                    if (started) {

                        onRecord(
                            ReportEvent.PLAYED
                        )
                    }

                    played = started

                    break
                }

                SafetyEvent.NONE -> {

                    message =
                        "✅ 정상 가상 주행 중..."
                }
            }

            previous = next
        }

        if (
            !played &&
            detector.waitingForRecovery
        ) {

            message =
                "🛑 회복 조건 미충족 · 재생하지 않았습니다."

            onRecord(
                ReportEvent.BLOCKED
            )
        }

        running = false
    }

    // ====================================
    // 화면: 홈 / 리포트
    // ====================================

    Scaffold(

        bottomBar = {

            NavigationBar(
                containerColor = Color(
                    0xFF192337
                )
            ) {

                NavigationBarItem(

                    selected = tab == 0,

                    onClick = {
                        tab = 0
                    },

                    icon = {
                        Text("🚗")
                    },

                    label = {
                        Text("홈")
                    }
                )

                NavigationBarItem(

                    selected = tab == 1,

                    onClick = {
                        tab = 1
                    },

                    icon = {
                        Text("📊")
                    },

                    label = {
                        Text("리포트")
                    }
                )
            }
        }

    ) { padding ->

        Column(

            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(20.dp),

            verticalArrangement =
                Arrangement.spacedBy(12.dp)

        ) {

            Text(
                "🚗 화내비",
                style =
                    MaterialTheme.typography.headlineMedium
            )

            Text(
                "화는 대신 내고, 운전은 안전하게.",
                color = Color(0xFFB6C1D5)
            )

            if (tab == 0) {

                // ============================
                // 홈 화면
                // ============================

                Card(
                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Column(

                        Modifier.padding(16.dp),

                        verticalArrangement =
                            Arrangement.spacedBy(10.dp)

                    ) {

                        Row(
                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {

                            Text(
                                "시뮬레이션 모니터링",
                                modifier =
                                    Modifier.weight(1f)
                            )

                            Switch(

                                checked = enabled,

                                onCheckedChange = {

                                    enabled = it

                                    if (!it) {

                                        running = false

                                        runId++

                                        onStopAudio()

                                        message =
                                            "서비스가 꺼졌습니다."

                                    } else {

                                        message =
                                            "주행 대기 중입니다."
                                    }
                                }
                            )
                        }

                        Text(
                            "${speed.toInt()} km/h",
                            style =
                                MaterialTheme.typography.headlineLarge
                        )

                        Text(
                            "가속도 ${
                                String.format(
                                    Locale.US,
                                    "%.2f",
                                    acceleration
                                )
                            } m/s²"
                        )

                        Text(
                            weatherStatus,
                            style =
                                MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Text(
                    "주행 상황 시험",
                    style =
                        MaterialTheme.typography.titleMedium
                )

                Button(

                    onClick = {
                        startDemo(DemoMode.STOP)
                    },

                    enabled =
                        enabled && !running,

                    modifier =
                        Modifier.fillMaxWidth()

                ) {

                    Text(
                        "① 급제동 → 정차 → 음성 재생"
                    )
                }

                Button(

                    onClick = {
                        startDemo(DemoMode.CRUISE)
                    },

                    enabled =
                        enabled && !running,

                    modifier =
                        Modifier.fillMaxWidth()

                ) {

                    Text(
                        "② 급제동 → 안정 주행 → 시연"
                    )
                }

                OutlinedButton(

                    onClick = {
                        startDemo(
                            DemoMode.NO_RECOVERY
                        )
                    },

                    enabled =
                        enabled && !running,

                    modifier =
                        Modifier.fillMaxWidth()

                ) {

                    Text(
                        "③ 안전 미확인 → 재생 안 함"
                    )
                }

                // 위치 및 날씨
                OutlinedButton(

                    modifier =
                        Modifier.fillMaxWidth(),

                    enabled = enabled,

                    onClick = {

                        val permitted = listOf(

                            Manifest.permission.ACCESS_FINE_LOCATION,

                            Manifest.permission.ACCESS_COARSE_LOCATION

                        ).any {

                            ContextCompat.checkSelfPermission(
                                context,
                                it
                            ) ==
                                    PackageManager.PERMISSION_GRANTED
                        }

                        if (permitted) {

                            onPrepareWeather {
                                weatherStatus = it
                            }

                        } else {

                            locationPermission.launch(
                                arrayOf(

                                    Manifest.permission.ACCESS_COARSE_LOCATION,

                                    Manifest.permission.ACCESS_FINE_LOCATION
                                )
                            )
                        }
                    }

                ) {

                    Text(
                        "📍 현재 지역·날씨 확인 (선택)"
                    )
                }

                // 개선된 음성 인식 버튼
                OutlinedButton(

                    modifier =
                        Modifier.fillMaxWidth(),

                    enabled =
                        enabled && !running,

                    onClick = {

                        if (listening) {

                            onStopAudio()

                            message =
                                "🎤 음성 인식을 중지했어요."

                        } else {

                            onStopAudio()

                            val micGranted =
                                ContextCompat.checkSelfPermission(

                                    context,

                                    Manifest.permission.RECORD_AUDIO

                                ) ==
                                        PackageManager.PERMISSION_GRANTED

                            if (micGranted) {

                                launchSpeech()

                            } else {

                                micPermission.launch(
                                    Manifest.permission.RECORD_AUDIO
                                )
                            }
                        }
                    }

                ) {

                    Text(
                        if (listening) {

                            "🎤 음성 인식 중지"

                        } else {

                            "🎤 음성 인식 시험 (정차 중만)"
                        }
                    )
                }

                OutlinedButton(

                    modifier =
                        Modifier.fillMaxWidth(),

                    onClick = {

                        onStopAudio()

                        message =
                            "🔇 음성을 중지했습니다."
                    }

                ) {

                    Text("음성 중지")
                }

                if (
                    recognizedText.isNotBlank()
                ) {

                    Text(
                        "인식한 말: $recognizedText"
                    )
                }

                Card(
                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Text(
                        message,
                        modifier =
                            Modifier.padding(16.dp)
                    )
                }

                Text(

                    "※ 실제 차량 센서·심박·음성 감정 분석은 아직 연결되지 않았어요. " +
                            "시뮬레이션은 정차 상태에서 시험하세요.",

                    style =
                        MaterialTheme.typography.bodySmall,

                    color = Color(0xFFAAB6CB)
                )

            } else {

                // ============================
                // 리포트 화면
                // ============================

                Text(
                    "오늘의 시뮬레이션 리포트",
                    style =
                        MaterialTheme.typography.titleLarge
                )

                Text(
                    "${report.day} · 가상 주행 결과",
                    color = Color(0xFFB6C1D5)
                )

                Row(
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {

                    ReportNumber(
                        "급제동 감지",
                        report.brakes,
                        Modifier.weight(1f)
                    )

                    ReportNumber(
                        "음성 재생",
                        report.plays,
                        Modifier.weight(1f)
                    )

                    ReportNumber(
                        "재생 보류",
                        report.blocked,
                        Modifier.weight(1f)
                    )
                }

                Text(
                    "최근 이벤트",
                    style =
                        MaterialTheme.typography.titleMedium
                )

                Card(
                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Column(

                        modifier =
                            Modifier.padding(16.dp),

                        verticalArrangement =
                            Arrangement.spacedBy(12.dp)

                    ) {

                        if (
                            report.history.isEmpty()
                        ) {

                            Text(
                                "아직 기록이 없어요. 홈에서 가상 주행을 시험해 보세요."
                            )

                        } else {

                            report.history.forEach {
                                    item ->

                                Text(
                                    item,
                                    style =
                                        MaterialTheme.typography.bodyMedium
                                )

                                HorizontalDivider(
                                    color = Color(
                                        0xFF364157
                                    )
                                )
                            }
                        }
                    }
                }

                OutlinedButton(

                    onClick = onClearReport,

                    modifier =
                        Modifier.fillMaxWidth()

                ) {

                    Text(
                        "오늘의 시뮬레이션 기록 초기화"
                    )
                }

                Text(

                    "기록은 이 스마트폰에만 저장되며, 날짜가 바뀌면 새 기록으로 시작해요. " +
                            "실제 운전 안전성·스트레스 측정 결과가 아닙니다.",

                    style =
                        MaterialTheme.typography.bodySmall,

                    color = Color(0xFFAAB6CB)
                )
            }
        }
    }
}

// ========================================
// 리포트 숫자 표시
// ========================================

@Composable
private fun ReportNumber(

    title: String,

    number: Int,

    modifier: Modifier = Modifier

) {

    Card(
        modifier = modifier
    ) {

        Column(

            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    vertical = 15.dp,
                    horizontal = 6.dp
                ),

            horizontalAlignment =
                Alignment.CenterHorizontally

        ) {

            Text(
                number.toString(),
                style =
                    MaterialTheme.typography.headlineMedium
            )

            Text(
                title,
                style =
                    MaterialTheme.typography.labelSmall
            )
        }
    }
}
