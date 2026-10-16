package com.drtech.tuya

/**
 * Minimal JSON reader/writer so the protocol module stays free of Android and
 * third-party dependencies. Objects map to [LinkedHashMap], arrays to [List],
 * integers to [Long], other numbers to [Double].
 */
object MiniJson {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd()) throw IllegalArgumentException("Trailing JSON content")
        return value
    }

    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value.toString())
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Number -> out.append(value.toString())
            is String -> appendString(out, value)
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    appendString(out, k.toString())
                    out.append(':')
                    append(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (v in value) {
                    if (!first) out.append(',')
                    first = false
                    append(out, v)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("Unsupported JSON value: ${value::class.simpleName}")
        }
    }

    private fun appendString(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append(String.format("\\u%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    /** Device and discovery data are shallow; refuse deep nesting so crafted input cannot overflow the stack. */
    private const val MAX_DEPTH = 32

    private class Reader(private val s: String) {
        private var i = 0
        private var depth = 0

        private inline fun <T> nested(block: () -> T): T {
            if (++depth > MAX_DEPTH) throw IllegalArgumentException("JSON nested too deeply")
            try { return block() } finally { depth-- }
        }

        fun atEnd() = i >= s.length

        fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (atEnd()) throw IllegalArgumentException("Unexpected end of JSON")
            return when (val c = s[i]) {
                '{' -> nested { readObject() }
                '[' -> nested { readArray() }
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw IllegalArgumentException("Unexpected '$c'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("Bad literal")
            i += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++ // {
            skipWhitespace()
            if (s[i] == '}') { i++; return map }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                if (s[i++] != ':') throw IllegalArgumentException("Expected ':'")
                map[key] = readValue()
                skipWhitespace()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw IllegalArgumentException("Expected ',' or '}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val list = ArrayList<Any?>()
            i++ // [
            skipWhitespace()
            if (s[i] == ']') { i++; return list }
            while (true) {
                list.add(readValue())
                skipWhitespace()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return list
                    else -> throw IllegalArgumentException("Expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            if (s[i] != '"') throw IllegalArgumentException("Expected string")
            i++
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("Unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> throw IllegalArgumentException("Bad escape")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val text = s.substring(start, i)
            return text.toLongOrNull() ?: text.toDouble()
        }
    }
}
