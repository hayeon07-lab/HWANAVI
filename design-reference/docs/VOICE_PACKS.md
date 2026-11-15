# Android 녹음 음성팩

9개 조합은 웹의 현재 대사와 ID를 그대로 가져왔습니다. 실제 MP3는 포함되어 있지 않습니다.

| 캐릭터 | 강도 | key | 표준 MP3 파일명 | 향후 res/raw 참조 |
|---|---|---|---|---|
|아지매|순한맛|ajumma_mild|hwanaebi_ajumma_mild.mp3|R.raw.hwanaebi_ajumma_mild|
|아지매|중간맛|ajumma_medium|hwanaebi_ajumma_medium.mp3|R.raw.hwanaebi_ajumma_medium|
|아지매|매운맛|ajumma_spicy|hwanaebi_ajumma_spicy.mp3|R.raw.hwanaebi_ajumma_spicy|
|절친|순한맛|bestie_mild|hwanaebi_bestie_mild.mp3|R.raw.hwanaebi_bestie_mild|
|절친|중간맛|bestie_medium|hwanaebi_bestie_medium.mp3|R.raw.hwanaebi_bestie_medium|
|절친|매운맛|bestie_spicy|hwanaebi_bestie_spicy.mp3|R.raw.hwanaebi_bestie_spicy|
|다정한 친구|순한맛|gentle_mild|hwanaebi_gentle_mild.mp3|R.raw.hwanaebi_gentle_mild|
|다정한 친구|중간맛|gentle_medium|hwanaebi_gentle_medium.mp3|R.raw.hwanaebi_gentle_medium|
|다정한 친구|매운맛|gentle_spicy|hwanaebi_gentle_spicy.mp3|R.raw.hwanaebi_gentle_spicy|

## 앱에서 추가

음성팩 화면에서 조합을 고르고 대사를 편집하세요. ‘내 녹음 MP3 추가’는 Android 파일 선택 창을 열고 실제 MP3를 검사한 뒤 앱 내부로 복사합니다. 각 조합에 여러 개를 보관하고 라디오 버튼으로 재생할 파일 하나를 고릅니다. 선택/대사/음량/파일은 앱 재시작 후 유지됩니다. 폰트와 캐릭터를 포함해 오프라인 동작하며 음성 생성 API·네트워크 업로드를 사용하지 않습니다. Android Studio 코드에 직접 MP3를 넣지 않아도 됩니다.

원본 파일 이름이 표준명과 달라도 앱 가져오기는 가능하며 내부에서는 UUID.mp3로 충돌 없이 관리합니다. 32 MiB 이하, 실제 audio/mpeg 트랙만 허용합니다. 파일 선택 창이 열릴 때는 전경 정책에 따라 재생이 중단됩니다. 녹음기 기능은 포함하지 않습니다.

## 향후 팀 배포용 리소스

표준 파일명은 res/raw 에 넣을 때도 유효한 소문자·밑줄 형식입니다. 위 R.raw 매핑은 향후 기존 프로젝트의 번들 리소스 어댑터에서 사용할 이름이며, 이 패키지의 기본 VoiceRepository는 **앱에서 가져온 내부 파일**을 재생합니다. res/raw에 파일을 복사하는 것만으로 자동 선택되지 않습니다. 팀 기본팩을 번들하려면 호스트 어댑터에서 raw를 내부 저장소로 복사/등록하거나 해당 리소스를 재생하는 PlaybackGateway를 추가하세요. 기존 재생 함수를 덮어쓰지 않습니다.

기본 대사는 ui/src/main/kotlin/dev/hwanaebi/ui/VoiceCatalog.kt 에서 수정할 수 있습니다. 사용자가 이미 수정한 대사는 저장된 값이 우선하므로 앱 기본 대사만 바꿔도 사용자 대사를 덮어쓰지 않습니다. 편집한 문장과 기존 MP3 내용은 자동 동기화되지 않습니다.

## 재생 규칙

미리 듣기는 안전한 정차 확인 또는 비차량 PREVIEW에서만 가능합니다. 자동 재생은 LIVE/DEMO 센서 AND → 최신 안전 허가 → 선택된 실제 녹음 → 오디오 포커스 → start 직전 안전 재확인을 거칩니다. 파일이 없으면 ‘녹음 파일 준비 중’이며 다른 캐릭터·강도·합성 음성으로 대체하지 않습니다. 강도는 대사 차이이며 음량은 최대 65%, 재생 속도는 원본입니다. 실제 차량 스피커의 dB 한도를 검증한 것은 아닙니다.
