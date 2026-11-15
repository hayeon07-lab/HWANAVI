package dev.hwanaebi.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hwanaebi.core.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

interface HwanaebiActions {
    fun page(index: Int) {}; fun drive() {}; fun endTrip() {}; fun leaveDrive() {}; fun service(on: Boolean) {}; fun consent(allowed: Boolean) {}
    fun chooseVoice(character: Int? = null, flavor: Int? = null) {}; fun importMp3(uri: Uri) {}
    fun chooseRecording(id: String) {}; fun volume(value: Float) {}; fun preview() {}; fun weekly(value: Boolean) {}
    fun rate(value: Int) {}; fun deleteData() {}; fun dismissMessage() {}
}
private val Navy = Color(0xFF0B1116)
private val Panel = Color(0xFF151E26)
private val Mint = Color(0xFF87E5CF)
private val Muted = Color(0xFF96ABB8)
private val Outline = Color(0xFF293D47)
private val Ink = Color(0xFFEDF3F5)
private val Korean = FontFamily(Font(R.font.noto_sans_kr))
@Composable fun HwanaebiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Color(0xFF153A34), background = Navy,
        surface = Panel, onSurface = Ink, onSurfaceVariant = Muted, outline = Outline),
        typography = Typography(bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontFamily = Korean),
            bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontFamily = Korean),
            bodySmall = MaterialTheme.typography.bodySmall.copy(fontFamily = Korean),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontFamily = Korean),
            titleMedium = MaterialTheme.typography.titleMedium.copy(fontFamily = Korean)), content = content)
}
@Composable fun HwanaebiRoute(vm: HwanaebiViewModel, developerContent: (@Composable () -> Unit)? = null) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.setScreenActive(true)
            if (event == Lifecycle.Event.ON_STOP) vm.setScreenActive(false)
        }
        lifecycle.addObserver(observer)
        vm.setScreenActive(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); vm.setScreenActive(false) }
    }
    HwanaebiTheme { HwanaebiScreen(state, vm, developerContent) }
}
fun phaseTitle(state: HwanaebiUiState) = when (state.engine.phase) {
    Phase.OFF -> "잠시 쉬어가고 있어요"
    Phase.UNAVAILABLE -> "센서 연결을 확인해 주세요"
    Phase.CHECKING -> "잠시 마음을 살피고 있어요"
    Phase.WAITING, Phase.PREPARING -> "안전한 순간을 기다리는 중"
    Phase.RECORDING_MISSING -> "녹음 파일 준비 중"
    Phase.PLAYING -> "화내비가 당신 편이 되어줄게요"
    Phase.CALM -> "편안한 드라이브 중"
}
@Composable private fun Fine(text: String) { Text(text, color = Muted, fontSize = 12.sp) }
@Composable private fun Tag(text: String) { Surface(color = Color(0xFF213A39), shape = RoundedCornerShape(6.dp)) {
    Text(text, color = Mint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) } }
@Composable private fun Block(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier, color = Panel, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (title.isNotEmpty()) Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            content()
        }
    }
}
@Composable fun HwanaebiScreen(s: HwanaebiUiState, a: HwanaebiActions,
    developerContent: (@Composable () -> Unit)? = null) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(a::importMp3) }
    var deleteDialog by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it); a.dismissMessage() } }
    Scaffold(containerColor = Navy, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val compact = maxWidth < 900.dp
            val shortHeight = maxHeight < 900.dp
            Row(Modifier.fillMaxSize()) {
                if (!s.driveMode) Column(Modifier.width(if (compact) 88.dp else 202.dp).fillMaxHeight().background(Color(0xFF0D141B)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (compact) "화내비" else "화내비\nHWANAEBI", color = Mint, fontSize = if (compact) 15.sp else 22.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 20.dp))
                    val names = listOf("홈", "하루 리포트", "AI 음성팩", "설정")
                    val icons = listOf(Icons.Outlined.Home, Icons.Outlined.BarChart, Icons.Outlined.GraphicEq, Icons.Outlined.Settings)
                    names.forEachIndexed { i, label ->
                        Surface(color = if (s.page == i) Color(0xFF1B3032) else Color.Transparent, shape = RoundedCornerShape(9.dp)) {
                            Column(Modifier.fillMaxWidth().clickable(enabled = s.canEdit) { a.page(i) }.padding(vertical = 16.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(icons[i], contentDescription = label, tint = if (s.page == i) Mint else Muted)
                                Text(label, color = if (s.page == i) Mint else Muted, fontSize = if (compact) 10.sp else 14.sp)
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f)); Fine("안전이 언제나 먼저")
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(if (compact) 18.dp else 30.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    Surface(color = Color(0xFF18282E), shape = RoundedCornerShape(8.dp)) {
                        Text(when(s.mode) {
                            RuntimeMode.PREVIEW -> "UI 미리보기 · 실제 차량·마이크·스마트워치 미연결 · 자동 재생 비활성"
                            RuntimeMode.DEMO -> "개발자 데모 · 센서·주행·안전 상태는 모두 가상 입력"
                            RuntimeMode.LIVE -> "실제 연동 모드 · 호스트 앱의 센서·안전 판정을 사용 · 미연결 항목은 감지 불가"
                        }, fontSize = 12.sp, color = Muted, modifier = Modifier.fillMaxWidth().padding(13.dp))
                    }
                    if (s.driveMode) Driving(s, a, shortHeight) else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Fine(listOf("A LITTLE CALMER, A LITTLE HAPPIER", "YOUR DAY, AT A GLANCE", "A VOICE ON YOUR SIDE", "MAKE IT YOURS")[s.page])
                            Text(listOf("화내비 HWANAEBI", "오늘의 드라이브를 돌아봐요.", "내 마음에 맞는 목소리.", "나에게 맞춘 화내비.")[s.page],
                                fontSize = if (compact) 25.sp else 32.sp, fontWeight = FontWeight.Medium, color = Ink)
                            if (s.page == 0) Text("길은 안내하고, 화는 대신 내고.", color = Mint, fontSize = 21.sp)
                        }
                        when (s.page) {
                            0 -> Home(s, a, compact)
                            1 -> Report(s, a)
                            2 -> Voices(s, a) { picker.launch(arrayOf("audio/*")) }
                            3 -> Settings(s, a) { deleteDialog = true }
                        }
                    }
                    developerContent?.invoke()
                    Fine("HWANAEBI · 독립 Compose UI · 실제 센서 어댑터는 별도 통합이 필요합니다.")
                }
            }
        }
    }
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("녹음 데이터를 삭제할까요?") },
        text = { Text("앱 내부에 복사한 녹음 MP3와 음성팩 설정을 삭제합니다. 원본 MP3 파일은 삭제하지 않습니다.") },
        confirmButton = { TextButton(onClick = { deleteDialog = false; a.deleteData() }) { Text("삭제") } },
        dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("취소") } })
}
@Composable private fun Home(s: HwanaebiUiState, a: HwanaebiActions, compact: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = a::drive) { Icon(Icons.Outlined.Navigation, null); Text("간편 주행 모드") }
        Text(if (s.engine.serviceOn) "서비스 ON" else "서비스 OFF", color = Mint)
        Switch(checked = s.engine.serviceOn, onCheckedChange = a::service, enabled = s.canEdit)
    }
    if (s.tripEnded) Block("오늘도 수고하셨어요") { Fine("주행이 끝났어요. 오늘의 기록을 만나보세요."); Button(onClick = { a.page(1) }) { Text("하루 리포트 보기") } }
    @Composable fun Monitor(modifier: Modifier) { Block("자동 감지 상태", modifier) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            HwanaebiCharacter(s.selectedKey.substringBefore('_'), expression(s), Modifier.size(180.dp))
            Text(phaseTitle(s), fontSize = 24.sp, color = Ink)
            Fine(s.engine.note)
            Tag("주행 AND 음성 AND 생체")
            Fine("세 조건 충족 후에도 최신 안전 확인이 필요해요.")
        }
    } }
    @Composable fun Summary(modifier: Modifier) { Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Block("오늘의 드라이빙 · 현재 세션") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Fine("스트레스 의심"); Text("${s.engine.suspected}회", fontSize = 36.sp) }
                Column { Fine("음성 재생"); Text("${s.engine.playbackCount}회", fontSize = 36.sp) }
            }
            TextButton(onClick = { a.page(1) }) { Text("리포트 보기 ↗") }
        }
        Block("나의 드라이빙 메이트") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HwanaebiCharacter(s.selectedKey.substringBefore('_'), expression(s), Modifier.size(72.dp))
                Column { Text("${s.spec.character} · ${s.spec.flavor}", fontSize = 20.sp); Fine("직접 녹음한 목소리") }
            }
        }
    } }
    if (compact) { Monitor(Modifier.fillMaxWidth()); Summary(Modifier.fillMaxWidth()) }
    else Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { Monitor(Modifier.weight(1f)); Summary(Modifier.weight(1f)) }
    Block("센서 및 분석 상태") {
        ConnectionRow("차량 데이터", s.engine.readiness.vehicle, s.mode)
        ConnectionRow("마이크 / 음성 분석", s.engine.readiness.voice && s.engine.readiness.microphonePermission, s.mode)
        ConnectionRow("스마트워치", s.engine.readiness.watch, s.mode)
        Fine("분석 동의: ${if (s.engine.consent) "동의함" else "동의 필요"} · 주행 변화/목소리 긴장/심박 변화의 시간 창 AND")
    }
}
@Composable private fun ConnectionRow(label: String, connected: Boolean, mode: RuntimeMode) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 14.sp)
        Text(if (!connected) "미연결" else if (mode == RuntimeMode.DEMO) "테스트용 가상 연결" else "호스트 앱 연결됨", color = if (connected) Mint else Muted, fontSize = 12.sp)
    }
}
@Composable private fun Driving(s: HwanaebiUiState, a: HwanaebiActions, short: Boolean) {
    Block("") {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (short) 12.dp else 20.dp)) {
            Text("화내비 HWANAEBI", fontSize = 24.sp, color = Mint)
            Tag("간편 주행 모드 · ${if (s.safelyParked) "정차 확인" else "정차 미확인"}")
            HwanaebiCharacter(s.selectedKey.substringBefore('_'), expression(s), Modifier.size(if (short) 180.dp else 270.dp))
            Text(phaseTitle(s), fontSize = 34.sp, color = Ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("화면 조작 없이 주행에 집중하세요.", fontSize = 19.sp, color = Muted)
            Button(onClick = a::leaveDrive, enabled = s.safelyParked || s.mode == RuntimeMode.PREVIEW,
                modifier = Modifier.heightIn(min = 64.dp)) { Text("${if (s.safelyParked || s.mode == RuntimeMode.PREVIEW) "일반 화면으로 돌아가기" else "안전한 정차 확인 후 복귀 가능"}", fontSize = 18.sp) }
            if (s.mode != RuntimeMode.LIVE) Fine("시뮬레이션 화면 · 실제 차량의 정차·안전 여부는 판단하지 않습니다.")
        }
    }
}
@Composable private fun Voices(s: HwanaebiUiState, a: HwanaebiActions, pick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        VoiceCatalog.characters.forEachIndexed { i, name ->
            Surface(Modifier.weight(1f), color = if (s.selectedKey.startsWith(VoiceCatalog.characterIds[i])) Color(0xFF1B3032) else Panel,
                shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, if (s.selectedKey.startsWith(VoiceCatalog.characterIds[i])) Mint else Outline)) {
                Column(Modifier.clickable(enabled = s.canEdit) { a.chooseVoice(character = i) }.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    HwanaebiCharacter(VoiceCatalog.characterIds[i], modifier = Modifier.size(140.dp))
                    Text(name, fontSize = 20.sp); Fine(listOf("유쾌한 호통", "재치 있는 공감", "부드러운 위로")[i])
                    if (s.selectedKey.startsWith(VoiceCatalog.characterIds[i])) Text("✓ 현재 선택", color = Mint, fontSize = 12.sp)
                }
            }
        }
    }
    Block("화내기 강도 · 음량") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { VoiceCatalog.flavors.forEachIndexed { i, name ->
            FilterChip(selected = s.selectedKey.endsWith(VoiceCatalog.flavorIds[i]), onClick = { a.chooseVoice(flavor = i) }, label = { Text(name) }, enabled = s.canEdit)
        } }
        Text("음성 음량 ${(s.volume * 100).toInt()}% · 최대 65%")
        Slider(value = s.volume, onValueChange = a::volume, valueRange = 0f..0.65f, enabled = s.canEdit)
        Fine("매운맛도 음량·속도를 높이지 않습니다. 강도는 대사 차이입니다.")
    }
    Block("${s.spec.character} · ${s.spec.flavor} 녹음 MP3") {
        Fine("표준 파일명: ${s.spec.filename}")
        Button(onClick = pick, enabled = s.canEdit && !s.importing) { Text(if (s.importing) "MP3 확인 중…" else "내 녹음 MP3 추가") }
        if (s.slot.recordings.isEmpty()) Text("녹음 파일 준비 중", color = Muted)
        s.slot.recordings.forEach { recording ->
            Row(Modifier.fillMaxWidth().clickable(enabled = s.canEdit) { a.chooseRecording(recording.id) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.slot.selectedId == recording.id, onClick = { a.chooseRecording(recording.id) }, enabled = s.canEdit)
                Text(recording.name, modifier = Modifier.weight(1f), fontSize = 14.sp)
            }
        }
        OutlinedButton(onClick = a::preview, enabled = s.canEdit && s.slot.selectedId != null) { Icon(if (s.previewPlaying) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, null)
            Text(if (s.previewPlaying) "미리 듣기 중지" else "음성 미리 듣기") }
        Fine("팀원 녹음 MP3 전용 · 합성 음성 없음 · 원본은 유지하고 앱 내부로 복사합니다.")
    }
}
@Composable private fun Report(s: HwanaebiUiState, a: HwanaebiActions) {
    val zone = ZoneId.systemDefault(); val today = LocalDate.now()
    val records = s.events.filter { it.at.atZone(zone).toLocalDate() >= today.minusDays(if (s.weekly) 6 else 0) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(today.toString(), fontSize = 20.sp)
        FilterChip(!s.weekly, { a.weekly(false) }, { Text("일간") }); FilterChip(s.weekly, { a.weekly(true) }, { Text("주간") })
    }
    Block("주행 리포트 · 현재 세션 기록") {
        Fine("총 주행 시간: 실제 주행 시간 어댑터 미연결")
        Text("급제동 ${s.engine.brakes}회 · 스트레스 의심 ${s.engine.suspected}회 · 음성 재생 ${s.engine.playbackCount}회 · 재생 보류 ${s.engine.heldCount}회")
        Fine("수치는 현재 세션 누계입니다. 아래 그래프·타임라인은 선택 기간에 해당하는 현재 세션의 이벤트만 표시합니다.")
    }
    Block(if (s.weekly) "요일별 의심 이벤트" else "시간대별 의심 이벤트") {
        val bins = if (s.weekly) 7 else 6
        val counts = (0 until bins).map { i -> records.count { event -> event.suspect && if (s.weekly)
            event.at.atZone(zone).toLocalDate() == today.minusDays((6 - i).toLong()) else event.at.atZone(zone).hour / 4 == i } }
        val max = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
        Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
            counts.forEachIndexed { i, count -> Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(count.toString(), color = Mint)
                Box(Modifier.fillMaxWidth().height((count.toFloat() / max * 100).coerceAtLeast(2f).dp).background(Mint, RoundedCornerShape(5.dp)))
                Fine(if (s.weekly) today.minusDays((6 - i).toLong()).format(DateTimeFormatter.ofPattern("MM/dd")) else "${i * 4}시")
            } }
        }
    }
    Block("이벤트 타임라인") {
        if (records.isEmpty()) Fine("아직 기록이 없어요. 실제 센서 미연결 시 자동 감지 기록이 생성되지 않습니다.")
        records.take(8).forEach { Text("${it.at.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm:ss"))}  ${it.label}", fontSize = 13.sp) }
    }
    Block("오늘의 화내비 한마디") { Text("“무사히 도착한 오늘, 그걸로 충분히 잘했어요.”", color = Mint)
        Fine("스트레스 의심은 의학적 진단이 아니며 감소율·효과를 계산하지 않습니다.") }
    Block("오늘 화내비는 어땠나요?") { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { listOf("아쉬워요", "괜찮아요", "좋았어요").forEachIndexed { i, label ->
        FilterChip(s.rating == i + 1, { a.rate(i + 1) }, { Text(label) }) } } }
}
@Composable private fun Settings(s: HwanaebiUiState, a: HwanaebiActions, delete: () -> Unit) {
    Block("서비스와 연결") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("화내비 서비스"); Switch(s.engine.serviceOn, a::service, enabled = s.canEdit) }
        ConnectionRow("차량 데이터", s.engine.readiness.vehicle, s.mode)
        ConnectionRow("스마트워치", s.engine.readiness.watch, s.mode)
        ConnectionRow("마이크 권한 / 음성 분석", s.engine.readiness.microphonePermission && s.engine.readiness.voice, s.mode)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("마이크 분석 동의"); Switch(s.engine.consent, a::consent, enabled = s.canEdit) }
        Fine("동의 스위치는 실제 마이크 권한 요청이나 녹음 시작이 아닙니다. 권한과 센서 연결은 기존 앱에서 제공해야 합니다.")
    }
    Block("안전한 음성 재생") {
        Text("안전 확인 후 재생 · 항상 사용", color = Mint)
        Text("반복 재생 방지 · 재생 완료 후 ${s.policy.cooldownMs / 1000}초 쿨다운")
        Text("음성 음량 제한 · 최대 65%")
        Fine("${s.policy.andWindowMs / 1000}초 AND 창 · 안전 정보 유효 ${s.policy.safetyTtlMs / 1000}초 · 보류 ${s.policy.pendingTtlMs / 1000}초 만료는 예시 정책입니다. 차량 환경 검증 후 조정하세요.")
    }
    Block("개인정보 및 데이터") { Text("추가한 녹음 MP3와 음성팩 선택은 기기 내부에만 저장됩니다.")
        OutlinedButton(onClick = delete, enabled = s.canEdit) { Text("녹음 데이터 삭제") } }
    OutlinedButton(onClick = a::endTrip, enabled = s.canEdit) { Text("주행 종료 · 리포트 안내") }
    Block("앱 정보") { Text("HWANAEBI · Compose 변환 패키지 v1.0")
        Fine("Pleos Connect v2.1.0 에뮬레이터 대상 디자인 · 실차 연동·차량 플랫폼 인증 미검증")
        Fine("현대자동차 공식 앱이 아닌 독립 UI 프로토타입입니다.") }
}
private fun previewState(page: Int = 0, driving: Boolean = false) = HwanaebiUiState(page = page, driveMode = driving,
    slots = VoiceCatalog.entries.associate { it.key to VoiceSlot() })
@Preview(widthDp = 1280, heightDp = 720, name = "홈") @Composable fun HomePreview() { HwanaebiTheme { HwanaebiScreen(previewState(), object : HwanaebiActions {}) } }
@Preview(widthDp = 1280, heightDp = 720, name = "하루 리포트") @Composable fun ReportPreview() { HwanaebiTheme { HwanaebiScreen(previewState(1), object : HwanaebiActions {}) } }
@Preview(widthDp = 1280, heightDp = 720, name = "음성팩") @Composable fun VoicesPreview() { HwanaebiTheme { HwanaebiScreen(previewState(2), object : HwanaebiActions {}) } }
@Preview(widthDp = 1280, heightDp = 720, name = "설정") @Composable fun SettingsPreview() { HwanaebiTheme { HwanaebiScreen(previewState(3), object : HwanaebiActions {}) } }
@Preview(widthDp = 1280, heightDp = 720, name = "간편 주행") @Composable fun DrivingPreview() { HwanaebiTheme { HwanaebiScreen(previewState(driving = true), object : HwanaebiActions {}) } }
