package com.drtech.ledsync

import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.drtech.tuya.TuyaLanClient
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opens every page with live state but never presses a device-changing control.
 * PixelCopy captures our window without taking over the accessibility service. */
@RunWith(AndroidJUnit4::class)
class ReleaseNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun everyLiveScreenOpensAndBrowsingChangesNoDeviceSettings() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hardware") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".releasecheck"))
        val config = requireNotNull(LocalDeviceConfigStore(context).load())
        fun snapshot() = TuyaLanClient(config.host, config.deviceId, config.localKey).use { it.connect(); it.queryDps() }
        val before = snapshot()
        val showcase = InstrumentationRegistry.getArguments().getString("showcase") == "true"
        val timeline = org.json.JSONArray()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            compose.waitUntil(15_000) {
                runCatching { compose.onAllNodesWithText("Connected · Local").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
            }
            fun click(text: String) { compose.onNodeWithText(text).performTouchInput { click() }; compose.waitForIdle() }
            fun shot(name: String) {
                compose.waitForIdle()
                Thread.sleep(200)
                val latch = CountDownLatch(1)
                var result = -1
                var bitmap: Bitmap? = null
                scenario.onActivity { activity ->
                    val view = activity.window.decorView
                    bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    PixelCopy.request(activity.window, requireNotNull(bitmap), { result = it; latch.countDown() }, Handler(Looper.getMainLooper()))
                }
                assertTrue(latch.await(5, TimeUnit.SECONDS))
                assertEquals(PixelCopy.SUCCESS, result)
                val folder = File(context.getExternalFilesDir(null), "qa-screenshots").apply { mkdirs() }
                File(folder, "$name.png").outputStream().use { requireNotNull(bitmap).compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap?.recycle()
                timeline.put(org.json.JSONObject().put("screen", name).put("at", android.os.SystemClock.elapsedRealtime()))
                if (showcase) Thread.sleep(5000)
            }
            shot("01-sync")
            click("Sound"); shot("02-sound")
            click("Scene"); shot("03-scenes")
            click("Settings"); shot("04-settings")
            for ((label, file) in listOf("Installation" to "05-installation", "Ambient light" to "06-ambient")) {
                click(label); shot(file); click("‹  Settings")
            }
            click("Color"); shot("07-color")
            click("Color picker  ›"); shot("08-picker"); click("‹  Settings")
            click("Adjust color parameters  ›"); shot("09-calibration"); click("‹  Settings"); click("‹  Settings")
            for ((label, file) in listOf("Presets" to "10-presets", "Timers" to "11-timers", "Schedule" to "12-schedule", "About Dr.Technolust Sync Box" to "13-about")) {
                click(label); shot(file); click("‹  Settings")
            }
            timeline.put(org.json.JSONObject().put("screen", "end").put("at", android.os.SystemClock.elapsedRealtime()))
            File(File(context.getExternalFilesDir(null), "qa-screenshots"), "timeline.json").writeText(timeline.toString())
        }
        val after = snapshot()
        for (dp in listOf("20", "22", "24", "25", "101", "105", "108", "109", "112", "113")) {
            assertEquals("Browsing must not change DP $dp", before[dp], after[dp])
        }
    }
}
