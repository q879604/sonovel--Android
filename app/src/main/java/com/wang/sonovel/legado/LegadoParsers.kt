package com.wang.sonovel.legado

import com.wang.sonovel.data.BookInfo
import com.wang.sonovel.data.ChapterRef
import com.wang.sonovel.data.SearchResult
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.IOException
import java.net.URLEncoder

/**
 * Legado（阅读）书源的抓取流程：搜索 → 详情 → 目录 → 正文。
 * 与 so-novel 规则的三段式解析器一一对应，便于在 [com.wang.sonovel.core] 中统一调度。
 */

// ================================ 搜索 ================================

class LegadoSearchParser(
    private val rt: LegadoRuntime,
    private val sourceKey: String,
    private val sourceName: String,
    private val limit: Int = 0,
) {

    fun search(keyword: String): List<SearchResult> {
        val src = rt.source
        val searchRule = src.searchUrl?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (!src.enabled) return emptyList()
        val rs = src.ruleSearch

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val extra = mapOf("key" to encoded, "searchKey" to encoded, "page" to rt.page.toString())

        // searchUrl 也可能是 JS（返回地址或 `地址,{请求体}`）
        val searchUrl = if (jsCodeOf(searchRule) != null) {
            LegadoRule.url(searchRule, rt, extra).ifBlank { return emptyList() }
        } else searchRule

        // 「阅读」的老式 POST 语法：`地址,{'body':'...','method':'POST','charset':'gbk'}`
        val spec = LegadoRequest.parse(searchUrl)
        val rawUrl = spec.url
        val rawBody = spec.body
        val url = LegadoRule.url(rawUrl, rt, extra)
        if (url.isBlank()) return emptyList()

        val text = if (rawBody != null || spec.method != "GET") {
            // 请求体里的 {{key}} 不做 URL 编码（表单/JSON 原样提交）
            rt.request(
                url,
                extra = spec.headers,
                charset = spec.charset,
                methodOverride = spec.method,
                bodyOverride = rawBody?.let { LegadoRule.template(it, rt, emptyMap()) },
            ).body
        } else {
            rt.request(url, extra = spec.headers).body
        }
        if (text.isBlank()) return emptyList()

        val ctx = rt.at(url)
        val doc = ctx.document(text)

        val items = LegadoRule.items(rs?.bookList, doc, ctx)
        if (items.isEmpty()) {
            // 部分书源搜索会直接跳到详情页
            val single = singleResult(doc, url, ctx) ?: return emptyList()
            return listOf(single)
        }
        val out = ArrayList<SearchResult>()
        val max = if (limit > 0) limit else Int.MAX_VALUE
        val checkKey = rs?.checkKeyWord?.takeIf { it.isNotBlank() }
        for (item in items) {
            if (out.size >= max) break
            val name = LegadoRule.text(rs?.name, item, ctx)
            if (name.isBlank()) continue
            // 校验关键字：结果不含该关键字视为无效（防搜索结果被劫持）
            if (checkKey != null && !name.contains(checkKey)) continue
            val bookUrl = runCatching { bookUrlOf(rs?.bookUrl, item, ctx) }.getOrDefault("")
            if (bookUrl.isBlank()) continue
            out += SearchResult(
                sourceKey = sourceKey,
                sourceName = sourceName,
                url = bookUrl,
                bookName = cleanText(name),
                author = cleanText(LegadoRule.text(rs?.author, item, ctx)).ifBlank { null },
                category = cleanText(LegadoRule.text(rs?.kind, item, ctx)).ifBlank { null },
                latestChapter = cleanText(LegadoRule.text(rs?.lastChapter, item, ctx)).ifBlank { null },
                lastUpdateTime = null,
                status = null,
                wordCount = LegadoRule.text(rs?.wordCount, item, ctx).ifBlank { null },
            )
        }
        return out
    }

    private fun bookUrlOf(rule: String?, item: Any, ctx: LegadoRuntime): String {
        if (!rule.isNullOrBlank()) {
            val v = LegadoRule.eval(rule, item, ctx)
            val href = when (v) {
                is Element -> v.absUrl("href").ifEmpty { v.attr("href") }
                is org.jsoup.select.Elements -> v.firstOrNull()?.let { it.absUrl("href").ifEmpty { it.attr("href") } }.orEmpty()
                else -> LegadoRule.stringify(v).trim()
            }
            if (href.isNotBlank()) return ctx.resolve(href)
        }
        if (item is Element) {
            val href = item.absUrl("href").ifEmpty { item.attr("href") }
            if (href.isNotBlank()) return ctx.resolve(href)
            item.selectFirst("a[href]")?.let { return ctx.resolve(it.absUrl("href").ifEmpty { it.attr("href") }) }
        }
        return ""
    }

    /** 结果页本身就是详情页：用 ruleBookInfo 取书名作者 */
    private fun singleResult(doc: Document, url: String, ctx: LegadoRuntime): SearchResult? {
        val bi = rt.source.ruleBookInfo
        val name = LegadoRule.text(bi?.name, doc, ctx)
            .ifBlank { doc.selectFirst("meta[property=og:novel:book_name]")?.attr("content").orEmpty() }
        if (name.isBlank()) return null
        val author = LegadoRule.text(bi?.author, doc, ctx)
            .ifBlank { doc.selectFirst("meta[property=og:novel:author]")?.attr("content").orEmpty() }
        return SearchResult(
            sourceKey = sourceKey,
            sourceName = sourceName,
            url = url,
            bookName = cleanText(name),
            author = cleanText(author).ifBlank { null },
            latestChapter = cleanText(LegadoRule.text(bi?.lastChapter, doc, ctx)).ifBlank { null },
        )
    }
}

// ================================ 详情 ================================

class LegadoBookParser(
    private val rt: LegadoRuntime,
    private val fallback: SearchResult? = null,
) {

    fun parse(url: String): BookInfo {
        val src = rt.source
        val ctx = rt.at(url)
        val text = ctx.get(url)
        if (text.isBlank()) throw IOException("详情页打开失败，可能书源已失效或被限流")
        val doc = ctx.document(text)
        val bi = src.ruleBookInfo
        bi?.init?.takeIf { it.isNotBlank() }?.let { runCatching { LegadoRule.eval(it, doc, ctx) } }

        fun rule(v: String?): String = if (v.isNullOrBlank()) "" else runCatching { LegadoRule.text(v, doc, ctx) }.getOrDefault("")
        fun meta(prop: String): String = doc.selectFirst("meta[property=og:novel:$prop]")?.attr("content").orEmpty()
        fun og(prop: String): String = doc.selectFirst("meta[property=og:$prop]")?.attr("content").orEmpty()

        val name = cleanText(rule(bi?.name).ifBlank { meta("book_name") }.ifBlank { fallback?.bookName.orEmpty() })
            .replace(Regex("^书名[：:]"), "").trim()
        val author = cleanText(rule(bi?.author).ifBlank { meta("author") }.ifBlank { fallback?.author.orEmpty() })
            .replace(Regex("^作者[：:]"), "").trim()
        if (name.isBlank() && author.isBlank()) throw IOException("详情页未解析到书名，书源规则可能不匹配")

        val cover = rule(bi?.coverUrl).ifBlank { meta("image") }.ifBlank { og("image") }
        return BookInfo(
            url = url,
            bookName = name.ifBlank { "未知书名" },
            author = author,
            intro = cleanText(rule(bi?.intro).ifBlank { meta("intro") }.ifBlank { og("description") }).ifBlank { null },
            category = cleanText(rule(bi?.kind)).ifBlank { meta("category") }.ifBlank { fallback?.category.orEmpty() }.ifBlank { null },
            coverUrl = ctx.resolve(cover).ifBlank { null },
            latestChapter = cleanText(rule(bi?.lastChapter)).ifBlank { meta("latest_chapter_name") }
                .ifBlank { fallback?.latestChapter.orEmpty() }.ifBlank { null },
            latestChapterUrl = null,
            lastUpdateTime = cleanText(meta("update_time")).ifBlank { null },
            status = cleanText(meta("status")).ifBlank { null },
        )
    }

    /** 目录页地址：ruleBookInfo.tocUrl 优先，否则详情页本身 */
    fun tocUrl(url: String, doc: Document, ctx: LegadoRuntime): String {
        val tpl = rt.source.ruleBookInfo?.tocUrl?.takeIf { it.isNotBlank() } ?: return url
        return LegadoRule.target(tpl, doc, ctx) ?: url
    }
}

// ================================ 目录 ================================

class LegadoTocParser(private val rt: LegadoRuntime) {

    fun parseAll(bookUrl: String): List<ChapterRef> {
        val toc = rt.source.ruleToc ?: throw IOException("书源缺少目录规则")
        val bookCtx = rt.at(bookUrl)
        val bookText = bookCtx.get(bookUrl)
        val bookDoc = bookCtx.document(bookText)
        val startUrl = if (bookText.isBlank()) bookUrl else LegadoBookParser(rt).tocUrl(bookUrl, bookDoc, bookCtx)

        val chapters = LinkedHashMap<String, ChapterRef>()
        val visited = HashSet<String>()
        val titles = HashMap<String, Int>()
        var next: String? = startUrl
        var guard = 0
        while (!next.isNullOrBlank() && visited.add(next) && guard++ < 100) {
            val ctx = rt.at(next)
            val text = ctx.get(next)
            if (text.isBlank()) break
            val doc = ctx.document(text)
            for (item in LegadoRule.items(toc.chapterList, doc, ctx)) {
                if (!toc.isVolume.isNullOrBlank()) {
                    val isVolume = LegadoRule.text(toc.isVolume, item, ctx)
                    if (isVolume.isNotBlank() && isVolume != "false" && isVolume != "0") continue
                }
                val title = cleanText(LegadoRule.text(toc.chapterName, item, ctx)).ifBlank {
                    if (item is Element) item.text().trim() else ""
                }
                if (title.isBlank()) continue
                val href = chapterUrlOf(toc.chapterUrl, item, ctx)
                if (href.isBlank()) continue
                // 同名章节保留靠后的一个（与 so-novel 的目录处理一致）
                val n = (titles[title] ?: 0) + 1
                titles[title] = n
                val key = if (n > 1) "$title#$n" else title
                chapters.remove(key)
                chapters[key] = ChapterRef(order = chapters.size + 1, title = title, url = ctx.resolve(href))
            }
            next = nextTocUrl(toc.nextTocUrl, doc, ctx, next)
        }
        if (chapters.isEmpty()) throw IOException("目录为空，书源规则可能不匹配")
        return chapters.values.mapIndexed { i, c -> c.copy(order = i + 1) }
    }

    private fun chapterUrlOf(rule: String?, item: Any, ctx: LegadoRuntime): String {
        if (rule.isNullOrBlank()) {
            if (item is Element) return item.absUrl("href").ifEmpty { item.attr("href") }
            if (item is com.google.gson.JsonElement) return LegadoJson.text(item)
            return ""
        }
        val v = LegadoRule.eval(rule, item, ctx)
        return when (v) {
            is Element -> v.absUrl("href").ifEmpty { v.attr("href") }
            is org.jsoup.select.Elements -> v.firstOrNull()?.let { it.absUrl("href").ifEmpty { it.attr("href") } }.orEmpty()
            else -> LegadoRule.stringify(v).trim()
        }
    }

    /** 下一页目录（可能是下拉框里的全部 value，或一个 URL） */
    private fun nextTocUrl(rule: String?, doc: Document, ctx: LegadoRuntime, current: String): String? {
        if (rule.isNullOrBlank()) return null
        if (!LegadoRule.isRuleLike(rule)) {
            val u = LegadoRule.url(rule, ctx)
            return u.takeIf { it.isNotBlank() && it != current }
        }
        val v = runCatching { LegadoRule.eval(rule, doc, ctx) }.getOrNull() ?: return null
        val urls = ArrayList<String>()
        when (v) {
            is Element -> {
                val u = v.absUrl("href").ifEmpty { v.absUrl("value").ifEmpty { v.attr("value").ifEmpty { v.attr("href") } } }
                if (u.isNotBlank()) urls += ctx.resolve(u)
            }
            is org.jsoup.select.Elements -> for (e in v) {
                val u = e.absUrl("href").ifEmpty { e.absUrl("value").ifEmpty { e.attr("value").ifEmpty { e.attr("href") } } }
                if (u.isNotBlank()) urls += ctx.resolve(u)
            }
            else -> for (line in LegadoRule.stringify(v).split('\n', ',')) {
                val u = line.trim()
                if (u.startsWith("http")) urls += u
            }
        }
        return urls.firstOrNull { it.isNotBlank() && it != current }
    }
}

// ================================ 正文 ================================

class LegadoChapterParser(private val rt: LegadoRuntime) {

    /** 抓取正文并整理为 <p> 段落 HTML */
    fun fetchContent(url: String): String {
        val rc = rt.source.ruleContent
        val ctx = rt.at(url)
        val text = ctx.get(url)
        if (text.isBlank()) throw IOException("章节页内容为空，可能被限流")
        val doc = ctx.document(text)

        var raw = ""
        // 1) webJs：脚本直接返回正文
        rc?.webJs?.takeIf { it.isNotBlank() }?.let { js ->
            val code = jsCodeOf(js) ?: js.removePrefix("@js:")
            raw = runCatching { rt.js?.run(code, ctx, null) ?: "" }.getOrDefault("")
        }
        // 2) 常规内容规则
        val contentRule = rc?.content
        if (raw.isBlank() && !contentRule.isNullOrBlank()) {
            raw = runCatching { LegadoRule.stringify(LegadoRule.eval(contentRule, doc, ctx)) }.getOrDefault("")
        }
        // 3) sourceRegex：从源码里正则提取
        val sourceRegex = rc?.sourceRegex
        if (raw.isBlank() && !sourceRegex.isNullOrBlank()) {
            raw = runCatching {
                val m = Regex(sourceRegex, RegexOption.DOT_MATCHES_ALL).find(text)
                if (m == null) "" else m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: m.value
            }.getOrDefault("")
        }
        if (raw.isBlank()) throw IOException("正文内容为空，书源规则可能不匹配或被限流")

        // 下一页正文
        val nextRule = rc?.nextContentUrl?.takeIf { it.isNotBlank() }
        if (nextRule != null) {
            val visited = HashSet<String>()
            var next = nextContentUrl(nextRule, doc, ctx)
            var guard = 0
            while (!next.isNullOrBlank() && visited.add(next) && guard++ < 30) {
                val nctx = rt.at(next)
                val ntext = nctx.get(next)
                if (ntext.isBlank()) break
                val ndoc = nctx.document(ntext)
                val more = runCatching {
                    LegadoRule.stringify(LegadoRule.eval(contentRule ?: "", ndoc, nctx))
                }.getOrDefault("")
                if (more.isNotBlank()) raw += "\n" + more
                next = nextContentUrl(nextRule, ndoc, nctx)
            }
        }
        return toParagraphHtml(replaceAll(raw, rc?.replaceRegex))
    }

    private fun nextContentUrl(rule: String, doc: Document, ctx: LegadoRuntime): String? =
        LegadoRule.target(rule, doc, ctx)

    /** replaceRegex：`正则##替换##` 可多组串联 */
    private fun replaceAll(text: String, rule: String?): String {
        val r = rule?.takeIf { it.isNotBlank() } ?: return text
        var s = text
        val parts = r.split("##")
        var i = 0
        while (i < parts.size) {
            val pattern = parts[i]
            val repl = parts.getOrElse(i + 1) { "" }
            if (pattern.isNotEmpty()) s = runCatching { Regex(pattern).replace(s, repl) }.getOrDefault(s)
            i += 2
        }
        return s
    }
}

// ================================ 工具 ================================

/** 解析为 jsoup Document（以当前页地址作为 base，便于相对链接转绝对） */
fun LegadoRuntime.document(text: String): Document = Jsoup.parse(text, baseUrl)

fun LegadoRuntime.post(url: String, body: String): String =
    runCatching {
        val spec = LegadoRequest.parse(resolve(url))
        fetcher.request(resolve(spec.url), "POST", spec.headers, body, spec.charset).body
    }.getOrDefault("")

/** 从 HTML 文本整理为 <p> 段落 */
fun toParagraphHtml(raw: String): String {
    if (raw.isBlank()) return ""
    var s = raw
    s = s.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
    s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
    s = s.replace(Regex("(?i)</?(p|div|h[1-6]|li|tr|section|article|dd|dt)\\b[^>]*>"), "\n")
    s = s.replace(Regex("(?s)<[^>]+>"), "")
    s = Parser.unescapeEntities(s, false)
    val sb = StringBuilder()
    for (line in s.split('\n')) {
        val t = line.trim().trim('\u3000').trim()
        if (t.isEmpty()) continue
        sb.append("<p>").append(escapeHtml(t)).append("</p>")
    }
    return sb.toString()
}

private fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** 去掉零宽字符与多余空白 */
fun cleanText(s: String): String = s
    .replace(Regex("[\\u200B-\\u200F\\uFEFF]"), "")
    .replace(Regex("[\\t\\u000B\\u000C\\r]+"), " ")
    .trim()
