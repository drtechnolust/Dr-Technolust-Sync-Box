package com.drtech.ledsync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.drtech.tuya.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real TCP provider recovery against a loopback simulator, not the owner's box. */
@RunWith(AndroidJUnit4::class)
class ReleaseRecoveryTest {
    @Test fun providerRecoversAfterTheDeviceDropsItsConnection() = runBlocking {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".releasecheck"))
        val key = ByteArray(16) { it.toByte() } // Public, deterministic simulator fixture; not a device key.
        val running = AtomicBoolean(true)
        val server = ServerSocket(6668, 4, InetAddress.getByName("127.0.0.1"))
        val active = java.util.concurrent.atomic.AtomicReference<Socket?>()
        val dropNextQuery = AtomicBoolean(true)
        val worker = thread(isDaemon = true) {
            while (running.get()) {
                try {
                    server.accept().use { socket ->
                        active.set(socket)
                        socket.soTimeout = 15_000
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        val start = TuyaCodec.decode(TuyaCodec.readFrame(input), key)
                        val nonce = ByteArray(16) { (it * 7 + 3).toByte() }
                        output.write(TuyaCodec.encode(TuyaCommand.SESS_KEY_NEG_RESP, start.seq,
                            nonce + javax.crypto.Mac.getInstance("HmacSHA256").run {
                                init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")); doFinal(start.payload)
                            }, key, 0))
                        output.flush()
                        val finish = TuyaCodec.decode(TuyaCodec.readFrame(input), key)
                        check(finish.cmd == TuyaCommand.SESS_KEY_NEG_FINISH)
                        val session = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding").run {
                            init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"))
                            doFinal(ByteArray(16) { (nonce[it].toInt() xor start.payload[it].toInt()).toByte() })
                        }
                        while (running.get()) {
                            val message = TuyaCodec.decode(TuyaCodec.readFrame(input), session)
                            if (message.cmd == TuyaCommand.DP_QUERY) {
                                if (dropNextQuery.getAndSet(false)) break
                                output.write(TuyaCodec.encode(TuyaCommand.DP_QUERY, message.seq,
                                    """{"dps":{"20":true,"22":500,"105":0,"108":500,"109":false,"112":false}}""".toByteArray(), session, 0))
                                output.flush()
                            }
                        }
                    }
                } catch (_: Exception) { } finally { active.set(null) }
            }
        }
        val provider = LocalSyncBoxProvider(LocalDeviceConfig("127.0.0.1", "testdevice1234567890", key))
        try {
            withTimeout(10_000) { provider.state.first { it.phase == ConnectionPhase.ERROR } }
            assertFalse(provider.state.value.supports(Feature.POWER))
            withTimeout(20_000) { provider.state.first { it.phase == ConnectionPhase.CONNECTED } }
            assertEquals(50, provider.state.value.brightnessPercent)
            active.get()?.close()
            provider.refresh()
            assertEquals(ConnectionPhase.CONNECTED, provider.state.value.phase)
            assertTrue(provider.state.value.supports(Feature.POWER))
            val started = System.nanoTime()
            provider.close()
            assertTrue("Closing a provider must not block the UI", System.nanoTime() - started < 500_000_000)
        } finally {
            provider.close()
            running.set(false)
            active.get()?.close()
            server.close()
            worker.join(2000)
        }
    }
}
