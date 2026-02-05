fun parseApiIssues(response: String): List<ApiIssueInfo> {
    val root = JsonParser(response).parse()
    val issues = root as? List<*>
        ?: error("Expected GitHub issues response to be a JSON array")

    return issues.mapNotNull { issue ->
        val issueObject = issue as? Map<*, *> ?: return@mapNotNull null
        val title = issueObject["title"] as? String ?: return@mapNotNull null
        val url = (issueObject["html_url"] as? String)
            ?: (issueObject["url"] as? String)
            ?: return@mapNotNull null
        val labels = issueObject["labels"] as? List<*> ?: emptyList<Any?>()
        val labelNames = labels.mapNotNull { label ->
            when (label) {
                is String -> label
                is Map<*, *> -> label["name"] as? String
                else -> null
            }
        }
        ApiIssueInfo(title = title, url = url, labelNames = labelNames)
    }
}

private class JsonParser(private val text: String) {
    private var index = 0

    fun parse(): Any? {
        val value = parseValue()
        skipWhitespace()
        if (index != text.length) {
            error("Unexpected trailing JSON at offset $index")
        }
        return value
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        if (index >= text.length) error("Unexpected end of JSON")

        return when (text[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            else -> parseNumber()
        }
    }

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        val result = mutableMapOf<String, Any?>()
        skipWhitespace()
        if (peek('}')) {
            index++
            return result
        }

        while (true) {
            val key = parseString()
            skipWhitespace()
            expect(':')
            result[key] = parseValue()
            skipWhitespace()
            when {
                peek(',') -> index++
                peek('}') -> {
                    index++
                    return result
                }
                else -> error("Expected ',' or '}' at offset $index")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        val result = mutableListOf<Any?>()
        skipWhitespace()
        if (peek(']')) {
            index++
            return result
        }

        while (true) {
            result += parseValue()
            skipWhitespace()
            when {
                peek(',') -> index++
                peek(']') -> {
                    index++
                    return result
                }
                else -> error("Expected ',' or ']' at offset $index")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (index < text.length) {
            val char = text[index++]
            when (char) {
                '"' -> return result.toString()
                '\\' -> result.append(parseEscapedCharacter())
                else -> result.append(char)
            }
        }
        error("Unterminated JSON string")
    }

    private fun parseEscapedCharacter(): Char {
        if (index >= text.length) error("Unterminated JSON escape")
        return when (val escaped = text[index++]) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseUnicodeEscape()
            else -> error("Unsupported JSON escape \\$escaped at offset ${index - 1}")
        }
    }

    private fun parseUnicodeEscape(): Char {
        if (index + 4 > text.length) error("Incomplete JSON unicode escape at offset $index")
        val value = text.substring(index, index + 4).toIntOrNull(16)
            ?: error("Invalid JSON unicode escape at offset $index")
        index += 4
        return value.toChar()
    }

    private fun parseNumber(): Number {
        val start = index
        if (peek('-')) index++
        readDigits()
        if (peek('.')) {
            index++
            readDigits()
        }
        if (peek('e') || peek('E')) {
            index++
            if (peek('+') || peek('-')) index++
            readDigits()
        }
        if (start == index) error("Expected JSON value at offset $index")

        val raw = text.substring(start, index)
        return if (raw.any { it == '.' || it == 'e' || it == 'E' }) {
            raw.toDoubleOrNull() ?: error("Invalid JSON number '$raw' at offset $start")
        } else {
            raw.toLongOrNull() ?: error("Invalid JSON number '$raw' at offset $start")
        }
    }

    private fun parseLiteral(raw: String, value: Any?): Any? {
        if (!text.startsWith(raw, index)) {
            error("Expected '$raw' at offset $index")
        }
        index += raw.length
        return value
    }

    private fun readDigits() {
        val start = index
        while (index < text.length && text[index].isDigit()) index++
        if (start == index) error("Expected digit at offset $index")
    }

    private fun expect(char: Char) {
        skipWhitespace()
        if (!peek(char)) error("Expected '$char' at offset $index")
        index++
    }

    private fun peek(char: Char): Boolean {
        return index < text.length && text[index] == char
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index++
    }
}
