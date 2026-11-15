
package dev.hwanaebi.ui

data class VoiceSpec(
    val key: String,
    val character: String,
    val flavor: String,
    val text: String,
    val filename: String
)

object VoiceCatalog {

    val characterIds = listOf("ajumma", "bestie", "gentle")
    val characters = listOf("아지매", "절친", "다정한 친구")

    val flavorIds = listOf("mild", "medium", "spicy")
    val flavors = listOf("순한맛", "중간맛", "매운맛")

    val entries = listOf(
        VoiceSpec(
            "ajumma_mild", "아지매", "순한맛",
            "아이고, 급할 거 없다. 우리 여유 있게 가자!",
            "ajimae_mild.mp3"
        ),
        VoiceSpec(
            "ajumma_medium", "아지매", "중간맛",
            "아이고 참, 마음만 바빠지면 우리만 손해야. 어깨 힘 빼고 가자!",
            "ajimae_medium.mp3"
        ),
        VoiceSpec(
            "ajumma_spicy", "아지매", "매운맛",
            "거 참, 오늘 길이 성질을 시험하네! 투덜거림은 내가 맡을 테니, 우리는 안전하게 가자!",
            "ajimae_spicy.mp3"
        ),
        VoiceSpec(
            "bestie_mild", "절친", "순한맛",
            "오늘도 운전하느라 고생이지? 내가 네 편이야.",
            "friend_mild.mp3"
        ),
        VoiceSpec(
            "bestie_medium", "절친", "중간맛",
            "오늘 도로 난이도 좀 높네. 투덜거림은 나한테 맡기고, 천천히 가자!",
            "friend_medium.mp3"
        ),
        VoiceSpec(
            "bestie_spicy", "절친", "매운맛",
            "와, 오늘 도로는 인내심 보스전이네! 불평은 내가 할게. 넌 안전하게만 가자!",
            "friend_spicy.mp3"
        ),
        VoiceSpec(
            "gentle_mild", "다정한 친구", "순한맛",
            "괜찮아요. 어깨의 힘을 빼고 천천히 가요.",
            "kind_mild.mp3"
        ),
        VoiceSpec(
            "gentle_medium", "다정한 친구", "중간맛",
            "많이 답답했죠. 속상한 마음, 제가 함께할게요. 앞을 보며 편안히 가요.",
            "kind_medium.mp3"
        ),
        VoiceSpec(
            "gentle_spicy", "다정한 친구", "매운맛",
            "오늘 길이 참 얄밉네요. 속상함은 제가 받아둘게요. 지금은 우리 안전이 먼저예요.",
            "kind_spicy.mp3"
        )
    )

    fun find(key: String): VoiceSpec =
        entries.first { it.key == key }
}
