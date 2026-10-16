package com.drtech.ledsync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drtech.tuya.DiscoveredDevice
import com.drtech.tuya.TuyaDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Progress of a network scan for Sync Boxes. */
sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState
    data class Done(val devices: List<DiscoveredDevice>) : ScanState
}

/**
 * True for addresses that can only be on a local network: loopback, private (RFC 1918),
 * link-local and carrier-grade NAT (used by Tailscale). Keeps the key and commands from
 * being sent to an arbitrary public host through a typo or a spoofed announcement.
 */
internal fun isLocalNetworkAddress(ip: String): Boolean {
    val p = ip.split('.').map { it.toIntOrNull() ?: return false }
    if (p.size != 4) return false
    return when {
        p[0] == 10 || p[0] == 127 -> true
        p[0] == 192 && p[1] == 168 -> true
        p[0] == 172 && p[1] in 16..31 -> true
        p[0] == 169 && p[1] == 254 -> true
        p[0] == 100 && p[1] in 64..127 -> true
        else -> false
    }
}

class ControllerViewModel : ViewModel() {
    private val providerFlow = MutableStateFlow(LedSyncApplication.createProvider())
    private val provider: SyncBoxProvider get() = providerFlow.value

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ControllerState> = providerFlow
        .flatMapLatest { it.state }
        .stateIn(viewModelScope, SharingStarted.Eagerly, providerFlow.value.state.value)

    private val app get() = LedSyncApplication.instance
    private val presetStore = PresetStore(app)
    private val uiPrefs = app.getSharedPreferences("ledsync_ui", android.content.Context.MODE_PRIVATE)

    private val mutablePresets = MutableStateFlow(presetStore.load())
    val presets: StateFlow<List<Preset?>> = mutablePresets.asStateFlow()

    private val mutableSchedules = MutableStateFlow(ScheduleManager.load(app))
    val schedules: StateFlow<Schedules> = mutableSchedules.asStateFlow()

    private val mutableAutoOn = MutableStateFlow(uiPrefs.getBoolean("auto_on", false))
    val autoOn: StateFlow<Boolean> = mutableAutoOn.asStateFlow()

    private val mutableUse24Hour = MutableStateFlow(uiPrefs.getBoolean("use_24_hour", false))
    val use24Hour: StateFlow<Boolean> = mutableUse24Hour.asStateFlow()

    fun setUse24Hour(enabled: Boolean) {
        mutableUse24Hour.value = enabled
        uiPrefs.edit().putBoolean("use_24_hour", enabled).apply()
    }

    /** Display only: Android owns the persisted deadline and executes the alarm. */
    private val mutableSleep = MutableStateFlow<Int?>(null)
    val sleepRemaining: StateFlow<Int?> = mutableSleep.asStateFlow()

    init {
        // "Turn on when the app opens": once, after the first successful connection.
        viewModelScope.launch {
            state.first { it.phase == ConnectionPhase.CONNECTED }
            if (mutableAutoOn.value && !state.value.isPowered) provider.setPower(true)
        }
        ScheduleManager.rearm(app)
        viewModelScope.launch {
            while (true) {
                val deadline = ScheduleManager.sleepDeadline(app)
                mutableSleep.value = if (deadline == 0L) null else
                    ((deadline - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0).toInt()
                delay(1_000)
            }
        }
    }

    fun startSleepTimer(minutes: Int) {
        ScheduleManager.startSleep(app, minutes)
        mutableSleep.value = minutes * 60
    }

    fun cancelSleepTimer() {
        ScheduleManager.cancelSleep(app)
        mutableSleep.value = null
    }

    fun setAutoOn(enabled: Boolean) {
        mutableAutoOn.value = enabled
        uiPrefs.edit().putBoolean("auto_on", enabled).apply()
    }

    fun setSchedules(schedules: Schedules) {
        mutableSchedules.value = schedules
        ScheduleManager.save(app, schedules)
    }

    fun savePreset(slot: Int) {
        if (state.value.phase != ConnectionPhase.CONNECTED) return
        presetStore.save(slot, Preset.capture(state.value))
        mutablePresets.value = presetStore.load()
    }

    fun clearPreset(slot: Int) {
        presetStore.clear(slot)
        mutablePresets.value = presetStore.load()
    }

    /** Recalls a saved look. The HDMI input is never touched. */
    fun applyPreset(slot: Int) = viewModelScope.launch {
        val p = mutablePresets.value.getOrNull(slot) ?: return@launch
        if (state.value.phase == ConnectionPhase.CONNECTED) applyLook(provider, p)
    }

    private val mutableScan = MutableStateFlow<ScanState>(ScanState.Idle)
    val scan: StateFlow<ScanState> = mutableScan.asStateFlow()

    /** Listens for boxes announcing themselves on the local network. */
    fun startScan() {
        if (mutableScan.value is ScanState.Scanning) return
        mutableScan.value = ScanState.Scanning
        viewModelScope.launch {
            val devices = withContext(Dispatchers.IO) {
                runCatching { TuyaDiscovery.scan() }.getOrDefault(emptyList())
            }
            mutableScan.value = ScanState.Done(devices)
        }
    }

    /** Validates and stores a box, then connects. Returns an error message, or null on success. */
    fun saveBox(host: String, deviceId: String, key: String): String? {
        val ip = host.trim()
        val id = deviceId.trim()
        val localKey = key.trim()
        if (!IPV4.matches(ip)) return "Enter the box's IP address, for example 192.168.1.50."
        if (!isLocalNetworkAddress(ip)) return "That isn't a home-network address. Use the box's local IP, for example 192.168.1.50."
        if (id.length !in 15..32 || !id.all { it.isLetterOrDigit() }) return "Enter the device ID (letters and digits)."
        if (localKey.length != 16) return "The local key is exactly 16 characters."
        if (localKey.any { it.code !in 33..126 }) return "The local key must contain only printable ASCII characters."
        runCatching { LedSyncApplication.configStore().save(ip, id, localKey) }
            .onFailure { return "Couldn't save the settings on this device." }
        replaceProvider(LedSyncApplication.createProvider())
        return null
    }

    /** Forgets the stored box and returns to the setup state. */
    fun forgetBox() {
        LedSyncApplication.configStore().clear()
        replaceProvider(UnconfiguredProvider())
    }

    private fun replaceProvider(next: SyncBoxProvider) {
        val old = providerFlow.value
        providerFlow.value = next
        old.close()
    }

    override fun onCleared() {
        provider.close()
    }

    fun refresh() = viewModelScope.launch { provider.refresh() }

    fun setPower(enabled: Boolean) = viewModelScope.launch { provider.setPower(enabled) }

    fun selectInput(input: HdmiInput) = viewModelScope.launch { provider.selectInput(input) }

    fun setBrightness(percent: Int) = viewModelScope.launch {
        provider.setBrightness(percent.coerceIn(0, 100))
    }

    fun setDiffusion(mode: DiffusionMode) = viewModelScope.launch { provider.setDiffusion(mode) }
    fun setDiffusionDegree(percent: Int) = viewModelScope.launch { provider.setDiffusionDegree(percent) }
    fun selectSoundMode(mode: SoundMode) = viewModelScope.launch { provider.selectSoundMode(mode) }
    fun selectScene(mode: SceneMode) = viewModelScope.launch { provider.selectScene(mode) }
    fun setStripDirection(direction: StripDirection) = viewModelScope.launch { provider.setStripDirection(direction) }
    fun setSceneModeCec(enabled: Boolean) = viewModelScope.launch { provider.setSceneModeCec(enabled) }
    fun setWirelessChannel(channel: WirelessChannel) = viewModelScope.launch { provider.setWirelessChannel(channel) }
    fun setSaturation(level: SaturationLevel) = viewModelScope.launch { provider.setSaturation(level) }
    fun setAmbientBacklight(mode: AmbientBacklightMode) = viewModelScope.launch { provider.setAmbientBacklight(mode) }
    fun setAmbientBrightness(percent: Int) = viewModelScope.launch { provider.setAmbientBrightness(percent) }
    fun setTvSync(enabled: Boolean) = viewModelScope.launch { provider.setTvSync(enabled) }
    fun setHdrAuto(enabled: Boolean) = viewModelScope.launch { provider.setHdrAuto(enabled) }
    fun setColorEnhance(percent: Int) = viewModelScope.launch { provider.setColorEnhance(percent) }
    fun setColorCalibration(calibration: ColorCalibration) = viewModelScope.launch { provider.setColorCalibration(calibration) }
    fun setMusicSensitivity(level: MusicSensitivity) = viewModelScope.launch { provider.setMusicSensitivity(level) }
    fun setBlackBarMode(mode: BlackBarMode) = viewModelScope.launch { provider.setBlackBarMode(mode) }
    fun setCustomColor(color: CustomColor) = viewModelScope.launch { provider.setCustomColor(color) }

    private companion object {
        val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""")
    }
}
