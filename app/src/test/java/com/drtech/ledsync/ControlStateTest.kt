package com.drtech.ledsync

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class ControlStateTest {
    @Test fun offlineAndUnconfiguredControlsNeverPretendToBeAvailable() {
        for (phase in listOf(ConnectionPhase.NEEDS_SETUP, ConnectionPhase.CONNECTING, ConnectionPhase.ERROR)) {
            assertFalse(ControllerState(phase = phase, supportedFeatures = setOf(Feature.POWER)).supports(Feature.POWER))
        }
        assertTrue(ControllerState(phase = ConnectionPhase.CONNECTED, supportedFeatures = setOf(Feature.POWER)).supports(Feature.POWER))
        assertFalse(ControllerState(phase = ConnectionPhase.CONNECTED, supportedFeatures = setOf(Feature.POWER)).supports(Feature.AMBIENT_LEVEL))
    }
    @Test fun cecAndQuickControlAlwaysUseTheSameReportedState() {
        for (enabled in listOf(true, false, true)) {
            val s = reduceDeviceState(ControllerState(tvSyncEnabled = !enabled), mapOf("109" to enabled))
            assertEquals(enabled, s.sceneModeCecEnabled)
            assertEquals(s.tvSyncEnabled, s.sceneModeCecEnabled)
        }
        val previous = ControllerState(tvSyncEnabled = true)
        assertTrue(reduceDeviceState(previous, mapOf("22" to 500)).sceneModeCecEnabled)
    }

    @Test fun scheduleClockFormatHandlesMidnightNoonAndEvening() {
        assertEquals("12:00 AM", clockTimeLabel(0, false))
        assertEquals("12:00 PM", clockTimeLabel(12 * 60, false))
        assertEquals("7:00 PM", clockTimeLabel(19 * 60, false))
        assertEquals("11:45 PM", clockTimeLabel(23 * 60 + 45, false))
        assertEquals("00:00", clockTimeLabel(0, true))
        assertEquals("19:00", clockTimeLabel(19 * 60, true))
        assertEquals("7:00 PM", DailySchedule(true, 19 * 60).label())
    }

    @Test fun colorEncodingMatchesPrimaryColorsAndClamps() {
        assertEquals("000003E803E8", CustomColor(0, 100, 100).toWire())
        assertEquals("007803E803E8", CustomColor(120, 100, 100).toWire())
        assertEquals("00F003E803E8", CustomColor(240, 100, 100).toWire())
        assertEquals("0168000003E8", CustomColor(999, -10, 200).toWire())
    }

    @Test fun reportedColorOverridesSavedPickerColor() {
        val next = reduceDeviceState(ControllerState(customColor = CustomColor(200)), mapOf("24" to "007803E803E8"))
        assertEquals(CustomColor(120, 100, 100), next.customColor)
        assertEquals(ConnectionPhase.CONNECTED, next.phase)
    }

    @Test fun malformedColorCannotReplacePickerState() {
        val original = ControllerState(customColor = CustomColor(30, 25, 70))
        for (raw in listOf("", "not-a-colour", "FFFF03E803E8", "0000FFFF03E8", "000003E8FFFF")) {
            assertNull(CustomColor.fromWire(raw))
            assertEquals(original.customColor, reduceDeviceState(original, mapOf("24" to raw)).customColor)
        }
    }

    @Test fun missingValuesAreUnknownDespiteSuccessfulRequests() {
        val s = reduceDeviceState(ControllerState(wirelessChannel = WirelessChannel.CH1,
            ambientBacklightMode = AmbientBacklightMode.ALWAYS, musicSensitivity = MusicSensitivity.LOW,
            blackBarMode = BlackBarMode.AUTO, lastSentChannel = WirelessChannel.CH2), mapOf("20" to true))
        assertNull(s.wirelessChannel)
        assertNull(s.ambientBacklightMode)
        assertNull(s.musicSensitivity)
        assertNull(s.blackBarMode)
        assertEquals(WirelessChannel.CH2, s.lastSentChannel)
    }

    @Test fun reportedValuesWinOverLastRequests() {
        val s = reduceDeviceState(ControllerState(lastSentChannel = WirelessChannel.CH2),
            mapOf("120" to "CH3", "121" to "Off", "115" to "middle", "117" to "WIDE"))
        assertEquals(WirelessChannel.CH3, s.wirelessChannel)
        assertEquals(AmbientBacklightMode.OFF, s.ambientBacklightMode)
        assertEquals(MusicSensitivity.MIDDLE, s.musicSensitivity)
        assertEquals(BlackBarMode.WIDE, s.blackBarMode)
    }

    @Test fun modeReportsAreMutuallyExclusive() {
        val s = reduceDeviceState(ControllerState(sceneMode = SceneMode.MOVIE), mapOf("25" to sceneDataFor(SoundMode.ROCK.code)))
        assertNull(s.sceneMode)
        assertEquals(SoundMode.ROCK, s.soundMode)
    }

    @Test fun onlyLocalNetworkAddressesAreAcceptedForTheBox() {
        for (ip in listOf("192.168.1.50", "10.0.0.7", "172.16.0.1", "172.31.255.254", "169.254.3.4", "100.64.0.9", "127.0.0.1")) {
            assertTrue(ip, isLocalNetworkAddress(ip))
        }
        for (ip in listOf("8.8.8.8", "203.0.113.5", "172.15.0.1", "172.32.0.1", "100.63.0.1", "100.128.0.1", "192.169.1.1", "1.2.3", "a.b.c.d", "")) {
            assertFalse(ip, isLocalNetworkAddress(ip))
        }
    }

    @Test fun presetsRoundTripAndRejectCorruption() {
        val p = Preset(46, 0, false, true, 100, SceneMode.COLOR.code, CustomColor(240, 80, 30))
        assertEquals(p, Preset.decode(p.encode()))
        assertNull(Preset.decode("46,0,0,1,100,128,200,100,100,broken"))
        assertNull(Preset.decode("46,0,2,1,100,128,200,100,100"))
        assertNull(Preset.decode("46,0,0,1,100,255,200,100,100"))
    }

    @Test fun dailyAlarmsAdvanceAndUseTheSelectedLocalTime() {
        val zone = TimeZone.getTimeZone("America/Chicago")
        val now = Calendar.getInstance(zone).apply { set(2026, Calendar.OCTOBER, 14, 19, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val next = Calendar.getInstance(zone).apply { timeInMillis = ScheduleManager.nextOccurrence(19 * 60, now, zone) }
        assertEquals(15, next.get(Calendar.DAY_OF_MONTH))
        assertEquals(19, next.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, next.get(Calendar.MINUTE))
        assertTrue(ScheduleManager.nextOccurrence(23 * 60, now, zone) > now)
    }
}
