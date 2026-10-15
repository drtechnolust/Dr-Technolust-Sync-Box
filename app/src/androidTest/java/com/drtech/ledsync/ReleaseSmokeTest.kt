package com.drtech.ledsync

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Destructive setup checks are restricted to a separate release-check package. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ReleaseSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun isolatedInstallOnly() {
        check(context.packageName.endsWith(".releasecheck")) { "Run only with -PreleaseCheck=true" }
        LocalDeviceConfigStore(context).clear()
        context.getSharedPreferences("ledsync_ui", 0).edit().clear().commit()
        ScheduleManager.cancelSleep(context)
        ScheduleManager.save(context, Schedules())
    }

    @After fun cleanup() {
        scenario?.close()
        if (context.packageName.endsWith(".releasecheck")) LocalDeviceConfigStore(context).clear()
    }

    private fun open() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        compose.waitUntil(10_000) {
            runCatching { compose.onAllNodesWithText("Set up your Sync Box").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
    }

    @Test fun firstRunValidatesFieldsAndTheSetupDialogOwnsFocus() {
        open()
        compose.onNodeWithText("Save and connect").performTouchInput { click() }
        compose.onNodeWithText("Enter the box's IP address, for example 192.168.1.50.").assertExists()
        compose.onNodeWithText("Box IP address").performTextInput("127.0.0.1")
        compose.onNodeWithText("Device ID").performTextInput("testdevice1234567890")
        compose.onNodeWithText("Local key").performTextInput("too-short")
        compose.onNodeWithText("Save and connect").performTouchInput { click() }
        compose.onNodeWithText("The local key is exactly 16 characters.").assertExists()
        // Navigate with the remote from the last field, then submit with OK.
        compose.onNodeWithText("Local key").performTextReplacement("0123456789abcdef")
        compose.onNodeWithText("Local key").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Local key").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("Save and connect").assert(hasAnyAncestor(isFocused()) or isFocused())
        compose.onNodeWithText("Save and connect").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        compose.waitUntil(5000) { LocalDeviceConfigStore(context).load() != null }
        compose.onNodeWithText("Sync Box connection").assertExists()
        compose.onNodeWithText("Forget box").performTouchInput { click() }
        compose.onNodeWithText("Forget this box").assertExists()
        assertNotNull("Opening the confirmation must not delete the key", LocalDeviceConfigStore(context).load())
        compose.onNodeWithText("Keep box").performTouchInput { click() }
        compose.onNodeWithText("Close").performTouchInput { click() }
        compose.onNodeWithText("Sync Box connection").assertDoesNotExist()
        compose.onNodeWithText("AWAITING CONNECTION").assertExists()
        compose.onNodeWithText("Sound").performTouchInput { click() }
        compose.onNodeWithText("Classic").assertExists()
    }

    @Test fun allSettingsPagesCanOpenWithoutABoxAndAboutIsBranded() {
        open()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("Settings", useUnmergedTree = true).performTouchInput { click() }
        for (page in listOf("Installation", "Ambient light", "Color", "Presets", "Timers", "Schedule", "About Dr.Technolust Sync Box")) {
            compose.onNodeWithText(page).performTouchInput { click() }
            compose.waitForIdle()
            if (page == "About Dr.Technolust Sync Box") compose.onNodeWithText("Developed by Dr. Technolust").assertExists()
            if (page == "Schedule") {
                compose.onNodeWithText("Time format: 12-hour").performTouchInput { click() }
                compose.onNodeWithText("19:00").assertExists()
                compose.onNodeWithText("Time format: 24-hour").performTouchInput { click() }
                compose.onNodeWithText("7:00 PM").assertExists()
            }
            compose.onNodeWithText("‹  Settings").performTouchInput { click() }
        }
        assertFalse(BuildConfig.DEBUG)
    }

    @Test fun encryptedConfigurationSurvivesReopeningAndCanBeForgotten() {
        val store = LocalDeviceConfigStore(context)
        store.save("127.0.0.1", "testdevice1234567890", "0123456789abcdef")
        val reopened = requireNotNull(LocalDeviceConfigStore(context).load())
        assertEquals("127.0.0.1", reopened.host)
        assertArrayEquals("0123456789abcdef".toByteArray(), reopened.localKey)
        val raw = java.io.File(context.filesDir, "local-device.sealed").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("Stored configuration must be encrypted", raw.contains("0123456789abcdef"))
        try { store.save("", "testdevice1234567890", "0123456789abcdef"); fail("Invalid replacement accepted") } catch (_: IllegalArgumentException) { }
        assertNotNull(store.load())
        store.clear()
        assertNull(store.load())
    }
}
