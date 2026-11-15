package com.example.daesinhwanaecar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.hwanaebi.core.Signal
import dev.hwanaebi.ui.HwanaebiViewModel

/** DEBUG only. Not packaged into the release app. */
@Composable
internal fun IntegratedDeveloperTools(
    vm: HwanaebiViewModel,
    stopLegacyAudio: () -> Unit,
    originalDemoScreen: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }

    OutlinedButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "개발자 테스트 닫기 · DEBUG" else "개발자 테스트 · DEBUG 전용")
    }
    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("실제 센서가 아닌 가상 입력입니다. 기존 급제동 시험과 3신호 AND 시험은 서로 분리돼 있습니다.")
            OutlinedButton(onClick = { showOriginal = true }) {
                Text("기존 급제동 / 음성 인식 / 리포트 시험 열기")
            }
            Text("3신호 AND 엔진 · 선택한 MP3가 있어야 재생됩니다.")
            Button(onClick = vm::enableDemo) { Text("데모 모드 켜기") }
            Button(onClick = vm::demoConnect) { Text("가상 센서 연결 + 서비스 ON") }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { vm.demoSignal(Signal.VEHICLE) }, modifier = Modifier.weight(1f)) { Text("급제동") }
                Button(onClick = { vm.demoSignal(Signal.VOICE) }, modifier = Modifier.weight(1f)) { Text("목소리") }
                Button(onClick = { vm.demoSignal(Signal.BIOMETRIC) }, modifier = Modifier.weight(1f)) { Text("심박") }
            }
            Button(onClick = { vm.demoSafety(true, false) }) { Text("안정 주행 확인 (가상)") }
            Button(onClick = { vm.demoSafety(true, true) }) { Text("안전 정차 확인 (가상)") }
            OutlinedButton(onClick = vm::demoHazard) { Text("새로운 위험 → 즉시 중단") }
            OutlinedButton(onClick = vm::demoDisconnect) { Text("연결 끊김 → 중단") }
            OutlinedButton(onClick = vm::demoEndTrip) { Text("주행 종료 (가상)") }
        }
    }
    if (showOriginal) {
        DisposableEffect(vm) {
            // Prevent simultaneous design-engine and original demo playback.
            vm.setScreenActive(false)
            onDispose { stopLegacyAudio(); vm.setScreenActive(true) }
        }
        Dialog(
            onDismissRequest = { showOriginal = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TextButton(onClick = { showOriginal = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("← 새로운 디자인으로 돌아가기 (기존 데모 종료)")
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        originalDemoScreen()
                    }
                }
            }
        }
    }
}
