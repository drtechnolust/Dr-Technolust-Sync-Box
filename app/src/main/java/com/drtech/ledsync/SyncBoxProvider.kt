package com.drtech.ledsync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Boundary between the TV UI and a Sync Box.
 *
 * An implementation owns the connection to one device and translates typed UI
 * actions into device commands. The only shipping implementation talks to the
 * box directly over the local network; no account, cloud service or vendor SDK
 * is involved.
 */
interface SyncBoxProvider {
    val state: StateFlow<ControllerState>

    suspend fun refresh()
    suspend fun setPower(enabled: Boolean)
    suspend fun selectInput(input: HdmiInput)
    suspend fun setBrightness(percent: Int)
    suspend fun setDiffusion(mode: DiffusionMode)
    suspend fun setDiffusionDegree(percent: Int)
    suspend fun selectSoundMode(mode: SoundMode)
    suspend fun selectScene(mode: SceneMode)
    suspend fun setStripDirection(direction: StripDirection)
    suspend fun setSceneModeCec(enabled: Boolean)
    suspend fun setWirelessChannel(channel: WirelessChannel)
    suspend fun setSaturation(level: SaturationLevel)
    suspend fun setAmbientBacklight(mode: AmbientBacklightMode)
    suspend fun setAmbientBrightness(percent: Int)
    suspend fun setTvSync(enabled: Boolean)
    suspend fun setHdrAuto(enabled: Boolean)
    suspend fun setColorEnhance(percent: Int)
    suspend fun setColorCalibration(calibration: ColorCalibration)
    suspend fun setMusicSensitivity(level: MusicSensitivity)
    suspend fun setBlackBarMode(mode: BlackBarMode)

    /** Shows a static color: switches to the Color scene and writes `colour_data` (DP 24). */
    suspend fun setCustomColor(color: CustomColor)

    /** Releases sockets and background work. Called when the provider is replaced. */
    fun close() {}
}

enum class HdmiInput(val label: String) {
    HDMI_1("HDMI 1"),
    HDMI_2("HDMI 2"),
    HDMI_3("HDMI 3"),
}

enum class DiffusionMode(val label: String) { LOW("Low"), MEDIUM("Medium"), HIGH("High") }

/**
 * Sound-reactive modes, carried by `scene_data` (DP 25) as a 32-hex string whose
 * first byte selects the mode. Verified on a real box: DP 25 accepts and reports
 * back `80…` (movie) and `82…` (gaming).
 */
enum class SoundMode(val label: String, val code: Int) {
    CLASSIC("Classic", 0x98), POP("Pop", 0x99), ROCK("Rock", 0x9A),
    ABSOLUTE("Absolute", 0x9B), ELECTRONIC("Electronic", 0x9C), AMBIENT("Ambient", 0x9D),
}

enum class SceneGroup(val title: String) { SYNC("Sync mode"), SCENE("Scenes") }

/** Sync modes and animated scenes, also carried by DP 25 (first byte = [code]). */
enum class SceneMode(val label: String, val code: Int, val group: SceneGroup) {
    MOVIE("Movie", 0x80, SceneGroup.SYNC),
    LEISURE("Leisure", 0x81, SceneGroup.SYNC),
    GAMING("Gaming", 0x82, SceneGroup.SYNC),
    RAINBOW("Rainbow", 0x86, SceneGroup.SCENE),
    FIRE("Fire", 0x87, SceneGroup.SCENE),
    READ("Read", 0x88, SceneGroup.SCENE),
    FIREWORK("Firework", 0x89, SceneGroup.SCENE),
    STAR("Star", 0x90, SceneGroup.SCENE),
    DRIP("Drip", 0x91, SceneGroup.SCENE),
    PARTICLE("Particle", 0x92, SceneGroup.SCENE),
    FLOW("Flow", 0x93, SceneGroup.SCENE),
    BALL("Ball", 0x94, SceneGroup.SCENE),
    SWING("Swing", 0x95, SceneGroup.SCENE),
    BREATH("Breath", 0x96, SceneGroup.SCENE),
    COLOR("Color", 0x97, SceneGroup.SCENE),
}

/** 32-character DP 25 value for a mode byte: the byte followed by 30 zeros. */
fun sceneDataFor(code: Int): String = String.format("%02X", code) + "0".repeat(30)

/** `r_l` (DP 101): 0 = left, 1 = right. */
enum class StripDirection(val label: String) { LEFT("Left"), RIGHT("Right") }
enum class WirelessChannel(val label: String) { CH1("CH 1"), CH2("CH 2"), CH3("CH 3") }
enum class SaturationLevel(val label: String) { LOW("Low"), MEDIUM("Medium"), HIGH("High") }
enum class AmbientBacklightMode(val label: String) { MIXED("Mixed"), ALWAYS("Always"), OFF("Off") }

/** `music_sensitivity` (DP 115): wire values "low" | "middle" | "high". */
enum class MusicSensitivity(val label: String, val wire: String) {
    LOW("Low", "low"), MIDDLE("Medium", "middle"), HIGH("High", "high"),
}

/** `blackchk_rate` (DP 117): how black bars (letterbox) are detected; wire values "AUTO" | "FULL" | "WIDE". */
enum class BlackBarMode(val label: String, val wire: String) {
    AUTO("Auto", "AUTO"), FULL("Full screen", "FULL"), WIDE("Widescreen", "WIDE"),
}

/** A static color: hue 0..360, saturation 0..100 %, brightness 0..100 %. */
data class CustomColor(val hue: Int = 200, val saturation: Int = 100, val value: Int = 100) {
    /** `colour_data` (DP 24): `hhhhssssvvvv`, hue 0..360, saturation and value 0..1000, all hex. */
    fun toWire(): String = String.format(
        "%04X%04X%04X",
        hue.coerceIn(0, 360), (saturation.coerceIn(0, 100) * 10), (value.coerceIn(0, 100) * 10),
    )

    companion object {
        fun fromWire(raw: String): CustomColor? {
            if (!raw.matches(Regex("[0-9a-fA-F]{12}"))) return null
            val h = raw.substring(0, 4).toInt(16)
            val s = raw.substring(4, 8).toInt(16)
            val v = raw.substring(8, 12).toInt(16)
            if (h !in 0..360 || s !in 0..1000 || v !in 0..1000) return null
            return CustomColor(h, (s + 5) / 10, (v + 5) / 10)
        }
    }
}

/** Controls the TV UI can expose. A provider declares which ones actually reach the device. */
enum class Feature {
    POWER, BRIGHTNESS, INPUT, DIFFUSION_DEGREE, DIFFUSION_MODE, SOUND, SCENE,
    STRIP_DIRECTION, CEC, WIRELESS_CHANNEL, SATURATION, AMBIENT_BACKLIGHT, AMBIENT_LEVEL,
    TV_SYNC, HDR_AUTO, COLOR_ENHANCE, CALIBRATION, MUSIC_SENSITIVITY, BLACK_BARS, CUSTOM_COLOR,
}

/**
 * Per-channel color correction, each −10..+10 percent (0 = factory default).
 * On the box this is a 4-byte raw value `[100+r, 100+g, 100+b, 100+w]`
 * (`color_correction`, DP 123).
 */
data class ColorCalibration(val r: Int = 0, val g: Int = 0, val b: Int = 0, val w: Int = 0) {
    companion object {
        const val MIN = -10
        const val MAX = 10
    }
}

/** Where the device is reached from; shown on the connection screen. */
data class ConnectionInfo(
    val transport: String,
    val host: String,
    /** Last characters of the device ID only; the full ID is not displayed. */
    val deviceIdTail: String,
)

data class ControllerState(
    val phase: ConnectionPhase = ConnectionPhase.NEEDS_SETUP,
    /** The controls a provider can actually send; null means all (used by previews). */
    val supportedFeatures: Set<Feature>? = null,
    val connection: ConnectionInfo? = null,
    val lastUpdatedMillis: Long = 0L,
    val deviceName: String = "Sync Box",
    val isPowered: Boolean = true,
    val selectedInput: HdmiInput = HdmiInput.HDMI_1,
    val brightnessPercent: Int = 65,
    val diffusionMode: DiffusionMode = DiffusionMode.HIGH,
    val diffusionDegreePercent: Int = 100,
    /** The active mode (sync, scene or sound) comes from DP 25; at most one is set. */
    val soundMode: SoundMode? = null,
    /** Not reported by the device, so unknown until the user picks one. */
    val sceneMode: SceneMode? = null,
    val stripDirection: StripDirection = StripDirection.LEFT,
    val colorEnhancePercent: Int = 50,
    /** Not readable over the LAN; remembered from the last value set on this TV. */
    val colorCalibration: ColorCalibration = ColorCalibration(),
    /** Not reported over the LAN, so unknown until the user sets it from this app. */
    val wirelessChannel: WirelessChannel? = null,
    val lastSentChannel: WirelessChannel? = null,
    /** Unknown until a provider can read it; never shown as a made-up default. */
    val saturation: SaturationLevel? = null,
    /** Not reported over the LAN, so unknown until the user sets it from this app. */
    val ambientBacklightMode: AmbientBacklightMode? = null,
    val lastSentBacklight: AmbientBacklightMode? = null,
    /** Unknown until a provider can read it; never shown as a made-up default. */
    val ambientBrightnessPercent: Int? = null,
    /** DP 109: Scene Mode CEC. Legacy name retained for saved preset compatibility. */
    val tvSyncEnabled: Boolean = false,
    val hdrAutoEnabled: Boolean = false,
    /** Reported by the box only after it has been set locally, so unknown until then. */
    val musicSensitivity: MusicSensitivity? = null,
    val lastSentSensitivity: MusicSensitivity? = null,
    val blackBarMode: BlackBarMode? = null,
    val lastSentBlackBars: BlackBarMode? = null,
    /** The last color shown by the picker; remembered on this TV. */
    val customColor: CustomColor = CustomColor(),
    val message: String = "Set up your Sync Box to get started.",
) {
    /** Both UI entry points expose the same device setting, never separate state. */
    val sceneModeCecEnabled: Boolean get() = tvSyncEnabled
}

fun ControllerState.supports(feature: Feature): Boolean =
    phase == ConnectionPhase.CONNECTED && (supportedFeatures?.contains(feature) ?: true)

enum class ConnectionPhase {
    NEEDS_SETUP,
    CONNECTING,
    CONNECTED,
    ERROR,
}

/** Shown until a box has been set up: every control is disabled and nothing is sent. */
class UnconfiguredProvider : SyncBoxProvider {
    private val mutableState = MutableStateFlow(
        ControllerState(
            phase = ConnectionPhase.NEEDS_SETUP,
            supportedFeatures = emptySet(),
            message = "Set up your Sync Box to get started.",
        ),
    )
    override val state: StateFlow<ControllerState> = mutableState.asStateFlow()

    override suspend fun refresh() = Unit
    override suspend fun setPower(enabled: Boolean) = Unit
    override suspend fun selectInput(input: HdmiInput) = Unit
    override suspend fun setBrightness(percent: Int) = Unit
    override suspend fun setDiffusion(mode: DiffusionMode) = Unit
    override suspend fun setDiffusionDegree(percent: Int) = Unit
    override suspend fun selectSoundMode(mode: SoundMode) = Unit
    override suspend fun selectScene(mode: SceneMode) = Unit
    override suspend fun setStripDirection(direction: StripDirection) = Unit
    override suspend fun setSceneModeCec(enabled: Boolean) = Unit
    override suspend fun setWirelessChannel(channel: WirelessChannel) = Unit
    override suspend fun setSaturation(level: SaturationLevel) = Unit
    override suspend fun setAmbientBacklight(mode: AmbientBacklightMode) = Unit
    override suspend fun setAmbientBrightness(percent: Int) = Unit
    override suspend fun setTvSync(enabled: Boolean) = Unit
    override suspend fun setHdrAuto(enabled: Boolean) = Unit
    override suspend fun setColorEnhance(percent: Int) = Unit
    override suspend fun setColorCalibration(calibration: ColorCalibration) = Unit
    override suspend fun setMusicSensitivity(level: MusicSensitivity) = Unit
    override suspend fun setBlackBarMode(mode: BlackBarMode) = Unit
    override suspend fun setCustomColor(color: CustomColor) = Unit
}
