package com.wang.sonovel.legado

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * 极简 JSONPath（Legado 的 `@json:` / `$.` 规则）与 JSON 工具。
 *
 * 支持：`$.a.b`、`a.b`、`[0]`、`[-1]`、`[*]`、`.*`、`['key']`、`..key`（递归下降）。
 */
object LegadoJson {

    fun parse(text: String?): JsonElement? {
        if (text.isNullOrBlank()) return null
        return runCatching { JsonParser.parseString(text.trim()) }.getOrNull()
    }

    fun headersOf(text: String): Map<String, String> {
        val obj = runCatching { JsonParser.parseString(text) }.getOrNull()
        if (obj is JsonObject) {
            val map = LinkedHashMap<String, String>()
            for ((k, v) in obj.entrySet()) {
                if (v.isJsonPrimitive) map[k] = v.asString
            }
            if (map.isNotEmpty()) return map
        }
        // 兼容 `Key: Value` 逐行写法
        val map = LinkedHashMap<String, String>()
        for (line in text.lines()) {
            val i = line.indexOf(':')
            if (i <= 0) continue
            val k = line.substring(0, i).trim()
            val v = line.substring(i + 1).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) map[k] = v
        }
        return map
    }

    /** 以 Legado 规则串取值，返回所有匹配节点 */
    fun select(root: JsonElement?, path: String): List<JsonElement> {
        if (root == null) return emptyList()
        val segs = tokenize(path)
        var current: List<JsonElement> = listOf(root)
        for (seg in segs) {
            val next = ArrayList<JsonElement>()
            for (node in current) apply(node, seg, next)
            if (next.isEmpty()) return emptyList()
            current = next
        }
        return current
    }

    fun text(el: JsonElement?): String = when (el) {
        null, is com.google.gson.JsonNull -> ""
        is JsonPrimitive -> el.asString
        else -> el.toString()
    }

    // ============================ 内部实现 ============================

    private sealed interface Seg {
        data class Key(val name: String) : Seg
        data class Index(val index: Int) : Seg
        data object Wildcard : Seg
        data class Deep(val name: String?) : Seg
    }

    private fun tokenize(path: String): List<Seg> {
        var p = path.trim()
        if (p.startsWith("@json:")) p = p.substring(6)
        p = p.trim()
        if (p.startsWith("$")) p = p.substring(1)
        val segs = ArrayList<Seg>()
        var i = 0
        while (i < p.length) {
            val c = p[i]
            when {
                c == '.' -> {
                    if (i + 1 < p.length && p[i + 1] == '.') {
                        // 递归下降
                        var j = i + 2
                        val sb = StringBuilder()
                        while (j < p.length && p[j] != '.' && p[j] != '[') sb.append(p[j++])
                        segs += Seg.Deep(sb.toString().takeIf { it.isNotEmpty() && it != "*" })
                        i = j
                    } else {
                        var j = i + 1
                        val sb = StringBuilder()
                        while (j < p.length && p[j] != '.' && p[j] != '[') sb.append(p[j++])
                        val name = sb.toString()
                        segs += if (name == "*") Seg.Wildcard else Seg.Key(name)
                        i = j
                    }
                }
                c == '[' -> {
                    val end = p.indexOf(']', i)
                    if (end < 0) { i = p.length; continue }
                    val inner = p.substring(i + 1, end).trim().trim('\'', '"')
                    segs += when {
                        inner == "*" -> Seg.Wildcard
                        inner.toIntOrNull() != null -> Seg.Index(inner.toInt())
                        else -> Seg.Key(inner)
                    }
                    i = end + 1
                }
                else -> {
                    var j = i
                    val sb = StringBuilder()
                    while (j < p.length && p[j] != '.' && p[j] != '[') sb.append(p[j++])
                    val name = sb.toString()
                    if (name.isNotEmpty()) segs += if (name == "*") Seg.Wildcard else Seg.Key(name)
                    i = j
                }
            }
        }
        return segs
    }

    private fun apply(node: JsonElement, seg: Seg, out: MutableList<JsonElement>) {
        when (seg) {
            is Seg.Wildcard -> when {
                node.isJsonArray -> node.asJsonArray.forEach { out += it }
                node.isJsonObject -> node.asJsonObject.entrySet().forEach { out += it.value }
            }
            is Seg.Key -> {
                if (node.isJsonObject) node.asJsonObject.get(seg.name)?.let { out += it }
            }
            is Seg.Index -> {
                if (node.isJsonArray) {
                    val arr = node.asJsonArray
                    val idx = if (seg.index < 0) arr.size() + seg.index else seg.index
                    if (idx in 0 until arr.size()) out += arr.get(idx)
                }
            }
            is Seg.Deep -> {
                val name = seg.name
                fun walk(el: JsonElement) {
                    when {
                        el.isJsonObject -> el.asJsonObject.entrySet().forEach { (k, v) ->
                            if (name == null || k == name) out += v
                            walk(v)
                        }
                        el.isJsonArray -> el.asJsonArray.forEach { walk(it) }
                    }
                }
                walk(node)
            }
        }
    }

    /** JSON 数组或对象的条目集合（用于书籍列表） */
    fun items(el: JsonElement?): List<JsonElement> = when {
        el == null -> emptyList()
        el.isJsonArray -> el.asJsonArray.toList()
        el.isJsonObject -> listOf(el)
        else -> emptyList()
    }

    fun arrayOf(vararg values: JsonElement): JsonArray = JsonArray().apply { values.forEach { add(it) } }
}
