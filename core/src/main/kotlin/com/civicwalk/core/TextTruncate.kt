package com.civicwalk.core

/**
 * UTF-8 safe truncation so a length limit never splits a Chinese character.
 * Used for on-device note drafts.
 */
object TextTruncate {
    fun truncateUtf8(s: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        val sb = StringBuilder()
        var used = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val len = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (used + len > maxBytes) break
            sb.appendCodePoint(cp)
            used += len
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
