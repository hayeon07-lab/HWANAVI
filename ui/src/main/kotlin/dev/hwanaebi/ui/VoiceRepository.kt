package dev.hwanaebi.ui

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Recording(val id: String, val name: String, val privateFilename: String)
data class VoiceSlot(val recordings: List<Recording> = emptyList(), val selectedId: String? = null)
/** Imported audio is copied to app-private storage; no fragile provider URI is retained. */
class VoiceRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("hwanaebi_voice_v1", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "hwanaebi_recordings").apply { mkdirs() }
    fun load(): Map<String, VoiceSlot> = VoiceCatalog.entries.associate { spec ->
        val json = runCatching { JSONObject(prefs.getString(spec.key, "{}")!!) }.getOrDefault(JSONObject())
        val items = json.optJSONArray("recordings") ?: JSONArray()
        val files = (0 until items.length()).mapNotNull { i -> runCatching {
            val item = items.getJSONObject(i)
            val filename = item.getString("file")
            require(filename.matches(Regex("[a-f0-9-]+\\.mp3")))
            Recording(item.getString("id"), item.getString("name"), filename)
        }.getOrNull() }.filter { file(it).isFile }
        spec.key to VoiceSlot(files,
            json.optString("selected").takeIf { id -> files.any { it.id == id } })
    }
    fun save(key: String, slot: VoiceSlot) {
        val files = JSONArray().apply { slot.recordings.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("file", it.privateFilename)) } }
        prefs.edit().putString(key, JSONObject().put("selected", slot.selectedId ?: "").put("recordings", files).toString()).apply()
    }
    fun file(recording: Recording): File = File(directory, recording.privateFilename)
    fun selectedFile(slot: VoiceSlot): File? = slot.recordings.firstOrNull { it.id == slot.selectedId }?.let(::file)?.takeIf { it.isFile }
    /** Call on Dispatchers.IO. 32 MiB limit; validate actual MP3 track MIME, not only the extension. */

    // 현재 캐릭터와 강도에 등록된 MP3 중 랜덤 선택
    fun randomFile(slot: VoiceSlot): File? =
        slot.recordings
            .map(::file)
            .filter { it.isFile }
            .randomOrNull()

    fun hasRecording(slot: VoiceSlot): Boolean =
        slot.recordings.any { file(it).isFile }

    // res/raw에 있는 기본 음성팩 자동 등록
    @Suppress("DEPRECATION")
    fun registerBundledPacks() {

        val settings = context.getSharedPreferences(
            "hwanaebi_bundled_packs_v3",
            Context.MODE_PRIVATE
        )

        val updatedAt = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .lastUpdateTime

        val shouldRefresh =
            settings.getLong("apk_updated_at", -1L) != updatedAt

        var allSuccessful = true

        VoiceCatalog.entries.forEach { spec ->

            val prefix = spec.filename.removeSuffix(".mp3")

            // 예: ajimae_mild, ajimae_mild_2, ajimae_mild_3...
            val resourceNames =
                listOf(prefix) + (2..20).map { "${prefix}_$it" }

            val bundled = mutableListOf<Recording>()

            resourceNames.forEachIndexed { index, resourceName ->

                val rawId = context.resources.getIdentifier(
                    resourceName,
                    "raw",
                    context.packageName
                )

                if (rawId == 0) return@forEachIndexed

                val id = UUID.nameUUIDFromBytes(
                    "hwanaebi-builtin-v3:$resourceName"
                        .toByteArray(Charsets.UTF_8)
                ).toString()

                val recording = Recording(
                    id = id,
                    name = "${spec.character} · ${spec.flavor} · 기본 음성 ${index + 1}",
                    privateFilename = "$id.mp3"
                )

                val target = file(recording)

                try {
                    if (shouldRefresh || !target.isFile) {

                        val temporary = File(directory, "$id.tmp")

                        try {
                            context.resources
                                .openRawResource(rawId)
                                .use { input ->
                                    temporary.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }

                            require(
                                temporary.length() in
                                        1L..(32L * 1024 * 1024)
                            ) {
                                "MP3 파일 크기를 확인하세요: $resourceName"
                            }

                            // 이름만 MP3인 잘못된 파일 방지
                            val extractor = MediaExtractor()

                            try {
                                extractor.setDataSource(
                                    temporary.absolutePath
                                )

                                val isMp3 =
                                    (0 until extractor.trackCount).any { track ->
                                        extractor.getTrackFormat(track)
                                            .getString(MediaFormat.KEY_MIME) ==
                                                "audio/mpeg"
                                    }

                                require(isMp3) {
                                    "올바른 MP3가 아닙니다: $resourceName"
                                }

                            } finally {
                                extractor.release()
                            }

                            temporary.copyTo(
                                target,
                                overwrite = true
                            )

                        } finally {
                            temporary.delete()
                        }
                    }

                    bundled.add(recording)

                } catch (error: Exception) {
                    allSuccessful = false

                    android.util.Log.e(
                        "HwanaebiVoice",
                        "음성팩 등록 실패: $resourceName",
                        error
                    )
                }
            }

            if (bundled.isEmpty()) return@forEach

            val old = load().getValue(spec.key)

            // 이전 패치로 등록됐을 수 있는 기본 음성 정리
            val olderBundledId = UUID.nameUUIDFromBytes(
                "hwanaebi:bundled-voice:v2:${spec.key}"
                    .toByteArray(Charsets.UTF_8)
            ).toString()

            val bundledIds = bundled.map { it.id }.toSet()

            // 사용자가 따로 가져온 녹음은 유지
            val custom = old.recordings.filterNot { item ->
                item.id in bundledIds ||
                        item.id == olderBundledId ||
                        item.name == "기존 분노 음성 (angry_voice.mp3)"
            }

            val recordings = bundled + custom

            val selected = old.selectedId
                ?.takeIf { id ->
                    recordings.any { it.id == id }
                }
                ?: bundled.first().id

            save(
                spec.key,
                VoiceSlot(
                    recordings = recordings,
                    selectedId = selected
                )
            )
        }

        if (allSuccessful) {
            settings.edit()
                .putLong("apk_updated_at", updatedAt)
                .apply()
        }
    }


    fun importRecording(uri: Uri): Recording {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "팀원 녹음.mp3"
        val id = UUID.randomUUID().toString()
        val recording = Recording(id, name, "$id.mp3")
        val target = file(recording)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "파일을 열 수 없습니다." }
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192); var total = 0L
                    while (true) { val size = input.read(buffer); if (size < 0) break; total += size
                        require(total <= 32L * 1024 * 1024) { "MP3는 32MB 이하로 선택해 주세요." }; output.write(buffer, 0, size) }
                    require(total > 0) { "빈 파일입니다." }
                }
            }
            val extractor = MediaExtractor()
            try { extractor.setDataSource(target.absolutePath)
                require((0 until extractor.trackCount).any { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mpeg" }) { "실제 MP3 파일을 선택해 주세요." }
            } finally { extractor.release() }
            return recording
        } catch (failure: Exception) { target.delete(); throw failure }
    }
    fun deleteAll() { directory.listFiles()?.forEach(File::delete); prefs.edit().clear().apply() }
}
