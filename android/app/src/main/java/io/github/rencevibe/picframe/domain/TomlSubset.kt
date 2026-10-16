package io.github.rencevibe.picframe.domain

/**
 * Minimal TOML reader for todo.toml (plan §4: "a small parser for `[[item]]`").
 *
 * Supports: comments, `[[array]]` and `[table]` headers, `key = value` with bare or quoted keys,
 * basic and literal strings (single line), booleans, integers, floats and single-line arrays of
 * those. Other bare values (dates, times) parse as [Other], so a field of the wrong type is
 * reported per item, as tomllib + today.py do. Anything else (multi-line strings or arrays,
 * inline tables, dotted keys, duplicate keys) throws [TomlError] and the file is treated as invalid.
 */
class TomlError(message: String) : Exception(message)

/** A bare value that isn't a string, boolean or number, e.g. a TOML date or time literal. */
data class Other(val raw: String)

object TomlSubset {
    fun parse(text: String): Map<String, Any?> {
        val root = LinkedHashMap<String, Any?>()
        var current: MutableMap<String, Any?> = root
        text.lines().forEachIndexed { index, rawLine ->
            val n = index + 1
            val line = Cursor(rawLine, n)
            line.skipSpace()
            if (line.atEnd() || line.peek() == '#') return@forEachIndexed
            if (line.peek() == '[') {
                current = header(line, root)
            } else {
                val key = line.key()
                line.skipSpace()
                line.expect('=')
                line.skipSpace()
                val value = line.value()
                line.skipSpace()
                if (!line.atEnd() && line.peek() != '#') throw TomlError("line $n: unexpected text after value")
                if (key in current) throw TomlError("line $n: duplicate key $key")
                current[key] = value
            }
        }
        return root
    }

    private fun header(line: Cursor, root: MutableMap<String, Any?>): MutableMap<String, Any?> {
        val array = line.rest().startsWith("[[")
        line.expect('[')
        if (array) line.expect('[')
        line.skipSpace()
        val name = line.key()
        line.skipSpace()
        line.expect(']')
        if (array) line.expect(']')
        line.skipSpace()
        if (!line.atEnd() && line.peek() != '#') throw TomlError("line ${line.n}: unexpected text after header")
        val table = LinkedHashMap<String, Any?>()
        if (array) {
            when (val existing = root[name]) {
                null -> root[name] = TableArray().apply { add(table) }
                is TableArray -> existing.add(table)
                else -> throw TomlError("line ${line.n}: $name is already defined")
            }
        } else {
            if (name in root) throw TomlError("line ${line.n}: $name is already defined")
            root[name] = table
        }
        return table
    }

    /** Array of tables built from `[[name]]` headers; a plain `key = [...]` array is a List. */
    private class TableArray : ArrayList<Any?>()

    private class Cursor(private val s: String, val n: Int) {
        private var i = 0
        fun atEnd() = i >= s.length
        fun peek() = s[i]
        fun rest() = s.substring(i)
        fun skipSpace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        }

        fun expect(c: Char) {
            if (atEnd() || s[i] != c) throw TomlError("line $n: expected '$c'")
            i++
        }

        fun key(): String {
            if (!atEnd() && (peek() == '"' || peek() == '\'')) return string()
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '-')) i++
            if (i == start) throw TomlError("line $n: expected a key")
            if (!atEnd() && s[i] == '.') throw TomlError("line $n: dotted keys are not supported")
            return s.substring(start, i)
        }

        fun value(): Any? {
            if (atEnd()) throw TomlError("line $n: missing value")
            return when (peek()) {
                '"', '\'' -> {
                    if (rest().startsWith("\"\"\"") || rest().startsWith("'''")) {
                        throw TomlError("line $n: multi-line strings are not supported")
                    }
                    string()
                }
                '[' -> array()
                '{' -> throw TomlError("line $n: inline tables are not supported")
                else -> bare()
            }
        }

        private fun array(): List<Any?> {
            expect('[')
            val items = mutableListOf<Any?>()
            skipSpace()
            if (!atEnd() && peek() == ']') {
                i++
                return items
            }
            while (true) {
                skipSpace()
                items += value()
                skipSpace()
                if (atEnd()) throw TomlError("line $n: unterminated array")
                when (peek()) {
                    ',' -> {
                        i++
                        skipSpace()
                        if (!atEnd() && peek() == ']') {
                            i++
                            return items
                        }
                    }
                    ']' -> {
                        i++
                        return items
                    }
                    else -> throw TomlError("line $n: expected ',' or ']'")
                }
            }
        }

        private fun bare(): Any {
            val start = i
            while (i < s.length && s[i] != ',' && s[i] != ']' && s[i] != '#' && s[i] != ' ' && s[i] != '\t') i++
            val token = s.substring(start, i)
            if (token.isEmpty()) throw TomlError("line $n: missing value")
            return when {
                token == "true" -> true
                token == "false" -> false
                INT.matches(token) -> token.replace("_", "").toLongOrNull() ?: throw TomlError("line $n: integer too large")
                FLOAT.matches(token) -> token.replace("_", "").toDouble()
                DATEISH.matches(token) -> Other(token)
                else -> throw TomlError("line $n: invalid value $token")
            }
        }

        fun string(): String {
            val quote = s[i]
            i++
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw TomlError("line $n: unterminated string")
                val c = s[i++]
                when {
                    c == quote -> return out.toString()
                    c == '\\' && quote == '"' -> out.append(escape())
                    else -> out.append(c)
                }
            }
        }

        private fun escape(): String {
            if (atEnd()) throw TomlError("line $n: bad escape")
            return when (val c = s[i++]) {
                'b' -> "\b"
                't' -> "\t"
                'n' -> "\n"
                'f' -> "\u000C"
                'r' -> "\r"
                '"' -> "\""
                '\\' -> "\\"
                'u', 'U' -> {
                    val len = if (c == 'u') 4 else 8
                    if (i + len > s.length) throw TomlError("line $n: bad unicode escape")
                    val code = s.substring(i, i + len).toIntOrNull(16) ?: throw TomlError("line $n: bad unicode escape")
                    i += len
                    if (!Character.isValidCodePoint(code)) throw TomlError("line $n: bad unicode escape")
                    String(Character.toChars(code))
                }
                else -> throw TomlError("line $n: bad escape \\$c")
            }
        }
    }

    private val INT = Regex("[+-]?(0|[1-9](_?[0-9])*)")
    private val FLOAT = Regex("[+-]?(0|[1-9](_?[0-9])*)(\\.[0-9](_?[0-9])*)?([eE][+-]?[0-9](_?[0-9])*)?|[+-]?(inf|nan)")
    private val DATEISH = Regex("[0-9][0-9:T.+\\-Zz]*")
}
