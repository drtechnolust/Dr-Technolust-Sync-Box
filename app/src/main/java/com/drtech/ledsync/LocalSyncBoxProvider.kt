package com.drtech.ledsync

import android.content.Context
import android.util.Log
import com.drtech.tuya.TuyaLanClient
import com.drtech.tuya.TuyaProtocolException
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Cloud-free provider: talks Tuya LAN protocol 3.4 straight to the Sync Box.
 *
 * Only data points confirmed by this product's cloud schema (h2nqrmaskyhckcmf,
 * see DP_MAP.md) are sent. Everything else in the UI stays preview-only until
 * its DP and value encoding are confirmed.
 *
 *   20  switch_led    boolean
 *   22  bright_value  integer 10..1000
 *   105 hdmi_switch   integer 0..2 (0 = HDMI 1)
 *   108 light_db      integer 0..1000, step 10 (diffusion degree)
 *   120 wireless_chn  enum "CH1" | "CH2" | "CH3"
 *   121 backlight_opt enum "Mixed" | "On" | "Off"
 */
class LocalSyncBoxProvider(
    config: LocalDeviceConfig,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SyncBoxProvider {
    private val host = config.host
    private val client = TuyaLanClient(config.host, config.deviceId, config.localKey)
    private val ioLock = Mutex()
    private val mutableState = MutableStateFlow(
        ControllerState(
            phase = ConnectionPhase.CONNECTING,
            message = "Connecting to your Sync Box…",
            supportedFeatures = SUPPORTED,
            colorCalibration = loadCalibration(),
            customColor = loadCustomColor(),
            connection = ConnectionInfo(
                transport = "Local network · Tuya LAN 3.4 · no cloud",
                host = config.host,
                deviceIdTail = config.deviceId.takeLast(6),
            ),
        ),
    )
    override val state: StateFlow<ControllerState> = mutableState.asStateFlow()

    init {
        scope.launch { pollLoop() }
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            val ok = runCatching {
                ioLock.withLock {
                    if (!client.isConnected) client.connect()
                    apply(client.queryDps())
                }
            }.onFailure {
                if (it is CancellationException) throw it
                client.close()
                Log.w(TAG, "poll failed: ${it.javaClass.simpleName}")
                mutableState.value = mutableState.value.copy(
                    phase = ConnectionPhase.ERROR,
                    message = friendlyError(it),
                )
            }.isSuccess
            delay(if (ok) POLL_MS else RETRY_MS)
        }
    }

    /** Plain-language failure text; never includes the key. */
    private fun friendlyError(error: Throwable): String = when {
        error is TuyaProtocolException && error.message?.contains("key", ignoreCase = true) == true ->
            "The box rejected the local key. Check the 16 characters and try again."
        error is java.net.ConnectException || error is java.net.SocketTimeoutException ->
            "Can't reach the box at ${host}. Check that it's powered on and on this network."
        else -> "Can't reach the Sync Box (${error.javaClass.simpleName}). Retrying…"
    }

    private fun apply(dps: Map<String, Any?>) {
        mutableState.value = reduceDeviceState(mutableState.value, dps)
    }

    private suspend fun send(dps: Map<String, Any?>, accepted: ControllerState.() -> ControllerState = { this }): Boolean =
        sendSequence(listOf(dps), accepted)

    private suspend fun sendSequence(commands: List<Map<String, Any?>>, accepted: ControllerState.() -> ControllerState): Boolean {
        // Callers run on the main dispatcher; socket I/O must not.
        return runCatching {
            withContext(Dispatchers.IO) {
                ioLock.withLock {
                    if (!client.isConnected) client.connect()
                    for (dps in commands) check(client.setDps(dps) == 0) { "Device rejected the command" }
                    mutableState.value = mutableState.value.accepted()
                    apply(client.queryDps())
                }
            }
        }.onFailure {
            if (it is CancellationException) throw it
            client.close()
            Log.w(TAG, "command failed: ${it.javaClass.simpleName}")
            mutableState.value = mutableState.value.copy(
                phase = ConnectionPhase.ERROR,
                message = "Command failed (${it.javaClass.simpleName}).",
            )
        }.isSuccess
    }

    override fun close() {
        scope.cancel()
        // Disposing/replacing the provider happens on the UI thread. Interrupt
        // the socket immediately; never wait here for a five-second read timeout.
        client.abort()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { client.close() }
    }

    override suspend fun refresh() {
        withContext(Dispatchers.IO) {
            runCatching {
                ioLock.withLock {
                    // Reconnect fresh so the button also recovers a stale session.
                    client.close()
                    client.connect()
                    apply(client.queryDps())
                }
            }.onFailure {
                if (it is CancellationException) throw it
                client.close()
                Log.w(TAG, "refresh failed: ${it.javaClass.simpleName}")
                mutableState.value = mutableState.value.copy(
                    phase = ConnectionPhase.ERROR,
                    message = "Can't reach the Sync Box (${it.javaClass.simpleName}).",
                )
            }
        }
    }

    override suspend fun setPower(enabled: Boolean) {
        send(mapOf("20" to enabled))
    }

    override suspend fun setBrightness(percent: Int) {
        val p = percent.coerceIn(1, 100)
        send(mapOf("22" to (p * 10).coerceIn(10, 1000)))
    }

    override suspend fun selectInput(input: HdmiInput) {
        send(mapOf("105" to input.ordinal))
    }

    override suspend fun setDiffusionDegree(percent: Int) {
        // light_db accepts 0..1000 in steps of 10, so 0 % is valid for diffusion.
        val p = percent.coerceIn(0, 100)
        send(mapOf("108" to p * 10))
    }

    override suspend fun setTvSync(enabled: Boolean) = setSceneModeCec(enabled)

    /** tv_sync_onoff is the FancyLEDs panel's Scene Mode CEC setting. */
    override suspend fun setSceneModeCec(enabled: Boolean) {
        send(mapOf("109" to enabled))
    }

    override suspend fun setHdrAuto(enabled: Boolean) {
        send(mapOf("112" to enabled))
    }

    override suspend fun setWirelessChannel(channel: WirelessChannel) {
        send(mapOf("120" to channel.name)) { copy(lastSentChannel = channel) }
    }

    override suspend fun setAmbientBacklight(mode: AmbientBacklightMode) {
        send(mapOf("121" to backlightWire.getValue(mode))) { copy(lastSentBacklight = mode) }
    }

    override suspend fun selectScene(mode: SceneMode) {
        send(mapOf("25" to sceneDataFor(mode.code)))
    }

    override suspend fun selectSoundMode(mode: SoundMode) {
        send(mapOf("25" to sceneDataFor(mode.code)))
    }

    override suspend fun setStripDirection(direction: StripDirection) {
        send(mapOf("101" to direction.ordinal))
    }

    override suspend fun setColorCalibration(calibration: ColorCalibration) {
        val c = ColorCalibration(
            calibration.r.coerceIn(ColorCalibration.MIN, ColorCalibration.MAX),
            calibration.g.coerceIn(ColorCalibration.MIN, ColorCalibration.MAX),
            calibration.b.coerceIn(ColorCalibration.MIN, ColorCalibration.MAX),
            calibration.w.coerceIn(ColorCalibration.MIN, ColorCalibration.MAX),
        )
        // Raw DP: four bytes, 100 = 0 %, sent base64-encoded as Tuya LAN raw values are.
        val bytes = byteArrayOf((100 + c.r).toByte(), (100 + c.g).toByte(), (100 + c.b).toByte(), (100 + c.w).toByte())
        if (send(mapOf("123" to Base64.getEncoder().encodeToString(bytes))) { copy(colorCalibration = c) }) saveCalibration(c)
    }

    /** The box accepts but never reports this value over the LAN, so remember it here. */
    private fun prefs() = LedSyncApplication.instance.getSharedPreferences("ledsync_local", Context.MODE_PRIVATE)

    private fun saveCalibration(c: ColorCalibration) {
        prefs().edit().putString("calibration", "${c.r},${c.g},${c.b},${c.w}").apply()
    }

    private fun loadCalibration(): ColorCalibration {
        val parts = prefs().getString("calibration", null)?.split(",")?.mapNotNull { it.toIntOrNull() }
        return if (parts != null && parts.size == 4) ColorCalibration(parts[0], parts[1], parts[2], parts[3]) else ColorCalibration()
    }

    override suspend fun setMusicSensitivity(level: MusicSensitivity) {
        send(mapOf("115" to level.wire)) { copy(lastSentSensitivity = level) }
    }

    override suspend fun setBlackBarMode(mode: BlackBarMode) {
        send(mapOf("117" to mode.wire)) { copy(lastSentBlackBars = mode) }
    }

    override suspend fun setCustomColor(color: CustomColor) {
        val c = CustomColor(color.hue.coerceIn(0, 360), color.saturation.coerceIn(0, 100), color.value.coerceIn(0, 100))
        // The Color scene (DP 25 first byte 0x97) shows whatever colour_data (DP 24) holds.
        // Two single-DP commands, the form already verified on this box.
        if (sendSequence(listOf(mapOf("25" to sceneDataFor(SceneMode.COLOR.code)), mapOf("24" to c.toWire()))) {
                copy(customColor = c)
            }) saveCustomColor(mutableState.value.customColor)
    }

    private fun saveCustomColor(c: CustomColor) {
        prefs().edit().putString("custom_color", "${c.hue},${c.saturation},${c.value}").apply()
    }

    private fun loadCustomColor(): CustomColor {
        val p = prefs().getString("custom_color", null)?.split(",")?.mapNotNull { it.toIntOrNull() }
        return if (p != null && p.size == 3) CustomColor(p[0], p[1], p[2]) else CustomColor()
    }

    override suspend fun setColorEnhance(percent: Int) {
        val p = percent.coerceIn(0, 100)
        send(mapOf("113" to p * 10))
    }

    // Schema confirms the DP exists but not its value encoding (or the UI-to-DP
    // mapping is ambiguous): UI state only, nothing is transmitted.
    override suspend fun setDiffusion(mode: DiffusionMode) = ui { copy(diffusionMode = mode) }
    override suspend fun setSaturation(level: SaturationLevel) = ui { copy(saturation = level) }
    override suspend fun setAmbientBrightness(percent: Int) = ui { copy(ambientBrightnessPercent = percent.coerceIn(0, 100)) }

    private inline fun ui(transform: ControllerState.() -> ControllerState) {
        mutableState.value = mutableState.value.transform()
    }

    private companion object {
        const val TAG = "LedSyncLocal"
        const val POLL_MS = 5_000L
        const val RETRY_MS = 10_000L

        /** Features whose DP and value encoding are confirmed by the product schema. */
        val SUPPORTED = setOf(
            Feature.POWER, Feature.BRIGHTNESS, Feature.INPUT, Feature.DIFFUSION_DEGREE,
            Feature.WIRELESS_CHANNEL, Feature.AMBIENT_BACKLIGHT, Feature.TV_SYNC, Feature.CEC, Feature.HDR_AUTO,
            Feature.SCENE, Feature.SOUND, Feature.STRIP_DIRECTION, Feature.COLOR_ENHANCE, Feature.CALIBRATION,
            Feature.MUSIC_SENSITIVITY, Feature.BLACK_BARS, Feature.CUSTOM_COLOR,
        )

    }
}
