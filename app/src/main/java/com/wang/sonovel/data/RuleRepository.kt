package com.wang.sonovel.data

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.wang.sonovel.legado.LegadoImporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class RuleFile(
    val name: String,
    val builtIn: Boolean,
    val rules: List<Rule>,
    /** 覆盖了同名内置文件 */
    val overridesBuiltIn: Boolean = false,
)

/**
 * 书源规则仓库：内置规则位于 assets/rules，用户导入的规则位于 files/rules（同名覆盖内置）。
 */
class RuleRepository(private val context: Context, private val settings: SettingsRepository) {

    private val gson = GsonBuilder().create()
    private val userDir = File(context.filesDir, "rules").apply { mkdirs() }
    private val legadoDir = File(context.filesDir, "legado").apply { mkdirs() }
    private val _files = MutableStateFlow<List<RuleFile>>(emptyList())
    val files: StateFlow<List<RuleFile>> = _files.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        val builtIn = context.assets.list("rules").orEmpty().filter { it.endsWith(".json") }.sorted()
        val user = userDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().map { it.name }.sorted()
        val legado = legadoDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().map { it.name }.sorted()
        val result = mutableListOf<RuleFile>()
        // main.json 始终排在第一位；「阅读」书源排在最后
        val order = (builtIn + user).distinct()
            .sortedWith(compareBy({ it != "main.json" }, { it !in builtIn }, { it })) + legado
        for (name in order) {
            if (name in legado) {
                val text = runCatching { File(legadoDir, name).readText() }.getOrNull() ?: continue
                val rules = runCatching { parseLegado(text, name) }.getOrElse { emptyList() }
                result += RuleFile(name = name, builtIn = false, rules = rules, overridesBuiltIn = false)
                continue
            }
            val userFile = File(userDir, name)
            val text = runCatching {
                if (userFile.exists()) userFile.readText()
                else context.assets.open("rules/$name").bufferedReader().use { it.readText() }
            }.getOrNull() ?: continue
            val rules = runCatching { parse(text, name) }.getOrElse { emptyList() }
            result += RuleFile(
                name = name,
                builtIn = name in builtIn,
                rules = rules,
                overridesBuiltIn = name in builtIn && userFile.exists(),
            )
        }
        _files.value = result
    }

    /** 解析「阅读」(Legado) 书源文件 */
    fun parseLegado(text: String, fileName: String): List<Rule> =
        LegadoImporter.parse(text).mapIndexed { i, src -> LegadoImporter.toRule(src, fileName, i + 1) }

    /** 该文件是否为「阅读」书源文件 */
    fun isLegadoFile(name: String): Boolean = File(legadoDir, name).exists()

    /** 解析规则 JSON 数组并填充默认值 */
    fun parse(text: String, fileName: String): List<Rule> {
        // 兼容 json5 风格的注释
        val cleaned = stripComments(text)
        val type = object : TypeToken<List<Rule>>() {}.type
        val list: List<Rule> = gson.fromJson(cleaned, type) ?: emptyList()
        return list.filter { !it.url.isNullOrBlank() }.mapIndexed { i, r ->
            r.id = i + 1
            r.file = fileName
            applyDefaults(r)
        }
    }

    fun rawJson(rule: Rule): String {
        if (isLegadoFile(rule.file)) {
            val text = runCatching { File(legadoDir, rule.file).readText() }.getOrDefault("")
            return runCatching {
                val arr = JsonParser.parseString(text).asJsonArray
                GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                    .toJson(arr[rule.id - 1])
            }.getOrDefault(text)
        }
        val file = File(userDir, rule.file)
        val text = if (file.exists()) file.readText()
        else context.assets.open("rules/${rule.file}").bufferedReader().use { it.readText() }
        return runCatching {
            val arr = JsonParser.parseString(stripComments(text)).asJsonArray
                .filter { it.asJsonObject.get("url")?.asString?.isNotBlank() == true }
            GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(arr[rule.id - 1])
        }.getOrDefault("")
    }

    /** 全部书源（所有规则文件合并为一个列表） */
    val allRules: List<Rule> get() = _files.value.flatMap { it.rules }

    /** 书源开关的存储 key */
    fun stateKey(rule: Rule) = "${rule.file}#${rule.url}"

    /**
     * 未手动设置时的默认开关。
     * 「阅读」书源跟随其在阅读里的启用状态（停用的不参与搜索，避免拖慢聚合搜索）。
     */
    fun defaultEnabled(rule: Rule): Boolean = rule.legado?.enabled ?: true

    fun isEnabled(rule: Rule): Boolean = settings.current.sourceStates[stateKey(rule)] ?: defaultEnabled(rule)

    fun setEnabled(rule: Rule, enabled: Boolean) = settings.update {
        val key = stateKey(rule)
        // 与默认状态一致时不必记录
        it.copy(sourceStates = if (enabled == defaultEnabled(rule)) it.sourceStates - key else it.sourceStates + (key to enabled))
    }

    fun setAllEnabled(rules: List<Rule>, enabled: Boolean) = settings.update { st ->
        val states = st.sourceStates.toMutableMap()
        for (r in rules) {
            val key = stateKey(r)
            if (enabled == defaultEnabled(r)) states.remove(key) else states[key] = enabled
        }
        st.copy(sourceStates = states)
    }

    /** 所有书源恢复为默认开关 */
    fun resetStates() = settings.update { it.copy(sourceStates = emptyMap()) }

    /** 可参与聚合搜索的书源：所有已开启且支持搜索的书源 */
    fun searchableRules(): List<Rule> = allRules.filter { it.searchable && isEnabled(it) }

    fun byKey(key: String): Rule? = allRules.firstOrNull { it.key == key }

    /** 根据书籍链接匹配书源：优先已开启的书源，其次全部书源 */
    fun matchByUrl(bookUrl: String): Rule? {
        val url = bookUrl.trim()
        fun match(r: Rule): Boolean {
            val base = r.url?.trim()?.trimEnd('/') ?: return false
            if (url.startsWith(base)) return true
            val host = hostOf(base) ?: return false
            return hostOf(url)?.removePrefix("www.") == host.removePrefix("www.")
        }
        val all = allRules
        return all.firstOrNull { match(it) && isEnabled(it) } ?: all.firstOrNull(::match)
    }

    /** 导入规则文件，返回规则数量 */
    fun import(fileName: String, text: String): Int {
        val name = fileName.substringAfterLast('/').let { if (it.endsWith(".json")) it else "$it.json" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val rules = parse(text, name)
        require(rules.isNotEmpty()) { "未解析到有效书源（需为 JSON 数组，且每个书源包含 url）" }
        File(userDir, name).writeText(text)
        reload()
        return rules.size
    }

    /** 删除导入的规则文件（对内置文件则是恢复为内置版本） */
    fun deleteUserFile(name: String) {
        File(userDir, name).delete()
        File(legadoDir, name).delete()
        reload()
    }

    /**
     * 导入「阅读」(Legado) 书源。
     *
     * @param text  书源 JSON 文本
     * @param fileName 保存的文件名（订阅地址导入时由地址推断）
     * @param enableAll 是否把「阅读」里停用的书源也一并开启
     * @return 导入结果描述
     */
    fun importLegado(text: String, fileName: String, enableAll: Boolean = false): String {
        val name = fileName.substringAfterLast('/')
            .let { if (it.endsWith(".json", true)) it else "$it.json" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val sources = LegadoImporter.parse(text)
        require(sources.isNotEmpty()) { "未解析到书源（阅读书源应为 JSON 数组，且包含 bookSourceUrl）" }
        val effective = if (enableAll) sources.map { it.copy(enabled = true) } else sources
        File(legadoDir, name).writeText(LegadoImporter.toJson(effective))
        reload()
        val searchable = effective.count { it.searchable }
        val off = effective.count { !it.enabled }
        return buildString {
            append("「$name」导入 ${effective.size} 个书源")
            if (searchable < effective.size) append("，${effective.size - searchable} 个不支持搜索")
            if (off > 0) append("，$off 个在阅读里是停用的（可长按书源开启）")
        }
    }

    private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host }.getOrNull()

    companion object {
        const val META_BOOK_NAME = "meta[property=\"og:novel:book_name\"]"
        const val META_AUTHOR = "meta[property=\"og:novel:author\"]"
        const val META_INTRO = "meta[name=\"description\"]"
        const val META_CATEGORY = "meta[property=\"og:novel:category\"]"
        const val META_COVER_URL = "meta[property=\"og:image\"]"
        const val META_LATEST_CHAPTER = "meta[property=\"og:novel:latest_chapter_name\"]"
        const val META_LATEST_CHAPTER_URL = "meta[property=\"og:novel:latest_chapter_url\"]"
        const val META_LAST_UPDATE_TIME = "meta[property=\"og:novel:update_time\"]"
        const val META_STATUS = "meta[property=\"og:novel:status\"]"

        fun applyDefaults(rule: Rule): Rule {
            rule.language = LangType.normalize(rule.language)
            rule.search?.apply {
                if (timeout == null) timeout = 15
            }
            val book = rule.book ?: Rule.Book().also { rule.book = it }
            book.apply {
                if (timeout == null) timeout = 15
                if (bookName.isNullOrBlank()) bookName = META_BOOK_NAME
                if (author.isNullOrBlank()) author = META_AUTHOR
                if (intro.isNullOrBlank()) intro = META_INTRO
                if (coverUrl.isNullOrBlank()) coverUrl = META_COVER_URL
                if (category.isNullOrBlank()) category = META_CATEGORY
                if (latestChapter.isNullOrBlank()) latestChapter = META_LATEST_CHAPTER
                if (latestChapterUrl.isNullOrBlank()) latestChapterUrl = META_LATEST_CHAPTER_URL
                if (lastUpdateTime.isNullOrBlank()) lastUpdateTime = META_LAST_UPDATE_TIME
                if (status.isNullOrBlank()) status = META_STATUS
            }
            rule.toc?.apply {
                if (timeout == null) timeout = 60
            }
            rule.chapter?.apply {
                if (timeout == null) timeout = 15
            }
            return rule
        }

        /** 去掉 // 与 /* */ 注释（忽略字符串内部） */
        fun stripComments(src: String): String {
            val sb = StringBuilder(src.length)
            var i = 0
            var inStr = false
            var quote = '"'
            while (i < src.length) {
                val c = src[i]
                if (inStr) {
                    sb.append(c)
                    if (c == '\\' && i + 1 < src.length) {
                        sb.append(src[i + 1]); i += 2; continue
                    }
                    if (c == quote) inStr = false
                    i++
                    continue
                }
                if (c == '"' || c == '\'') {
                    inStr = true; quote = c; sb.append(c); i++; continue
                }
                if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                    while (i < src.length && src[i] != '\n') i++
                    continue
                }
                if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                    val end = src.indexOf("*/", i + 2)
                    i = if (end == -1) src.length else end + 2
                    continue
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }
    }
}
