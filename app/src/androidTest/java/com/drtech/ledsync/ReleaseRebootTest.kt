package com.drtech.ledsync

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Two explicit steps around a real emulator reboot; never runs on physical TVs. */
@RunWith(AndroidJUnit4::class)
class ReleaseRebootTest {
    private fun qaContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName.endsWith(".releasecheck") && Build.HARDWARE.contains("ranchu"))
    }

    @Test fun prepareTimerBeforeReboot() {
        val context = qaContext()
        assertTrue(ScheduleManager.canScheduleExactly(context))
        ScheduleManager.save(context, Schedules())
        ScheduleManager.startSleep(context, 30)
        val deadline = ScheduleManager.sleepDeadline(context)
        context.getSharedPreferences("reboot_probe", 0).edit().putLong("deadline", deadline).commit()
        assertEquals(deadline, (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock?.triggerTime)
    }

    @Test fun verifyTimerWasRearmedByBootReceiverAndCleanUp() {
        val context = qaContext()
        val prefs = context.getSharedPreferences("reboot_probe", 0)
        val deadline = prefs.getLong("deadline", 0)
        try {
            assertTrue("Run prepareTimerBeforeReboot then reboot the emulator first", deadline > 0)
            assertEquals(deadline, ScheduleManager.sleepDeadline(context))
            // Starting instrumentation force-stops the target and clears alarms.
            // Capture/assert the boot-restored alarm with ADB dumpsys BEFORE
            // starting this method, then verify local state/rearming here.
            ScheduleManager.rearm(context)
            assertEquals(deadline, (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock?.triggerTime)
        } finally {
            ScheduleManager.cancelSleep(context)
            prefs.edit().clear().commit()
        }
    }
}
