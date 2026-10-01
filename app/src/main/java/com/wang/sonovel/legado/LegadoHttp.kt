package com.wang.sonovel.legado

import java.net.URLEncoder

/** 一次 HTTP 请求的结果 */
class LegadoResponse(
    val url: String,
    val code: Int,
    val headers: Map<String, String>,
    val body: String,
) {
    fun header(name: String): String = headers[name.lowercase()] ?: ""
}

/** 同步抓取器：App 用 OkHttp 实现，本地测试用 HttpURLConnection */
interface LegadoFetcher {
    fun request(url: String, method: String, headers: Map<String, String>, body: String?, charset: String?): LegadoResponse

    fun get(url: String, headers: Map<String, String> = emptyMap(), charset: String? = null): LegadoResponse =
        request(url, "GET", headers, null, charset)
}

/** 解析后的请求描述（含「阅读」老式 POST 语法 `url,{'body':'...','method':'POST'}`） */
data class HttpSpec(
    val url: String,
    val method: String = "GET",
    val body: String? = null,
    val charset: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

object LegadoRequest {

    /** 是否含老式 POST 语法：`url,{...}` */
    fun hasSpec(raw: String): Boolean = splitIndex(raw) >= 0

    private fun splitIndex(raw: String): Int {
        val i = raw.lastIndexOf(",{")
        if (i <= 0) return -1
        // `,{` 之后应当是选项对象，且应以 } 结尾
        return if (raw.trimEnd().endsWith("}")) i else -1
    }

    /**
     * 解析请求：可能是纯地址，也可能是
     * `url,{'charset':'gbk','body':'...','method':'POST','headers':{'a':'b'}}`
     */
    fun parse(raw: String): HttpSpec {
        val text = raw.trim()
        val idx = splitIndex(text)
        if (idx < 0) return HttpSpec(text)
        val url = text.substring(0, idx).trim()
        val opt = text.substring(idx + 1)
        return HttpSpec(
            url = url,
            method = str(opt, "method")?.uppercase() ?: "GET",
            body = str(opt, "body"),
            charset = str(opt, "charset"),
            headers = headers(opt),
        )
    }

    private fun str(opt: String, key: String): String? {
        val m = Regex("['\"]" + Regex.escape(key) + "['\"]\\s*:\\s*(['\"])(.*?)\\1", RegexOption.DOT_MATCHES_ALL).find(opt)
        return m?.groupValues?.get(2)
    }

    private fun headers(opt: String): Map<String, String> {
        val i = Regex("['\"]headers['\"]\\s*:\\s*\\{").find(opt) ?: return emptyMap()
        val start = i.range.last
        var depth = 1
        var j = start + 1
        while (j < opt.length && depth > 0) {
            when (opt[j]) {
                '{' -> depth++
                '}' -> depth--
            }
            j++
        }
        val body = opt.substring(start + 1, (j - 1).coerceAtLeast(start))
        val out = LinkedHashMap<String, String>()
        Regex("['\"]([^'\"]+)['\"]\\s*:\\s*(['\"])(.*?)\\2", RegexOption.DOT_MATCHES_ALL).findAll(body).forEach {
            out[it.groupValues[1]] = it.groupValues[3]
        }
        return out
    }

    fun encode(value: String, charset: String? = null): String =
        runCatching { URLEncoder.encode(value, charset?.takeIf { it.isNotBlank() } ?: "UTF-8") }
            .getOrDefault(URLEncoder.encode(value, "UTF-8"))
}

/**
 * 全局 Cookie 罐（简化版）：按站点保存 Cookie，与「阅读」一样跨搜索/详情/目录/正文共享。
 */
object LegadoCookies {
    private val jar = LinkedHashMap<String, String>()

    @Synchronized
    fun get(url: String): String = jar[key(url)].orEmpty()

    @Synchronized
    fun set(url: String, cookie: String) {
        if (cookie.isBlank()) return
        jar[key(url)] = cookie
    }

    @Synchronized
    fun remove(url: String) {
        jar.remove(key(url))
    }

    @Synchronized
    fun saveFromResponse(url: String, setCookie: List<String>) {
        if (setCookie.isEmpty()) return
        val k = key(url)
        val merged = LinkedHashMap<String, String>()
        jar[k].orEmpty().split(';').map { it.trim() }.filter { it.contains('=') }.forEach {
            merged[it.substringBefore('=')] = it
        }
        for (c in setCookie) {
            val kv = c.substringBefore(';').trim()
            if (kv.contains('=')) merged[kv.substringBefore('=')] = kv
        }
        jar[k] = merged.values.joinToString("; ")
    }

    private fun key(url: String): String = runCatching {
        val u = java.net.URI(url)
        "${u.scheme}://${u.authority}"
    }.getOrDefault(url)
}
