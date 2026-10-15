package com.drtech.tuya

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TuyaDiscoveryTest {
    @Test
    fun parsesAnEncryptedAnnounce() {
        val frame = TuyaDiscovery.buildAnnounce(
            """{"ip":"10.0.0.7","gwId":"abc123deviceid","active":2,"encrypt":true,"productKey":"pk123","version":"3.4"}""",
        )
        val device = TuyaDiscovery.parsePacket(frame, "10.0.0.99")
        assertEquals(DiscoveredDevice("10.0.0.7", "abc123deviceid", "pk123", "3.4"), device)
    }

    @Test
    fun fallsBackToTheSenderAddress() {
        val frame = TuyaDiscovery.buildAnnounce("""{"gwId":"idonly","version":"3.3"}""")
        assertEquals("10.0.0.99", TuyaDiscovery.parsePacket(frame, "10.0.0.99")?.ip)
    }

    @Test
    fun ignoresNoise() {
        assertNull(TuyaDiscovery.parsePacket(ByteArray(10), "1.2.3.4"))
        assertNull(TuyaDiscovery.parsePacket(ByteArray(64) { 7 }, "1.2.3.4"))
    }
}
