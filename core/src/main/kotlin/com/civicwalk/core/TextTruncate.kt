package com.civicwalk.core

/**
 * UTF-8 safe truncation so a length limit never splits a Chinese character.
 * The helper is used for on-device note drafts (WalkNotes caps a saved note at
 * 600 bytes; an earlier CivicWalk prototype used 1500 bytes for a web form).
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
