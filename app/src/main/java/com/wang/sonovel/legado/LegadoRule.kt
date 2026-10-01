package com.wang.sonovel.legado

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.URI

/** JS 执行器（Android 用 QuickJS，本地测试可用 Rhino） */
interface LegadoJsRunner {
    fun run(code: String, runtime: LegadoRuntime, input: String?): String?
}

/** 规则里的 JS 片段：`@js:` 前缀或 `<js>...</js>` 块 */
fun jsCodeOf(rule: String?): String? {
    val raw = rule?.trim().orEmpty()
    if (raw.isEmpty()) return null
    if (raw.startsWith("@js:", true)) return raw.substring(4)
    if (raw.startsWith("<js>", true) && raw.endsWith("</js>")) {
        return raw.substring(4, raw.length - 5)
    }
    return null
}

/**
 * 一次规则求值所依赖的运行时上下文（对应 Legado 的 `AnalyzeRule` + `BaseSource`）。
 */
class LegadoRuntime(
    val source: LegadoSource,
    var baseUrl: String,
    val fetcher: LegadoFetcher,
    val js: LegadoJsRunner? = null,
    val key: String? = null,
    val page: Int = 1,
    val variables: MutableMap<String, String> = mutableMapOf(),
) {
    /** 供 JS 使用的变量 */
    var title: String = ""
    var bookJson: String = "{}"
    var cookie: String = ""

    private val headers: Map<String, String> = source.headers()

    /** 相对地址转绝对地址 */
    fun resolve(url: String?): String {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        if (raw.startsWith("//")) return (if (baseUrl.startsWith("https")) "https:" else "http:") + raw
        return runCatching { URI(baseUrl).resolve(raw).toString() }.getOrDefault(raw)
    }

    private fun mergedHeaders(extra: Map<String, String>, url: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out.putAll(headers)
        out.putAll(extra)
        val jar = LegadoCookies.get(url)
        if (jar.isNotBlank() && out.keys.none { it.equals("Cookie", true) }) out["Cookie"] = jar
        return out
    }

    /** 执行一次请求（自动解析「阅读」老式 POST 语法、自动带 Cookie） */
    fun request(
        raw: String,
        extra: Map<String, String> = emptyMap(),
        charset: String? = null,
        methodOverride: String? = null,
        bodyOverride: String? = null,
    ): LegadoResponse {
        val spec = LegadoRequest.parse(resolve(raw))
        val abs = resolve(spec.url)
        if (abs.isEmpty()) return LegadoResponse("", 0, emptyMap(), "")
        val hs = LinkedHashMap<String, String>()
        hs.putAll(spec.headers)
        hs.putAll(extra)
        val method = methodOverride?.takeIf { it.isNotBlank() }?.uppercase() ?: spec.method
        val body = bodyOverride ?: spec.body
        val resp = runCatching { fetcher.request(abs, method, mergedHeaders(hs, abs), body, spec.charset ?: charset) }
            .getOrElse { return LegadoResponse(abs, 0, emptyMap(), "") }
        if (resp.headers.isNotEmpty()) {
            val sc = resp.headers["set-cookie"]?.let { listOf(it) } ?: emptyList()
            LegadoCookies.saveFromResponse(abs, sc)
        }
        return resp
    }

    /** 同步 GET（带书源 header），返回响应体 */
    fun get(url: String?): String = request(url.orEmpty()).body

    /** 同步 POST，返回响应体 */
    fun post(url: String?, body: String): String =
        runCatching {
            val spec = LegadoRequest.parse(resolve(url.orEmpty()))
            val abs = resolve(spec.url)
            fetcher.request(abs, "POST", mergedHeaders(spec.headers, abs), body, spec.charset).body
        }.getOrDefault("")

    /** 以新地址派生一个子上下文（共享变量与 JS 引擎） */
    fun at(url: String): LegadoRuntime = LegadoRuntime(source, url, fetcher, js, key, page, variables).also {
        it.title = title
        it.bookJson = bookJson
        it.cookie = cookie
    }
}

/**
 * Legado（阅读）书源规则解析器。
 *
 * 规则语法：`选择器@选择器@取值`，选择器支持
 *  - 简写：`tag.li`、`class.item`、`id.content`、`text.关键字`、`children`
 *  - 前缀：`@css:`、`@json:` / `$.`、`@xpath:` / `//`、`@js:`
 *  - 后缀：`@text`、`@html`、`@href`、`@src`、`@content`、`@ownText`、`@textNodes`、`@all`
 *  - 索引：`.0`、`.-1`；替换：`##正则##替换##`
 */
object LegadoRule {

    private val INDEX = Regex("-?\\d+")
    private val SLICE = Regex("\\[(-?\\d*):(-?\\d*)]\\s*$")
    private val SINGLE_INDEX = Regex("\\[(-?\\d+)]\\s*$")

    /** 取值类型关键字（单独成段） */
    private val CONTENT_TYPES = setOf(
        "text", "textNodes", "ownText", "html", "all", "outerHtml",
        "href", "src", "content", "value", "title",
    )

    private val PREFIXES = listOf("js:", "css:", "json:", "xpath:", "XPath:")

    // ============================== 对外入口 ==============================

    fun eval(rule: String?, value: Any?, rt: LegadoRuntime): Any? {
        val raw = rule?.trim().orEmpty()
        if (raw.isEmpty()) return value
        if (raw.startsWith("<js>", true)) {
            // 整条规则就是一个 <js> 块：其中的 @、||、&& 都是 JS 语法，不能当规则分隔符
            val (main, replacements) = splitReplacements(raw)
            var cur: Any? = jsCodeOf(main)?.let { runJs(it, value, rt) } ?: value
            if (replacements.isNotEmpty()) {
                var s = stringify(cur)
                for ((pattern, repl) in replacements) s = replaceRegex(s, pattern, repl)
                cur = s
            }
            return cur
        }
        if (!raw.contains("@js:")) {
            splitTop(raw, "||")?.let { parts ->
                var last: Any? = ""
                for (p in parts) {
                    val v = eval(p, value, rt)
                    if (!isBlank(v)) return v
                    last = v
                }
                return last
            }
            splitTop(raw, "&&")?.let { parts ->
                val values = parts.map { eval(it, value, rt) }
                val texts = values.map { stringify(it) }.filter { it.isNotBlank() }
                return when (values.size) {
                    1 -> values[0]
                    else -> texts.joinToString("\n")
                }
            }
        }
        val (main, replacements) = splitReplacements(raw)
        var cur: Any? = value
        for (step in splitSteps(main)) cur = applyStep(step, cur, rt)
        if (replacements.isNotEmpty()) {
            var s = stringify(cur)
            for ((pattern, repl) in replacements) s = replaceRegex(s, pattern, repl)
            cur = s
        }
        return cur
    }

    fun text(rule: String?, value: Any?, rt: LegadoRuntime): String = stringify(eval(rule, value, rt)).trim()

    fun elements(rule: String?, value: Any?, rt: LegadoRuntime): List<Element> {
        val v = eval(rule, value, rt)
        return when (v) {
            is Element -> listOf(v)
            is Elements -> v.toList()
            is List<*> -> v.filterIsInstance<Element>()
            is String -> if (v.isBlank()) emptyList() else listOf(Jsoup.parse(v))
            else -> emptyList()
        }
    }

    /** 把规则结果统一成条目列表：HTML 元素 或 JSON 条目 */
    fun items(rule: String?, value: Any?, rt: LegadoRuntime): List<Any> {
        if (rule.isNullOrBlank()) {
            return when (value) {
                is Element -> listOf(value)
                is Elements -> value.toList()
                is JsonElement -> LegadoJson.items(value)
                else -> emptyList()
            }
        }
        val v = eval(rule, value, rt)
        return when (v) {
            null -> emptyList()
            is Element -> listOf(v)
            is Elements -> v.toList()
            is JsonElement -> LegadoJson.items(v)
            is String -> {
                val s = v.trim()
                val json = if (s.startsWith("[") || s.startsWith("{")) LegadoJson.parse(s) else null
                if (json != null) LegadoJson.items(json) else if (s.isBlank()) emptyList() else listOf(s)
            }
            is List<*> -> v.filterNotNull()
            else -> listOf(v)
        }
    }

    /**
     * 「下一页 / 目录地址」一类的字段：既可能是纯地址模板（`/toc/1.html`、`/api/toc?id=1`、`{{bookUrl}}`），
     * 也可能是从当前页面取值的规则（`class.next@href`、`//a/@href`）。返回绝对地址。
     */
    fun target(rule: String?, doc: Any?, rt: LegadoRuntime): String? {
        val tpl = rule?.trim().orEmpty()
        if (tpl.isEmpty()) return null
        jsCodeOf(tpl)?.let { code ->
            val out = rt.js?.run(code, rt, null).orEmpty().trim()
            return rt.resolve(out).ifBlank { null }
        }
        if (!isRuleLike(tpl)) return LegadoRule.url(tpl, rt).ifBlank { null }
        val v = runCatching { eval(tpl, doc, rt) }.getOrNull()
        val s = when (v) {
            is Element -> v.absUrl("href").ifEmpty { v.absUrl("value").ifEmpty { v.attr("value").ifEmpty { v.attr("href") } } }
            is Elements -> v.firstOrNull()
                ?.let { it.absUrl("href").ifEmpty { it.absUrl("value").ifEmpty { it.attr("value").ifEmpty { it.attr("href") } } } }.orEmpty()
            else -> stringify(v).trim()
        }
        return s.trim().ifBlank { null }?.let { rt.resolve(it) }
    }

    private val RULE_HEADS = listOf("tag.", "class.", "id.", "text.", "children", "@css:", "@xpath:", "@json:", "@js:", "$.")

    /** 判断一个字段是「规则」还是「地址」 */
    fun isRuleLike(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.startsWith("//") || t.startsWith("(//")) return true
        if (RULE_HEADS.any { t.startsWith(it, ignoreCase = true) }) return true
        return t.contains('@') && !t.contains("{{")
    }

    /**
     * URL 规则：替换 `{{key}}` `{{page}}` 等变量，支持 `@js:`（脚本返回地址）。
     */
    fun url(rule: String?, rt: LegadoRuntime, extra: Map<String, String> = emptyMap()): String {
        val raw = rule?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        jsCodeOf(raw)?.let { code ->
            val out = rt.js?.run(code, rt, null) ?: ""
            return rt.resolve(out.trim())
        }
        return rt.resolve(template(raw, rt, extra))
    }

    /** 只做 `{{变量}}` 替换（不解析为绝对地址），用于 POST 请求体 */
    fun template(rule: String?, rt: LegadoRuntime, extra: Map<String, String> = emptyMap()): String {
        var s = rule?.trim().orEmpty()
        if (s.isEmpty()) return ""
        if (s.startsWith("@js:")) return rt.js?.run(s.substring(4), rt, null)?.trim().orEmpty()
        for ((k, v) in vars(rt) + extra) s = s.replace("{{$k}}", v)
        // 未识别的变量置空
        return Regex("\\{\\{[^}]*}}").replace(s, "")
    }

    private fun vars(rt: LegadoRuntime): Map<String, String> {
        val m = HashMap<String, String>()
        m["key"] = rt.key.orEmpty()
        m["searchKey"] = rt.key.orEmpty()
        m["page"] = rt.page.toString()
        m["searchPage"] = rt.page.toString()
        m["baseUrl"] = rt.source.url
        m["bookUrl"] = rt.baseUrl
        m["sourceUrl"] = rt.source.url
        m["title"] = rt.title
        m.putAll(rt.variables)
        return m
    }

    // ============================== 规则切分 ==============================

    /** 按 `@` 切分规则段；JS 段内部的 `@` 不切分，方括号/引号内的 `@` 也不切分 */
    internal fun splitSteps(rule: String): List<String> {
        val steps = ArrayList<String>()
        val cur = StringBuilder()
        var bracket = 0
        var quote = ' '
        var i = 0
        fun flush() {
            if (cur.isNotBlank()) steps += cur.toString().trim()
            cur.setLength(0)
        }
        while (i < rule.length) {
            val c = rule[i]
            when {
                quote != ' ' -> {
                    cur.append(c)
                    if (c == quote) quote = ' '
                    i++
                }
                c == '\'' || c == '"' -> {
                    quote = c; cur.append(c); i++
                }
                c == '[' -> {
                    bracket++; cur.append(c); i++
                }
                c == ']' -> {
                    if (bracket > 0) bracket--; cur.append(c); i++
                }
                c == '@' && bracket == 0 -> {
                    val rest = rule.substring(i + 1)
                    val isPrefix = PREFIXES.any { rest.startsWith(it, ignoreCase = true) }
                    val inJs = cur.startsWith("js:", true)
                    // xpath 的属性写法 `//a/@href` 不切分
                    val xpathAttr = i > 0 && rule[i - 1] == '/'
                    if (isPrefix) {
                        flush(); i++
                    } else if (!inJs && !xpathAttr) {
                        flush(); i++
                    } else {
                        cur.append(c); i++
                    }
                }
                else -> {
                    cur.append(c); i++
                }
            }
        }
        flush()
        return steps
    }

    /** 只在顶层（不在括号/引号/JS 内）按分隔符切分 */
    private fun splitTop(rule: String, op: String): List<String>? {
        if (!rule.contains(op)) return null
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var bracket = 0
        var i = 0
        while (i < rule.length) {
            val c = rule[i]
            when {
                c == '[' || c == '(' -> { bracket++; cur.append(c); i++ }
                c == ']' || c == ')' -> { if (bracket > 0) bracket--; cur.append(c); i++ }
                bracket == 0 && rule.startsWith(op, i) -> {
                    parts += cur.toString(); cur.setLength(0); i += op.length
                }
                else -> { cur.append(c); i++ }
            }
        }
        parts += cur.toString()
        return if (parts.size > 1) parts else null
    }

    /** 拆出 `##正则##替换##`（JS 段内的 `##` 不算） */
    private fun splitReplacements(rule: String): Pair<String, List<Pair<String, String>>> {
        var inJs = false
        var idx = -1
        var i = 0
        while (i < rule.length - 1) {
            if (rule[i] == '@') {
                val rest = rule.substring(i + 1)
                val isJs = rest.startsWith("js:", true)
                val isOther = PREFIXES.any { rest.startsWith(it, ignoreCase = true) }
                if (isJs) inJs = true else if (isOther) inJs = false
                i += if (isOther) 2 else 1
                continue
            }
            if (!inJs && rule[i] == '#' && rule[i + 1] == '#') { idx = i; break }
            i++
        }
        if (idx < 0) return rule to emptyList()
        val parts = rule.substring(idx).split("##")
        val pairs = ArrayList<Pair<String, String>>()
        var k = 1
        while (k < parts.size) {
            val pattern = parts[k]
            val repl = parts.getOrElse(k + 1) { "" }
            // 结尾的 `###` 会产生只有 # 的空段，跳过
            if (pattern.isNotEmpty() && pattern.any { it != '#' }) pairs += pattern to repl
            k += 2
        }
        return rule.substring(0, idx) to pairs
    }

    private fun replaceRegex(text: String, pattern: String, repl: String): String =
        runCatching { Regex(pattern).replace(text, repl) }.getOrDefault(text)

    // ============================== 单步求值 ==============================

    private fun applyStep(step: String, value: Any?, rt: LegadoRuntime): Any? {
        val st = step.trim()
        if (st.isEmpty()) return value
        if (st.startsWith("js:", true)) return runJs(st.substring(3), value, rt)
        jsCodeOf(st)?.let { return runJs(it, value, rt) }
        return when {
            st.startsWith("css:", true) -> cssSelect(st.substring(4), value)
            st.startsWith("json:", true) -> jsonSelect(st.substring(5), value)
            st.startsWith("xpath:", true) -> xpathSelect(st.substring(6), value)
            st.startsWith("$") -> jsonSelect(st, value)
            st.startsWith("//") || st.startsWith("(//") -> xpathSelect(st, value)
            else -> defaultStep(st, value)
        }
    }

    private fun runJs(code: String, value: Any?, rt: LegadoRuntime): Any? {
        val runner = rt.js ?: return stringify(value)
        return runner.run(code, rt, stringify(value)) ?: ""
    }

    private fun defaultStep(st: String, value: Any?): Any? {
        if (st in CONTENT_TYPES) return contents(value, st)
        // 末尾的切片索引：`[0:1]`、`[:3]`、`[2:]`、`[0]`
        var rule = st
        var slice: Pair<Int, Int>? = null
        SLICE.find(rule)?.let { m ->
            val from = m.groupValues[1].toIntOrNull() ?: 0
            val to = m.groupValues[2].toIntOrNull() ?: Int.MAX_VALUE
            slice = from to to
            rule = rule.substring(0, m.range.first)
        }
        if (slice == null) SINGLE_INDEX.find(rule)?.let { m ->
            val i = m.groupValues[1].toInt()
            slice = i to (i + 1)
            rule = rule.substring(0, m.range.first)
        }
        val parts = rule.split('.')
        val head = parts[0]
        var tokens = parts.drop(1)
        var index: Int? = null
        if (tokens.size > 0 && INDEX.matches(tokens.last())) {
            index = tokens.last().toInt()
            tokens = tokens.dropLast(1)
        }
        val roots = rootsOf(value)
        if (roots.isEmpty()) return ""
        val out = Elements()
        when (head) {
            "tag" -> {
                val tag = tokens.firstOrNull() ?: return value
                val classes = tokens.drop(1)
                for (r in roots) {
                    val found = r.getElementsByTag(tag)
                    out.addAll(if (classes.isEmpty()) found else found.filter { e -> classes.all { e.hasClass(it) } })
                }
            }
            "class" -> {
                val cls = tokens.firstOrNull() ?: return value
                for (r in roots) {
                    val found = r.getElementsByClass(cls)
                    out.addAll(if (tokens.size > 1) found.filter { e -> tokens.drop(1).all { e.hasClass(it) } } else found)
                }
            }
            "id" -> {
                val id = tokens.firstOrNull() ?: return value
                for (r in roots) out.addAll(r.select("#$id"))
            }
            "text" -> {
                val t = tokens.joinToString(".")
                for (r in roots) out.addAll(containsText(r, t))
            }
            "children" -> for (r in roots) out.addAll(r.children())
            "" -> for (r in roots) out.add(r)
            else -> for (r in roots) runCatching { out.addAll(r.select(st)) }
        }
        if (out.isEmpty()) return ""
        if (slice != null) {
            val (from, to) = slice
            val size = out.size
            val a = if (from < 0) (size + from).coerceAtLeast(0) else from.coerceAtMost(size)
            val b = if (to < 0) (size + to).coerceAtLeast(0) else to.coerceAtMost(size)
            val sub = ArrayList(out.subList(a.coerceAtMost(b), b))
            if (index != null) {
                val i = if (index < 0) sub.size + index else index
                return sub.getOrNull(i) ?: ""
            }
            return if (sub.isEmpty()) "" else Elements(sub)
        }
        if (index != null) {
            val i = if (index < 0) out.size + index else index
            return out.getOrNull(i) ?: ""
        }
        return out
    }

    private fun containsText(root: Element, text: String): Elements {
        if (text.isEmpty()) return Elements()
        val byApi = runCatching { root.getElementsContainingText(text) }.getOrNull()
        if (byApi != null) return byApi
        return runCatching { root.select(":contains($text)") }.getOrDefault(Elements())
    }

    private fun cssSelect(selector: String, value: Any?): Any? {
        var css = selector.trim()
        if (css.isEmpty()) return value
        var slice: Pair<Int, Int>? = null
        SLICE.find(css)?.let { m ->
            val from = m.groupValues[1].toIntOrNull() ?: 0
            val to = m.groupValues[2].toIntOrNull() ?: Int.MAX_VALUE
            slice = from to to
            css = css.substring(0, m.range.first)
        }
        var index: Int? = null
        if (css.endsWith("!0") || css.endsWith("!-1")) {
            index = css.substringAfterLast('!').toIntOrNull()
            css = css.substringBeforeLast('!')
        }
        val roots = rootsOf(value)
        if (roots.isEmpty()) return ""
        val out = Elements()
        for (r in roots) runCatching { out.addAll(r.select(css)) }
        if (out.isEmpty()) return ""
        if (slice != null) {
            val (from, to) = slice
            val size = out.size
            val a = if (from < 0) (size + from).coerceAtLeast(0) else from.coerceAtMost(size)
            val b = if (to < 0) (size + to).coerceAtLeast(0) else to.coerceAtMost(size)
            val sub = ArrayList(out.subList(a.coerceAtMost(b), b))
            if (sub.isEmpty()) return ""
            if (index != null) {
                val i = if (index < 0) sub.size + index else index
                return sub.getOrNull(i) ?: ""
            }
            return Elements(sub)
        }
        if (index != null) {
            val i = if (index < 0) out.size + index else index
            return out.getOrNull(i) ?: ""
        }
        return out
    }

    private fun jsonSelect(path: String, value: Any?): Any? {
        val el: JsonElement? = when (value) {
            is JsonElement -> value
            is String -> LegadoJson.parse(value)
            else -> LegadoJson.parse(stringify(value))
        } ?: return ""
        val list = LegadoJson.select(el, path)
        return when (list.size) {
            0 -> ""
            1 -> list[0]
            else -> JsonArray().apply { list.forEach { add(it) } }
        }
    }

    private fun xpathSelect(path: String, value: Any?): Any? {
        var xp = path.trim()
        var attr: String? = null
        Regex("/(@[\\w:.-]+)$").find(xp)?.let {
            attr = it.groupValues[1].substring(1)
            xp = xp.substring(0, it.range.first)
        }
        val roots = rootsOf(value)
        if (roots.isEmpty()) return ""
        val out = Elements()
        for (r in roots) runCatching { out.addAll(r.selectXpath(xp)) }
        if (attr != null) {
            val values = out.map { it.attr(attr) }.filter { it.isNotBlank() }
            return if (values.size <= 1) values.firstOrNull() ?: "" else values.joinToString("\n")
        }
        if (out.isEmpty()) return ""
        return out
    }

    // ============================== 取值 ==============================

    fun stringify(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Element -> value.text()
        is Elements -> value.joinToString("\n") { it.text() }
        is JsonElement -> LegadoJson.text(value)
        is List<*> -> value.joinToString("\n") { stringify(it) }
        else -> value.toString()
    }

    private fun isBlank(value: Any?): Boolean = when (value) {
        null -> true
        is String -> value.isBlank()
        is Elements -> value.isEmpty()
        is List<*> -> value.isEmpty()
        else -> stringify(value).isBlank()
    }

    private fun contents(value: Any?, type: String): String = when (value) {
        null -> ""
        is String -> if (type == "html" || type == "all") value else value
        is Element -> contentOf(value, type)
        is Elements -> value.joinToString("\n") { contentOf(it, type) }.trim()
        is JsonElement -> LegadoJson.text(value)
        is List<*> -> value.joinToString("\n") { contents(it, type) }.trim()
        else -> value.toString()
    }

    private fun contentOf(el: Element, type: String): String = when (type) {
        "text" -> el.text()
        "ownText" -> el.ownText()
        "textNodes" -> el.textNodes().joinToString("") { it.text() }
        "html" -> el.html()
        "all", "outerHtml" -> el.outerHtml()
        "href", "src" -> el.absUrl(type).ifEmpty { el.attr(type) }
        "content", "value", "title" -> el.attr(type)
        else -> el.text()
    }

    private fun rootsOf(value: Any?): List<Element> = when (value) {
        null -> emptyList()
        is Document -> listOf(value)
        is Element -> listOf(value)
        is Elements -> value.toList()
        is List<*> -> value.filterIsInstance<Element>()
        is String -> if (value.isBlank()) emptyList() else listOf(Jsoup.parse(value))
        else -> emptyList()
    }
}
