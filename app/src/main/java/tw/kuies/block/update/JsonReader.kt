package tw.kuies.block.update

/**
 * The value of one JSON field, kept only as far as this app reads JSON.
 *
 * There is no JSON library in the project and the releases response is the only JSON it reads, so this
 * is a deliberately small reader rather than a new dependency. It is fail-closed: anything it does not
 * fully recognise makes the whole document invalid, and it never throws, so a malformed or hostile
 * response can be rejected but can never take the app down.
 */
internal sealed interface JsonValue {
    data class Text(val value: String) : JsonValue
    data class Flag(val value: Boolean) : JsonValue
    data class Number(val raw: String) : JsonValue
    data object Null : JsonValue
    data class Items(val values: List<JsonValue>) : JsonValue
    data class Fields(val values: Map<String, JsonValue>) : JsonValue

    /** The value as a string, or null when it is any other kind of JSON value. */
    fun asText(): String? = (this as? Text)?.value

    /** The value as a boolean, or null when it is any other kind of JSON value. */
    fun asFlag(): Boolean? = (this as? Flag)?.value
}

/**
 * Reads one JSON document; only a top-level object is accepted.
 *
 * Strict on purpose — the escapes of RFC 8257, no trailing commas, no duplicate keys, and nothing
 * after the closing brace — because a document that is almost a release description is exactly the
 * kind of input that should be refused rather than interpreted.
 */
internal object JsonReader {

    /** The document's top-level fields, or null when [text] is not exactly one JSON object. */
    fun readObject(text: String): Map<String, JsonValue>? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue() ?: return null
        reader.skipWhitespace()
        if (!reader.atEnd) return null
        return (value as? JsonValue.Fields)?.values
    }

    private class Reader(private val text: String) {
        private var index = 0

        val atEnd: Boolean get() = index >= text.length

        fun skipWhitespace() {
            while (!atEnd && text[index] in WHITESPACE) index++
        }

        fun readValue(): JsonValue? {
            skipWhitespace()
            if (atEnd) return null
            return when (text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()?.let { JsonValue.Text(it) }
                't' -> if (readLiteral("true")) JsonValue.Flag(true) else null
                'f' -> if (readLiteral("false")) JsonValue.Flag(false) else null
                'n' -> if (readLiteral("null")) JsonValue.Null else null
                else -> readNumber()
            }
        }

        private fun readObject(): JsonValue? {
            index++ // the opening brace
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (current() == '}') {
                index++
                return JsonValue.Fields(fields)
            }
            while (true) {
                skipWhitespace()
                val key = readString() ?: return null
                skipWhitespace()
                if (current() != ':') return null
                index++
                val value = readValue() ?: return null
                // A repeated key is refused rather than silently overwritten: two readings of one
                // field would make the document ambiguous about what the release actually says.
                if (fields.put(key, value) != null) return null
                skipWhitespace()
                when (current()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return JsonValue.Fields(fields)
                    }
                    else -> return null
                }
            }
        }

        private fun readArray(): JsonValue? {
            index++ // the opening bracket
            val values = mutableListOf<JsonValue>()
            skipWhitespace()
            if (current() == ']') {
                index++
                return JsonValue.Items(values)
            }
            while (true) {
                values += readValue() ?: return null
                skipWhitespace()
                when (current()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return JsonValue.Items(values)
                    }
                    else -> return null
                }
            }
        }

        private fun readString(): String? {
            if (current() != '"') return null
            index++
            val out = StringBuilder()
            while (true) {
                val ch = current() ?: return null
                index++
                when {
                    ch == '"' -> return out.toString()
                    ch == '\\' -> {
                        val escape = current() ?: return null
                        index++
                        when (escape) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            // A surrogate pair is two escapes and stays two chars, which is exactly how
                            // a Kotlin String holds one character outside the BMP.
                            'u' -> out.append(readHexDigit4() ?: return null)
                            else -> return null
                        }
                    }
                    // A raw control character is not valid inside a JSON string.
                    ch.code < 0x20 -> return null
                    else -> out.append(ch)
                }
            }
        }

        private fun readHexDigit4(): Char? {
            if (index + 4 > text.length) return null
            var value = 0
            repeat(4) {
                val digit = text[index++].digitToIntOrNull(16) ?: return null
                value = value * 16 + digit
            }
            return value.toChar()
        }

        private fun readLiteral(literal: String): Boolean {
            if (!text.startsWith(literal, index)) return false
            index += literal.length
            return true
        }

        private fun readNumber(): JsonValue? {
            val start = index
            while (!atEnd && text[index] in NUMBER_CHARS) index++
            if (index == start) return null
            val raw = text.substring(start, index)
            return if (NUMBER_PATTERN.matches(raw)) JsonValue.Number(raw) else null
        }

        private fun current(): Char? = if (atEnd) null else text[index]
    }

    private const val WHITESPACE = " \t\n\r"
    private const val NUMBER_CHARS = "-+.eE0123456789"
    private val NUMBER_PATTERN = Regex("""^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$""")
}
