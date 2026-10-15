package com.drtech.ledsync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomationDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun presetPersistenceAndRecallNeverSwitchHdmi() = runBlocking {
        val prefs = context.getSharedPreferences("ledsync_ui", 0)
        val original = prefs.getString("preset_3", null)
        val calls = mutableListOf<String>()
        val state = MutableStateFlow(ControllerState(phase = ConnectionPhase.CONNECTED))
        val fake = object : SyncBoxProvider by UnconfiguredProvider() {
            override val state = state
            override suspend fun setPower(enabled: Boolean) { calls += "power" }
            override suspend fun setBrightness(percent: Int) { calls += "brightness" }
            override suspend fun setDiffusionDegree(percent: Int) { calls += "diffusion" }
            override suspend fun setTvSync(enabled: Boolean) { calls += "tvSync" }
            override suspend fun setHdrAuto(enabled: Boolean) { calls += "hdr" }
            override suspend fun setColorEnhance(percent: Int) { calls += "enhance" }
            override suspend fun selectInput(input: HdmiInput) { fail("Presets must never switch HDMI") }
            override suspend fun setCustomColor(color: CustomColor) { calls += "color" }
        }
        try {
            val preset = Preset(46, 0, false, true, 100, SceneMode.COLOR.code, CustomColor(120, 70, 40))
            PresetStore(context).save(3, preset)
            assertEquals(preset, PresetStore(context).load()[3])
            assertTrue(applyLook(fake, requireNotNull(PresetStore(context).load()[3])))
            assertEquals(listOf("power", "brightness", "diffusion", "tvSync", "hdr", "enhance", "color"), calls)
            calls.clear()
            state.value = state.value.copy(phase = ConnectionPhase.ERROR)
            assertFalse(applyLook(fake, preset))
            assertEquals(listOf("power"), calls)
            PresetStore(context).clear(3)
            assertNull(PresetStore(context).load()[3])
        } finally {
            if (original == null) prefs.edit().remove("preset_3").commit()
            else prefs.edit().putString("preset_3", original).commit()
        }
    }

    @Test fun persistedTimersAndSchedulesCanBeRearmedAndCancelled() {
        val originalSchedule = ScheduleManager.load(context)
        val originalSleep = ScheduleManager.sleepDeadline(context)
        try {
            val future = System.currentTimeMillis() + 600_000
            ScheduleManager.setSleepDeadline(context, future)
            ScheduleManager.rearm(context)
            assertEquals(future, ScheduleManager.sleepDeadline(context))
            ScheduleManager.cancelSleep(context)
            assertEquals(0L, ScheduleManager.sleepDeadline(context))
            val schedule = Schedules(DailySchedule(false, 75), DailySchedule(false, 1425))
            ScheduleManager.save(context, schedule)
            assertEquals(schedule, ScheduleManager.load(context))
        } finally {
            ScheduleManager.save(context, originalSchedule)
            if (originalSleep > 0) ScheduleManager.setSleepDeadline(context, originalSleep)
        }
    }
}
