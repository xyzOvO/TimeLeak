package com.chengliu.app.core

/**
 * 极简 JSON5 解析器。
 *
 * GKD 的 dist 订阅与各家跳过工具的规则文件都不是严格 JSON：允许行注释与块注释、
 * 不带引号的 key、单引号字符串、尾逗号。Android 自带的 org.json 解析不了这些，
 * 这里手写一个够用的小解析器，不引入任何第三方依赖。
 *
 * 返回值只会是：Map<String, Any?>、List<Any?>、String、Double、Boolean、null。
 * 解析失败返回 null（调用方按「没有规则」处理，不影响主流程）。
 */
object Json5 {

    /**
     * 解析失败返回 null。
     *
     * 特例：很多人习惯一行贴一个 `{...}`，整段其实是「多个顶层值」。这种情况
     * 返回 List，调用方按列表继续遍历即可。
     */
    fun parse(text: String): Any? {
        if (text.isBlank()) return null
        return runCatching {
            val r = Reader(text)
            val first = r.readValue()
            val rest = r.readRest()
            if (rest.isEmpty()) first else listOf(first) + rest
        }.getOrNull()
    }

    private class Reader(private val s: String) {
        private var i = 0
        private var depth = 0

        fun readValue(): Any? {
            skip()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"', '\'' -> readString()
                else -> readLiteral()
            }
        }

        /** 读取第一个值之后残留的其它顶层值（一行一个 JSON 对象的情况）。 */
        fun readRest(): List<Any?> {
            val out = ArrayList<Any?>()
            while (true) {
                skip()
                if (i >= s.length) break
                val before = i
                val v = readValue() ?: break
                out.add(v)
                if (i == before) break
                if (out.size > 256) break
            }
            return out
        }

        private fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            if (++depth > 64) { depth--; return map }
            i++ // 跳过 {
            while (i < s.length) {
                skip()
                if (i >= s.length) break
                val c = s[i]
                if (c == '}') { i++; break }
                if (c == ',') { i++; continue }
                val key = if (c == '"' || c == '\'') readString() else readKey()
                skip()
                if (i < s.length && s[i] == ':') i++
                val v = readValue()
                if (key.isNotEmpty()) map[key] = v
                skip()
                if (i < s.length && s[i] == ',') i++
            }
            depth--
            return map
        }

        private fun readArray(): List<Any?> {
            val list = ArrayList<Any?>()
            if (++depth > 64) { depth--; return list }
            i++ // 跳过 [
            while (i < s.length) {
                skip()
                if (i >= s.length) break
                val c = s[i]
                if (c == ']') { i++; break }
                if (c == ',') { i++; continue }
                list.add(readValue())
                skip()
                if (i < s.length && s[i] == ',') i++
            }
            depth--
            return list
        }

        private fun readKey(): String {
            val start = i
            while (i < s.length) {
                val c = s[i]
                if (c == ':' || c == ' ' || c == '\t' || c == '\n' || c == '\r' ||
                    c == ',' || c == '}' || c == '{' || c == '[' || c == ']'
                ) break
                i++
            }
            return s.substring(start, i).trim()
        }

        private fun readString(): String {
            val quote = s[i]
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    i++
                    when (val e = s[i]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (i + 4 < s.length) {
                                val hex = s.substring(i + 1, i + 5)
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: ' ')
                                i += 4
                            }
                        }
                        else -> sb.append(e)
                    }
                    i++
                    continue
                }
                if (c == quote) { i++; break }
                sb.append(c)
                i++
            }
            return sb.toString()
        }

        private fun readLiteral(): Any? {
            val start = i
            while (i < s.length) {
                val c = s[i]
                if (c == ',' || c == '}' || c == ']' || c == '\n' || c == '\r') break
                i++
            }
            val raw = s.substring(start, i).trim()
            if (raw.isEmpty()) return null
            return when (raw) {
                "true" -> true
                "false" -> false
                "null", "undefined" -> null
                else -> raw.toDoubleOrNull() ?: raw
            }
        }

        private fun skip() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\uFEFF') { i++; continue }
                if (c == '/' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        '/' -> {
                            i += 2
                            while (i < s.length && s[i] != '\n') i++
                            continue
                        }
                        '*' -> {
                            i += 2
                            while (i + 1 < s.length && !(s[i] == '*' && s[i + 1] == '/')) i++
                            i = minOf(i + 2, s.length)
                            continue
                        }
                    }
                }
                break
            }
        }
    }
}
