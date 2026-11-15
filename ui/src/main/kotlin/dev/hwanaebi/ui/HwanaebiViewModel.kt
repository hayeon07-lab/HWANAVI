package dev.hwanaebi.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.hwanaebi.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

enum class RuntimeMode { PREVIEW, DEMO, LIVE }
data class DriveEvent(val at: Instant, val label: String, val suspect: Boolean = false)
data class HwanaebiUiState(val engine: EngineState = EngineState(), val policy: EnginePolicy = EnginePolicy(), val mode: RuntimeMode = RuntimeMode.PREVIEW,
    val page: Int = 0, val tripEnded: Boolean = false, val driveMode: Boolean = false, val safelyParked: Boolean = false,
    val selectedKey: String = "ajumma_mild", val slots: Map<String, VoiceSlot> = emptyMap(), val volume: Float = .45f,
    val previewPlaying: Boolean = false, val importing: Boolean = false, val weekly: Boolean = false, val rating: Int = 0,
    val events: List<DriveEvent> = emptyList(), val message: String? = null, val nowMs: Long = 0) {
    val spec get() = VoiceCatalog.find(selectedKey)
    val slot get() = slots.getValue(selectedKey)
    val canEdit get() = (mode == RuntimeMode.PREVIEW || safelyParked) && engine.activeToken == null
}
/** UI owner / serialized integration boundary. No microphone, vehicle or watch collector is fabricated here. */
class HwanaebiViewModel(application: Application, private val initialMode: RuntimeMode = RuntimeMode.PREVIEW,
    private val existingSafety: ExistingSafetyGate = UnconnectedSafetyGate,
    private val allowDeveloperMode: Boolean = false,
    playerFactory: ((Application) -> PlaybackGateway)? = null, policy: EnginePolicy = EnginePolicy()) : AndroidViewModel(application), HwanaebiActions {
    private val repo = VoiceRepository(application)
    private val choices = application.getSharedPreferences("hwanaebi_choices", 0)
    private val engine = StressEngine(policy)
    private val player = playerFactory?.invoke(application) ?: RecordedMp3Player(application)
    private val queue = Channel<() -> Unit>(Channel.UNLIMITED)
    private val initialKey = choices.getString("voice", "ajumma_mild")!!.takeIf { key -> VoiceCatalog.entries.any { it.key == key } } ?: "ajumma_mild"
    private val _ui = MutableStateFlow(HwanaebiUiState(policy = policy, mode = initialMode, slots = repo.load(), selectedKey = initialKey,
        volume = choices.getFloat("volume", .45f).coerceIn(0f, .65f)))
    val ui = _ui.asStateFlow()
    private var screenActive = true
    private var demoSafe = false
    private var demoParked = true
    init {
        viewModelScope.launch(Dispatchers.Main.immediate) { for (task in queue) task() }
        post { process(EngineInput.Consent(choices.getBoolean("consent", false))); syncVoice() }
        viewModelScope.launch { while (isActive) { delay(250); post {
            if (_ui.value.mode == RuntimeMode.DEMO && screenActive) process(EngineInput.Safety(SafetySample(demoSafe, demoParked, now())))
            process(EngineInput.Tick)
        } } }
    }
    private fun now() = SystemClock.elapsedRealtime()
    private fun post(block: () -> Unit) { queue.trySend(block) }
    private fun note(text: String) { _ui.value = _ui.value.copy(message = text) }
    override fun dismissMessage() = post { _ui.value = _ui.value.copy(message = null) }
    private fun externalAudioSafe() = _ui.value.mode == RuntimeMode.DEMO || runCatching { existingSafety.isAudioSafeNow() }.getOrDefault(false)
    private fun parked() = _ui.value.mode == RuntimeMode.PREVIEW || (engine.parked(now()) &&
        (_ui.value.mode == RuntimeMode.DEMO || runCatching { existingSafety.isSafelyParkedNow() }.getOrDefault(false)))
    private fun log(label: String, suspect: Boolean = false) { _ui.value = _ui.value.copy(events = (listOf(DriveEvent(Instant.now(), label, suspect)) + _ui.value.events).take(100)) }
    private fun process(input: EngineInput) {
        if (engine.state.activeToken != null && input != EngineInput.Hazard && (!screenActive || !externalAudioSafe())) process(EngineInput.Hazard)
        val previous = engine.state
        val effects = engine.accept(input, now())
        if (engine.state.suspected > previous.suspected) log("세 조건 AND 충족 · 스트레스 의심 이벤트", true)
        if (engine.state.playbackCount > previous.playbackCount) log("선택한 팀원 녹음 재생 시작")
        if (engine.state.heldCount > previous.heldCount) log("안전 확인 대기 · 재생 보류")
        if (engine.state.completed && !previous.completed) log("재생 완료 · 쿨다운 적용")
        val safeParked = parked()
        _ui.value = _ui.value.copy(engine = engine.state, safelyParked = safeParked && _ui.value.mode != RuntimeMode.PREVIEW, nowMs = now(),
            driveMode = _ui.value.driveMode || (_ui.value.mode == RuntimeMode.LIVE && engine.state.readiness.vehicle && !safeParked))
        for (effect in effects) when (effect) {
            EngineEffect.Stop -> { player.stop(); _ui.value = _ui.value.copy(previewPlaying = false) }
            is EngineEffect.Play -> {
                _ui.value = _ui.value.copy(previewPlaying = false)

                val file = _ui.value.slots[effect.voiceKey]
                    ?.let(repo::randomFile)

                if (file == null || !screenActive || !engine.permits(effect.token, now()) || !externalAudioSafe()) { post { process(EngineInput.PlaybackFailed(effect.token)) }; continue }
                player.play(file, _ui.value.volume,
                    stillPermitted = { screenActive && engine.permits(effect.token, now()) && externalAudioSafe() },
                    started = { post { process(EngineInput.PlaybackStarted(effect.token)) } },
                    ended = { post { process(EngineInput.PlaybackEnded(effect.token)) } },
                    failed = { post { process(EngineInput.PlaybackFailed(effect.token)) } },
                    interrupted = { post { process(EngineInput.PlaybackInterrupted(effect.token)) } })
            }
        }
    }

    private fun syncVoice() {
        val state = _ui.value
        process(
            EngineInput.Voice(
                state.selectedKey,
                repo.hasRecording(state.slot)
            )
        )
    }


    /**
     * 실제 차량 속도 신호 연결 여부만 업데이트.
     * 목소리/심박 연결을 가짜로 활성화하지 않는다.
     */
    fun updateVehicleConnected(connected: Boolean) = post {

        if (_ui.value.mode == RuntimeMode.DEMO) {
            return@post
        }

        val readiness = engine.state.readiness

        if (readiness.vehicle != connected) {
            process(
                EngineInput.Connections(
                    readiness.copy(
                        vehicle = connected
                    )
                )
            )
        }
    }


    /** Wire existing collectors to this method. capturedAtMs MUST use elapsedRealtime, not epoch milliseconds. */
    fun acceptSensor(input: EngineInput) = post {
        if (_ui.value.mode != RuntimeMode.LIVE || !screenActive) return@post
        if (input is EngineInput.Changed || input is EngineInput.Safety || input is EngineInput.Connections || input == EngineInput.Hazard) process(input)
    }
    fun setScreenActive(active: Boolean) = post { screenActive = active; if (!active) { player.stop(); _ui.value = _ui.value.copy(previewPlaying = false); process(EngineInput.Suspend) } }
    override fun page(index: Int) = post { if (_ui.value.driveMode || !parked()) { note("상세 메뉴는 안전한 정차 확인 후 이용해 주세요."); return@post }
        stopPreview(); _ui.value = _ui.value.copy(page = index.coerceIn(0, 3)) }
    override fun drive() = post { _ui.value = _ui.value.copy(tripEnded = false); stopPreview(); if (_ui.value.mode == RuntimeMode.DEMO) { demoParked = false; process(EngineInput.Safety(SafetySample(demoSafe, false, now()))) }; _ui.value = _ui.value.copy(driveMode = true) }
    override fun leaveDrive() = post { if (!parked()) { note("안전한 정차 확인이 필요합니다. 실제 상태 미연동 시 복귀할 수 없습니다."); return@post }; _ui.value = _ui.value.copy(driveMode = false) }
    override fun endTrip() = post { if (!parked()) { note("주행 종료는 안전한 정차 확인 후 가능합니다."); return@post }
        player.stop(); process(EngineInput.Suspend); log("주행 종료 · 리포트 안내")
        _ui.value = _ui.value.copy(driveMode = false, tripEnded = true, page = 0, previewPlaying = false) }
    fun demoEndTrip() = post { if (_ui.value.mode == RuntimeMode.DEMO && allowDeveloperMode) {
        demoParked = true; demoSafe = false; process(EngineInput.Safety(SafetySample(false, true, now()))); endTrip() } }
    override fun service(on: Boolean) = post { if (parked()) { stopPreview(); process(EngineInput.Service(on)) } else note("정차 상태에서 변경해 주세요.") }
    override fun consent(allowed: Boolean) = post { if (parked()) { choices.edit().putBoolean("consent", allowed).apply(); process(EngineInput.Consent(allowed)) } }
    override fun chooseVoice(character: Int?, flavor: Int?) = post {
        if (!_ui.value.canEdit) return@post
        stopPreview(); val old = _ui.value.selectedKey.split('_')
        val key = "${character?.let { VoiceCatalog.characterIds[it] } ?: old[0]}_${flavor?.let { VoiceCatalog.flavorIds[it] } ?: old[1]}"
        _ui.value = _ui.value.copy(selectedKey = key); choices.edit().putString("voice", key).apply(); syncVoice()
    }
    private fun updateSlot(key: String, slot: VoiceSlot) { repo.save(key, slot); _ui.value = _ui.value.copy(slots = _ui.value.slots + (key to slot)) }
    override fun importMp3(uri: Uri) = post {
        if (!_ui.value.canEdit || _ui.value.importing) return@post
        val key = _ui.value.selectedKey; _ui.value = _ui.value.copy(importing = true)
        viewModelScope.launch { val result = withContext(Dispatchers.IO) { runCatching { repo.importRecording(uri) } }
            post { _ui.value = _ui.value.copy(importing = false); result.fold(onSuccess = { recording ->
                val slot = _ui.value.slots.getValue(key); updateSlot(key, slot.copy(recordings = slot.recordings + recording, selectedId = recording.id))
                // Stop only preview; an active automatic playback keeps its immutable file until completion.
                if (_ui.value.selectedKey == key && engine.state.activeToken == null) { stopPreview(); syncVoice() }
                note("MP3를 앱 내부 저장소에 추가했어요.")
            }, onFailure = { note(it.message ?: "녹음 파일을 확인해 주세요.") }) }
        }
    }
    override fun chooseRecording(id: String) = post { if (!_ui.value.canEdit) return@post; val slot = _ui.value.slot
        if (slot.recordings.none { it.id == id }) return@post
        stopPreview(); updateSlot(_ui.value.selectedKey, slot.copy(selectedId = id)); syncVoice() }
    override fun volume(value: Float) = post { if (!parked()) return@post; val volume = value.coerceIn(0f, .65f)
        _ui.value = _ui.value.copy(volume = volume); choices.edit().putFloat("volume", volume).apply(); player.setVolume(volume) }
    private fun stopPreview() { if (_ui.value.previewPlaying) player.stop(); _ui.value = _ui.value.copy(previewPlaying = false) }
    override fun preview() = post {
        if (!parked() || engine.state.activeToken != null) return@post
        if (_ui.value.previewPlaying) { stopPreview(); return@post }

        val file = repo.randomFile(_ui.value.slot) ?: run {
            note("녹음 파일 준비 중")
            return@post
        }

        _ui.value = _ui.value.copy(previewPlaying = true)
        player.play(file, _ui.value.volume, stillPermitted = { screenActive && parked() && engine.state.activeToken == null },
            started = {}, ended = { post { _ui.value = _ui.value.copy(previewPlaying = false) } },
            failed = { post { _ui.value = _ui.value.copy(previewPlaying = false); note("파일 형식 또는 오디오 포커스를 확인해 주세요.") } },
            interrupted = { post { _ui.value = _ui.value.copy(previewPlaying = false) } })
    }
    override fun weekly(value: Boolean) = post { _ui.value = _ui.value.copy(weekly = value) }
    override fun rate(value: Int) = post { _ui.value = _ui.value.copy(rating = value) }
    override fun deleteData() = post { if (!_ui.value.canEdit) return@post; player.stop(); process(EngineInput.Service(false)); repo.deleteAll()
        choices.edit().clear().apply(); _ui.value = _ui.value.copy(slots = repo.load(), previewPlaying = false, events = emptyList(), rating = 0)
        process(EngineInput.Consent(false)); syncVoice(); note("녹음 MP3와 음성팩 설정을 삭제했어요. 이벤트 카운터는 현재 세션 값입니다.") }
    /** Debug-only UI must opt in. This API never accepts demo inputs in a LIVE session. */
    fun enableDemo() = post { if (allowDeveloperMode && initialMode != RuntimeMode.LIVE) {
        process(EngineInput.Service(false)); _ui.value = _ui.value.copy(mode = RuntimeMode.DEMO); note("테스트 전용 · 모든 센서와 안전 상태는 가상입니다.") } }
    fun demoConnect() = post { if (_ui.value.mode != RuntimeMode.DEMO || !allowDeveloperMode) return@post
        demoParked = true; demoSafe = false; process(EngineInput.Connections(Readiness(true, true, true, true)))
        process(EngineInput.Consent(true)); process(EngineInput.Service(true)); process(EngineInput.Safety(SafetySample(false, true, now()))) }
    fun demoSignal(signal: Signal) = post { if (_ui.value.mode == RuntimeMode.DEMO && allowDeveloperMode) process(EngineInput.Changed(signal, now(), signal == Signal.VEHICLE)) }
    fun demoSafety(safe: Boolean, parked: Boolean) = post { if (_ui.value.mode == RuntimeMode.DEMO && allowDeveloperMode) {
        demoSafe = safe; demoParked = parked; process(EngineInput.Safety(SafetySample(safe, parked, now()))) } }
    fun demoHazard() = post { if (_ui.value.mode == RuntimeMode.DEMO && allowDeveloperMode) { demoSafe = false; demoParked = false; process(EngineInput.Hazard) } }
    fun demoDisconnect() = post { if (_ui.value.mode == RuntimeMode.DEMO && allowDeveloperMode) process(EngineInput.Connections(Readiness())) }
    override fun onCleared() { player.release(); queue.close(); super.onCleared() }
    companion object {
        fun factory(app: Application, mode: RuntimeMode = RuntimeMode.PREVIEW, safety: ExistingSafetyGate = UnconnectedSafetyGate,
            developer: Boolean = false, player: ((Application) -> PlaybackGateway)? = null, policy: EnginePolicy = EnginePolicy()) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(HwanaebiViewModel::class.java))
                return HwanaebiViewModel(app, mode, safety, developer, player, policy) as T
            }
        }
    }
}
