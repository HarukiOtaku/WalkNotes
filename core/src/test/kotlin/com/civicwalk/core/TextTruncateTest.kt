package com.civicwalk.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextTruncateTest {

    @Test
    fun asciiExact() {
        val s = "a".repeat(10)
        val maxBytes = 5
        val result = TextTruncate.truncateUtf8(s, maxBytes)
        assertEquals("aaaaa", result)
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= maxBytes)
        assertTrue(!result.contains("\uFFFD"))
    }

    @Test
    fun chineseBoundary() {
        val s = "\u4E2D\u6587".repeat(10)
        val maxBytes = 9
        val result = TextTruncate.truncateUtf8(s, maxBytes)
        assertEquals("\u4E2D\u6587\u4E2D", result)
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= maxBytes)
        assertTrue(!result.contains("\uFFFD"))
    }

    @Test
    fun mixed() {
        val s = "a\u4E2Db"
        val maxBytes = 4
        val result = TextTruncate.truncateUtf8(s, maxBytes)
        assertEquals("a\u4E2D", result)
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= maxBytes)
        assertTrue(!result.contains("\uFFFD"))
    }

    @Test
    fun emojiSurrogatePair() {
        val s = "\uD83D\uDE00a"
        val maxBytes = 4
        val result = TextTruncate.truncateUtf8(s, maxBytes)
        assertEquals("\uD83D\uDE00", result)
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= maxBytes)
        assertTrue(!result.contains("\uFFFD"))
    }

    @Test
    fun maxBytesSmallerThanOneCodePoint() {
        val s = "\u4E2D"
        val maxBytes = 2
        val result = TextTruncate.truncateUtf8(s, maxBytes)
        assertEquals("", result)
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= maxBytes)
        assertTrue(!result.contains("\uFFFD"))
    }

    @Test
    fun zero() {
        val s = "abc"
        val maxBytes = 0
        val result = TextTruncate.truncateUtf8(s, maxBytes)
        assertEquals("", result)
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= maxBytes)
        assertTrue(!result.contains("\uFFFD"))
    }

    @Test
    fun negative() {
        val s = "abc"
        val maxBytes = -1
        val result = TextTruncate.truncateUtf8(s, maxBytes)
        // A negative maxBytes cannot satisfy the "bytes <= maxBytes" invariant,
        // so the contract here is only that the result is empty.
        assertEquals("", result)
    }

    @Test
    fun fiveHundredChineseTo1500() {
        val s = "\u4E2D".repeat(500)
        val maxBytes = 1500
        val result = TextTruncate.truncateUtf8(s, maxBytes)
        assertEquals(s, result)
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= maxBytes)
        assertTrue(!result.contains("\uFFFD"))
    }

    @Test
    fun asciiExact1500() {
        val s = "a".repeat(1500)
        val maxBytes = 1500
        val result = TextTruncate.truncateUtf8(s, maxBytes)
        assertEquals(s, result)
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= maxBytes)
        assertTrue(!result.contains("\uFFFD"))
    }
}
