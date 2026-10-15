package com.drtech.tuya

import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TuyaLanClientTest {
    // Test-only dummy key; unrelated to any real device.
    private val key = ByteArray(16) { it.toByte() }

    @Test fun abortInterruptsAStalledHandshakeWithoutBlockingTheCaller() {
        val accepted = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val peer = thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    accepted.countDown()
                    while (socket.getInputStream().read() != -1) { /* Deliberately never respond. */ }
                }
            } catch (_: Exception) { }
        }
        val client = TuyaLanClient("127.0.0.1", "dev123", key, port = server.localPort, readTimeoutMs = 30_000)
        val connector = thread(isDaemon = true) {
            try { client.connect() } catch (_: Exception) { } finally { finished.countDown() }
        }
        try {
            assertTrue(accepted.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val started = System.nanoTime()
            client.abort()
            assertTrue("Abort must not wait for the read timeout", System.nanoTime() - started < 500_000_000)
            assertTrue("Handshake must exit after socket interruption", finished.await(2, java.util.concurrent.TimeUnit.SECONDS))
            client.close()
            assertFalse(client.isConnected)
        } finally {
            client.abort()
            server.close()
            peer.join(2000)
            connector.join(2000)
        }
    }

    @Test
    fun codecRoundTripsAndRejectsTampering() {
        val frame = TuyaCodec.encode(TuyaCommand.CONTROL_NEW, 7, """{"a":1}""".toByteArray(), key)
        val decoded = TuyaCodec.decode(frame, key)
        assertEquals(7, decoded.seq)
        assertEquals(TuyaCommand.CONTROL_NEW, decoded.cmd)
        assertEquals("""{"a":1}""", decoded.payload.toString(Charsets.UTF_8))

        val tampered = frame.clone().also { it[20] = (it[20].toInt() xor 1).toByte() }
        try {
            TuyaCodec.decode(tampered, key)
            fail("tampered frame accepted")
        } catch (_: TuyaProtocolException) {
        }
        try {
            TuyaCodec.decode(frame, "fedcba9876543210".toByteArray())
            fail("wrong key accepted")
        } catch (_: TuyaProtocolException) {
        }
    }

    @Test
    fun codecHandlesReturnCode() {
        val frame = TuyaCodec.encode(TuyaCommand.DP_QUERY, 1, """{"dps":{}}""".toByteArray(), key, retCode = 0)
        val decoded = TuyaCodec.decode(frame, key)
        assertEquals(0, decoded.retCode)
        assertEquals("""{"dps":{}}""", decoded.payload.toString(Charsets.UTF_8))
    }

    @Test
    fun negotiatesQueriesAndControlsAgainstSimulatedDevice() {
        val received = CopyOnWriteArrayList<String>()
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val worker = thread(isDaemon = true) { runFakeDevice(server, received) }

        TuyaLanClient("127.0.0.1", "dev123", key, port = server.localPort).use { client ->
            client.connect()
            assertTrue(client.isConnected)

            val dps = client.queryDps()
            assertEquals(true, dps["20"])
            assertEquals(500L, dps["22"])

            assertEquals(0, client.setDps(mapOf("22" to 800, "20" to false)))
            assertEquals(800L, client.queryDps()["22"])
            assertFalse(client.lastDps["20"] as Boolean)
        }
        worker.join(2_000)
        server.close()
        assertTrue(received.any { it.contains(""""22":800""") && it.contains(""""protocol":5""") })
    }

    @Test
    fun wrongLocalKeyFailsNegotiation() {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        thread(isDaemon = true) { runFakeDevice(server, CopyOnWriteArrayList()) }
        val client = TuyaLanClient("127.0.0.1", "dev123", "fedcba9876543210".toByteArray(), port = server.localPort)
        try {
            client.connect()
            fail("connected with wrong key")
        } catch (_: TuyaProtocolException) {
        } catch (_: java.io.IOException) {
            // device may drop the connection after failing authentication
        } finally {
            client.close()
            server.close()
        }
    }

    /** Independent device-side implementation of the 3.4 handshake and a tiny DPS store. */
    private fun runFakeDevice(server: ServerSocket, received: MutableList<String>) {
        try {
            server.accept().use { sock ->
                val input = sock.getInputStream()
                val output = sock.getOutputStream()
                val start = TuyaCodec.decode(TuyaCodec.readFrame(input), key)
                check(start.cmd == TuyaCommand.SESS_KEY_NEG_START)
                val localNonce = start.payload
                val remoteNonce = ByteArray(16) { (it * 7 + 3).toByte() }
                output.write(
                    TuyaCodec.encode(
                        TuyaCommand.SESS_KEY_NEG_RESP, start.seq,
                        remoteNonce + TuyaCrypto.hmacSha256(key, localNonce), key, retCode = 0,
                    ),
                )
                val finish = TuyaCodec.decode(TuyaCodec.readFrame(input), key)
                check(finish.cmd == TuyaCommand.SESS_KEY_NEG_FINISH)
                assertArrayEquals(TuyaCrypto.hmacSha256(key, remoteNonce), finish.payload)

                val xor = ByteArray(16) { (localNonce[it].toInt() xor remoteNonce[it].toInt()).toByte() }
                val session = TuyaCrypto.aesEcb(key, xor, encrypt = true, pkcs7 = false)

                var power = true
                var brightness = 500L
                while (true) {
                    val msg = TuyaCodec.decode(TuyaCodec.readFrame(input), session)
                    val text = msg.payload.toString(Charsets.UTF_8)
                    received += text
                    when (msg.cmd) {
                        TuyaCommand.DP_QUERY -> {
                            val body = """{"devId":"dev123","dps":{"20":$power,"22":$brightness}}"""
                            output.write(TuyaCodec.encode(TuyaCommand.DP_QUERY, msg.seq, body.toByteArray(), session, 0))
                        }
                        TuyaCommand.CONTROL_NEW -> {
                            @Suppress("UNCHECKED_CAST")
                            val dps = ((MiniJson.parse(text) as Map<String, Any?>)["data"] as Map<String, Any?>)["dps"]
                                as Map<String, Any?>
                            (dps["20"] as? Boolean)?.let { power = it }
                            (dps["22"] as? Long)?.let { brightness = it }
                            output.write(TuyaCodec.encode(TuyaCommand.CONTROL_NEW, msg.seq, ByteArray(0), session, 0))
                        }
                    }
                    output.flush()
                }
            }
        } catch (_: Exception) {
            // client closed or handshake rejected
        }
    }
}
