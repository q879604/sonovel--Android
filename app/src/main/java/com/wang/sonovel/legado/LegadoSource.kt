package com.wang.sonovel.legado

/**
 * 「阅读」(Legado) 书源模型。
 *
 * 字段名与 Legado 导出的 JSON 完全一致，直接用 Gson 反序列化；
 * 未识别的字段（app 相关、变量等）会被忽略。
 */
data class LegadoSource(
    val bookSourceUrl: String? = null,
    val bookSourceName: String? = null,
    val bookSourceGroup: String? = null,
    val bookSourceType: Int = 0,
    val bookSourceComment: String? = null,
    val enabled: Boolean = true,
    val enabledExplore: Boolean = true,
    val header: String? = null,
    val loginUrl: String? = null,
    val searchUrl: String? = null,
    val exploreUrl: String? = null,
    val ruleSearch: SearchRule? = null,
    val ruleExplore: SearchRule? = null,
    val ruleBookInfo: BookInfoRule? = null,
    val ruleToc: TocRule? = null,
    val ruleContent: ContentRule? = null,
    val concurrentRate: String? = null,
    val customOrder: Int = 0,
    val weight: Int = 0,
    val lastUpdateTime: Long = 0L,
) {

    data class SearchRule(
        val bookList: String? = null,
        val name: String? = null,
        val author: String? = null,
        val kind: String? = null,
        val wordCount: String? = null,
        val lastChapter: String? = null,
        val intro: String? = null,
        val coverUrl: String? = null,
        val bookUrl: String? = null,
        val checkKeyWord: String? = null,
    )

    data class BookInfoRule(
        val init: String? = null,
        val name: String? = null,
        val author: String? = null,
        val kind: String? = null,
        val wordCount: String? = null,
        val lastChapter: String? = null,
        val intro: String? = null,
        val coverUrl: String? = null,
        val tocUrl: String? = null,
        val canReName: String? = null,
    )

    data class TocRule(
        val chapterList: String? = null,
        val chapterName: String? = null,
        val chapterUrl: String? = null,
        val isVolume: String? = null,
        val isVip: String? = null,
        val updateTime: String? = null,
        val nextTocUrl: String? = null,
    )

    data class ContentRule(
        val content: String? = null,
        val nextContentUrl: String? = null,
        val webJs: String? = null,
        val sourceRegex: String? = null,
        val replaceRegex: String? = null,
        val imageStyle: String? = null,
    )

    val url: String get() = bookSourceUrl.orEmpty().trim()
    val name: String get() = bookSourceName?.takeIf { it.isNotBlank() } ?: url
    val searchable: Boolean get() = !searchUrl.isNullOrBlank()

    /** 请求头（Legado 的 header 字段是一个 JSON 字符串） */
    fun headers(): Map<String, String> {
        val h = header?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return LegadoJson.headersOf(h)
    }

    /** concurrentRate：单个数字为间隔毫秒，`min,max` 为随机区间 */
    fun intervals(): Pair<Int, Int>? {
        val raw = concurrentRate?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parts = raw.split(',', '，', '-').map { it.trim() }.filter { it.isNotEmpty() }
        val nums = parts.mapNotNull { it.toIntOrNull() }
        return when (nums.size) {
            0 -> null
            1 -> nums[0] to nums[0]
            else -> nums[0] to nums[1]
        }
    }

    /** 是否为「有声/文件」等非文本书源 */
    val isText: Boolean get() = bookSourceType == 0
}
