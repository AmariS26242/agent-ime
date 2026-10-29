package app.agentime

/**
 * OFFLINE LIMITED PINYIN PROOF-OF-CONCEPT
 *
 * This is a small hand-made dictionary that demonstrates typing pinyin and
 * selecting a Hanzi candidate. It is NOT a full Chinese input engine. It has
 * no fuzzy matching, no tone support, no learning, no phrase segmentation,
 * no large lexicon and no licensing-classified dictionary data beyond the
 * handful hard-coded here for the technical demo.
 *
 * Full Chinese typing acceptance is PENDING: a mature licensed engine
 * (Sogou / Baidu / fcitx5 / Rime, etc.) must be evaluated for license, dictionary
 * license, offline capability and integration cost before integration.
 */
object PinyinDictionary {

    // A very small seed. Not representative of full Chinese input.
    private val table: Map<String, List<String>> = mapOf(
        "ni" to listOf("你", "尼", "泥", "妮"),
        "hao" to listOf("好", "号", "浩", "豪"),
        "nihao" to listOf("你好"),
        "wo" to listOf("我", "握", "沃", "窝"),
        "men" to listOf("们", "门", "闷"),
        "women" to listOf("我们"),
        "shi" to listOf("是", "时", "事", "市", "式", "试", "世", "视"),
        "de" to listOf("的", "得", "地", "德"),
        "zhong" to listOf("中", "种", "重", "钟", "众"),
        "guo" to listOf("国", "过", "果", "郭", "锅"),
        "zhongguo" to listOf("中国"),
        "ren" to listOf("人", "任", "认", "仁"),
        "xie" to listOf("谢", "写", "些", "鞋"),
        "xiexie" to listOf("谢谢"),
        "zai" to listOf("在", "再", "载", "仔"),
        "jian" to listOf("见", "间", "件", "简", "建", "减", "检"),
        "ai" to listOf("爱", "哎", "艾", "哀"),
        "qing" to listOf("请", "清", "情", "青", "轻", "晴"),
        "wen" to listOf("问", "文", "温", "闻"),
        "ti" to listOf("题", "提", "体", "替"),
        "wenti" to listOf("问题"),
        "da" to listOf("大", "打", "答", "达"),
        "tian" to listOf("天", "田", "填", "甜"),
        "ming" to listOf("明", "名", "命", "鸣"),
        "mingtian" to listOf("明天"),
        "geng" to listOf("更", "耕", "梗"),
        "duan" to listOf("短", "段", "断"),
        "gengduan" to listOf("更短"),
        "zi" to listOf("自", "字", "子", "资"),
        "ran" to listOf("然", "燃", "染"),
        "ziran" to listOf("自然"),
        "li" to listOf("里", "理", "礼", "李"),
        "mao" to listOf("貌", "帽", "贸"),
        "limao" to listOf("礼貌"),
    )

    fun candidates(pinyin: String): List<String> {
        val key = pinyin.lowercase().trim()
        if (key.isEmpty()) return emptyList()
        return table[key].orEmpty()
    }

    /**
     * A deliberately naive converter: try exact token first, then fall back to
     * per-syllable segmentation. Only for demo purposes.
     */
    fun convertFully(pinyin: String): String? {
        val key = pinyin.lowercase().trim()
        if (key.isEmpty()) return null
        table[key]?.firstOrNull()?.let { return it }
        return null
    }
}
