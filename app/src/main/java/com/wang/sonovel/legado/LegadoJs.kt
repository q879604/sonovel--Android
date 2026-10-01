package com.wang.sonovel.legado

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.security.MessageDigest

/**
 * 「阅读」JS 规则的运行时 API（`java.*`、`cookie`、`source`、全局函数）。
 *
 * 平台无关：Android 用 QuickJS、本地测试用 Rhino，二者只需要把
 * `__bridge.call(op, a, b, c, d)` 转发到 [LegadoJsApi.call]。
 */
class LegadoJsApi(private val rt: LegadoRuntime) {

    /** 日志/提示回调（App 里可以弹 Toast 或写日志） */
    var onLog: ((String) -> Unit)? = null
    var onToast: ((String) -> Unit)? = null

    private val gson = Gson()

    /** JS 桥入口：固定 5 个参数，便于两个平台统一适配 */
    fun call(op: Any?, a: Any?, b: Any?, c: Any?, d: Any?): String {
        val name = str(op)
        return try {
            when (name) {
                "http" -> httpBody(str(a), str(b), str(c))
                "httpx" -> httpEnvelope(str(a), str(b), str(c), str(d))
                "ajax" -> httpBody("GET", str(a), "")
                "post" -> httpBody("POST", str(a), str(b))
                "b64d" -> b64Decode(str(a))
                "b64e" -> LegadoCodec.base64Encode(str(a).toByteArray(Charsets.UTF_8))
                "md5" -> md5(str(a))
                "hexd" -> hexDecode(str(a))
                "hexe" -> hexEncode(str(a))
                "enc" -> LegadoRequest.encode(str(a), str(b).ifBlank { null })
                "dec" -> runCatching { java.net.URLDecoder.decode(str(a), str(b).ifBlank { "UTF-8" }) }.getOrDefault(str(a))
                "varGet" -> rt.variables[str(a)] ?: ""
                "varPut" -> {
                    rt.variables[str(a)] = str(b); ""
                }
                "cookieGet" -> LegadoCookies.get(str(a))
                "cookieSet" -> {
                    LegadoCookies.set(str(a), str(b)); ""
                }
                "cookieRemove" -> {
                    LegadoCookies.remove(str(a)); ""
                }
                "host" -> hostOf(str(a))
                "source" -> gson.toJson(rt.source)
                "baseUrl" -> rt.baseUrl
                "log" -> {
                    onLog?.invoke(str(a)); ""
                }
                "toast" -> {
                    onToast?.invoke(str(a)); ""
                }
                "unsupported" -> throw IllegalStateException("书源使用了本 App 暂不支持的接口：${str(b)}${str(c)}")
                else -> ""
            }
        } catch (e: Throwable) {
            if (name == "log" || name == "toast") "" else throw e
        }
    }

    // ------------------------------------------------------------------

    private fun str(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    private fun httpBody(method: String, url: String, body: String): String =
        httpEnvelopeJson(method, url, body, "").let { __envBody(it) }

    private fun __envBody(json: String): String =
        runCatching { gson.fromJson(json, JsonObject::class.java).get("body").asString }.getOrDefault("")

    /** 与 [httpBody] 相同，但返回 `{code, headers, body}` 信封（供 `java.post(...).header()` 使用） */
    private fun httpEnvelope(method: String, url: String, body: String, opts: String): String =
        httpEnvelopeJson(method, url, body, opts)

    private fun httpEnvelopeJson(method: String, url: String, body: String, opts: String): String {
        val spec = LegadoRequest.parse(rt.resolve(url))
        var m = if (spec.method != "GET") spec.method else method
        var charset = spec.charset
        var payload = body.ifBlank { spec.body.orEmpty() }
        val extra = LinkedHashMap<String, String>()
        extra.putAll(spec.headers)
        if (opts.isNotBlank() && opts.trimStart().startsWith("{")) {
            runCatching {
                val obj = gson.fromJson(opts, JsonObject::class.java)
                obj.get("method")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let { m = it.uppercase() }
                obj.get("charset")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let { charset = it }
                obj.get("body")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let { payload = it }
                obj.getAsJsonObject("headers")?.entrySet()?.forEach { extra[it.key] = it.value.asString }
            }
        }
        val resp = runCatching {
            rt.request(spec.url, extra, charset, methodOverride = m.ifBlank { "GET" }, bodyOverride = payload.ifBlank { null })
        }.getOrElse { LegadoResponse(spec.url, 0, emptyMap(), "") }
        val env = JsonObject()
        env.addProperty("code", resp.code)
        env.addProperty("body", resp.body)
        val hs = JsonObject()
        resp.headers.forEach { (k, v) -> hs.addProperty(k, v) }
        env.add("headers", hs)
        return gson.toJson(env)
    }

    private fun b64Decode(s: String): String =
        runCatching { String(LegadoCodec.base64Decode(s.trim()), Charsets.UTF_8) }.getOrDefault("")

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun hexDecode(s: String): String =
        runCatching { String(LegadoCodec.hexDecode(s), Charsets.UTF_8) }.getOrDefault("")

    private fun hexEncode(s: String): String = LegadoCodec.hexEncode(s.toByteArray(Charsets.UTF_8))

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(if (url.contains("://")) url else "http://$url").host.orEmpty() }.getOrDefault(url)
}

/**
 * JS 前置脚本：把 [LegadoJsApi] 包装成「阅读」的 JS 运行环境。
 * 平台只需提供 `__bridge.call(op, a, b, c, d)`。
 */
object LegadoJsPrelude {

    /** 变量注入：baseUrl/key/page/title/src/result 等由平台按次注入 */
    fun bootstrap(rt: LegadoRuntime): String {
        val gson = Gson()
        val src = rt.source
        return """
            var __key = ${gson.toJson(rt.key ?: "")};
            var __page = ${rt.page};
            var __baseUrl = ${gson.toJson(rt.baseUrl)};
            var key = __key;
            var page = __page;
            var baseUrl = __baseUrl;
            var title = ${gson.toJson(rt.title)};
            var src = ${gson.toJson(src.url)};
            var book = ${if (rt.bookJson.isBlank()) "{}" else rt.bookJson};
        """.trimIndent()
    }

    /** JS 环境（java / cookie / source / cookie 全局函数等） */
    val JS: String = """
    var __B = (typeof __bridge !== 'undefined') ? __bridge : null;
    function __c(op, a, b, c, d) {
      if (!__B) return '';
      if (a === undefined || a === null) a = '';
      if (b === undefined || b === null) b = '';
      if (c === undefined || c === null) c = '';
      if (d === undefined || d === null) d = '';
      return String(__B.call(String(op), a, b, c, d));
    }
    function __env(json) {
      try { return JSON.parse(json); } catch (e) { return { code: 0, headers: {}, body: '' }; }
    }
    function __response(json) {
      var env = __env(json);
      return {
        code: env.code,
        body: env.body,
        header: function (name) { return env.headers[String(name).toLowerCase()] || ''; },
        headers: env.headers,
        toString: function () { return env.body; }
      };
    }
    var java = {
      ajax: function (url, opts) { return __c('http', 'GET', String(url), opts ? String(typeof opts === 'object' ? JSON.stringify(opts) : opts) : '', ''); },
      get: function (url) { return __c('http', 'GET', String(url), '', ''); },
      post: function (url, body, opts) { return __response(__c('httpx', 'POST', String(url), body === undefined ? '' : String(body), opts ? String(typeof opts === 'object' ? JSON.stringify(opts) : opts) : '')); },
      put: function (k, v) { return __c('varPut', String(k), v === undefined || v === null ? '' : String(v), '', ''); },
      getVariable: function (k) { return __c('varGet', String(k), '', '', ''); },
      setVariable: function (k, v) { return __c('varPut', String(k), v === undefined || v === null ? '' : String(v), '', ''); },
      base64Decode: function (s) { return __c('b64d', String(s), '', '', ''); },
      base64Encode: function (s) { return __c('b64e', String(s), '', '', ''); },
      md5Encode: function (s) { return __c('md5', String(s), '', '', ''); },
      hexDecodeToString: function (s) { return __c('hexd', String(s), '', '', ''); },
      hexEncodeToString: function (s) { return __c('hexe', String(s), '', '', ''); },
      encodeURI: function (s, cs) { return __c('enc', String(s), cs ? String(cs) : '', '', ''); },
      decodeURI: function (s, cs) { return __c('dec', String(s), cs ? String(cs) : '', '', ''); },
      log: function (s) { return __c('log', String(s), '', '', ''); },
      toast: function (s) { return __c('toast', String(s), '', '', ''); },
      longToast: function (s) { return __c('toast', String(s), '', '', ''); },
      timeFormat: function (t) { return t === undefined || t === null ? '' : String(t); },
      androidId: function () { return ''; },
      startBrowserAwait: function () { __c('unsupported', '', 'startBrowserAwait（浏览器验证）', '', ''); return ''; },
      getElement: function () { __c('unsupported', '', 'getElement（网页模式）', '', ''); return ''; },
      getString: function () { __c('unsupported', '', 'getString（网页模式）', '', ''); return ''; },
      setContent: function () { __c('unsupported', '', 'setContent（网页模式）', '', ''); return ''; }
    };
    var source = {};
    (function () {
      source = {
        getKey: function () { return __c('host', source.bookSourceUrl || '', '', '', ''); },
        getVariable: function (k) { return __c('varGet', String(k), '', '', ''); },
        setVariable: function (k, v) { return __c('varPut', String(k), v === undefined || v === null ? '' : String(v), '', ''); }
      };
      try {
        var s = JSON.parse(__c('source', '', '', '', ''));
        for (var k in s) if (s.hasOwnProperty(k)) source[k] = s[k];
      } catch (e) {}
    })();
    var cookie = {
      getCookie: function (u) { return __c('cookieGet', String(u || baseUrl), '', '', ''); },
      setCookie: function (u, v) { return __c('cookieSet', String(u || baseUrl), String(v), '', ''); },
      removeCookie: function (u) { return __c('cookieRemove', String(u || baseUrl), '', '', ''); }
    };
    var cache = {
      __m: {},
      get: function (k) { return this.__m[String(k)] || null; },
      put: function (k, v) { this.__m[String(k)] = v; return v; },
      delete: function (k) { delete this.__m[String(k)]; }
    };
    function ajax(url, opts) { return java.ajax(url, opts); }
    function get(url) { return java.get(url); }
    function post(url, body, opts) { return java.post(url, body, opts); }
    function base64Decode(s) { return java.base64Decode(s); }
    function base64Encode(s) { return java.base64Encode(s); }
    function md5Encode(s) { return java.md5Encode(s); }
    function encodeURI(s, cs) { return java.encodeURI(s, cs); }
    function decodeURI(s, cs) { return java.decodeURI(s, cs); }
    function t2s(s) { return String(s); }
    function s2t(s) { return String(s); }
    function log(s) { return java.log(s); }
    function toast(s) { return java.toast(s); }
    function sleep(ms) { return ''; }
    function random(n) { return Math.floor(Math.random() * (n || 100)); }
    function importScript() { return ''; }
    function getElement() { return java.getElement(); }
    function setContent() { return java.setContent(); }
    """.trimIndent()

    /** 把规则 JS 包装成可执行脚本：`result` 为入参，返回值统一转成字符串 */
    fun wrap(code: String, input: String?): String {
        val gson = Gson()
        val arg = if (input == null) "undefined" else gson.toJson(input)
        return """
            (function () {
              $JS
              function __user(result) {
                $code
              }
              var __in = $arg;
              var __r = __user(__in);
              if (__r === undefined || __r === null) return '';
              if (typeof __r === 'string') return __r;
              if (typeof __r === 'number' || typeof __r === 'boolean') return String(__r);
              if (typeof __r.length === 'number') return JSON.stringify(__r);
              var __s = String(__r);
              if (__s !== '[object Object]') return __s;
              return JSON.stringify(__r);
            })()
        """.trimIndent()
    }
}
