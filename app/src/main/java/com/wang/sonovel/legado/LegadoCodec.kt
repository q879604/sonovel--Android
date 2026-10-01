package com.wang.sonovel.legado

/**
 * 纯 Kotlin 的 Base64 / HEX 编解码。
 *
 * 不用 `java.util.Base64`（Android API 26+ 才有），也不用 android.util.Base64，
 * 好让 legado 包保持纯 JVM、可在本地直接跑测试。
 */
object LegadoCodec {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val REVERSE = IntArray(128) { -1 }.also { arr ->
        ALPHABET.forEachIndexed { i, c -> arr[c.code] = i }
    }

    fun base64Encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
            if (i + 1 < bytes.size) sb.append(ALPHABET[((b1 and 0x0F) shl 2) or (b2 shr 6)]) else sb.append('=')
            if (i + 2 < bytes.size) sb.append(ALPHABET[b2 and 0x3F]) else sb.append('=')
            i += 3
        }
        return sb.toString()
    }

    fun base64Decode(text: String): ByteArray {
        val clean = text.filter { !it.isWhitespace() && it != '=' }
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = if (c.code < 128) REVERSE[c.code] else -1
            if (v < 0) continue
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    fun hexEncode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v shr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    fun hexDecode(text: String): ByteArray {
        val clean = text.trim().removePrefix("0x").removePrefix("0X").filter { !it.isWhitespace() }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return ByteArray(0)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private const val HEX = "0123456789abcdef"
}
