package com.wang.sonovel.legado

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.wang.sonovel.data.Rule

/**
 * 「阅读」(Legado) 书源导入：
 *  - 支持 `[{...},{...}]`（标准书源分享格式）、单个对象、以及 `{"sources":[...]}` 之类的包装
 *  - 支持网络订阅地址（由调用方下载文本后交给 [parse]）
 */
object LegadoImporter {

    private val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    private val listType = object : TypeToken<List<LegadoSource>>() {}.type

    /** 解析书源 JSON 文本（兼容数组 / 单对象 / 包装对象） */
    fun parse(text: String): List<LegadoSource> {
        val root = JsonParser.parseString(text.trim())
        val arr: JsonArray? = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> {
                val obj = root.asJsonObject
                when {
                    obj.has("bookSourceUrl") -> JsonArray().apply { add(obj) }
                    else -> firstArray(obj, "sources", "bookSources", "data", "list", "items")
                }
            }
            else -> null
        }
        val raw = arr ?: throw IllegalArgumentException("不是有效的书源 JSON（应为书源数组）")
        val list: List<LegadoSource> = gson.fromJson(raw, listType) ?: emptyList()
        return list.mapNotNull { it.takeIf { s -> !s.url.isBlank() } }
            .distinctBy { it.url }
    }

    /** 统计信息，用于导入提示 */
    fun summary(sources: List<LegadoSource>): String {
        val searchable = sources.count { it.searchable }
        val disabled = sources.count { !it.enabled }
        return buildString {
            append("共 ${sources.size} 个书源")
            if (searchable < sources.size) append("（${sources.size - searchable} 个不支持搜索）")
            if (disabled > 0) append("，其中 $disabled 个在阅读里是停用状态")
        }
    }

    fun toJson(sources: List<LegadoSource>): String = gson.toJson(sources)

    /** 由订阅地址推断保存的文件名 */
    fun fileNameFor(url: String, sources: List<LegadoSource>): String {
        val host = runCatching { java.net.URI(url.trim()).host }.getOrNull().orEmpty()
        val last = url.trim().substringBefore('?').substringAfterLast('/')
        val base = when {
            last.endsWith(".json", true) -> last.removeSuffix(".json").removeSuffix(".JSON")
            host.isNotBlank() -> host
            else -> sources.firstOrNull()?.name ?: "订阅"
        }
        val safe = base.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_").take(40).ifBlank { "订阅" }
        return "legado-$safe.json"
    }

    /** 转成本应用的 [Rule]（书源能力由 [Rule.legado] 承载） */
    fun toRule(src: LegadoSource, file: String, id: Int): Rule {
        val rule = Rule()
        rule.id = id
        rule.file = file
        rule.url = src.url
        rule.name = src.name
        rule.comment = listOfNotNull(
            src.bookSourceGroup?.takeIf { it.isNotBlank() }?.let { "分组：$it" },
            src.bookSourceComment?.takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifBlank { null }
        rule.disabled = !src.enabled
        rule.search = Rule.Search().apply { url = src.searchUrl?.takeIf { it.isNotBlank() } }
        // 阅读书源的正文由引擎整理成 <p> 段落，这里关闭按 <br> 切分
        rule.chapter = Rule.Chapter().apply { paragraphTagClosed = true }
        src.intervals()?.let { (min, max) ->
            if (min > 0) {
                rule.crawl = Rule.Crawl().apply {
                    minInterval = min
                    maxInterval = maxOf(min, max)
                }
            }
        }
        rule.legado = src
        return rule
    }

    private fun firstArray(obj: JsonObject, vararg keys: String): JsonArray? {
        for (k in keys) {
            val v: JsonElement? = obj.get(k)
            if (v != null && v.isJsonArray) return v.asJsonArray
        }
        // 任意一个数组字段都可以尝试
        for ((_, v) in obj.entrySet()) if (v.isJsonArray) return v.asJsonArray
        return null
    }
}
