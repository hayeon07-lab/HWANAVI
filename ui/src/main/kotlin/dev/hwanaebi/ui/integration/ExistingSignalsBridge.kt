package dev.hwanaebi.ui.integration

import dev.hwanaebi.core.EngineInput
import dev.hwanaebi.ui.ExistingSafetyGate
import dev.hwanaebi.ui.HwanaebiViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Host implementation must emit actual evaluated signals. This interface performs no hardware I/O. */
interface ExistingSignalsSource { val inputs: Flow<EngineInput> }
fun bindExistingSignals(scope: CoroutineScope, source: ExistingSignalsSource, viewModel: HwanaebiViewModel): Job =
    scope.launch { source.inputs.collect { viewModel.acceptSensor(it) } }

/** These callbacks must consult the existing safety authority, not a UI toggle. Unknown must return false. */
class CallbackSafetyGate(private val audioSafe: () -> Boolean, private val safelyParked: () -> Boolean) : ExistingSafetyGate {
    override fun isAudioSafeNow() = audioSafe()
    override fun isSafelyParkedNow() = safelyParked()
}
