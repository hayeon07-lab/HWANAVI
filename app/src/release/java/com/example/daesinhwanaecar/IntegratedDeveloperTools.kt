package com.example.daesinhwanaecar

import androidx.compose.runtime.Composable
import dev.hwanaebi.ui.HwanaebiViewModel

/** Release build intentionally has NO developer test controls. */
@Composable
internal fun IntegratedDeveloperTools(
    vm: HwanaebiViewModel,
    stopLegacyAudio: () -> Unit,
    originalDemoScreen: @Composable () -> Unit
) = Unit
