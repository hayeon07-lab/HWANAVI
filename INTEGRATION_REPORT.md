# 변경된 파일과 검증 상태

## 기존 프로젝트에서 수정

- `settings.gradle.kts`: `:core`, `:ui` 모듈 포함.
- `build.gradle.kts`, `gradle/libs.versions.toml`: 원래 AGP 9.5.1 버전을 유지한 채 Android Library 플러그인 선언 추가.
- `app/build.gradle.kts`: `:ui` 의존성, Lifecycle 의존성, Java 17 및 BuildConfig 추가. 기존 `applicationId`, `compileSdk`, `targetSdk`, `minSdk`, 다른 의존성은 유지.
- `gradle.properties`: `android.useAndroidX=true` 추가.
- `app/src/main/res/values/themes.xml`: Android 바탕·상태 표시줄을 Codex 네이비 배경과 일치시킴.
- `app/src/main/java/com/example/daesinhwanaecar/MainActivity.kt`: 기존 위치·날씨·음성·시뮬레이션·리포트 함수와 이전 Compose 화면 **그대로 유지**, `onCreate`의 화면 호출을 새 `HwanaebiRoute`로 연결하고 기존 진정 TTS에 대한 어댑터 메서드만 추가함.

## 추가

- `core/`: 원본 Codex 3신호 엔진과 테스트. `build.gradle.kts`만 기존 AGP 9용 Android Library로 재작성.
- `ui/`: 원본 Codex Compose UI 코드와 PNG·폰트 보존. `build.gradle.kts`만 기존 AGP 9용으로 재작성.
- `app/src/main/java/com/example/daesinhwanaecar/HwanaebiIntegration.kt`: 원본 MP3 등록, 단일 재생 Gateway, 무료 날씨 패널, 기존 저장형 리포트 패널.
- `app/src/debug/java/.../IntegratedDeveloperTools.kt`: 숨겨진 개발자 테스트.
- `app/src/release/java/.../IntegratedDeveloperTools.kt`: 개발자 UI 없는 릴리스 구현.
- `design-reference/`: 원본 Codex 설명서·SVG·폰트 라이선스.
- `README_FIRST.md`: 사용법과 검증 범위.

## 의도적으로 유지 / 교체하지 않음

- `app/src/main/res/raw/angry_voice.mp3`: 변동 없음.
- `app/src/main/AndroidManifest.xml`: 기존 권한 및 `RecognitionService` 조회 설정 그대로 유지.
- 기존 Java/Kotlin 파일의 package 및 기존 앱 ID.
- `ui/src/main`의 24개 모든 소스 및 리소스 바이트 동일.
- `core/src/main` 및 `core/src/test` 원본 바이트 동일.
- Codex의 `sample/` 앱은 **통합 프로젝트에 추가하지 않음**.
- 기존 유료 AI / Python 서버 코드는 **다시 넣지 않음**.

## 검증

- 파일 완전성 검사: 성공.
- 원본 3신호 엔진 단독 Kotlin 컴파일: 성공.
- 원본 17개 단위 테스트를 별도 오프라인 JVM 하네스로 실행: **17개 모두 통과**.
- Android Gradle `:app:assembleDebug`, `:core:testDebugUnitTest`: **미실행/미검증**. Gradle Wrapper가 배포 파일을 다운로드하려다 `UnknownHostException: services.gradle.org`로 중단됨.
- 에뮬레이터에서 UI/음성 테스트: **미실행/미검증**. 사용자의 Android Studio 실행이 필요함.

## 남은 개발 과제

- 실제 차량 GPS 주행센서 급제동, 음성 긴장도 분석, 스마트워치 생체 신호, 실제 안전 판정 게이트 연결.
- 새 디자인 리포트의 장기 저장/기존 가상 리포트와의 데이터 모델 통합(현재는 혼동 방지 위해 별도 표시).
- 스마트폰 세로 화면 상세 반응형 레이아웃 및 실기 음성/권한/오디오 포커스 회귀 테스트.
- 백그라운드 감지 서비스 및 실차 안전 검증.
