package com.jetbrains.kotlinllm.kotlinllmplugin.snapshot.json

/**
 * A minimal, dependency-free JSON parser/emitter shared by the snapshot convention
 * ([SnapshotIo]) and the mutflow JSON output parser ([MutflowIntegration]).
 *
 * Supports the object/array/string/number/true/false/null subset used by mutflow's
 * `[mutflow-json]` summary and the snapshot state file. Keeps the plugin build free
 * of a kotlinx-serialization dependency.
 */
object MiniJson {

    sealed interface Value {
        data class Obj(val entries: LinkedHashMap<String, Value>) : Value {
            fun get(key: String): Value? = entries[key]
            fun str(key: String): String? = (entries[key] as? Str)?.value
            fun int(key: String): Int? = (entries[key] as? Num)?.value?.toIntOrNull()
            fun arr(key: String): List<Value> = (entries[key] as? Arr)?.items.orEmpty()
        }
        data class Arr(val items: List<Value>) : Value
        data class Str(val value: String) : Value
        data class Num(val value: String) : Value
        data object Null : Value
        data object True : Value
        data object False : Value

        companion object {
            fun parse(json: String): Value? = Parser(json).parse()
        }
    }

    fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
            }
        }
        append('"')
    }

    private class Parser(val s: String) {
        var pos = 0
        fun parse(): Value? {
            skipWs()
            if (pos >= s.length) return null
            return parseValue()
        }

        private fun parseValue(): Value {
            skipWs()
            return when (s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> Value.Str(parseString())
                't' -> { expect("true"); Value.True }
                'f' -> { expect("false"); Value.False }
                'n' -> { expect("null"); Value.Null }
                else -> Value.Num(parseNumber())
            }
        }

        private fun parseObject(): Value.Obj {
            pos++
            val map = LinkedHashMap<String, Value>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return Value.Obj(map) }
            while (pos < s.length) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(":")
                map[key] = parseValue()
                skipWs()
                if (pos < s.length && s[pos] == ',') { pos++; continue }
                if (pos < s.length && s[pos] == '}') { pos++; break }
                break
            }
            return Value.Obj(map)
        }

        private fun parseArray(): Value.Arr {
            pos++
            val items = mutableListOf<Value>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return Value.Arr(items) }
            while (pos < s.length) {
                items.add(parseValue())
                skipWs()
                if (pos < s.length && s[pos] == ',') { pos++; continue }
                if (pos < s.length && s[pos] == ']') { pos++; break }
                break
            }
            return Value.Arr(items)
        }

        private fun parseString(): String {
            if (s[pos] != '"') throw IllegalArgumentException("Expected string")
            pos++
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos]
                when {
                    c == '"' -> { pos++; return sb.toString() }
                    c == '\\' -> {
                        pos++
                        when (s[pos]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = s.substring(pos + 1, pos + 5)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> sb.append(s[pos])
                        }
                        pos++
                    }
                    else -> { sb.append(c); pos++ }
                }
            }
            return sb.toString()
        }

        private fun parseNumber(): String {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            return s.substring(start, pos)
        }

        private fun expect(token: String) {
            if (s.startsWith(token, pos)) pos += token.length else throw IllegalArgumentException("Expected $token at $pos")
        }

        private fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
    }
}
