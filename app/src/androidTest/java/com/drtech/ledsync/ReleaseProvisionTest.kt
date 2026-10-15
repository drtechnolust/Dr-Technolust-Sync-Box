package com.drtech.ledsync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.net.ServerSocket
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Test-only ADB-forwarded provisioning. No secrets in APKs, files, arguments or logs. */
@RunWith(AndroidJUnit4::class)
class ReleaseProvisionTest {
    @Test fun receiveConfigurationOverAdbLoopback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".releasecheck"))
        val token = requireNotNull(InstrumentationRegistry.getArguments().getString("provisionToken"))
        check(token.length >= 32)
        ServerSocket(39017, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 60_000
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val bytes = ArrayList<Byte>()
                val input = socket.getInputStream()
                while (bytes.size < 4096) {
                    val b = input.read()
                    if (b == -1 || b == 10) break
                    bytes.add(b.toByte())
                }
                val data = JSONObject(bytes.toByteArray().toString(Charsets.UTF_8))
                check(data.getString("token") == token) { "Provisioning token rejected" }
                val store = LocalDeviceConfigStore(context)
                store.save(data.getString("host"), data.getString("deviceId"), data.getString("localKey"))
                assertNotNull(store.load())
                socket.getOutputStream().write("OK\n".toByteArray())
            }
        }
    }
}
