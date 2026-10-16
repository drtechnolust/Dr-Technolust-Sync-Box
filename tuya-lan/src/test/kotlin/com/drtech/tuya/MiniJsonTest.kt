package com.drtech.tuya

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class MiniJsonTest {
    @Test
    fun parsesNestedDeviceStyleJson() {
        val parsed = MiniJson.parse("""{"dps":{"20":true,"22":460,"25":"80"},"t":1}""") as Map<*, *>
        assertEquals(mapOf("20" to true, "22" to 460L, "25" to "80"), parsed["dps"])
    }

    @Test
    fun refusesPathologicallyDeepNestingInsteadOfOverflowingTheStack() {
        for (open in listOf("[", "{\"a\":")) {
            val text = open.repeat(2_000)
            try {
                MiniJson.parse(text)
                fail("deeply nested input must be rejected")
            } catch (expected: IllegalArgumentException) {
                // rejected as a normal parse failure, not a StackOverflowError
            }
        }
    }
}
