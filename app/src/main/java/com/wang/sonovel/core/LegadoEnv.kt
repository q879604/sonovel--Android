package com.wang.sonovel.core

import android.util.Log
import com.wang.sonovel.data.AppSettings
import com.wang.sonovel.data.Rule
import com.wang.sonovel.legado.LegadoFetcher
import com.wang.sonovel.legado.LegadoJsApi
import com.wang.sonovel.legado.LegadoJsPrelude
import com.wang.sonovel.legado.LegadoJsRunner
import com.wang.sonovel.legado.LegadoResponse
import com.wang.sonovel.legado.LegadoRuntime
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.Charset

/**
 * 「阅读」书源在 App 里的运行环境：
 *  - 抓取走 App 的 OkHttp（含代理/忽略证书等设置）
 *  - JS 走内置 QuickJS（与桌面版 Javet 对应）
 */
object LegadoEnv {

    /** 为某个书源创建运行时上下文 */
    fun runtime(rule: Rule, settings: AppSettings, baseUrl: String? = null, key: String? = null, page: Int = 1): LegadoRuntime {
        val source = rule.legado ?: error("不是「阅读」书源")
        val client = Http.client(settings, unsafe = rule.ignoreSsl)
        return LegadoRuntime(
            source = source,
            baseUrl = baseUrl?.takeIf { it.isNotBlank() } ?: source.url,
            fetcher = OkFetcher(client, rule.search?.timeout ?: rule.book?.timeout ?: 15),
            js = QuickJs(),
            key = key,
            page = page,
        )
    }
}

/** 用 App 的 OkHttp 执行「阅读」书源请求 */
class OkFetcher(private val client: okhttp3.OkHttpClient, private val timeoutSec: Int) : LegadoFetcher {

    override fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        charset: String?,
    ): LegadoResponse {
        val m = method.ifBlank { "GET" }.uppercase()
        val rb = Request.Builder().url(url)
            .header("User-Agent", headers["User-Agent"] ?: headers["user-agent"] ?: RandomUA.generate())
        var hasReferer = false
        headers.forEach { (k, v) ->
            if (!k.equals("User-Agent", true)) {
                if (k.equals("Referer", true)) hasReferer = true
                runCatching { rb.header(k, v) }
            }
        }
        if (!hasReferer) rb.header("Referer", Http.referer(url))
        if (body != null) {
            val cs = charset?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            val type = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
                ?: "application/x-www-form-urlencoded; charset=${cs.name()}"
            rb.method(m, body.toByteArray(cs).toRequestBody(type.toMediaTypeOrNull()))
        } else if (m != "GET") {
            rb.method(m, ByteArray(0).toRequestBody(null))
        }
        return try {
            Http.execute(client, rb.build(), timeoutSec).let { page ->
                val hs = LinkedHashMap<String, String>()
                page.headers.forEach { (k, v) -> hs[k.lowercase()] = v }
                LegadoResponse(page.url, page.code, hs, decodeHtml(page.bytes, charset ?: page.charset))
            }
        } catch (e: Throwable) {
            Log.w("Legado", "请求失败 $url：${e.message}")
            LegadoResponse(url, 0, emptyMap(), "")
        }
    }
}

/** 用内置 QuickJS 执行「阅读」书源的 JS 规则 */
class QuickJs : LegadoJsRunner {

    override fun run(code: String, runtime: LegadoRuntime, input: String?): String? {
        JsEngine.init()
        val ctx = QuickJSContext.create()
        return try {
            ctx.setMaxStackSize(4 * 1024 * 1024)
            val api = LegadoJsApi(runtime)
            api.onLog = { Log.d("LegadoJs", it) }
            api.onToast = { }
            // 全局函数 __nativeCall(op, a, b, c, d)：QuickJS 侧用 JSCallFunction 桥接回 Kotlin
            ctx.getGlobalObject().setProperty("__nativeCall", JSCallFunction { args ->
                val a = args.takeLast(5)
                api.call(
                    a.getOrNull(0), a.getOrNull(1), a.getOrNull(2), a.getOrNull(3), a.getOrNull(4),
                )
            })

            // 单次求值：环境变量 + 预置库 + 规则（wrap 内部自带预置库，避免依赖多次 evaluate 的全局作用域）
            val script = LegadoJsPrelude.bootstrap(runtime) + "\n" +
                LegadoJsPrelude.wrap(code, input)
            val out = ctx.evaluate(script)
            when (out) {
                null -> ""
                is String -> out
                else -> out.toString()
            }
        } catch (e: Throwable) {
            Log.w("LegadoJs", "JS 执行失败：${e.message}")
            null
        } finally {
            runCatching { ctx.destroy() }
        }
    }
}
