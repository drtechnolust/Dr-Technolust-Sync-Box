package com.drtech.ledsync

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.drtech.tuya.TuyaLanClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: -e hardware true. No identifiers or keys appear in test output. */
@RunWith(AndroidJUnit4::class)
class LiveControlTest {
    @Test fun controlsAndBackgroundSleepRoundTripWithRestoration(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hardware") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = requireNotNull(LocalDeviceConfigStore(context).load()) { "Set up the box before live testing" }
        fun client() = TuyaLanClient(config.host, config.deviceId, config.localKey)
        val original = client().use { it.connect(); it.queryDps().toMap() }
        val originalSleep = ScheduleManager.sleepDeadline(context)
        val originalLocal = context.getSharedPreferences("ledsync_local", 0).all.toMap()
        val uiPrefs = context.getSharedPreferences("ledsync_ui", 0)
        val originalAutoOn = uiPrefs.getBoolean("auto_on", false)
        val hadAutoOn = uiPrefs.contains("auto_on")
        val vmStore = androidx.lifecycle.ViewModelStore()
        val originalSchedule = ScheduleManager.load(context)
        val provider = LocalSyncBoxProvider(config)
        try {
            withTimeout(20_000) { provider.state.first { it.phase == ConnectionPhase.CONNECTED } }
            val saved = Preset.capture(provider.state.value)
            for (color in listOf(CustomColor(0, 100, 50), CustomColor(120, 100, 50), CustomColor(240, 100, 50))) {
                provider.setCustomColor(color)
                assertEquals(ConnectionPhase.CONNECTED, provider.state.value.phase)
                assertEquals("Picker must follow the device report", color, provider.state.value.customColor)
                assertEquals(SceneMode.COLOR, provider.state.value.sceneMode)
            }
            val nextBrightness = if (saved.brightness == 100) 99 else saved.brightness + 1
            val nextDiffusion = if (saved.diffusion == 100) 99 else saved.diffusion + 1
            provider.setBrightness(nextBrightness)
            assertEquals("Brightness supports one-percent steps", nextBrightness, provider.state.value.brightnessPercent)
            provider.setDiffusionDegree(nextDiffusion)
            assertEquals("Diffusion supports one-percent steps", nextDiffusion, provider.state.value.diffusionDegreePercent)
            assertTrue(applyLook(provider, saved))
            assertEquals(saved.brightness, provider.state.value.brightnessPercent)
            assertEquals(saved.diffusion, provider.state.value.diffusionDegreePercent)
            assertEquals(saved.modeCode, provider.state.value.sceneMode?.code ?: provider.state.value.soundMode?.code)
            assertEquals((original["105"] as Number).toInt(), provider.state.value.selectedInput.ordinal)
            for (mode in SceneMode.entries) {
                provider.selectScene(mode)
                assertEquals("Scene readback: ${mode.label}", mode, provider.state.value.sceneMode)
                assertNull(provider.state.value.soundMode)
            }
            for (mode in SoundMode.entries) {
                provider.selectSoundMode(mode)
                assertEquals("Sound readback: ${mode.label}", mode, provider.state.value.soundMode)
                assertNull(provider.state.value.sceneMode)
            }
            if (original["101"] != null) {
                val direction = if ((original["101"] as Number).toInt() == 0) StripDirection.RIGHT else StripDirection.LEFT
                provider.setStripDirection(direction)
                assertEquals(direction, provider.state.value.stripDirection)
            }
            if (original["112"] is Boolean) {
                provider.setHdrAuto(original["112"] != true)
                assertEquals(original["112"] != true, provider.state.value.hdrAutoEnabled)
            }
            if (original["113"] is Number) {
                val enhancement = if (saved.colorEnhance == 100) 99 else saved.colorEnhance + 1
                provider.setColorEnhance(enhancement)
                assertEquals(enhancement, provider.state.value.colorEnhancePercent)
            }
            for (level in MusicSensitivity.entries) {
                if (original["115"] != null) {
                    provider.setMusicSensitivity(level)
                    assertEquals(level, provider.state.value.lastSentSensitivity)
                    assertEquals("Music sensitivity readback", level, provider.state.value.musicSensitivity)
                    assertEquals(ConnectionPhase.CONNECTED, provider.state.value.phase)
                }
            }
            for (mode in BlackBarMode.entries) {
                if (original["117"] != null) {
                    provider.setBlackBarMode(mode)
                    assertEquals(mode, provider.state.value.lastSentBlackBars)
                    assertEquals("Black-bar mode readback", mode, provider.state.value.blackBarMode)
                    assertEquals(ConnectionPhase.CONNECTED, provider.state.value.phase)
                }
            }
            Log.i("LedSyncVerification", "Color readback, preset recall, scene/sound readback and unchanged HDMI passed. Sensitivity/black-bars restorable=${original["115"] != null}/${original["117"] != null}")
            provider.close()
            // Real AlarmManager -> receiver -> WorkManager -> TCP command; no activity is open.
            ScheduleManager.setSleepDeadline(context, System.currentTimeMillis() + 4_000)
            withTimeout(45_000) {
                while (ScheduleManager.sleepDeadline(context) != 0L) delay(500)
            }
            client().use { it.connect(); assertEquals(false, it.queryDps()["20"]) }
            Log.i("LedSyncVerification", "Persisted sleep alarm expired with no activity and lights-off readback passed")
            // Exercise the scheduled-on worker path too; restore schedule preferences below.
            ScheduleManager.save(context, originalSchedule.copy(on = originalSchedule.on.copy(enabled = true)))
            ScheduleManager.enqueue(context, ScheduleManager.ACTION_ON, System.currentTimeMillis())
            withTimeout(30_000) {
                var on = false
                while (!on) {
                    delay(1000)
                    on = client().use { it.connect(); it.queryDps()["20"] == true }
                }
            }
            client().use { it.connect(); check(it.setDps(mapOf("20" to false)) == 0) }
            uiPrefs.edit().putBoolean("auto_on", true).commit()
            lateinit var vm: ControllerViewModel
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                vm = androidx.lifecycle.ViewModelProvider(vmStore, androidx.lifecycle.ViewModelProvider.NewInstanceFactory())[ControllerViewModel::class.java]
            }
            withTimeout(20_000) { vm.state.first { it.phase == ConnectionPhase.CONNECTED && it.isPowered } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vmStore.clear() }
            client().use { it.connect(); assertEquals(true, it.queryDps()["20"]) }
            Log.i("LedSyncVerification", "Scheduled-on worker and auto-on at ViewModel startup passed")
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vmStore.clear() }
            if (hadAutoOn) uiPrefs.edit().putBoolean("auto_on", originalAutoOn).commit()
            else uiPrefs.edit().remove("auto_on").commit()
            provider.close()
            ScheduleManager.cancelSleep(context)
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork(ScheduleManager.ACTION_ON).result.get()
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork(ScheduleManager.ACTION_OFF).result.get()
            ScheduleManager.save(context, originalSchedule)
            client().use { c ->
                c.connect()
                // Restore exact original payloads, including the full scene blob.
                for (dp in listOf("22", "101", "108", "109", "112", "113", "115", "117", "24", "25", "20")) {
                    if (original.containsKey(dp)) check(c.setDps(mapOf(dp to original[dp])) == 0)
                }
                val restored = c.queryDps()
                for (dp in listOf("20", "22", "24", "25", "101", "105", "108", "109", "112", "113", "115", "117")) {
                    if (original.containsKey(dp)) assertEquals("Restoration failed for DP $dp", original[dp], restored[dp])
                }
            }
            context.getSharedPreferences("ledsync_local", 0).edit().clear().apply {
                originalLocal.forEach { (key, value) -> if (value is String) putString(key, value) }
            }.commit()
            if (originalSleep > 0) ScheduleManager.setSleepDeadline(context, originalSleep)
            Log.i("LedSyncVerification", "Original box settings and automation preferences restored")
        }
    }
}
