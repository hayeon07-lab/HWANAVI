# 기존 Android 앱 통합 — 기존 파일 보호

## 기존 앱이 없어 확정할 수 없는 부분

이 패키지 작성 시 기존 Android 프로젝트·MainActivity.kt·안전 판단 함수에 접근하지 않았습니다. 따라서 실제 수정 행 번호나 기존 함수 이름을 추측하지 않았습니다. 먼저 독립 sample에서 검토한 뒤 별도 브랜치에서 통합하세요. ZIP의 sample 앱을 기존 앱 폴더에 통째로 복사하지 마세요.

## 추가 파일과 Gradle

1. 기존 프로젝트에 `core`와 `ui` 모듈을 새 이름으로 추가하거나, 해당 모듈의 kotlin/res 폴더를 충돌 없는 `dev.hwanaebi` 패키지로 추가합니다. 기존 `MainActivity.kt`는 보존합니다.
2. 멀티 모듈이라면 기존 settings.gradle.kts에 `include(":core", ":ui")`만 추가하고 app의 dependencies에 `implementation(project(":ui"))`를 추가합니다. 기존 SDK/플러그인 버전·서명·applicationId를 이 ZIP 값으로 덮어쓰지 마세요.
3. 샘플 검증 구성: AGP 8.9.2, Gradle 8.11.1, Kotlin/Compose compiler plugin 2.1.20, Compose BOM 2025.04.01, compile/target SDK 35, min SDK 26, JDK 17. 기존 Kotlin 버전과 Compose 플러그인은 반드시 같은 버전으로 맞추세요. AndroidX·lifecycle·coroutines 의존성은 ui/build.gradle.kts를 참고합니다.
4. 이미 Compose인 앱에서는 기존 navigation destination에 `HwanaebiRoute(vm)`를 **추가**합니다. XML 앱에서는 새 화면의 ComposeView#setContent 안에 삽입합니다. 기존 setContent 전체를 삭제하거나 MainActivity 파일을 교체하지 않습니다.
5. ui 모듈에는 Activity나 권한 선언이 없습니다. sample Activity는 독립 미리보기용이므로 기존 앱 manifest에는 그대로 복사할 필요가 없습니다.

## 실제 모드 생성 예시

다음 `legacySafety`와 `source`는 **호스트가 기존 코드로 구현해야 하는 의존성**입니다. 데모 객체나 상수 true로 대체하면 안 됩니다.

```kotlin
val vm: HwanaebiViewModel = viewModel(
    factory = HwanaebiViewModel.factory(
        app = application,
        mode = RuntimeMode.LIVE,
        safety = legacySafety,       // ExistingSafetyGate 구현: 기존 안전 판단 함수 호출
        developer = false,
        player = { app -> legacyRecordedFileGateway } // 기존 플레이어 어댑터, 하나만 구동
    )
)
HwanaebiRoute(vm) // 일반 사용자는 개발자 콘텐츠를 전달하지 않음
```

기존 플레이어가 임의 파일을 처리하지 못하면 기본 RecordedMp3Player를 사용할 수 있지만, 기존 자동 재생 트리거는 이 화면이 활성화된 동안 중복 실행되지 않게 통합해야 합니다. 원본 분노/진정 재생 함수는 삭제하지 말고 하나의 PlaybackGateway 안에서 소유권·오디오 포커스·중단을 통일하세요. 파일 경로 재생을 추가할 필요가 있다면 새 어댑터 메서드로 확장합니다. 기존 원본 음성을 새 MP3 선택 대신 몰래 재생하면 안 됩니다.

## 기존 신호를 자동 전달

```kotlin
// 아래는 기존 센서 콜백 내부에서 호출하는 연결 지점 예시. 센서 자체를 구현한 코드는 아님.
vm.acceptSensor(EngineInput.Connections(Readiness(
    vehicle = actualVehicleConnected,
    voice = actualVoiceAnalyzerConnected,
    watch = actualWatchConnected,
    microphonePermission = actualMicrophonePermissionGranted
)))
vm.acceptSensor(EngineInput.Changed(Signal.VEHICLE, vehicleSampleElapsedMs, hardBrake = isActualHardBrake))
vm.acceptSensor(EngineInput.Changed(Signal.VOICE, voiceSampleElapsedMs))
vm.acceptSensor(EngineInput.Changed(Signal.BIOMETRIC, biometricSampleElapsedMs))

// 기존 안전 판단 결과를 지속적으로 갱신. false/unknown은 재생 허가 아님.
vm.acceptSensor(EngineInput.Safety(SafetySample(
    safeToPlay = existingSafeAudioDecision,
    safelyParked = existingSafeParkDecision,
    capturedAtMs = SystemClock.elapsedRealtime()
)))
// 새로운 위험은 debounce 없이 즉시 전달. 이후 gate도 즉시 false여야 함.
vm.acceptSensor(EngineInput.Hazard)
```

`capturedAtMs`는 센서가 실제 관측된 시간을 elapsedRealtime 기준으로 변환한 값이어야 합니다. 오래된 버퍼 신호를 지금 시각으로 새로 포장하지 마세요. UTC epoch, 워치 자체 uptime, 차량 bus clock을 그대로 넘기면 안 됩니다. 호스트가 시간 기준을 동기화해야 합니다. 기본 2초 TTL보다 자주 안전 판정을 업데이트하세요. 중단은 메인 이벤트 큐에서 즉시 처리되지만 실시간/차량 인증 지연을 보장하지 않습니다.

Flow 기반 기존 어댑터는 ExistingSignalsSource를 구현하고 `bindExistingSignals(scope, source, vm)`로 연결할 수 있습니다. 중복 바인딩을 만들지 않고 해당 scope 종료 시 Job을 취소하세요.

## 권한·연결

- 마이크 권한과 분석 동의는 별개입니다. 동의 스위치가 권한을 부여하지 않습니다.
- 샘플은 센서 녹음·실시간 워치 연결이 없으므로 RECORD_AUDIO, BLE, BODY_SENSORS 권한을 선언하지 않습니다. 실제 기존 센서 구현에 필요한 권한은 기존 앱 정책에 따라 유지/추가하세요.
- 파일 가져오기는 Storage Access Framework OpenDocument를 사용합니다. broad storage 권한은 필요하지 않습니다.
- 안전 판정 미구현인 경우 UnconnectedSafetyGate를 유지하면 자동 재생이 차단됩니다. 단순 정차 여부나 화면 버튼으로 실제 안전 게이트를 우회하지 마세요.

## 수명주기·기존 백그라운드 서비스

HwanaebiRoute의 ON_STOP은 기본 플레이어 중단, 안전 샘플 무효화, 전경 신호 차단을 수행합니다. 이는 샘플의 명시적인 동작입니다. 실제 기존 차량 서비스가 백그라운드에서도 동작해야 한다면 core StressEngine과 PlaybackGateway를 기존 서비스의 단일 소유 coordinator로 옮기고 UI는 StateFlow만 구독하게 하세요. 기존 foreground service·알림·오디오 정책을 보존하고 별도 검증하세요. 이 ZIP에 동작한다고 표시한 가짜 백그라운드 서비스는 없습니다.

## 정책값 주입

기존 검증된 정책이 있다면 factory의 `policy = EnginePolicy(andWindowMs = ..., safetyTtlMs = ..., pendingTtlMs = ..., cooldownMs = ...)`에 반영하세요. UI 설정 문구도 주입한 값으로 표시됩니다. 예시값을 실차 검증된 안전 기준으로 해석하면 안 됩니다. 표준 sample의 Gradle/SDK 버전을 기존 앱에 강제로 덮어쓰지 마세요.
