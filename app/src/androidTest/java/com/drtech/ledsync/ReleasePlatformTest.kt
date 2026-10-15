package com.drtech.ledsync

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleasePlatformTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName.endsWith(".releasecheck"))
    }

    @Test fun releaseRejectsTheDevelopmentOnlyPlaintextImport() {
        assertFalse(BuildConfig.DEBUG)
        val store = LocalDeviceConfigStore(context)
        val original = store.load()
        // Only run this on a clean QA install, never erase an existing live config.
        org.junit.Assume.assumeTrue(original == null)
        val file = java.io.File(context.filesDir, "local-device.import.json")
        try {
            file.writeText("""{"host":"127.0.0.1","deviceId":"testdevice1234567890","localKey":"${"0".repeat(16)}"}""")
            assertNull(store.load())
        } finally { file.delete() }
    }

    @Test fun alarmsUseTheGrantedOrDeniedPermissionPathAndRearmAfterSystemEvents() {
        val expected = InstrumentationRegistry.getArguments().getString("expectExact")?.toBooleanStrictOrNull()
        if (expected != null) assertEquals(expected, ScheduleManager.canScheduleExactly(context))
        val saved = ScheduleManager.load(context)
        val sleep = ScheduleManager.sleepDeadline(context)
        try {
            ScheduleManager.save(context, Schedules(DailySchedule(true, 1200), DailySchedule(true, 1320)))
            ScheduleManager.startSleep(context, 10)
            val deadline = ScheduleManager.sleepDeadline(context)
            for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
                ScheduleReceiver().onReceive(context, Intent(action))
                assertEquals(deadline, ScheduleManager.sleepDeadline(context))
                assertTrue(ScheduleManager.load(context).on.enabled)
            }
        } finally {
            ScheduleManager.cancelSleep(context)
            ScheduleManager.save(context, saved)
            if (sleep > 0) ScheduleManager.setSleepDeadline(context, sleep)
        }
    }
}
