package tw.kuies.block.filter

/**
 * Encodes several values into one line-oriented preference record and back.
 *
 * The filter settings are stored with `SharedPreferences.putStringSet`, which keeps a set of strings
 * and answers with them in no particular order. Each field group therefore becomes one record and a
 * set of records, and a record is its fields joined by [FIELD_SEPARATOR], a control character that
 * cannot occur in a domain, a display name, a URL, an enum name or a number.
 *
 * Fields are escaped, so a value that does contain a control character still round-trips:
 *  * `\`      → `\\`
 *  * [FIELD_SEPARATOR] → `\.`
 *  * newline  → `\n`
 *  * carriage return → `\r`
 *
 * [decode] is deliberately strict: an unknown escape or a dangling `\` makes the whole record
 * unreadable, and the caller falls back to its default for that record instead of guessing at
 * half-corrupted data. Nothing here can throw on malformed input.
 */
object FilterPreferenceCodec {

    /** Unit separator: not a legal character in any field this codec stores. */
    const val FIELD_SEPARATOR: Char = '\u001F'

    private const val ESCAPE: Char = '\\'
    private const val ESCAPED_SEPARATOR: Char = '.'
    private const val ESCAPED_NEWLINE: Char = 'n'
    private const val ESCAPED_CARRIAGE_RETURN: Char = 'r'

    fun encode(fields: List<String>): String {
        val builder = StringBuilder(64)
        for ((index, field) in fields.withIndex()) {
            if (index > 0) builder.append(FIELD_SEPARATOR)
            appendEscaped(builder, field)
        }
        return builder.toString()
    }

    /** The fields of [record], or null when it cannot be decoded. */
    fun decode(record: String): List<String>? {
        val fields = ArrayList<String>(8)
        val field = StringBuilder(32)
        var index = 0
        while (index < record.length) {
            val c = record[index]
            when {
                c == FIELD_SEPARATOR -> {
                    fields.add(field.toString())
                    field.setLength(0)
                }

                c == ESCAPE -> {
                    index++
                    if (index >= record.length) return null
                    when (val escaped = record[index]) {
                        ESCAPE -> field.append(ESCAPE)
                        ESCAPED_SEPARATOR -> field.append(FIELD_SEPARATOR)
                        ESCAPED_NEWLINE -> field.append('\n')
                        ESCAPED_CARRIAGE_RETURN -> field.append('\r')
                        else -> return null
                    }
                }

                else -> field.append(c)
            }
            index++
        }
        fields.add(field.toString())
        return fields
    }

    private fun appendEscaped(builder: StringBuilder, value: String) {
        for (c in value) {
            when (c) {
                ESCAPE -> builder.append(ESCAPE).append(ESCAPE)
                FIELD_SEPARATOR -> builder.append(ESCAPE).append(ESCAPED_SEPARATOR)
                '\n' -> builder.append(ESCAPE).append(ESCAPED_NEWLINE)
                '\r' -> builder.append(ESCAPE).append(ESCAPED_CARRIAGE_RETURN)
                else -> builder.append(c)
            }
        }
    }
}
