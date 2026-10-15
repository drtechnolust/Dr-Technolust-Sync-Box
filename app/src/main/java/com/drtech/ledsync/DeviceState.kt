package com.drtech.ledsync

/** A query snapshot is authoritative. Command acknowledgements are not state reports. */
internal fun reduceDeviceState(state: ControllerState, dps: Map<String, Any?>): ControllerState {
    var s = state.copy(
        phase = ConnectionPhase.CONNECTED,
        message = "Connected locally to your Sync Box.",
        lastUpdatedMillis = System.currentTimeMillis(),
        wirelessChannel = (dps["120"] as? String)?.let { raw -> WirelessChannel.entries.firstOrNull { it.name == raw } },
        ambientBacklightMode = (dps["121"] as? String)?.let { raw -> backlightWire.entries.firstOrNull { it.value == raw }?.key },
        musicSensitivity = (dps["115"] as? String)?.let { raw -> MusicSensitivity.entries.firstOrNull { it.wire == raw } },
        blackBarMode = (dps["117"] as? String)?.let { raw -> BlackBarMode.entries.firstOrNull { it.wire == raw } },
    )
    (dps["20"] as? Boolean)?.let { s = s.copy(isPowered = it) }
    (dps["22"] as? Number)?.let { s = s.copy(brightnessPercent = (it.toInt() / 10).coerceIn(1, 100)) }
    (dps["105"] as? Number)?.toInt()?.let { idx -> HdmiInput.entries.getOrNull(idx)?.let { s = s.copy(selectedInput = it) } }
    (dps["108"] as? Number)?.let { s = s.copy(diffusionDegreePercent = (it.toInt() / 10).coerceIn(0, 100)) }
    (dps["24"] as? String)?.let { raw -> CustomColor.fromWire(raw)?.let { s = s.copy(customColor = it) } }
    (dps["25"] as? String)?.let { raw ->
        val code = raw.take(2).toIntOrNull(16)
        s = s.copy(sceneMode = SceneMode.entries.firstOrNull { it.code == code }, soundMode = SoundMode.entries.firstOrNull { it.code == code })
    }
    (dps["101"] as? Number)?.toInt()?.let { idx -> StripDirection.entries.getOrNull(idx)?.let { s = s.copy(stripDirection = it) } }
    (dps["113"] as? Number)?.let { s = s.copy(colorEnhancePercent = (it.toInt() / 10).coerceIn(0, 100)) }
    (dps["109"] as? Boolean)?.let { s = s.copy(tvSyncEnabled = it) }
    (dps["112"] as? Boolean)?.let { s = s.copy(hdrAutoEnabled = it) }
    return s
}

internal val backlightWire = mapOf(
    AmbientBacklightMode.MIXED to "Mixed", AmbientBacklightMode.ALWAYS to "On", AmbientBacklightMode.OFF to "Off",
)
