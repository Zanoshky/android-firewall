package com.zanoshky.firewall

/**
 * Just enough DNS to read a question and write an answer.
 *
 * The firewall never invents an answer for a name it is letting through: that
 * query is passed upstream and the reply comes back untouched. What is built
 * here are the two refusals, an address that goes nowhere and an empty answer,
 * plus the outright "no such name".
 */
object DnsMessage {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    private const val RCODE_NOERROR = 0
    private const val RCODE_NXDOMAIN = 3

    data class Question(
        val name: String,
        val type: Int,
        /** Offset one past the question section, where answers would start. */
        val end: Int
    )

    /**
     * Read the single question out of a query. Returns null for anything that is
     * not a plain query with one question, which includes responses, updates and
     * truncated rubbish.
     */
    fun parseQuestion(msg: ByteArray): Question? {
        if (msg.size < 17) return null
        val flags = ((msg[2].toInt() and 0xFF) shl 8) or (msg[3].toInt() and 0xFF)
        if (flags and 0x8000 != 0) return null            // a response, not a query
        if ((flags shr 11) and 0xF != 0) return null      // not a standard query
        val questions = ((msg[4].toInt() and 0xFF) shl 8) or (msg[5].toInt() and 0xFF)
        if (questions != 1) return null

        val sb = StringBuilder()
        var pos = 12
        while (pos < msg.size) {
            val len = msg[pos].toInt() and 0xFF
            if (len == 0) { pos++; break }
            if (len > 63) return null                     // compression pointer in a question
            pos++
            if (pos + len > msg.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until len) {
                val c = msg[pos + i].toInt() and 0xFF
                sb.append(if (c in 65..90) (c + 32).toChar() else c.toChar())
            }
            pos += len
        }
        if (pos + 4 > msg.size) return null
        val type = ((msg[pos].toInt() and 0xFF) shl 8) or (msg[pos + 1].toInt() and 0xFF)
        val name = sb.toString()
        if (name.isEmpty()) return null
        return Question(name, type, pos + 4)
    }

    /** The name the app asked about, or null when the message is not a usable query. */
    fun queryName(msg: ByteArray): String? = parseQuestion(msg)?.name

    /**
     * An answer that sends the app to [address] instead of the real host. A
     * lookup for anything other than an address record gets an empty answer, so
     * a browser asking for HTTPS or IPv6 records is told "nothing here" rather
     * than being handed something it cannot use.
     */
    fun buildSinkholeResponse(query: ByteArray, address: ByteArray, ttlSeconds: Int = 60): ByteArray {
        val question = parseQuestion(query) ?: return buildNxDomain(query)
        if (question.type != TYPE_A) return buildEmptyAnswer(query)

        val head = header(query, question.end, RCODE_NOERROR, answers = 1)
        val out = ByteArray(head.size + 16)
        head.copyInto(out)

        var p = head.size
        out[p++] = 0xC0.toByte(); out[p++] = 0x0C   // pointer back to the question's name
        out[p++] = 0; out[p++] = TYPE_A.toByte()
        out[p++] = 0; out[p++] = 1                   // class IN
        out[p++] = ((ttlSeconds shr 24) and 0xFF).toByte()
        out[p++] = ((ttlSeconds shr 16) and 0xFF).toByte()
        out[p++] = ((ttlSeconds shr 8) and 0xFF).toByte()
        out[p++] = (ttlSeconds and 0xFF).toByte()
        out[p++] = 0; out[p++] = 4                   // rdlength
        address.copyInto(out, p)
        return out
    }

    /** NOERROR with no records: the name exists, it just has nothing of this type. */
    fun buildEmptyAnswer(query: ByteArray): ByteArray {
        val question = parseQuestion(query) ?: return buildNxDomain(query)
        return header(query, question.end, RCODE_NOERROR, answers = 0)
    }

    /** No such name. Fails instantly and costs the app nothing further. */
    fun buildNxDomain(query: ByteArray): ByteArray {
        val end = parseQuestion(query)?.end ?: minOf(query.size, 12)
        return header(query, end, RCODE_NXDOMAIN, answers = 0)
    }

    /**
     * Header plus a verbatim copy of the question section. Keeps the query's
     * transaction id and its recursion-desired bit, and claims recursion is
     * available, which is what a resolver on the other end of a stub would say.
     */
    private fun header(query: ByteArray, questionEnd: Int, rcode: Int, answers: Int): ByteArray {
        val end = questionEnd.coerceIn(12, query.size)
        val out = ByteArray(end)
        query.copyInto(out, 0, 0, end)
        val recursionDesired = query[2].toInt() and 0x01
        out[2] = (0x80 or recursionDesired).toByte()   // QR = 1
        out[3] = (0x80 or rcode).toByte()              // RA = 1
        out[4] = 0; out[5] = 1                          // one question
        out[6] = ((answers shr 8) and 0xFF).toByte()
        out[7] = (answers and 0xFF).toByte()
        out[8] = 0; out[9] = 0                          // no authority records
        out[10] = 0; out[11] = 0                        // no additional records
        return out
    }
}
