package dev.hwanaebi.core

import org.junit.Assert.*
import org.junit.Test

class StressEngineTest {
    private var now = 100L
    private fun ready(e: StressEngine, recording: Boolean = true) {
        e.accept(EngineInput.Service(true), now)
        e.accept(EngineInput.Connections(Readiness(true, true, true, true)), now)
        e.accept(EngineInput.Consent(true), now)
        e.accept(EngineInput.Voice("ajumma_mild", recording), now)
    }
    private fun all(e: StressEngine): List<EngineEffect> {
        e.accept(EngineInput.Changed(Signal.VEHICLE, ++now), now)
        e.accept(EngineInput.Changed(Signal.VOICE, ++now), now)
        return e.accept(EngineInput.Changed(Signal.BIOMETRIC, ++now), now)
    }
    private fun safe(e: StressEngine) = e.accept(EngineInput.Safety(SafetySample(true, true, now)), now)
    @Test fun twoSignalsNeverTrigger() { val e = StressEngine(); ready(e)
        e.accept(EngineInput.Changed(Signal.VEHICLE, now), now); e.accept(EngineInput.Changed(Signal.VOICE, now), now)
        safe(e); assertEquals(0, e.state.suspected); assertNull(e.state.activeToken) }
    @Test fun andThenSafetyAutomaticallyRequestsPlayback() { val e = StressEngine(); ready(e)
        assertTrue(all(e).isEmpty()); assertEquals(Phase.WAITING, e.state.phase)
        val play = safe(e).single() as EngineEffect.Play
        assertEquals("ajumma_mild", play.voiceKey); assertEquals(0, e.state.playbackCount)
        e.accept(EngineInput.PlaybackStarted(play.token), now); assertEquals(1, e.state.playbackCount)
        e.accept(EngineInput.PlaybackEnded(play.token), now); assertEquals(Phase.CALM, e.state.phase)
        assertTrue(e.state.completed); assertTrue(e.state.cooldownUntilMs > now)
        all(e); assertEquals(1, e.state.suspected) }
    @Test fun safeBeforeAndAlsoWorksWithoutUiButton() { val e = StressEngine(); ready(e); safe(e)
        assertTrue(all(e).single() is EngineEffect.Play) }
    @Test fun expiredSignalAndFutureSignalDoNotCount() { val e = StressEngine(); ready(e)
        e.accept(EngineInput.Changed(Signal.VEHICLE, now), now); now += 6_000
        e.accept(EngineInput.Changed(Signal.VOICE, now), now)
        e.accept(EngineInput.Changed(Signal.BIOMETRIC, now + 1), now)
        assertEquals(setOf(Signal.VOICE), e.state.signals); assertEquals(0, e.state.suspected) }
    @Test fun staleSafetyNeverStarts() { val e = StressEngine(); ready(e); safe(e); now += 3_000
        assertTrue(all(e).isEmpty()); assertEquals(Phase.WAITING, e.state.phase) }
    @Test fun newHazardStopsAndDiscardsCurrentPlayback() { val e = StressEngine(); ready(e); safe(e)
        val token = (all(e).single() as EngineEffect.Play).token
        e.accept(EngineInput.PlaybackStarted(token), now)
        assertTrue(e.accept(EngineInput.Hazard, now).contains(EngineEffect.Stop)); assertNull(e.state.activeToken)
        assertTrue(safe(e).isEmpty()); assertTrue(e.state.cooldownUntilMs > now)
        e.accept(EngineInput.PlaybackEnded(token), now); assertFalse(e.state.completed) }
    @Test fun lossOfConnectionAndConsentStopImmediately() { for (input in listOf(EngineInput.Connections(Readiness()), EngineInput.Consent(false), EngineInput.Service(false))) {
        val e = StressEngine(); ready(e); safe(e); all(e)
        assertTrue(e.accept(input, now).contains(EngineEffect.Stop)); assertNull(e.state.activeToken)
        assertFalse(e.permits(1, now))
    } }
    @Test fun missingRecordingDoesNotPlayOrSynthesize() { val e = StressEngine(); ready(e, false); safe(e)
        assertTrue(all(e).isEmpty()); assertEquals(Phase.RECORDING_MISSING, e.state.phase)
        val effects = e.accept(EngineInput.Voice("gentle_spicy", true), now)
        assertEquals("gentle_spicy", (effects.single() as EngineEffect.Play).voiceKey) }
    @Test fun staleTokenCannotFinishNewPlayback() { val e = StressEngine(EnginePolicy(cooldownMs = 0)); ready(e); safe(e)
        val first = (all(e).single() as EngineEffect.Play).token
        e.accept(EngineInput.PlaybackEnded(first), now)
        val second = (all(e).single() as EngineEffect.Play).token
        e.accept(EngineInput.PlaybackEnded(first), now); assertEquals(second, e.state.activeToken) }
    @Test fun expiryStopsPreparationAndPermitFailsAtPlayerBoundary() { val e = StressEngine(); ready(e); safe(e)
        val token = (all(e).single() as EngineEffect.Play).token; now += 2_001
        assertFalse(e.permits(token, now)); assertTrue(e.accept(EngineInput.Tick, now).contains(EngineEffect.Stop)) }
    @Test fun pendingExpiresAndDoesNotPlayLater() { val e = StressEngine(); ready(e); all(e); now += 16_000
        e.accept(EngineInput.Tick, now); assertTrue(safe(e).isEmpty()); assertNull(e.state.activeToken) }
    @Test fun longRecordingIsNotKilledByPendingTtl() { val e = StressEngine(); ready(e); safe(e)
        val token = (all(e).single() as EngineEffect.Play).token; e.accept(EngineInput.PlaybackStarted(token), now)
        repeat(20) { now += 1_000; safe(e); e.accept(EngineInput.Tick, now) }
        assertEquals(Phase.PLAYING, e.state.phase); e.accept(EngineInput.PlaybackEnded(token), now)
        assertTrue(e.state.completed) }
    @Test fun consumedDuplicateSamplesCannotRetrigger() { val e = StressEngine(EnginePolicy(cooldownMs = 0)); ready(e); safe(e)
        val token = (all(e).single() as EngineEffect.Play).token; e.accept(EngineInput.PlaybackEnded(token), now)
        e.accept(EngineInput.Changed(Signal.BIOMETRIC, now), now); assertTrue(e.state.signals.isEmpty()) }
    @Test fun unavailableByDefault() { val e = StressEngine(); all(e); safe(e)
        assertEquals(0, e.state.suspected); assertNull(e.state.activeToken) }
    @Test fun suspendStopsAndClearsEvidence() { val e = StressEngine(); ready(e); safe(e); all(e)
        assertTrue(e.accept(EngineInput.Suspend, now).contains(EngineEffect.Stop)); assertNull(e.state.activeToken)
        assertFalse(e.parked(now)); assertTrue(safe(e).isEmpty()) }
    @Test fun safetyRevocationDuringAsyncPreparationStops() { val e = StressEngine(); ready(e); safe(e)
        val token = (all(e).single() as EngineEffect.Play).token
        assertTrue(e.accept(EngineInput.Safety(SafetySample(false, false, now)), now).contains(EngineEffect.Stop))
        assertFalse(e.permits(token, now)); e.accept(EngineInput.PlaybackStarted(token), now); assertEquals(0, e.state.playbackCount) }
    @Test fun hardBrakeCountIsNotSuppressedByCooldownAndDuplicatesAreIgnored() {
        val e = StressEngine(); ready(e); safe(e)
        val token = (all(e).single() as EngineEffect.Play).token; e.accept(EngineInput.PlaybackEnded(token), now)
        e.accept(EngineInput.Changed(Signal.VEHICLE, ++now, hardBrake = true), now)
        e.accept(EngineInput.Changed(Signal.VEHICLE, now, hardBrake = true), now)
        assertEquals(1, e.state.brakes); assertEquals(1, e.state.suspected)
    }

}
