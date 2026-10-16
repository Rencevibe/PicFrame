package io.github.rencevibe.picframe.domain

/**
 * Strict JSON reader for the one Open-Meteo response the app reads. Objects become
 * Map<String, Any?> (key order kept), arrays List<Any?>, numbers Long or Double, plus String,
 * Boolean and null. Throws IllegalArgumentException on malformed input.
 *
 * Used instead of org.json so the domain layer stays pure Kotlin and its tests need no extra
 * JVM test dependency (see android/docs/m2-domain.md).
 */
object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.space()
        val v = p.value()
        p.space()
        require(p.atEnd()) { "JSON: unexpected text at ${p.pos}" }
        return v
    }

    private class Parser(private val s: String) {
        var pos = 0
        fun atEnd() = pos >= s.length

        fun space() {
            while (pos < s.length && s[pos] in " \t\r\n") pos++
        }

        private fun fail(what: String): Nothing = throw IllegalArgumentException("JSON: $what at $pos")

        fun value(): Any? {
            if (atEnd()) fail("unexpected end")
            return when (s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> num()
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, pos)) fail("expected $w")
            pos += w.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            space()
            if (!atEnd() && s[pos] == '}') {
                pos++
                return out
            }
            while (true) {
                space()
                if (atEnd() || s[pos] != '"') fail("expected a key")
                val k = str()
                space()
                if (atEnd() || s[pos] != ':') fail("expected ':'")
                pos++
                space()
                out[k] = value()
                space()
                if (atEnd()) fail("unterminated object")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arr(): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            space()
            if (!atEnd() && s[pos] == ']') {
                pos++
                return out
            }
            while (true) {
                space()
                out += value()
                space()
                if (atEnd()) fail("unterminated array")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun str(): String {
            pos++
            val out = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (atEnd()) fail("bad escape")
                        when (val e = s[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("bad unicode escape")
                                val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: fail("bad unicode escape")
                                out.append(code.toChar()) // surrogate pairs arrive as two escapes
                                pos += 4
                            }
                            else -> fail("bad escape \\$e")
                        }
                    }
                    c < ' ' -> fail("control character in string")
                    else -> out.append(c)
                }
            }
        }

        private fun num(): Any {
            val m = NUMBER.find(s, pos)?.takeIf { it.range.first == pos } ?: fail("invalid value")
            pos += m.value.length
            val t = m.value
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
        }
    }

    private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
}
