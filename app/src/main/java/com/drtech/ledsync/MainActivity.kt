@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.drtech.ledsync

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

private val Ink = Color(0xFF070815)
private val InkGlass = Color(0xE6141528)
private val InkSoft = Color(0xFF1A1B30)
private val InkLift = Color(0xFF26284A)
private val CopyMuted = Color(0xFFC9C5D4)
private val Cyan = Color(0xFF5CE8FF)
private val Magenta = Color(0xFFFF5DDE)
private val Mint = Color(0xFF6AFFBE)
private val Warm = Color(0xFFFFB96A)
private val Danger = Color(0xFFFF7A8A)

private val SelectKeys = setOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.ButtonA)

private enum class TvSection(val label: String) {
    SYNC("Sync"), SOUND("Sound"), SCENE("Scene"), SETTINGS("Settings"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LedSyncApp() }
    }

    override fun onResume() {
        super.onResume()
        ScheduleManager.rearm(this)
    }
}

@Composable
private fun LedSyncApp(viewModel: ControllerViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scan by viewModel.scan.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val schedules by viewModel.schedules.collectAsStateWithLifecycle()
    val autoOn by viewModel.autoOn.collectAsStateWithLifecycle()
    val use24Hour by viewModel.use24Hour.collectAsStateWithLifecycle()
    val sleepRemaining by viewModel.sleepRemaining.collectAsStateWithLifecycle()
    val automation = AutomationUi(
        presets = presets,
        onSavePreset = viewModel::savePreset,
        onApplyPreset = { viewModel.applyPreset(it) },
        onClearPreset = viewModel::clearPreset,
        sleepRemaining = sleepRemaining,
        onStartSleep = viewModel::startSleepTimer,
        onCancelSleep = viewModel::cancelSleepTimer,
        autoOn = autoOn,
        use24Hour = use24Hour,
        onUse24Hour = viewModel::setUse24Hour,
        onAutoOn = viewModel::setAutoOn,
        schedules = schedules,
        onSchedules = viewModel::setSchedules,
    )
    var section by rememberSaveable { mutableStateOf(TvSection.SYNC) }
    var showDevice by rememberSaveable { mutableStateOf(false) }
    val tabFocus = remember { FocusRequester() }
    // First run: open the setup screen straight away.
    LaunchedEffect(state.phase == ConnectionPhase.NEEDS_SETUP) {
        if (state.phase == ConnectionPhase.NEEDS_SETUP) showDevice = true
    }

    // HDMI safety guard: confirm before leaving the current input, then require a
    // keypress within 10 s or the box returns to the previous input.
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ledsync_ui", Context.MODE_PRIVATE) }
    var guardEnabled by remember { mutableStateOf(prefs.getBoolean("hdmi_guard", true)) }
    var pendingInput by remember { mutableStateOf<HdmiInput?>(null) }
    var trial by remember { mutableStateOf<Pair<HdmiInput, HdmiInput>?>(null) }
    val requestInput: (HdmiInput) -> Unit = { input ->
        if (input != state.selectedInput) {
            if (guardEnabled) pendingInput = input else viewModel.selectInput(input)
        }
    }

    // Back returns to the Sync home tab first; only from there does it leave the app.
    BackHandler(enabled = section != TvSection.SYNC && !showDevice) { section = TvSection.SYNC }
    // Land D-pad focus on Sync when the activity first opens so the remote
    // works immediately.
    // This must happen only once: repeated requests can pull focus away while
    // the user is already navigating the first screen with the remote.
    LaunchedEffect(Unit) {
        delay(300)
        runCatching { tabFocus.requestFocus() }
    }

    // The layout is authored for a 1920 dp wide canvas. TVs run at different
    // density overrides (the Shield's 1080p mode is only 960 dp wide), so scale
    // the density to keep proportions identical everywhere.
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Ink)) {
        val baseDensity = LocalDensity.current
        val scale = (maxWidth.value / 1920f).coerceIn(0.35f, 2f)
        CompositionLocalProvider(LocalDensity provides Density(baseDensity.density * scale, baseDensity.fontScale)) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(modifier = Modifier.fillMaxSize().background(Ink)) {
            Image(
                painter = painterResource(R.drawable.ambient_backdrop),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Color(0x73030812)))
            Column(
                // TV safe area: many TVs crop ~5% of the picture, so keep content inside it.
                modifier = Modifier.fillMaxSize().padding(horizontal = 80.dp, vertical = 52.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                TopBar(
                    state = state,
                    section = section,
                    tabFocus = tabFocus,
                    onSection = { section = it },
                    onDevice = { showDevice = true },
                    onPower = { viewModel.setPower(!state.isPowered) },
                )
                Hero(state)
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    color = InkGlass,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                ) {
                    when (section) {
                        TvSection.SYNC -> SyncPanel(
                            state,
                            requestInput,
                            viewModel::setBrightness,
                            viewModel::setDiffusion,
                            viewModel::setDiffusionDegree,
                            viewModel::setTvSync,
                            viewModel::setHdrAuto,
                        )
                        TvSection.SOUND -> SoundPanel(state, viewModel::selectSoundMode, viewModel::setMusicSensitivity)
                        TvSection.SCENE -> ScenePanel(state, viewModel::selectScene)
                        TvSection.SETTINGS -> SettingsPanel(
                            state,
                            viewModel::setStripDirection,
                            viewModel::setSceneModeCec,
                            viewModel::setWirelessChannel,
                            viewModel::setSaturation,
                            viewModel::setAmbientBacklight,
                            viewModel::setAmbientBrightness,
                            viewModel::setColorEnhance,
                            viewModel::setColorCalibration,
                            viewModel::setBlackBarMode,
                            viewModel::setCustomColor,
                            automation,
                            guardEnabled,
                            { enabled ->
                                guardEnabled = enabled
                                prefs.edit().putBoolean("hdmi_guard", enabled).apply()
                            },
                        )
                    }
                }
                Text(
                    "D-pad: move  ·  OK: select  ·  Left/Right on a slider: adjust  ·  Back: return to Sync",
                    color = CopyMuted.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            if (showDevice) {
                DeviceScreen(
                    state = state,
                    scan = scan,
                    startInSetup = state.connection == null,
                    onBack = { showDevice = false },
                    onReconnect = viewModel::refresh,
                    onScan = viewModel::startScan,
                    onSave = viewModel::saveBox,
                    onForget = viewModel::forgetBox,
                )
            }
            pendingInput?.let { target ->
                HdmiConfirmDialog(
                    target = target,
                    current = state.selectedInput,
                    onConfirm = {
                        trial = target to state.selectedInput
                        pendingInput = null
                        viewModel.selectInput(target)
                    },
                    onCancel = { pendingInput = null },
                )
            }
            trial?.let { (target, previous) ->
                HdmiKeepDialog(
                    target = target,
                    previous = previous,
                    onKeep = { trial = null },
                    onRevert = {
                        trial = null
                        viewModel.selectInput(previous)
                    },
                )
            }
        }
    }
        }
    }
}

// ---------------------------------------------------------------- HDMI safety guard

@Composable
private fun HdmiConfirmDialog(target: HdmiInput, current: HdmiInput, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { focus.requestFocus() }
    }
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        CanvasScale {
            Box(Modifier.fillMaxSize().background(Color(0xEB070815)), contentAlignment = Alignment.Center) {
                Surface(
                    color = InkGlass,
                    shape = RoundedCornerShape(32.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier.widthIn(max = 900.dp).focusGroup(),
                ) {
                    Column(modifier = Modifier.padding(40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Switch to ${target.label}?", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "The Sync Box will show ${target.label}. If the device running this app is on " +
                                "${current.label}, this screen will go blank.",
                            color = CopyMuted,
                            fontSize = 18.sp,
                        )
                        Notice("After switching, press OK within 10 seconds to keep it. Otherwise the box returns to ${current.label} automatically.")
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.focusGroup()) {
                            Tile(
                                modifier = Modifier.width(320.dp).height(60.dp).focusRequester(focus),
                                selected = true,
                                accent = Cyan,
                                onClick = onConfirm,
                            ) { Text("Switch to ${target.label}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = tileText(true)) }
                            Tile(modifier = Modifier.width(200.dp).height(60.dp), selected = false, accent = Magenta, onClick = onCancel) {
                                Text("Cancel", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Shown right after switching: auto-returns to [previous] unless the user keeps [target]. */
@Composable
private fun HdmiKeepDialog(target: HdmiInput, previous: HdmiInput, onKeep: () -> Unit, onRevert: () -> Unit) {
    val focus = remember { FocusRequester() }
    var remaining by remember { mutableIntStateOf(10) }
    val revert by rememberUpdatedState(onRevert)
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
        revert()
    }
    Dialog(
        onDismissRequest = onRevert,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        CanvasScale {
            Box(Modifier.fillMaxSize().background(Color(0xCC070815)), contentAlignment = Alignment.Center) {
                Surface(
                    color = InkGlass,
                    shape = RoundedCornerShape(32.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier.widthIn(max = 900.dp).focusGroup(),
                ) {
                    Column(modifier = Modifier.padding(40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Can you see this?", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Press OK to keep ${target.label}. Going back to ${previous.label} in $remaining s.",
                            color = CopyMuted,
                            fontSize = 20.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.focusGroup()) {
                            Tile(
                                modifier = Modifier.width(320.dp).height(60.dp).focusRequester(focus),
                                selected = true,
                                accent = Mint,
                                onClick = onKeep,
                            ) { Text("Keep ${target.label}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = tileText(true)) }
                            Tile(modifier = Modifier.width(280.dp).height(60.dp), selected = false, accent = Magenta, onClick = onRevert) {
                                Text("Go back now", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- top bar & hero

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TopBar(
    state: ControllerState,
    section: TvSection,
    tabFocus: FocusRequester,
    onSection: (TvSection) -> Unit,
    onDevice: () -> Unit,
    onPower: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(modifier = Modifier.widthIn(min = 220.dp)) {
            Text("Dr.Technolust Sync Box", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Text(state.deviceName, color = CopyMuted, fontSize = 14.sp, maxLines = 1)
        }
        Row(modifier = Modifier.weight(1f).focusGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvSection.entries.forEach { option ->
                val selected = option == section
                Tile(
                    modifier = Modifier
                        .width(150.dp)
                        .height(52.dp)
                    // Keep the initial requester anchored to a stable node.
                    // Moving the same requester onto the newly selected tab
                    // can consume the next Right press during recomposition.
                    .then(if (option == TvSection.SYNC) Modifier.focusRequester(tabFocus) else Modifier),
                    selected = selected,
                    accent = Cyan,
                    onClick = { onSection(option) },
                ) {
                    Text(option.label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = tileText(selected))
                }
            }
        }
        StatusChip(state, onDevice)
        Tile(
            modifier = Modifier.width(210.dp).height(52.dp),
            selected = state.isPowered,
            accent = if (state.isPowered) Mint else Magenta,
            enabled = state.supports(Feature.POWER),
            onClick = onPower,
        ) {
            // Names the action: turning the box off blanks every LED and keeps the picture passing through.
            Text(
                if (state.isPowered) "Lights off" else "Lights on",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = tileText(state.isPowered),
            )
        }
    }
}

@Composable
private fun StatusChip(state: ControllerState, onClick: () -> Unit) {
    val (label, color) = statusOf(state)
    Tile(modifier = Modifier.width(230.dp).height(52.dp), selected = false, accent = color, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
        }
    }
}

private fun statusOf(state: ControllerState): Pair<String, Color> = when (state.phase) {
    ConnectionPhase.CONNECTED -> "Connected · Local" to Mint
    ConnectionPhase.CONNECTING -> "Connecting…" to Warm
    ConnectionPhase.ERROR -> "Offline · Retry" to Danger
    ConnectionPhase.NEEDS_SETUP -> "Set up your box" to CopyMuted
}

@Composable
private fun Hero(state: ControllerState) {
    val hasReport = state.lastUpdatedMillis > 0
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val modeName = state.sceneMode?.label ?: state.soundMode?.label
            Text(
                (if (!hasReport) "AWAITING CONNECTION" else if (state.phase != ConnectionPhase.CONNECTED) "LAST KNOWN STATE · OFFLINE" else if (state.isPowered) "AMBIENT SYNC ACTIVE" else "AMBIENT SYNC PAUSED") +
                    (if (modeName != null) "  ·  ${modeName.uppercase()}" else ""),
                color = if (state.isPowered) Cyan else CopyMuted,
                fontSize = 14.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(if (hasReport) state.selectedInput.label else "Sync Box", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-2).sp)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            Metric(if (hasReport) "${state.brightnessPercent}%" else "—", "BRIGHTNESS", Magenta)
            Metric(if (hasReport) "${state.diffusionDegreePercent}%" else "—", "DIFFUSION", Mint)
            Metric(state.ambientBacklightMode?.label ?: "—", "BACKLIGHT", Cyan)
        }
    }
}

@Composable
private fun Metric(value: String, caption: String, accent: Color) {
    Column(horizontalAlignment = Alignment.End) {
        Text(value, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(caption, color = accent, fontSize = 12.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------- panels

@Composable
private fun SyncPanel(
    state: ControllerState,
    onInput: (HdmiInput) -> Unit,
    onBrightness: (Int) -> Unit,
    onDiffusion: (DiffusionMode) -> Unit,
    onDegree: (Int) -> Unit,
    onTvSync: (Boolean) -> Unit,
    onHdrAuto: (Boolean) -> Unit,
) {
    PanelColumn("Sync control", "Pick the HDMI source and tune how the light follows the picture") {
        Row(modifier = Modifier.fillMaxWidth().height(160.dp).focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            HdmiInput.entries.forEach { input ->
                val active = state.selectedInput == input
                ChoiceTile(
                    label = input.label,
                    caption = if (active) "ACTIVE" else "SWITCH",
                    selected = active,
                    accent = Cyan,
                    enabled = state.supports(Feature.INPUT),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { onInput(input) },
                )
            }
        }
        // Stacked, not side by side: Left/Right adjusts, Up/Down moves between sliders.
        SliderTile(
            title = "BRIGHTNESS",
            value = state.brightnessPercent,
            accent = Magenta,
            enabled = state.supports(Feature.BRIGHTNESS),
            modifier = Modifier.fillMaxWidth().height(136.dp),
            onCommit = onBrightness,
        )
        SliderTile(
            title = "DIFFUSION",
            value = state.diffusionDegreePercent,
            accent = Mint,
            enabled = state.supports(Feature.DIFFUSION_DEGREE),
            modifier = Modifier.fillMaxWidth().height(136.dp),
            minValue = 0,
            onCommit = onDegree,
        )
        Row(modifier = Modifier.fillMaxWidth().height(110.dp).focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ChoiceTile(
                label = if (state.sceneModeCecEnabled) "Scene CEC on" else "Scene CEC off",
                caption = "SCENE MODE HDMI CONTROL",
                selected = state.tvSyncEnabled,
                accent = Mint,
                enabled = state.supports(Feature.TV_SYNC),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onClick = { onTvSync(!state.tvSyncEnabled) },
            )
            ChoiceTile(
                label = if (state.hdrAutoEnabled) "HDR auto on" else "HDR auto off",
                caption = "AUTOMATIC HDR HANDLING",
                selected = state.hdrAutoEnabled,
                accent = Warm,
                enabled = state.supports(Feature.HDR_AUTO),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onClick = { onHdrAuto(!state.hdrAutoEnabled) },
            )
        }
        if (state.supports(Feature.DIFFUSION_MODE)) {
            Row(modifier = Modifier.fillMaxWidth().focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DiffusionMode.entries.forEach { mode ->
                    ChoiceTile(
                        label = mode.label,
                        caption = "DIFFUSION PRESET",
                        selected = state.diffusionMode == mode,
                        accent = Mint,
                        modifier = Modifier.weight(1f).height(84.dp),
                        onClick = { onDiffusion(mode) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SoundPanel(state: ControllerState, onSelect: (SoundMode) -> Unit, onSensitivity: (MusicSensitivity) -> Unit) {
    PanelColumn("Sound modes", "Make the lighting react to music and room audio") {
        if (!state.supports(Feature.SOUND)) {
            Notice("Sound modes are not available over the local connection yet. Their encoding is still being verified against your Sync Box.")
        }
        LowBrightnessHint(state)
        SoundMode.entries.toList().chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth().height(116.dp).focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { mode ->
                    ChoiceTile(
                        label = mode.label,
                        caption = if (state.supports(Feature.SOUND)) "SOUND REACTIVE" else "COMING SOON",
                        selected = state.soundMode == mode,
                        accent = Magenta,
                        enabled = state.supports(Feature.SOUND),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = { onSelect(mode) },
                    )
                }
            }
        }
        if (state.supports(Feature.MUSIC_SENSITIVITY)) {
            GroupLabel("MUSIC SENSITIVITY")
            Text(settingStatus(state.musicSensitivity?.label, state.lastSentSensitivity?.label), color = CopyMuted, fontSize = 14.sp)
            Row(modifier = Modifier.fillMaxWidth().height(88.dp).focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MusicSensitivity.entries.forEach { level ->
                    val active = state.musicSensitivity == level
                    ChoiceTile(
                        label = level.label,
                        caption = settingCaption(active, state.lastSentSensitivity == level),
                        selected = active,
                        accent = Magenta,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = { onSensitivity(level) },
                    )
                }
            }
        }
    }
}

/** Explains why a mode change can look like nothing happened at very low brightness. */
@Composable
private fun LowBrightnessHint(state: ControllerState) {
    if (state.isPowered && state.brightnessPercent <= 5) {
        Notice("Brightness is ${state.brightnessPercent}%, so changes may be too dim to see. Raise it on the Sync tab, or use Lights off to turn every LED off.")
    }
}

@Composable
private fun ScenePanel(state: ControllerState, onSelect: (SceneMode) -> Unit) {
    PanelColumn("Scenes & modes", "Choose how the light behaves") {
        val enabled = state.supports(Feature.SCENE)
        if (!enabled) Notice("Scenes are not available in this connection mode.")
        LowBrightnessHint(state)
        SceneGroup.entries.forEach { group ->
            GroupLabel(group.title.uppercase())
            SceneMode.entries.filter { it.group == group }.chunked(6).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth().height(84.dp).focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { mode ->
                        ChoiceTile(
                            label = mode.label,
                            caption = null,
                            selected = state.sceneMode == mode,
                            accent = Warm,
                            enabled = enabled,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onClick = { onSelect(mode) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    state: ControllerState,
    onDirection: (StripDirection) -> Unit,
    onSceneModeCec: (Boolean) -> Unit,
    onChannel: (WirelessChannel) -> Unit,
    onSaturation: (SaturationLevel) -> Unit,
    onAmbientMode: (AmbientBacklightMode) -> Unit,
    onAmbientBrightness: (Int) -> Unit,
    onColorEnhance: (Int) -> Unit,
    onCalibration: (ColorCalibration) -> Unit,
    onBlackBars: (BlackBarMode) -> Unit,
    onCustomColor: (CustomColor) -> Unit,
    automation: AutomationUi,
    guardEnabled: Boolean,
    onGuardChange: (Boolean) -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    // Back steps out of a sub-page to its parent before leaving Settings.
    BackHandler(enabled = page != SettingsPage.HOME) {
        page = if (page == SettingsPage.CALIBRATION || page == SettingsPage.PICKER) SettingsPage.COLOR else SettingsPage.HOME
    }
    when (page) {
        SettingsPage.HOME -> SettingsHome(onPage = { page = it })
        SettingsPage.PRESETS -> PresetsSettings(state, automation, onBack = { page = SettingsPage.HOME })
        SettingsPage.TIMERS -> TimersSettings(state, automation, onBack = { page = SettingsPage.HOME })
        SettingsPage.SCHEDULE -> ScheduleSettings(automation, onBack = { page = SettingsPage.HOME })
        SettingsPage.PICKER -> ColorPickerSettings(state, onCustomColor, onBack = { page = SettingsPage.COLOR })
        SettingsPage.INSTALLATION -> InstallationSettings(
            state = state,
            onDirection = onDirection,
            onSceneModeCec = onSceneModeCec,
            onBlackBars = onBlackBars,
            guardEnabled = guardEnabled,
            onGuardChange = onGuardChange,
            onBack = { page = SettingsPage.HOME },
        )
        SettingsPage.AMBIENT -> AmbientSettings(
            state = state,
            onChannel = onChannel,
            onAmbientMode = onAmbientMode,
            onAmbientBrightness = onAmbientBrightness,
            onBack = { page = SettingsPage.HOME },
        )
        SettingsPage.COLOR -> ColorSettings(
            state = state,
            onSaturation = onSaturation,
            onColorEnhance = onColorEnhance,
            onOpenCalibration = { page = SettingsPage.CALIBRATION },
            onOpenPicker = { page = SettingsPage.PICKER },
            onBack = { page = SettingsPage.HOME },
        )
        SettingsPage.CALIBRATION -> CalibrationSettings(
            state = state,
            onCalibration = onCalibration,
            onBack = { page = SettingsPage.COLOR },
        )
        SettingsPage.ABOUT -> AboutSettings(onBack = { page = SettingsPage.HOME })
    }
}

private enum class SettingsPage { HOME, INSTALLATION, AMBIENT, COLOR, CALIBRATION, PICKER, PRESETS, TIMERS, SCHEDULE, ABOUT }

/** Presets, timers and schedules, with the callbacks that drive them. */
private class AutomationUi(
    val presets: List<Preset?>,
    val onSavePreset: (Int) -> Unit,
    val onApplyPreset: (Int) -> Unit,
    val onClearPreset: (Int) -> Unit,
    val sleepRemaining: Int?,
    val onStartSleep: (Int) -> Unit,
    val onCancelSleep: () -> Unit,
    val autoOn: Boolean,
    val use24Hour: Boolean,
    val onUse24Hour: (Boolean) -> Unit,
    val onAutoOn: (Boolean) -> Unit,
    val schedules: Schedules,
    val onSchedules: (Schedules) -> Unit,
)

@Composable
private fun SettingsHome(onPage: (SettingsPage) -> Unit) {
    val firstDestinationFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { firstDestinationFocus.requestFocus() }
    }
    PanelColumn("Settings", "Fine-tune the Sync Box, then make it yours") {
        Row(
            modifier = Modifier.fillMaxWidth().height(160.dp).focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsDestination("Installation", "Strip direction · black bars · CEC", Warm, Modifier.weight(1f).fillMaxHeight().focusRequester(firstDestinationFocus)) {
                onPage(SettingsPage.INSTALLATION)
            }
            SettingsDestination("Ambient light", "Wireless channel · backlight", Cyan, Modifier.weight(1f).fillMaxHeight()) {
                onPage(SettingsPage.AMBIENT)
            }
            SettingsDestination("Color", "Picker · enhancement · calibration", Mint, Modifier.weight(1f).fillMaxHeight()) {
                onPage(SettingsPage.COLOR)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(140.dp).focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsDestination("Presets", "Save and recall your favorite looks", Magenta, Modifier.weight(1f).fillMaxHeight()) {
                onPage(SettingsPage.PRESETS)
            }
            SettingsDestination("Timers", "Sleep timer · turn on when opened", Warm, Modifier.weight(1f).fillMaxHeight()) {
                onPage(SettingsPage.TIMERS)
            }
            SettingsDestination("Schedule", "Daily on and off times", Cyan, Modifier.weight(1f).fillMaxHeight()) {
                onPage(SettingsPage.SCHEDULE)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(120.dp).focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsDestination("About Dr.Technolust Sync Box", "Version ${BuildConfig.VERSION_NAME}  ·  Developed by Dr. Technolust", Magenta, Modifier.weight(1f).fillMaxHeight()) {
                onPage(SettingsPage.ABOUT)
            }
        }
        Notice("Settings the box doesn't expose over your local network yet are shown disabled.")
    }
}

@Composable
private fun SettingsDestination(
    label: String,
    caption: String,
    accent: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Tile(modifier = modifier, selected = false, accent = accent, onClick = onClick) {
        Text(label, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Text(caption, fontSize = 15.sp, color = CopyMuted, textAlign = TextAlign.Center, maxLines = 2)
        Spacer(Modifier.height(8.dp))
        Text("OPEN  ›", fontSize = 14.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = accent)
    }
}

@Composable
private fun SettingsHeader(title: String, subtitle: String, onBack: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { backFocus.requestFocus() }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Tile(modifier = Modifier.width(160.dp).height(56.dp).focusRequester(backFocus), selected = false, accent = Cyan, onClick = onBack) {
            Text("‹  Settings", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title.uppercase(), color = Cyan, fontSize = 14.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = CopyMuted, fontSize = 16.sp)
        }
        trailing?.invoke()
    }
}

/** A Settings sub-page: one header (back + title), then its controls. */
@Composable
private fun SettingsSubpage(title: String, subtitle: String, onBack: () -> Unit, headerAction: @Composable (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsHeader(title, subtitle, onBack, headerAction)
        content()
    }
}

@Composable
private fun InstallationSettings(
    state: ControllerState,
    onDirection: (StripDirection) -> Unit,
    onSceneModeCec: (Boolean) -> Unit,
    onBlackBars: (BlackBarMode) -> Unit,
    guardEnabled: Boolean,
    onGuardChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    SettingsSubpage("Installation", "Match the strip direction and HDMI behavior to your setup", onBack) {
        GroupLabel("HDMI SAFETY GUARD")
        ChoiceTile(
            label = if (guardEnabled) "Guard on" else "Guard off",
            caption = "CONFIRM BEFORE SWITCHING · AUTO-RETURN IN 10 S",
            selected = guardEnabled,
            accent = Mint,
            modifier = Modifier.fillMaxWidth().height(84.dp),
            onClick = { onGuardChange(!guardEnabled) },
        )
        GroupLabel("LED STRIP ORIENTATION")
        Row(modifier = Modifier.fillMaxWidth().height(96.dp).focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StripDirection.entries.forEach { direction ->
                ChoiceTile(
                    label = direction.label,
                    caption = if (state.stripDirection == direction) "ACTIVE" else "SELECT DIRECTION",
                    selected = state.stripDirection == direction,
                    accent = Warm,
                    enabled = state.supports(Feature.STRIP_DIRECTION),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { onDirection(direction) },
                )
            }
        }
        if (state.supports(Feature.BLACK_BARS)) {
            GroupLabel("BLACK BAR DETECTION")
            Text(settingStatus(state.blackBarMode?.label, state.lastSentBlackBars?.label), color = CopyMuted, fontSize = 14.sp)
            Row(modifier = Modifier.fillMaxWidth().height(84.dp).focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BlackBarMode.entries.forEach { mode ->
                    val active = state.blackBarMode == mode
                    ChoiceTile(
                        label = mode.label,
                        caption = settingCaption(active, state.lastSentBlackBars == mode),
                        selected = active,
                        accent = Cyan,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = { onBlackBars(mode) },
                    )
                }
            }
        }
        GroupLabel("SCENE MODE CEC")
        val cec = state.supports(Feature.CEC)
        ChoiceTile(
            label = if (!cec) "Scene mode CEC" else if (state.sceneModeCecEnabled) "CEC on" else "CEC off",
            caption = if (cec) "SCENE MODE HDMI CONTROL" else "NOT AVAILABLE OVER LOCAL NETWORK",
            selected = cec && state.sceneModeCecEnabled,
            accent = Magenta,
            enabled = cec,
            modifier = Modifier.fillMaxWidth().height(84.dp),
            onClick = { onSceneModeCec(!state.sceneModeCecEnabled) },
        )
    }
}

@Composable
private fun AmbientSettings(
    state: ControllerState,
    onChannel: (WirelessChannel) -> Unit,
    onAmbientMode: (AmbientBacklightMode) -> Unit,
    onAmbientBrightness: (Int) -> Unit,
    onBack: () -> Unit,
) {
    SettingsSubpage("Ambient light", "Wireless sync and the light that stays on around the screen", onBack) {
        GroupLabel("WIRELESS SYNC CHANNEL")
        Text(settingStatus(state.wirelessChannel?.label, state.lastSentChannel?.label), color = CopyMuted, fontSize = 14.sp)
        Row(modifier = Modifier.fillMaxWidth().height(96.dp).focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            WirelessChannel.entries.forEach { channel ->
                ChoiceTile(channel.label, settingCaption(state.wirelessChannel == channel, state.lastSentChannel == channel), state.wirelessChannel == channel, Magenta,
                    Modifier.weight(1f).fillMaxHeight(), state.supports(Feature.WIRELESS_CHANNEL)) { onChannel(channel) }
            }
        }
        GroupLabel("AMBIENT BACKLIGHT")
        Text(settingStatus(state.ambientBacklightMode?.label, state.lastSentBacklight?.label), color = CopyMuted, fontSize = 14.sp)
        Row(modifier = Modifier.fillMaxWidth().height(96.dp).focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AmbientBacklightMode.entries.forEach { mode ->
                ChoiceTile(mode.label, settingCaption(state.ambientBacklightMode == mode, state.lastSentBacklight == mode), state.ambientBacklightMode == mode, Cyan,
                    Modifier.weight(1f).fillMaxHeight(), state.supports(Feature.AMBIENT_BACKLIGHT)) { onAmbientMode(mode) }
            }
        }
        val level = state.ambientBrightnessPercent
        if (state.supports(Feature.AMBIENT_LEVEL) && level != null) {
            SliderTile(
                title = "AMBIENT LEVEL",
                value = level,
                accent = Cyan,
                modifier = Modifier.fillMaxWidth().height(136.dp),
                minValue = 0,
                onCommit = onAmbientBrightness,
            )
        } else {
            InfoCard("Ambient level", "Not available over the local network yet. Adjust it from the manufacturer's phone app for now.")
        }
    }
}

@Composable
private fun ColorSettings(
    state: ControllerState,
    onSaturation: (SaturationLevel) -> Unit,
    onColorEnhance: (Int) -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenPicker: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsSubpage("Color", "Color picker, enhancement and calibration", onBack) {
        GroupLabel("CUSTOM COLOR")
        ChoiceTile(
            label = "Color picker  ›",
            caption = "SHOW ANY STATIC COLOR",
            selected = false,
            accent = Cyan,
            enabled = state.supports(Feature.CUSTOM_COLOR),
            modifier = Modifier.fillMaxWidth().height(96.dp),
            onClick = onOpenPicker,
        )
        if (state.supports(Feature.SATURATION)) {
            GroupLabel("SATURATION")
            Row(modifier = Modifier.fillMaxWidth().height(108.dp).focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SaturationLevel.entries.forEach { level ->
                    ChoiceTile(level.label, if (state.saturation == level) "ACTIVE" else "SELECT LEVEL", state.saturation == level, Magenta,
                        Modifier.weight(1f).fillMaxHeight(), true) { onSaturation(level) }
                }
            }
        }
        SliderTile(
            title = "COLOR ENHANCEMENT",
            value = state.colorEnhancePercent,
            accent = Mint,
            enabled = state.supports(Feature.COLOR_ENHANCE),
            modifier = Modifier.fillMaxWidth().height(136.dp),
            minValue = 0,
            onCommit = onColorEnhance,
        )
        GroupLabel("COLOR CALIBRATION")
        ChoiceTile(
            label = "Adjust color parameters  ›",
            caption = "RED · GREEN · BLUE · WHITE",
            selected = false,
            accent = Magenta,
            enabled = state.supports(Feature.CALIBRATION),
            modifier = Modifier.fillMaxWidth().height(112.dp),
            onClick = onOpenCalibration,
        )
    }
}

/** R/G/B/W gain trim, −10..+10 %, matching the phone app's "Adjust Color Parameters". */
@Composable
private fun CalibrationSettings(
    state: ControllerState,
    onCalibration: (ColorCalibration) -> Unit,
    onBack: () -> Unit,
) {
    val cal = state.colorCalibration
    val enabled = state.supports(Feature.CALIBRATION)
    val sign: (Int) -> String = { if (it > 0) "+$it%" else "$it%" }
    SettingsSubpage("Color calibration", "Trim each LED channel by up to 10% either way", onBack) {
        Text("Remembered on this TV, not read back from the box. If never set here, 0% is the default starting point.", color = CopyMuted, fontSize = 15.sp)
        listOf(
            Triple("RED", cal.r, Color(0xFFFF6B6B)),
            Triple("GREEN", cal.g, Color(0xFF6AFFBE)),
            Triple("BLUE", cal.b, Color(0xFF6BA8FF)),
            Triple("WHITE", cal.w, Color(0xFFF2F3FF)),
        ).forEachIndexed { index, (name, value, accent) ->
            SliderTile(
                title = name,
                value = value,
                accent = accent,
                modifier = Modifier.fillMaxWidth().height(92.dp),
                enabled = enabled,
                minValue = ColorCalibration.MIN,
                maxValue = ColorCalibration.MAX,
                step = 1,
                compact = true,
                valueLabel = sign,
            ) { v ->
                onCalibration(
                    when (index) {
                        0 -> cal.copy(r = v)
                        1 -> cal.copy(g = v)
                        2 -> cal.copy(b = v)
                        else -> cal.copy(w = v)
                    },
                )
            }
        }
        ChoiceTile(
            label = "Reset to default",
            caption = "ALL CHANNELS 0%",
            selected = false,
            accent = Cyan,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(84.dp),
            onClick = { onCalibration(ColorCalibration()) },
        )
    }
}

/** Four saved looks. HDMI input is never part of a preset, so recalling one can't switch sources. */
@Composable
private fun PresetsSettings(state: ControllerState, automation: AutomationUi, onBack: () -> Unit) {
    SettingsSubpage("Presets", "Save the current look in a slot, then recall it with one press", onBack) {
        Row(modifier = Modifier.fillMaxWidth().focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PresetStore.TITLES.forEachIndexed { slot, title ->
                val preset = automation.presets.getOrNull(slot)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChoiceTile(
                        label = title,
                        caption = if (preset == null) "EMPTY" else "${preset.brightness}% · ${(preset.modeLabel ?: "NO MODE").uppercase()}",
                        selected = false,
                        accent = Magenta,
                        enabled = preset != null && state.phase == ConnectionPhase.CONNECTED,
                        modifier = Modifier.fillMaxWidth().height(130.dp),
                        onClick = { automation.onApplyPreset(slot) },
                    )
                    ChoiceTile(
                        label = "Save current",
                        caption = null,
                        selected = false,
                        accent = Mint,
                        enabled = state.phase == ConnectionPhase.CONNECTED,
                        modifier = Modifier.fillMaxWidth().height(68.dp),
                        onClick = { automation.onSavePreset(slot) },
                    )
                    ChoiceTile(
                        label = "Clear",
                        caption = null,
                        selected = false,
                        accent = Warm,
                        enabled = preset != null,
                        modifier = Modifier.fillMaxWidth().height(68.dp),
                        onClick = { automation.onClearPreset(slot) },
                    )
                }
            }
        }
        InfoCard(
            "What a preset stores",
            "Brightness, diffusion, Scene Mode CEC, HDR auto, color enhancement and the active scene, sound mode or custom color. It never changes the HDMI input.",
        )
    }
}

@Composable
private fun TimersSettings(state: ControllerState, automation: AutomationUi, onBack: () -> Unit) {
    SettingsSubpage("Timers", "Turn the lights off after a while, or on when you open the app", onBack) {
        GroupLabel("SLEEP TIMER")
        val remaining = automation.sleepRemaining
        if (remaining != null) {
            Notice(String.format("Lights turn off in %d:%02d. You can close this app; Android keeps the timer.", remaining / 60, remaining % 60))
        }
        Row(modifier = Modifier.fillMaxWidth().height(110.dp).focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(15, 30, 60, 90).forEach { minutes ->
                ChoiceTile(
                    label = "$minutes min",
                    caption = "LIGHTS OFF AFTER",
                    selected = false,
                    accent = Warm,
                    enabled = state.phase == ConnectionPhase.CONNECTED,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { automation.onStartSleep(minutes) },
                )
            }
            ChoiceTile(
                label = "Cancel",
                caption = if (remaining != null) "STOP THE TIMER" else "NO TIMER RUNNING",
                selected = false,
                accent = Magenta,
                enabled = remaining != null,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onClick = automation.onCancelSleep,
            )
        }
        GroupLabel("AUTO-ON")
        ChoiceTile(
            label = if (automation.autoOn) "Turn on when opened" else "Leave as is when opened",
            caption = "IF THE BOX IS OFF WHEN THIS APP STARTS",
            selected = automation.autoOn,
            accent = Mint,
            modifier = Modifier.fillMaxWidth().height(96.dp),
            onClick = { automation.onAutoOn(!automation.autoOn) },
        )
        AlarmTimingInfo()
    }
}

@Composable
private fun ScheduleSettings(automation: AutomationUi, onBack: () -> Unit) {
    val s = automation.schedules
    val slotLabel: (Int) -> String = { clockTimeLabel(it * 15, automation.use24Hour) }
    SettingsSubpage("Schedule", "Turn the lights on and off at the same time every day", onBack, headerAction = {
        Tile(modifier = Modifier.width(310.dp).height(52.dp), selected = false, accent = Cyan,
            onClick = { automation.onUse24Hour(!automation.use24Hour) }) {
            Text(if (automation.use24Hour) "Time format: 24-hour" else "Time format: 12-hour", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }) {
        GroupLabel("TURN ON")
        ChoiceTile(
            label = if (s.on.enabled) "On at ${s.on.label(automation.use24Hour)}" else "Turn-on disabled",
            caption = if (s.on.enabled) "ENABLED" else "PRESS TO ENABLE",
            selected = s.on.enabled,
            accent = Mint,
            modifier = Modifier.fillMaxWidth().height(84.dp),
            onClick = { automation.onSchedules(s.copy(on = s.on.copy(enabled = !s.on.enabled))) },
        )
        SliderTile(
            title = "ON TIME",
            value = s.on.minutes / 15,
            accent = Mint,
            modifier = Modifier.fillMaxWidth().height(92.dp),
            minValue = 0,
            maxValue = 95,
            step = 1,
            compact = true,
            valueLabel = slotLabel,
        ) { v -> automation.onSchedules(s.copy(on = s.on.copy(minutes = v * 15))) }
        GroupLabel("TURN OFF")
        ChoiceTile(
            label = if (s.off.enabled) "Off at ${s.off.label(automation.use24Hour)}" else "Turn-off disabled",
            caption = if (s.off.enabled) "ENABLED" else "PRESS TO ENABLE",
            selected = s.off.enabled,
            accent = Warm,
            modifier = Modifier.fillMaxWidth().height(84.dp),
            onClick = { automation.onSchedules(s.copy(off = s.off.copy(enabled = !s.off.enabled))) },
        )
        SliderTile(
            title = "OFF TIME",
            value = s.off.minutes / 15,
            accent = Warm,
            modifier = Modifier.fillMaxWidth().height(92.dp),
            minValue = 0,
            maxValue = 95,
            step = 1,
            compact = true,
            valueLabel = slotLabel,
        ) { v -> automation.onSchedules(s.copy(off = s.off.copy(minutes = v * 15))) }
        Text("Runs with the app closed. Keep the TV powered and the box reachable. Android force-stop suspends automation until the app is opened again.", color = CopyMuted, fontSize = 14.sp)
        AlarmTimingInfo()
    }
}

@Composable
private fun AlarmTimingInfo() {
    val context = LocalContext.current
    val exact by produceState(ScheduleManager.canScheduleExactly(context)) {
        while (true) {
            value = ScheduleManager.canScheduleExactly(context)
            delay(1000)
        }
    }
    if (!exact) {
        Tile(modifier = Modifier.fillMaxWidth().height(66.dp), selected = false, accent = Warm,
            onClick = { ScheduleManager.requestExactTiming(context) }) {
            Text("Timing is approximate · Allow precise alarms  ›", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun settingCaption(reported: Boolean, sent: Boolean) = when {
    reported -> "REPORTED BY BOX"
    sent -> "SENT · UNCONFIRMED"
    else -> "SELECT"
}

private fun settingStatus(reported: String?, sent: String?) = when {
    reported != null -> "Reported by box: $reported"
    sent != null -> "Last sent: $sent. The box does not report its current value."
    else -> "Current value unavailable. Choose a setting to send it to the box."
}

@Composable
private fun ColorPickerSettings(state: ControllerState, onColor: (CustomColor) -> Unit, onBack: () -> Unit) {
    val c = state.customColor
    val enabled = state.supports(Feature.CUSTOM_COLOR)
    fun swatch(h: Int, s: Int) = Color(android.graphics.Color.HSVToColor(floatArrayOf(h.toFloat(), s / 100f, 1f)))
    SettingsSubpage("Color picker", "Pick a static color; it replaces the active scene", onBack) {
        Row(modifier = Modifier.fillMaxWidth().height(84.dp).focusGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                Triple("Red", 0, 100), Triple("Orange", 30, 100), Triple("Yellow", 60, 100),
                Triple("Green", 120, 100), Triple("Cyan", 180, 100), Triple("Blue", 240, 100),
                Triple("Purple", 270, 100), Triple("Pink", 320, 100), Triple("White", 0, 0),
            ).forEach { (name, h, s) ->
                Tile(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    selected = false,
                    accent = swatch(h, s),
                    enabled = enabled,
                    onClick = { onColor(CustomColor(h, s, c.value.coerceAtLeast(20))) },
                ) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(swatch(h, s)).border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape))
                    Text(name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp))
                .background(Color(android.graphics.Color.HSVToColor(floatArrayOf(c.hue.toFloat(), c.saturation / 100f, c.value / 100f)))),
        )
        SliderTile("HUE", c.hue, Magenta, Modifier.fillMaxWidth().height(92.dp), enabled, 0, 360, 5, true, { "$it°" }) {
            onColor(c.copy(hue = it))
        }
        SliderTile("SATURATION", c.saturation, Cyan, Modifier.fillMaxWidth().height(92.dp), enabled, 0, 100, 1, true) {
            onColor(c.copy(saturation = it))
        }
        SliderTile("BRIGHTNESS", c.value, Mint, Modifier.fillMaxWidth().height(92.dp), enabled, 1, 100, 1, true) {
            onColor(c.copy(value = it))
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(InkSoft)
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(body, color = CopyMuted, fontSize = 15.sp)
    }
}

@Composable
private fun AboutSettings(onBack: () -> Unit) {
    SettingsSubpage("About Dr.Technolust Sync Box", "A local-first TV remote for your Sync Box", onBack) {
        Row(modifier = Modifier.fillMaxWidth().height(260.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.logo_drtech_512),
                contentDescription = "DrTech logo",
                modifier = Modifier.size(210.dp),
                contentScale = ContentScale.Fit,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Dr.Technolust Sync Box", color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.ExtraBold)
                Text("Version ${BuildConfig.VERSION_NAME}  ·  Local control, no cloud", color = Cyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Developed by Dr. Technolust", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("An Android TV remote for your Sync Box. Commands go straight to the box over your local network; the device key stays encrypted on this TV and is never shown.", color = CopyMuted, fontSize = 17.sp)
            }
        }
        InfoCard("Unofficial", "An independent, community-built app. Not affiliated with, endorsed by or supported by FancyLEDs or Tuya. Product names are trademarks of their respective owners and are used only to describe compatibility.")
    }
}

@Composable
private fun PanelColumn(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title.uppercase(), color = Cyan, fontSize = 14.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = CopyMuted, fontSize = 16.sp)
        }
        content()
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(text, color = CopyMuted, fontSize = 12.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Notice(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Warm.copy(alpha = 0.12f))
            .border(1.dp, Warm.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Warm))
        Text(text, color = Color.White.copy(alpha = 0.92f), fontSize = 15.sp)
    }
}

// ---------------------------------------------------------------- connection & setup screen

@Composable
private fun DeviceScreen(
    state: ControllerState,
    scan: ScanState,
    startInSetup: Boolean,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    onScan: () -> Unit,
    onSave: (host: String, deviceId: String, key: String) -> String?,
    onForget: () -> Unit,
) {
    val primaryFocus = remember { FocusRequester() }
    var editing by rememberSaveable { mutableStateOf(startInSetup) }
    var confirmForget by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1000)
            value = System.currentTimeMillis()
        }
    }
    LaunchedEffect(editing) {
        delay(150)
        runCatching { primaryFocus.requestFocus() }
    }
    val (statusLabel, statusColor) = statusOf(state)
    val info = state.connection

    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        // A dialog is its own window and does not inherit the scaled density.
        CanvasScale {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xEB070815)).padding(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = InkGlass,
                    shape = RoundedCornerShape(32.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier.widthIn(max = 980.dp).focusGroup(),
                ) {
                    Column(modifier = Modifier.padding(40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Box(Modifier.size(16.dp).clip(CircleShape).background(statusColor))
                            Column {
                                Text(if (editing) "Set up your Sync Box" else "Sync Box connection", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                                Text(if (editing) "Local network only: no account, no cloud" else statusLabel, color = if (editing) CopyMuted else statusColor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (editing) {
                            SetupForm(
                                scan = scan,
                                primaryFocus = primaryFocus,
                                canCancel = info != null,
                                onScan = onScan,
                                onSave = { host, id, key ->
                                    val error = onSave(host, id, key)
                                    if (error == null) editing = false
                                    error
                                },
                                onCancel = { editing = false },
                            )
                        } else {
                            Text(state.message, color = CopyMuted, fontSize = 17.sp)
                            if (info != null) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(InkSoft).padding(22.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    DetailRow("Device", state.deviceName)
                                    DetailRow("Connection", info.transport)
                                    DetailRow("Address", info.host)
                                    DetailRow("Device ID", "…${info.deviceIdTail}")
                                    DetailRow("Last update", agoLabel(now, state.lastUpdatedMillis))
                                }
                            } else {
                                Notice("No Sync Box is set up yet. Choose Set up to find your box on this network.")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.focusGroup()) {
                                if (info != null) {
                                    Tile(
                                        modifier = Modifier.width(210.dp).height(58.dp).focusRequester(primaryFocus),
                                        selected = true,
                                        accent = Cyan,
                                        onClick = onReconnect,
                                    ) { Text("Reconnect", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = tileText(true)) }
                                    Tile(modifier = Modifier.width(210.dp).height(58.dp), selected = false, accent = Warm, onClick = { editing = true }) {
                                        Text("Change box", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    }
                                    Tile(modifier = Modifier.width(210.dp).height(58.dp), selected = false, accent = Danger, onClick = { confirmForget = true }) {
                                        Text("Forget box", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    }
                                } else {
                                    Tile(
                                        modifier = Modifier.width(220.dp).height(58.dp).focusRequester(primaryFocus),
                                        selected = true,
                                        accent = Cyan,
                                        onClick = { editing = true },
                                    ) { Text("Set up", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = tileText(true)) }
                                }
                                Tile(modifier = Modifier.width(170.dp).height(58.dp), selected = false, accent = Magenta, onClick = onBack) {
                                    Text("Close", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                }
                            }
                            if (confirmForget) {
                                Notice("Forget this box? You will need its local key to connect again. Your saved looks and schedules remain on this TV.")
                                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Tile(modifier = Modifier.width(250.dp).height(58.dp), selected = false, accent = Danger, onClick = {
                                        confirmForget = false
                                        onForget()
                                        editing = true
                                    }) { Text("Forget this box", color = Color.White, fontSize = 19.sp) }
                                    Tile(modifier = Modifier.width(170.dp).height(58.dp), selected = true, accent = Cyan, onClick = { confirmForget = false }) {
                                        Text("Keep box", color = tileText(true), fontSize = 19.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Finds boxes on the network, then asks for the box's 16-character local key.
 * The key never leaves the device and is stored encrypted with the Android Keystore.
 */
@Composable
private fun SetupForm(
    scan: ScanState,
    primaryFocus: FocusRequester,
    canCancel: Boolean,
    onScan: () -> Unit,
    onSave: (host: String, deviceId: String, key: String) -> String?,
    onCancel: () -> Unit,
) {
    var host by rememberSaveable { mutableStateOf("") }
    var deviceId by rememberSaveable { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val keyFocus = remember { FocusRequester() }
    val hostFocus = remember { FocusRequester() }
    val idFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }

    // Fill the address and ID automatically when exactly one box is found.
    LaunchedEffect(scan) {
        val found = (scan as? ScanState.Done)?.devices.orEmpty()
        if (found.size == 1 && host.isBlank() && deviceId.isBlank()) {
            host = found[0].ip
            deviceId = found[0].deviceId
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "1. Find your box (it must be powered on and on the same network).",
            color = CopyMuted, fontSize = 17.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.focusGroup()) {
            Tile(
                modifier = Modifier.width(260.dp).height(56.dp).focusRequester(primaryFocus),
                selected = scan !is ScanState.Scanning,
                accent = Cyan,
                enabled = scan !is ScanState.Scanning,
                onClick = onScan,
            ) {
                Text(
                    if (scan is ScanState.Scanning) "Searching…" else "Find my box",
                    fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    color = if (scan is ScanState.Scanning) Color.White else tileText(true),
                )
            }
            val found = (scan as? ScanState.Done)?.devices.orEmpty()
            found.take(3).forEach { device ->
                val label = if (device.productKey == SUPPORTED_PRODUCT) "Sync Box · ${device.ip}" else "Device · ${device.ip}"
                Tile(
                    modifier = Modifier.width(300.dp).height(56.dp),
                    selected = host == device.ip,
                    accent = Mint,
                    onClick = { host = device.ip; deviceId = device.deviceId },
                ) { Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = tileText(host == device.ip), maxLines = 1) }
            }
        }
        if (scan is ScanState.Done && scan.devices.isEmpty()) {
            Text("No box heard. You can still enter its address and ID below.", color = Warm, fontSize = 15.sp)
        }
        SetupField("Box IP address", host, KeyboardType.Uri, ImeAction.Next, onNext = { idFocus.requestFocus() },
            modifier = Modifier.focusRequester(hostFocus).focusProperties { next = idFocus; down = idFocus; up = primaryFocus }) { host = it.trim() }
        SetupField("Device ID", deviceId, KeyboardType.Ascii, ImeAction.Next, onNext = { keyFocus.requestFocus() },
            modifier = Modifier.focusRequester(idFocus).focusProperties { next = keyFocus; down = keyFocus; up = hostFocus }) { deviceId = it.trim() }
        Text("2. Enter the 16-character local key for this box.", color = CopyMuted, fontSize = 17.sp)
        Text("This is not your account password. See GET_LOCAL_KEY.md in the download. Verified for the F62 box using LAN protocol 3.4.", color = CopyMuted, fontSize = 15.sp)
        SetupField("Local key", key, KeyboardType.Password, ImeAction.Done, onNext = { saveFocus.requestFocus() },
            modifier = Modifier.focusRequester(keyFocus).focusProperties { next = saveFocus; down = saveFocus; up = idFocus }) { key = it }
        error?.let { Text(it, color = Danger, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.focusGroup()) {
            Tile(
                modifier = Modifier.width(220.dp).height(58.dp).focusRequester(saveFocus).focusProperties { up = keyFocus },
                selected = true,
                accent = Mint,
                onClick = { error = onSave(host, deviceId, key) },
            ) { Text("Save and connect", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = tileText(true), maxLines = 1) }
            if (canCancel) {
                Tile(modifier = Modifier.width(170.dp).height(58.dp), selected = false, accent = Magenta, onClick = onCancel) {
                    Text("Cancel", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun SetupField(
    label: String,
    value: String,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
        visualTransformation = if (keyboardType == KeyboardType.Password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { onNext() }, onDone = { onNext() }),
        colors = accountTextFieldColors(),
        modifier = modifier.fillMaxWidth().onPreviewKeyEvent { event ->
            when (event.key) {
                Key.DirectionDown, Key.DirectionUp -> {
                    if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(
                        if (event.key == Key.DirectionDown) FocusDirection.Down else FocusDirection.Up,
                    )
                    true
                }
                else -> false
            }
        },
    )
}

/** Authoring canvas is 1920 dp wide; scales density so any TV renders identically. */
@Composable
private fun CanvasScale(content: @Composable () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val base = LocalDensity.current
        val scale = (maxWidth.value / 1920f).coerceIn(0.35f, 2f)
        CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
            content()
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = CopyMuted, fontSize = 17.sp)
        Text(value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

private fun agoLabel(now: Long, then: Long): String {
    if (then == 0L) return "Waiting for first update"
    val seconds = ((now - then) / 1000).coerceAtLeast(0)
    return if (seconds < 3) "Just now" else "$seconds s ago"
}

/** Product key of the box this app was verified against. */
private const val SUPPORTED_PRODUCT = "h2nqrmaskyhckcmf"
@Composable
private fun accountTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    disabledTextColor = CopyMuted,
    focusedContainerColor = Color(0xFF080A16),
    unfocusedContainerColor = Color(0xFF080A16),
    cursorColor = Cyan,
    focusedBorderColor = Cyan,
    unfocusedBorderColor = Color.White.copy(alpha = 0.28f),
    focusedLabelColor = Color.White,
    unfocusedLabelColor = Color.White.copy(alpha = 0.82f),
)

// ---------------------------------------------------------------- building blocks

private fun tileText(selected: Boolean) = if (selected) Ink else Color.White

/**
 * One focusable surface used by every control: large focus ring, scale, glow,
 * and legible dark-on-accent text when selected. Disabled tiles are not focusable
 * so D-pad navigation never lands on something that does nothing.
 */
@Composable
private fun Tile(
    modifier: Modifier,
    selected: Boolean,
    accent: Color,
    enabled: Boolean = true,
    focusScale: Float = 1.04f,
    onKey: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    // Grow by a fixed distance (not a percentage) so wide tiles never overshoot
    // the panel margin or neighbouring tiles when focused.
    val growPx = with(LocalDensity.current) { 10.dp.toPx() }
    val focusAmount by animateFloatAsState(if (focused) 1f else 0f, tween(120), label = "focus")
    val background by animateColorAsState(
        when {
            !enabled -> InkSoft.copy(alpha = 0.45f)
            selected -> accent
            focused -> InkLift
            else -> InkSoft
        },
        tween(120),
        label = "background",
    )
    val shape = RoundedCornerShape(18.dp)
    val interactive = if (enabled) {
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                when {
                    onKey != null && onKey(event) -> true
                    onClick != null && event.type == KeyEventType.KeyUp && event.key in SelectKeys -> {
                        onClick()
                        true
                    }
                    else -> false
                }
            }
            .pointerInput(onClick) { detectTapGestures(onTap = { onClick?.invoke() }) }
            .semantics { role = Role.Button }
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .onSizeChanged { size = it }
            .graphicsLayer {
                val grow = if (focusScale > 1f) growPx * focusAmount else 0f
                scaleX = 1f + grow / size.width.coerceAtLeast(1)
                scaleY = 1f + grow / size.height.coerceAtLeast(1)
            }
            .shadow(if (focused) 22.dp else 0.dp, shape, ambientColor = accent, spotColor = accent)
            .clip(shape)
            .background(background)
            .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color.White.copy(alpha = 0.10f), shape)
            .then(interactive)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

@Composable
private fun ChoiceTile(
    label: String,
    caption: String?,
    selected: Boolean,
    accent: Color,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Tile(modifier = modifier, selected = selected, accent = accent, enabled = enabled, onClick = onClick) {
        val textColor = if (enabled) tileText(selected) else Color.White.copy(alpha = 0.45f)
        Text(label, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = textColor, maxLines = 1)
        if (caption != null) {
            Text(
                caption,
                fontSize = 14.sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Bold,
                color = textColor.copy(alpha = 0.78f),
                maxLines = 1,
            )
        }
    }
}

/**
 * D-pad slider: Left/Right change the value by 1 by default, with changes sent once the
 * user pauses so held keys do not flood the device.
 */
@Composable
private fun SliderTile(
    title: String,
    value: Int,
    accent: Color,
    modifier: Modifier,
    enabled: Boolean = true,
    minValue: Int = 1,
    maxValue: Int = 100,
    step: Int = 1,
    compact: Boolean = false,
    valueLabel: (Int) -> String = { "$it%" },
    onCommit: (Int) -> Unit,
) {
    var draft by remember { mutableIntStateOf(value) }
    var lastAdjust by remember { mutableLongStateOf(0L) }
    LaunchedEffect(value, lastAdjust) {
        // Follow the device unless the user is mid-adjustment.
        val remaining = 1200 - (System.currentTimeMillis() - lastAdjust)
        if (remaining > 0) delay(remaining)
        draft = value
    }
    LaunchedEffect(draft) {
        if (draft != value) {
            delay(250)
            onCommit(draft)
        }
    }
    Tile(
        modifier = modifier,
        selected = false,
        accent = accent,
        enabled = enabled,
        focusScale = 1f,
        onKey = { event ->
            if (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) {
                val delta = if (event.key == Key.DirectionRight) step else -step
                // Clamp to the range so the slider lands exactly on its lowest and
                // highest values; once at an end the key falls through so focus can move on.
                val next = (draft + delta).coerceIn(minValue, maxValue)
                if (next == draft) {
                    false
                } else {
                    if (event.type == KeyEventType.KeyDown) {
                        draft = next
                        lastAdjust = System.currentTimeMillis()
                    }
                    true
                }
            } else {
                false
            }
        },
    ) {
        // Bars start at 0 for ranges that include negatives only via the offset below.
        val low = minOf(minValue, 0)
        val fraction = ((draft - low).toFloat() / (maxValue - low).coerceAtLeast(1)).coerceIn(0f, 1f)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(title, color = accent, fontSize = if (compact) 18.sp else 16.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(valueLabel(draft), color = Color.White, fontSize = if (compact) 30.sp else 40.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = 0.14f))) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(7.dp)).background(accent))
        }
        if (!compact) {
            Spacer(Modifier.height(8.dp))
            Text("◀  Left / Right to adjust  ▶", color = CopyMuted, fontSize = 14.sp, maxLines = 1)
        }
    }
}
