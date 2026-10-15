package com.drtech.ledsync

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.drtech.tuya.TuyaLanClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Controls only DP 109. Does not send HDMI standby, wake, or source commands. */
@RunWith(AndroidJUnit4::class)
class CecControlTest {
    @Test fun cecBothWaysAndLegacyQuickControlReadBackOnTheBox(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hardware") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = requireNotNull(LocalDeviceConfigStore(context).load())
        fun client() = TuyaLanClient(config.host, config.deviceId, config.localKey)
        val original = client().use { it.connect(); it.queryDps().toMap() }
        val originalCec = original["109"] as? Boolean ?: error("DP 109 must be reported before testing CEC")
        val provider = LocalSyncBoxProvider(config)
        try {
            withTimeout(20_000) { provider.state.first { it.phase == ConnectionPhase.CONNECTED } }
            assertTrue(provider.state.value.supports(Feature.CEC))
            for (enabled in listOf(!originalCec, originalCec)) {
                provider.setSceneModeCec(enabled)
                assertEquals(ConnectionPhase.CONNECTED, provider.state.value.phase)
                assertEquals(enabled, provider.state.value.sceneModeCecEnabled)
                assertEquals(enabled, provider.state.value.tvSyncEnabled)
            }
            provider.setTvSync(!originalCec)
            assertEquals(!originalCec, provider.state.value.sceneModeCecEnabled)
            provider.setSceneModeCec(originalCec)
            assertEquals(originalCec, provider.state.value.sceneModeCecEnabled)
        } finally {
            provider.close()
            client().use { c ->
                c.connect()
                check(c.setDps(mapOf("109" to originalCec)) == 0)
                val restored = c.queryDps()
                assertEquals("CEC original value restored", originalCec, restored["109"])
                for (dp in listOf("20", "25", "105")) {
                    if (original.containsKey(dp)) assertEquals("Unrelated DP $dp changed", original[dp], restored[dp])
                }
            }
        }
        Log.i("LedSyncVerification", "CEC on/off and quick-control readback passed on DP 109; original CEC state restored; power, scene and HDMI unchanged")
    }
}
