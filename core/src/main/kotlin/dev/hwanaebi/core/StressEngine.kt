package dev.hwanaebi.core

/** Policy values are examples; calibrate and validate with the existing vehicle/safety team. */
data class EnginePolicy(val andWindowMs: Long = 5_000, val safetyTtlMs: Long = 2_000,
    val pendingTtlMs: Long = 15_000, val cooldownMs: Long = 30_000) {
    init { require(andWindowMs > 0 && safetyTtlMs > 0 && pendingTtlMs > 0 && cooldownMs >= 0) }
}
enum class Signal { VEHICLE, VOICE, BIOMETRIC }
enum class Phase { OFF, UNAVAILABLE, CALM, CHECKING, WAITING, RECORDING_MISSING, PREPARING, PLAYING }
data class Readiness(val vehicle: Boolean = false, val voice: Boolean = false,
    val watch: Boolean = false, val microphonePermission: Boolean = false) {
    val complete get() = vehicle && voice && watch && microphonePermission
}
data class SafetySample(val safeToPlay: Boolean, val safelyParked: Boolean, val capturedAtMs: Long)
data class EngineState(val serviceOn: Boolean = false, val consent: Boolean = false,
    val readiness: Readiness = Readiness(), val phase: Phase = Phase.OFF,
    val signals: Set<Signal> = emptySet(), val suspected: Int = 0, val playbackCount: Int = 0,
    val heldCount: Int = 0, val brakes: Int = 0, val cooldownUntilMs: Long = 0,
    val completed: Boolean = false, val voiceKey: String = "ajumma_mild", val hasRecording: Boolean = false,
    val activeToken: Long? = null, val note: String = "실제 센서 미연결")
sealed interface EngineInput {
    data class Service(val on: Boolean) : EngineInput
    data class Consent(val allowed: Boolean) : EngineInput
    data class Connections(val value: Readiness) : EngineInput
    data class Changed(val signal: Signal, val capturedAtMs: Long, val hardBrake: Boolean = false) : EngineInput
    data class Safety(val value: SafetySample) : EngineInput
    data object Hazard : EngineInput
    data class Voice(val key: String, val available: Boolean) : EngineInput
    data class PlaybackStarted(val token: Long) : EngineInput
    data class PlaybackEnded(val token: Long) : EngineInput
    data class PlaybackFailed(val token: Long) : EngineInput
    data class PlaybackInterrupted(val token: Long) : EngineInput
    data object Tick : EngineInput
    data object Suspend : EngineInput
}
sealed interface EngineEffect {
    data class Play(val token: Long, val voiceKey: String) : EngineEffect
    data object Stop : EngineEffect
}
/** Single-owner deterministic reducer. Call from one serialized actor, never from UI sensor buttons. */
class StressEngine(private val policy: EnginePolicy = EnginePolicy()) {
    var state = EngineState(); private set
    private val accepted = mutableMapOf<Signal, Long>()
    private val stamps = mutableMapOf<Signal, Long>()
    private var safety: SafetySample? = null
    private var pendingSince: Long? = null
    private var serial = 0L
    private var lastBrakeStamp = -1L
    private var lastNow = 0L
    fun parked(now: Long): Boolean = safety?.let { it.safelyParked && fresh(it.capturedAtMs, now, policy.safetyTtlMs) } == true
    private fun safe(now: Long) = safety?.let { it.safeToPlay && fresh(it.capturedAtMs, now, policy.safetyTtlMs) } == true
    private fun fresh(stamp: Long, now: Long, age: Long) = stamp in 0..now && now - stamp <= age
    private fun ready() = state.serviceOn && state.consent && state.readiness.complete
    fun permits(token: Long, now: Long): Boolean = ready() && safe(now) && state.activeToken == token &&
        state.phase in setOf(Phase.PREPARING, Phase.PLAYING)
    fun accept(input: EngineInput, now: Long): List<EngineEffect> {
        require(now >= lastNow) { "Use a monotonic clock (SystemClock.elapsedRealtime)." }; lastNow = now
        val effects = mutableListOf<EngineEffect>()
        val wasActive = state.activeToken != null
        fun stop() { if (state.activeToken != null) effects += EngineEffect.Stop; state = state.copy(activeToken = null) }
        fun clearEvidence() { stamps.clear(); pendingSince = null; state = state.copy(signals = emptySet()) }
        fun calm(note: String, cooldown: Boolean = false) {
            stop(); clearEvidence(); state = state.copy(phase = Phase.CALM, completed = false,
                cooldownUntilMs = if (cooldown) now + policy.cooldownMs else state.cooldownUntilMs, note = note)
        }
        // Freshness is enforced for every input, including while asynchronous preparation is in progress.
        if (state.activeToken != null && !safe(now) && input !is EngineInput.PlaybackEnded) {
            stop(); state = state.copy(phase = Phase.WAITING, note = "안전 정보 갱신 대기")
        }
        when (input) {
            is EngineInput.Service -> { state = state.copy(serviceOn = input.on); if (!input.on) { stop(); clearEvidence(); safety = null } }
            is EngineInput.Consent -> { state = state.copy(consent = input.allowed); if (!input.allowed) { stop(); clearEvidence(); safety = null } }
            is EngineInput.Connections -> { state = state.copy(readiness = input.value); if (!input.value.complete) { stop(); clearEvidence(); safety = null } }
            is EngineInput.Voice -> {
                if (input.key != state.voiceKey || input.available != state.hasRecording) { stop() }
                state = state.copy(voiceKey = input.key, hasRecording = input.available)
            }
            is EngineInput.Safety -> {
                safety = input.value.takeIf { fresh(it.capturedAtMs, now, policy.safetyTtlMs) }
                if (!safe(now)) { stop(); if (pendingSince != null) state = state.copy(phase = Phase.WAITING, note = "안전한 순간을 기다리는 중") }
            }
            EngineInput.Hazard -> { safety = null; if (wasActive) calm("새로운 위험 · 재생 중단", true)
                else { stop(); if (pendingSince != null) state = state.copy(phase = Phase.WAITING, note = "위험 상황 · 재생 보류") } }
            is EngineInput.Changed -> {
                if (state.serviceOn && state.readiness.vehicle && input.signal == Signal.VEHICLE && input.hardBrake &&
                    fresh(input.capturedAtMs, now, policy.andWindowMs) && input.capturedAtMs > lastBrakeStamp) {
                    lastBrakeStamp = input.capturedAtMs; state = state.copy(brakes = state.brakes + 1)
                }
                if (ready() && pendingSince == null && state.activeToken == null && now >= state.cooldownUntilMs &&
                    fresh(input.capturedAtMs, now, policy.andWindowMs) && input.capturedAtMs > (accepted[input.signal] ?: -1)) {
                    accepted[input.signal] = input.capturedAtMs
                    val old = stamps[input.signal]
                    if (old == null || input.capturedAtMs > old) stamps[input.signal] = input.capturedAtMs
                    stamps.entries.removeAll { !fresh(it.value, now, policy.andWindowMs) }
                    state = state.copy(signals = stamps.keys.toSet(), phase = Phase.CHECKING, completed = false)
                    if (stamps.size == Signal.entries.size && stamps.values.max() - stamps.values.min() <= policy.andWindowMs) {
                        pendingSince = now; stamps.clear()
                        state = state.copy(suspected = state.suspected + 1, phase = Phase.WAITING, signals = Signal.entries.toSet(),
                            heldCount = state.heldCount + if (safe(now)) 0 else 1, note = "안전한 순간을 기다리는 중")
                    }
                }
            }
            is EngineInput.PlaybackStarted -> if (permits(input.token, now)) state = state.copy(phase = Phase.PLAYING, playbackCount = state.playbackCount + 1)
            is EngineInput.PlaybackEnded -> if (state.activeToken == input.token) {
                calm("음성 재생 완료", true); state = state.copy(completed = true)
            }
            is EngineInput.PlaybackFailed -> if (state.activeToken == input.token) calm("녹음 파일 확인 필요 · 재생하지 않았어요", true)
            is EngineInput.PlaybackInterrupted -> if (state.activeToken == input.token) calm("오디오 포커스 변경 · 재생 중단", true)
            EngineInput.Suspend -> { safety = null; calm("화면 실행 종료 · 안전 정보 재확인 필요", true) }
            EngineInput.Tick -> Unit
        }
        if (!ready()) {
            stop(); clearEvidence(); state = state.copy(phase = if (state.serviceOn) Phase.UNAVAILABLE else Phase.OFF,
                completed = false, note = if (!state.serviceOn) "서비스 OFF" else "연결·권한·분석 동의를 확인해 주세요")
            return effects
        }
        if (state.phase == Phase.OFF || state.phase == Phase.UNAVAILABLE) state = state.copy(phase = Phase.CALM, note = "세 신호를 조용히 살피고 있어요")
        stamps.entries.removeAll { !fresh(it.value, now, policy.andWindowMs) }
        if (pendingSince == null && state.activeToken == null && state.phase == Phase.CHECKING) {
            state = state.copy(signals = stamps.keys.toSet(), phase = if (stamps.isEmpty()) Phase.CALM else Phase.CHECKING)
        }
        val pending = pendingSince
        if (pending != null && state.activeToken == null && now - pending > policy.pendingTtlMs) calm("대기 만료 · 새로운 신호를 기다려요", true)
        if (pendingSince != null && state.activeToken == null) {
            when {
                !state.hasRecording -> state = state.copy(phase = Phase.RECORDING_MISSING, note = "녹음 파일 준비 중")
                !safe(now) -> state = state.copy(phase = Phase.WAITING)
                else -> { serial++; state = state.copy(phase = Phase.PREPARING, activeToken = serial, note = "안전 확인 · 녹음 준비 중")
                    effects += EngineEffect.Play(serial, state.voiceKey) }
            }
        }
        return effects
    }
}
