package dev.hwanaebi.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import dev.hwanaebi.core.Phase
fun expression(state: HwanaebiUiState): String = when {
    state.engine.completed -> "bright"
    state.engine.phase == Phase.PLAYING -> "bold"
    state.engine.phase in setOf(Phase.CHECKING, Phase.UNAVAILABLE) -> "concerned"
    state.engine.phase in setOf(Phase.WAITING, Phase.PREPARING, Phase.RECORDING_MISSING) -> "waiting"
    else -> "calm"
}
@Composable fun HwanaebiCharacter(characterId: String, expression: String = "calm", modifier: Modifier = Modifier) {
    val asset = when ("${characterId}_${expression}") {
        "ajumma_calm" -> R.drawable.character_ajumma_calm
        "ajumma_concerned" -> R.drawable.character_ajumma_concerned
        "ajumma_waiting" -> R.drawable.character_ajumma_waiting
        "ajumma_bold" -> R.drawable.character_ajumma_bold
        "ajumma_bright" -> R.drawable.character_ajumma_bright
        "bestie_calm" -> R.drawable.character_bestie_calm
        "bestie_concerned" -> R.drawable.character_bestie_concerned
        "bestie_waiting" -> R.drawable.character_bestie_waiting
        "bestie_bold" -> R.drawable.character_bestie_bold
        "bestie_bright" -> R.drawable.character_bestie_bright
        "gentle_calm" -> R.drawable.character_gentle_calm
        "gentle_concerned" -> R.drawable.character_gentle_concerned
        "gentle_waiting" -> R.drawable.character_gentle_waiting
        "gentle_bold" -> R.drawable.character_gentle_bold
        "gentle_bright" -> R.drawable.character_gentle_bright
        else -> R.drawable.character_ajumma_calm
    }
    Image(painterResource(asset), contentDescription = "$characterId · $expression 표정", modifier = modifier)
}
