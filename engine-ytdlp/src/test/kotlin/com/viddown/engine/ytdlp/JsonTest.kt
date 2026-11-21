package com.viddown.engine.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonTest {
    @Test
    fun readsObjectsArraysNumbersAndLiterals() {
        val root = Json.parse("""{"a":1,"b":2.5,"c":[true,false,null],"d":"x","e":{"f":-3}}""").asMap()!!
        assertEquals(1L, root["a"])
        assertEquals(2.5, root["b"].asDouble()!!, 0.0)
        assertEquals(listOf(true, false, null), root["c"])
        assertEquals("x", root["d"])
        assertEquals(-3L, root["e"].asMap()!!["f"])
    }

    @Test
    fun readsEscapesAndSurrogatePairs() {
        val text = Json.parse("\"a\\n\\\"b\\\" \\u00e9 \\ud83d\\ude00\"") as String
        assertEquals("a\n\"b\" é \uD83D\uDE00", text)
    }

    @Test
    fun pythonNaNAndInfinityBecomeNull() {
        val root = Json.parse("""{"x":NaN,"y":Infinity,"z":-Infinity}""").asMap()!!
        assertNull(root["x"]); assertNull(root["y"]); assertNull(root["z"])
    }

    @Test
    fun rejectsBrokenInput() {
        for (bad in listOf("", "{", "{\"a\":}", "[1,]x", "{\"a\":1} trailing", "\"unterminated", "{\"a\":01x}")) {
            try {
                Json.parse(bad)
                fail("should have been rejected: $bad")
            } catch (e: Json.ParseException) {
                assertTrue(e.message!!.isNotEmpty())
            }
        }
    }

    @Test
    fun deeplyNestedInputIsRefusedNotCrashed() {
        val deep = "[".repeat(500) + "]".repeat(500)
        try {
            Json.parse(deep)
            fail("expected a ParseException")
        } catch (e: Json.ParseException) {
            // expected
        }
    }

    @Test
    fun numbersConvertSafely() {
        assertEquals(5L, "5.0".asLong())
        assertNull("abc".asLong())
        assertNull(Double.NaN.asLong())
        assertEquals(7, 7L.asInt())
    }
}
