package com.snakesan.neonflux

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.wear.compose.material.*
import kotlinx.coroutines.*
import kotlin.math.*

// --- ENUMS & THEMES ---
enum class FluxState { MONITOR, ACTIVE }
enum class Deck { REACTOR, CLINICAL }

// Docs promise the app refuses to run below this and closes if it drops below
// this mid-session - see README "SAFE MODE".
const val SAFE_MODE_BATTERY_THRESHOLD = 15

// Once tripped, Safe Mode requires actually charging back up to this (not
// just the percentage happening to read fine again) before it releases -
// see updateSafeModeLock() below. Deliberately above the trip threshold so
// a percentage hovering right at 15% can't flap the lock on and off.
const val SAFE_MODE_RELEASE_THRESHOLD = 25

private const val SAFETY_PREFS = "FluxWatchSafety"
private const val KEY_SAFE_MODE_LOCKED = "safe_mode_locked"

// Persisted (not just derived live) so the lock survives the app closing/
// reopening or FluxService being restarted by the OS - the whole point of
// requiring actual charging is that a live "is the percentage OK right now"
// check isn't enough on its own. Both MainActivity and FluxService call this
// with their own fresh battery read, so either one can trip or release the
// lock independent of whether the other is currently running.
fun isSafeModeLockPersisted(context: Context): Boolean =
    context.getSharedPreferences(SAFETY_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SAFE_MODE_LOCKED, false)

private fun setSafeModeLockPersisted(context: Context, locked: Boolean) {
    context.getSharedPreferences(SAFETY_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SAFE_MODE_LOCKED, locked).apply()
}

// Re-evaluated fresh from live inputs every time this is called (never a
// one-shot latch), so a charger making poor contact just re-locks on the
// next check instead of leaving the app stuck waiting for a single
// "started charging" event that may never have cleanly fired. Returns the
// resulting locked state.
fun updateSafeModeLock(context: Context, batteryManager: BatteryManager, batteryLevel: Int): Boolean {
    val locked = isSafeModeLockPersisted(context)
    return when {
        !locked && batteryLevel < SAFE_MODE_BATTERY_THRESHOLD -> {
            setSafeModeLockPersisted(context, true)
            true
        }
        locked && batteryManager.isCharging && batteryLevel >= SAFE_MODE_RELEASE_THRESHOLD -> {
            setSafeModeLockPersisted(context, false)
            false
        }
        else -> locked
    }
}

// --- THEME/A11Y STATE ---
// Backs FluxCyan/Pink/Dark/Bg/TextMain/TextDim below, so every existing color
// reference in this file stays theme-aware without threading a value through
// every composable. Written from MainActivity.onCreate (persisted prefs) and
// from the /flux_a11y_sync + /flux_visual_theme broadcast receivers in
// NeonFluxWatchUI - never read directly, only through the computed properties.
object WatchThemeState {
    var primaryRaw by mutableStateOf(Color(0xFF00F3FF))
    var secondaryRaw by mutableStateOf(Color(0xFFFF0055))
    var panelRaw by mutableStateOf(Color(0xFF121212))
    var bgRaw by mutableStateOf(Color(0xFF050505))
    var textMainRaw by mutableStateOf(Color.White)
    var textDimRaw by mutableStateOf(Color.Gray)
    var isMonochrome by mutableStateOf(false)
    var isHighContrast by mutableStateOf(false)
    var fontScale by mutableFloatStateOf(1.0f)
}

// Mirrors the phone's smartContrast() (MainActivity.kt in neonfluxmobile) -
// boosts any color that isn't already bright to pure white, so accessory
// colors on a dark background stay readable in High Contrast mode without
// needing a whole separate palette.
private fun watchSmartContrast(base: Color, highContrast: Boolean, monochrome: Boolean): Color {
    if (monochrome || !highContrast) return base
    return if (base.luminance() <= 0.7f) Color.White else base
}

val FluxCyan: Color get() = watchSmartContrast(WatchThemeState.primaryRaw, WatchThemeState.isHighContrast, WatchThemeState.isMonochrome)
val FluxPink: Color get() = watchSmartContrast(WatchThemeState.secondaryRaw, WatchThemeState.isHighContrast, WatchThemeState.isMonochrome)
val FluxDark: Color get() = WatchThemeState.panelRaw
val FluxBg: Color get() = WatchThemeState.bgRaw
val FluxTextMain: Color get() = watchSmartContrast(WatchThemeState.textMainRaw, WatchThemeState.isHighContrast, WatchThemeState.isMonochrome)
val FluxTextDim: Color get() = watchSmartContrast(WatchThemeState.textDimRaw, WatchThemeState.isHighContrast, WatchThemeState.isMonochrome)

// Font-scale-aware sp, mirroring the phone's scaledSp().
val Number.wsp: TextUnit get() = (this.toFloat() * WatchThemeState.fontScale).sp

// --- HELPERS ---
fun vibrateAck(context: Context) {
    val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    vibrateAck(v)
}

fun vibrateAck(vibrator: Vibrator) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
    } else {
        vibrator.vibrate(100)
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    // Configs
    var clinicalProfile by mutableIntStateOf(0)
    var clinicalBpm by mutableIntStateOf(60)
    var clinicalIntensity by mutableIntStateOf(50)
    var clinicalSleep by mutableStateOf(false)
    var reactorProfile by mutableIntStateOf(0)
    
    // Vis
    var isSyncing by mutableStateOf(false)

    // Service Binding
    var fluxService: FluxService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as FluxService.LocalBinder
            fluxService = binder.getService()
            isBound = true
            updateServiceState(currentDeckState, currentAudioState, currentActiveState)
        }
        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            fluxService = null
        }
    }

    private var currentDeckState = Deck.REACTOR
    private var currentAudioState = false
    private var currentActiveState = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("FluxWatchConfig", Context.MODE_PRIVATE)
        
        // Restore
        clinicalProfile = prefs.getInt("profile", 0)
        clinicalBpm = prefs.getInt("bpm", 60)
        clinicalIntensity = prefs.getInt("intensity", 50)
        clinicalSleep = prefs.getBoolean("sleep", false)
        reactorProfile = prefs.getInt("reactor_profile", 0)

        // Restore a11y/theme (see /flux_a11y_sync, /flux_visual_theme in
        // FluxService) - defaults match this file's original hardcoded colors
        // exactly, so anyone who has never touched the phone's theme/a11y
        // controls sees no change.
        WatchThemeState.isHighContrast = prefs.getBoolean("a11y_contrast", false)
        WatchThemeState.fontScale = prefs.getFloat("a11y_font_scale", 1.0f)
        WatchThemeState.isMonochrome = prefs.getBoolean("theme_mono", false)
        WatchThemeState.primaryRaw = Color(prefs.getInt("theme_primary", Color(0xFF00F3FF).toArgb()))
        WatchThemeState.secondaryRaw = Color(prefs.getInt("theme_secondary", Color(0xFFFF0055).toArgb()))
        WatchThemeState.panelRaw = Color(prefs.getInt("theme_l1", Color(0xFF121212).toArgb()))
        WatchThemeState.bgRaw = Color(prefs.getInt("theme_bg", Color(0xFF050505).toArgb()))
        WatchThemeState.textMainRaw = Color(prefs.getInt("theme_text_main", Color.White.toArgb()))
        WatchThemeState.textDimRaw = Color(prefs.getInt("theme_l2", Color.Gray.toArgb()))

        setContent { MaterialTheme { NeonFluxWatchUI(this) } }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, FluxService::class.java).also { intent ->
            startForegroundService(intent)
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    // --- SERVICE CONTROLS ---
    fun updateServiceState(deck: Deck, isAudio: Boolean, isActive: Boolean) {
        currentDeckState = deck
        currentAudioState = isAudio
        currentActiveState = isActive

        if (!isBound || fluxService == null) return

        if (isActive) {
            if (deck == Deck.REACTOR) {
                fluxService?.setReactorMode(isAudio, reactorProfile)
            } else {
                // For Clinical, we use manual triggers now via button, 
                // but this keeps state consistent if switching decks while running
            }
        } else {
            fluxService?.haltLoops() 
        }
    }
    
    fun terminateApp(reason: String? = null) {
        fluxService?.haltService(reason)
        finish()
    }

    fun saveReactorProfile(profile: Int) {
        reactorProfile = profile
        prefs.edit().putInt("reactor_profile", profile).apply()
        updateServiceState(currentDeckState, currentAudioState, currentActiveState)
    }
}

// --- UI ---

@Composable
fun FluxButton(text: String, onClick: () -> Unit, color: Color = FluxCyan, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(35.dp)
            .clip(CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .background(color.copy(alpha = 0.2f))
            .border(1.dp, color.copy(alpha = 0.5f), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = color, fontSize = 10.wsp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

@Composable
fun FluxLabel(title: String, value: String, color: Color = FluxTextMain) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = FluxTextDim, fontSize = 8.wsp, fontWeight = FontWeight.Bold)
        Text(value, color = color, fontSize = 16.wsp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
fun RunningIndicator(color: Color, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "runningPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(700), repeatMode = RepeatMode.Reverse),
        label = "runningPulseAlpha"
    )
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color.copy(alpha = pulseAlpha)))
        Spacer(Modifier.width(4.dp))
        Text("RUNNING", color = color.copy(alpha = pulseAlpha), fontSize = 8.wsp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
fun SafeModeScreen(batteryLevel: Int) {
    Box(Modifier.fillMaxSize().background(FluxBg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SAFE MODE", color = FluxPink, fontSize = 14.wsp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Spacer(Modifier.height(10.dp))
            Text("CORE $batteryLevel%", color = FluxTextMain, fontSize = 22.wsp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "CHARGE ABOVE $SAFE_MODE_RELEASE_THRESHOLD% TO CONTINUE",
                color = FluxTextDim, fontSize = 9.wsp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "50%+ RECOMMENDED BEFORE STARTING A NEW SESSION",
                color = FluxTextDim, fontSize = 7.wsp, letterSpacing = 1.sp
            )
        }
    }
}

// Full-screen takeover while Emergency Protocol is engaged - reuses the
// "[ FIRMWARE UPDATING ]" sync screen's look (same bracketed title, RX/TX
// status line, and bordered progress-bar box) rather than a bespoke design,
// so it reads as the same family of system-level overlay. Unlike that sync
// screen, this one is sticky: it has no animation of its own that finishes
// and dismisses it - it stays up for as long as isEmergencyActive is true
// (driven by FluxService's real timer/halt, not a local animation), which
// makes it the definitive on-watch indicator that this mode is engaged.
// The whole screen is the stop target (not a small button, and not the
// 2-finger/3s Lockdown Gesture used elsewhere) - continuous max-strength
// vibration makes fine motor control and multi-touch timing an unreasonable
// ask, so a single tap anywhere, or the physical back button, both HALT it.
@Composable
fun EmergencyOverrideScreen(endTime: Long, durationMin: Int, texture: Int, onHalt: () -> Unit) {
    val isIndefinite = durationMin < 0
    BackHandler(enabled = true) { onHalt() }

    var remainingText by remember { mutableStateOf(if (isIndefinite) "INDEFINITE" else "--:--") }
    var remainingFraction by remember { mutableFloatStateOf(1f) }
    val totalMs = remember(durationMin) { durationMin * 60_000L }

    LaunchedEffect(endTime, isIndefinite, totalMs) {
        if (!isIndefinite) {
            while (true) {
                val remainingMs = (endTime - System.currentTimeMillis()).coerceAtLeast(0)
                val totalSec = remainingMs / 1000
                remainingText = "%02d:%02d".format(totalSec / 60, totalSec % 60)
                remainingFraction = if (totalMs > 0) (remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f) else 0f
                if (remainingMs <= 0) break
                delay(250)
            }
        }
    }

    // INF mode has no fixed duration to drain toward, so the bar pulses
    // (full <-> dim) instead of counting down - still a live, sticky signal
    // that the override is running, just an indefinite one.
    val infiniteTransition = rememberInfiniteTransition(label = "emergencyPulse")
    val pulseRatio by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "emergencyPulseRatio"
    )
    val barFraction = if (isIndefinite) pulseRatio else remainingFraction

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FluxBg)
            .clickable(onClick = onHalt),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("[ EMERGENCY PROTOCOL ]", color = FluxPink, fontSize = 10.wsp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            // Names the active texture on-wrist so mid-test you can confirm
            // which one you're actually feeling right now - the whole point
            // of this screen while textures are being A/B tested.
            Text(
                "TX: ${EmergencyTexture.label(texture)} // ${if (isIndefinite) "CONTINUOUS" else remainingText}",
                color = FluxTextDim, fontSize = 8.wsp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(15.dp))
            Box(Modifier.width(120.dp).height(8.dp).border(1.dp, FluxPink).background(FluxDark)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(barFraction).background(FluxPink))
            }
            Spacer(Modifier.height(15.dp))
            Text("TAP ANYWHERE TO HALT", color = FluxTextDim, fontSize = 8.wsp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NeonFluxWatchUI(activity: MainActivity) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val vibrator = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }

    var currentDeck by remember { mutableStateOf(Deck.REACTOR) }
    var showExitDialog by remember { mutableStateOf(false) }
    var profileNameToast by remember { mutableStateOf("") }
    val batteryManager = remember { context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager }
    // Read live on first composition (not a placeholder default) so Safe Mode's
    // launch-time refusal is correct on the very first frame, not just after the
    // first 5s poll tick below.
    var batteryLevel by remember { mutableIntStateOf(batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)) }
    // Same reasoning as batteryLevel above: derive this synchronously from the
    // persisted lock + the live reading we just took, so a still-locked Safe
    // Mode from before this launch shows correctly on the very first frame
    // instead of waiting for the first poll tick.
    var isSafeModeLocked by remember { mutableStateOf(updateSafeModeLock(context, batteryManager, batteryLevel)) }
    var timeRemaining by remember { mutableStateOf("CALC...") }
    var isAudioMode by remember { mutableStateOf(false) }
    var fluxState by remember { mutableStateOf(FluxState.MONITOR) }
    var isClinicalActive by remember { mutableStateOf(false) }
    var countdownValue by remember { mutableIntStateOf(0) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var scrollAccumulator by remember { mutableFloatStateOf(0f) }
    var visualMotionMag by remember { mutableFloatStateOf(0f) }

    // --- EMERGENCY PROTOCOL STATE ---
    // Distinct from isLockedDown/isClinicalActive: this takes over from
    // whatever deck/activity was running, regardless of state, until HALTed
    // or its timer runs out - see EMERGENCY_STATE receiver below.
    var isEmergencyActive by remember { mutableStateOf(false) }
    var emergencyDurationMin by remember { mutableIntStateOf(-1) }
    var emergencyEndTime by remember { mutableLongStateOf(0L) }
    var emergencyTexture by remember { mutableIntStateOf(EmergencyTexture.STEADY) }
    var previousDeckBeforeEmergency by remember { mutableStateOf(Deck.REACTOR) }

    // --- REMOTE SYNC RECEIVER ---
    DisposableEffect(Unit) {
        val syncReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.snakesan.neonflux.REMOTE_CONFIG") {
                    val p = intent.getIntExtra("profile", 0)
                    val b = intent.getIntExtra("bpm", 60)
                    val i = intent.getIntExtra("intensity", 50)
                    val s = intent.getBooleanExtra("sleep", false)

                    activity.getSharedPreferences("FluxWatchConfig", Context.MODE_PRIVATE).edit().apply {
                        putInt("profile", p); putInt("bpm", b); putInt("intensity", i); putBoolean("sleep", s); apply()
                    }

                    activity.clinicalProfile = p
                    activity.clinicalBpm = b
                    activity.clinicalIntensity = i
                    activity.clinicalSleep = s
                    
                    if (!isClinicalActive) activity.isSyncing = true
                }
            }
        }
        val filter = IntentFilter("com.snakesan.neonflux.REMOTE_CONFIG")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(syncReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(syncReceiver, filter)
        }
        onDispose { try { context.unregisterReceiver(syncReceiver) } catch (e: Exception) {} }
    }

    // --- REMOTE ENGAGE RECEIVER ---
    // Fired by FluxService when a /clinical_engage arrives from the phone, so a
    // phone-initiated session still shows the watch's countdown and arms the
    // two-finger lockdown gesture (isLockedDown below) - without this the watch
    // would be running a session with no on-device emergency stop available.
    DisposableEffect(Unit) {
        val engageReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.snakesan.neonflux.REMOTE_ENGAGE") {
                    currentDeck = Deck.CLINICAL
                    isClinicalActive = true
                }
            }
        }
        val filter = IntentFilter("com.snakesan.neonflux.REMOTE_ENGAGE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(engageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(engageReceiver, filter)
        }
        onDispose { try { context.unregisterReceiver(engageReceiver) } catch (e: Exception) {} }
    }

    // --- EMERGENCY PROTOCOL RECEIVER ---
    // Fired by FluxService on /emergency_override (on) and on any halt path
    // (off - phone HALT, watch single-tap stop, or the timer running out).
    // Registered up-front like the other receivers above so it keeps working
    // no matter what full-screen state (Safe Mode, sync, exit dialog) is
    // currently showing.
    DisposableEffect(Unit) {
        val emergencyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.snakesan.neonflux.EMERGENCY_STATE") {
                    val active = intent.getBooleanExtra("active", false)
                    val durationMin = intent.getIntExtra("duration_min", -1)
                    val texture = intent.getIntExtra("texture", EmergencyTexture.STEADY)
                    if (active) {
                        if (!isEmergencyActive) previousDeckBeforeEmergency = currentDeck
                        isEmergencyActive = true
                        emergencyDurationMin = durationMin
                        emergencyEndTime = if (durationMin >= 0) System.currentTimeMillis() + durationMin * 60_000L else 0L
                        emergencyTexture = texture
                    } else {
                        isEmergencyActive = false
                        // Graceful return: standby on whichever deck was active before -
                        // never auto-resume a Clinical beat or Reactor session on its own.
                        currentDeck = previousDeckBeforeEmergency
                        fluxState = FluxState.MONITOR
                        isClinicalActive = false
                        countdownValue = 0
                    }
                }
            }
        }
        val filter = IntentFilter("com.snakesan.neonflux.EMERGENCY_STATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(emergencyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(emergencyReceiver, filter)
        }
        onDispose { try { context.unregisterReceiver(emergencyReceiver) } catch (e: Exception) {} }
    }

    // --- REMOTE A11Y / THEME RECEIVERS ---
    // FluxService already persists these to SharedPreferences; this just keeps
    // WatchThemeState (and therefore every themed color/text size on screen)
    // live-updated while the app is open, without needing a restart to apply.
    DisposableEffect(Unit) {
        val a11yReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.snakesan.neonflux.REMOTE_A11Y") {
                    WatchThemeState.isHighContrast = intent.getBooleanExtra("high_contrast", WatchThemeState.isHighContrast)
                    WatchThemeState.fontScale = intent.getFloatExtra("font_scale", WatchThemeState.fontScale)
                }
            }
        }
        val filter = IntentFilter("com.snakesan.neonflux.REMOTE_A11Y")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(a11yReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(a11yReceiver, filter)
        }
        onDispose { try { context.unregisterReceiver(a11yReceiver) } catch (e: Exception) {} }
    }

    DisposableEffect(Unit) {
        val themeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.snakesan.neonflux.REMOTE_THEME") {
                    WatchThemeState.isMonochrome = intent.getBooleanExtra("mono", WatchThemeState.isMonochrome)
                    WatchThemeState.primaryRaw = Color(intent.getIntExtra("primary", WatchThemeState.primaryRaw.toArgb()))
                    WatchThemeState.secondaryRaw = Color(intent.getIntExtra("secondary", WatchThemeState.secondaryRaw.toArgb()))
                    WatchThemeState.panelRaw = Color(intent.getIntExtra("l1", WatchThemeState.panelRaw.toArgb()))
                    WatchThemeState.textDimRaw = Color(intent.getIntExtra("l2", WatchThemeState.textDimRaw.toArgb()))
                    WatchThemeState.textMainRaw = Color(intent.getIntExtra("text_main", WatchThemeState.textMainRaw.toArgb()))
                    WatchThemeState.bgRaw = Color(intent.getIntExtra("bg", WatchThemeState.bgRaw.toArgb()))
                }
            }
        }
        val filter = IntentFilter("com.snakesan.neonflux.REMOTE_THEME")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(themeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(themeReceiver, filter)
        }
        onDispose { try { context.unregisterReceiver(themeReceiver) } catch (e: Exception) {} }
    }

    // --- KILL SWITCH ---
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.snakesan.overseer.KILL_COMMAND") activity.terminateApp()
            }
        }
        val filter = IntentFilter("com.snakesan.overseer.KILL_COMMAND")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, "com.snakesan.neonflux.permission.OVERSEER_CONTROL", null, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, "com.snakesan.neonflux.permission.OVERSEER_CONTROL", null)
        }
        onDispose { try { context.unregisterReceiver(receiver) } catch (e: Exception) {} }
    }

    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val accelerometer = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }

    // No reason to keep sampling wrist motion while Safe Mode refuses to run
    // anything with it - skip registering the listener entirely while locked,
    // matching the "optimize background power draw during the lockout" intent
    // of Safe Mode itself.
    DisposableEffect(isSafeModeLocked) {
        if (isSafeModeLocked) return@DisposableEffect onDispose {}
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event?.let {
                    val x = it.values[0]; val y = it.values[1]; val z = it.values[2]
                    val accel = sqrt(x*x + y*y + z*z) - 9.8f
                    visualMotionMag = (visualMotionMag * 0.9f) + (abs(accel) * 0.1f)
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensorManager.unregisterListener(listener) }
    }

    // STATE PUSH TO SERVICE
    LaunchedEffect(currentDeck, isAudioMode, fluxState, isClinicalActive, activity.reactorProfile) {
        val isActiveSession = (fluxState == FluxState.ACTIVE) || isClinicalActive
        activity.updateServiceState(currentDeck, isAudioMode, isActiveSession)
    }
    
    val isActiveSession = (fluxState == FluxState.ACTIVE) || isClinicalActive
    val window = (context as? Activity)?.window
    DisposableEffect(isActiveSession) {
        if (window != null) {
            if (isActiveSession) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // BATTERY MON - deliberately keyed on Unit, not on isClinicalActive/
    // isAudioMode/fluxState. Those flip constantly during an active Reactor
    // session (fluxState toggles ACTIVE/MONITOR with every motion change),
    // and keying a LaunchedEffect on them was restarting this coroutine - and
    // therefore its delay(5000) - far more often than every 5s, so
    // batteryLevel effectively stopped updating during exactly the sessions
    // Safe Mode needs to be watching. Read the current values of those flags
    // fresh each iteration instead of keying on them.
    LaunchedEffect(Unit) {
        while(isActive) {
            val lvl = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            batteryLevel = lvl
            isSafeModeLocked = updateSafeModeLock(context, batteryManager, lvl)
            val burnRate = if(isClinicalActive) 0.3 else if (isAudioMode) 0.8 else 0.5
            val minsLeft = (lvl / burnRate).toInt()
            timeRemaining = "${minsLeft / 60}h ${minsLeft % 60}m"
            delay(5000)
        }
    }

    LaunchedEffect(profileNameToast) { if (profileNameToast.isNotEmpty()) { delay(1500); profileNameToast = "" } }

    val isLockedDown = isClinicalActive && countdownValue == 0
    val isReactorRunning = fluxState == FluxState.ACTIVE && currentDeck == Deck.REACTOR
    val isSessionRunning = isLockedDown || isReactorRunning || isEmergencyActive

    // SAFE MODE: refuse to run below the threshold. If the battery crosses under
    // it while a session is actually running, close out rather than let a session
    // that's already draining power keep going.
    LaunchedEffect(isSafeModeLocked) {
        if (isSafeModeLocked && isSessionRunning) {
            activity.terminateApp(reason = "LOW_BATTERY")
        }
    }

    // Bail out before BackHandler/countdown/auto-sleep/motion-wake are even
    // registered, so none of them can react while the lockout screen is up -
    // the back button falls through to the normal system behavior instead of
    // being intercepted by the (unregistered) exit dialog.
    if (isSafeModeLocked) {
        SafeModeScreen(batteryLevel)
        return
    }

    // Emergency Protocol takes over regardless of activity or state: bail out
    // before BackHandler/countdown/auto-sleep/motion-wake/gestures are
    // registered (same reasoning as Safe Mode above), so nothing but its own
    // dedicated stop control (or the timer) can end it.
    if (isEmergencyActive) {
        EmergencyOverrideScreen(
            endTime = emergencyEndTime,
            durationMin = emergencyDurationMin,
            texture = emergencyTexture,
            onHalt = { activity.fluxService?.haltEmergencyFromWatch() }
        )
        return
    }

    // Dark curtain now follows the phone's SLEEP PROTOCOL toggle only - outside
    // of sleep mode we keep the deck UI up so it's clear what's running.
    val shouldDarken = activity.clinicalSleep && isSessionRunning && !showExitDialog
    val curtainAlpha by animateFloatAsState(targetValue = if (shouldDarken) 1f else 0f, animationSpec = tween(800))

    BackHandler(enabled = !showExitDialog && !isLockedDown) { showExitDialog = true }

    LaunchedEffect(isClinicalActive) {
        if (isClinicalActive) {
            for (i in 3 downTo 1) { countdownValue = i; vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)); delay(1000) }
            countdownValue = 0
        }
    }
    
    // REACTOR AUTO-SLEEP
    LaunchedEffect(fluxState) {
        if (fluxState == FluxState.ACTIVE) {
            while (isActive) {
                delay(1000)
                if (System.currentTimeMillis() - lastInteractionTime > 5000) {
                    fluxState = FluxState.MONITOR
                }
            }
        }
    }
    
    // MOTION WAKE
    LaunchedEffect(visualMotionMag) {
        if (currentDeck == Deck.REACTOR && !isClinicalActive && !activity.isSyncing && !isEmergencyActive) {
            if (visualMotionMag > 5.0f) { 
                lastInteractionTime = System.currentTimeMillis()
                fluxState = FluxState.ACTIVE
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FluxBg)
            .onRotaryScrollEvent {
                if (!isLockedDown && !showExitDialog && !activity.isSyncing) { 
                    scrollAccumulator += it.verticalScrollPixels
                    if (abs(scrollAccumulator) > 60f) {
                        vibrateAck(vibrator)
                        lastInteractionTime = System.currentTimeMillis()
                        currentDeck = if (currentDeck == Deck.REACTOR) Deck.CLINICAL else Deck.REACTOR
                        scrollAccumulator = 0f; true
                    } else false
                } else false
            }
            .focusRequester(focusRequester).focusable()
            .pointerInput(fluxState, isLockedDown, currentDeck) {
                detectTapGestures(
                    onDoubleTap = {
                        if (currentDeck == Deck.REACTOR && !isLockedDown && !activity.isSyncing) {
                            val next = (activity.reactorProfile + 1) % 3
                            activity.saveReactorProfile(next)
                            vibrateAck(vibrator)
                            profileNameToast = when(next) { 0 -> "PULSE"; 1 -> "GEIGER"; else -> "THROB" }
                            lastInteractionTime = System.currentTimeMillis(); fluxState = FluxState.ACTIVE
                        }
                    },
                    onTap = { if (!isLockedDown && !activity.isSyncing) { lastInteractionTime = System.currentTimeMillis(); fluxState = FluxState.ACTIVE } }
                )
            }
            .pointerInput(isLockedDown) {
                 if (isLockedDown) {
                     awaitEachGesture {
                         val down = awaitFirstDown(requireUnconsumed = false)
                         val start = System.currentTimeMillis()
                         var holding = true
                         do {
                             val ev = awaitPointerEvent()
                             if (ev.changes.size < 2 && System.currentTimeMillis() - start > 200) holding = false
                             if (holding && ev.changes.size >= 2 && System.currentTimeMillis() - start > 3000) {
                                 // --- EMERGENCY CRASH GESTURE ---
                                 // Stop Local
                                 isClinicalActive = false
                                 vibrator.vibrate(VibrationEffect.createOneShot(500, 255))
                                 // Stop Remote (Phone)
                                 activity.fluxService?.broadcastStopToPhone()
                                 holding = false
                             }
                         } while (ev.changes.any { it.pressed } && holding)
                     }
                 }
            }
    ) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        if (activity.isSyncing) {
            val syncProgress = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                syncProgress.animateTo(1f, animationSpec = tween(2500, easing = LinearEasing))
                delay(200) 
                activity.isSyncing = false
                currentDeck = Deck.CLINICAL 
            }
            Box(Modifier.fillMaxSize().zIndex(500f).background(FluxBg), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("[ FIRMWARE UPDATING ]", color = FluxPink, fontSize = 10.wsp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("RX: CONFIG_PACKET_01", color = FluxTextDim, fontSize = 8.wsp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(15.dp))
                    Box(Modifier.width(120.dp).height(8.dp).border(1.dp, FluxCyan).background(FluxDark)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(syncProgress.value).background(FluxCyan))
                    }
                }
            }
        }

        if (curtainAlpha < 1.0f) {
            Text(
                text = if (currentDeck == Deck.REACTOR) "REACTOR" else "CLINICAL",
                color = if (currentDeck == Deck.REACTOR) FluxCyan else FluxPink,
                fontSize = 10.wsp, fontWeight = FontWeight.Black, letterSpacing = 2.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp)
            )
            if (isSessionRunning) {
                RunningIndicator(
                    color = if (currentDeck == Deck.REACTOR) FluxCyan else FluxPink,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 34.dp)
                )
            }

            if (currentDeck == Deck.REACTOR) {
                Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(110.dp)) {
                        CircularProgressIndicator(progress = 1f, indicatorColor = FluxDark, strokeWidth = 6.dp, modifier = Modifier.fillMaxSize())
                        val batCol = if(batteryLevel < 20) FluxPink else if(batteryLevel < 50) Color(0xFFFF9900) else FluxCyan
                        CircularProgressIndicator(progress = batteryLevel / 100f, indicatorColor = batCol, strokeWidth = 6.dp, modifier = Modifier.fillMaxSize())
                        val fluxVis = (visualMotionMag / 10f).coerceIn(0f, 1f)
                        if (fluxVis > 0.1f) CircularProgressIndicator(progress = fluxVis, indicatorColor = FluxTextMain.copy(alpha=0.5f), strokeWidth = 2.dp, modifier = Modifier.fillMaxSize().padding(8.dp))

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("CORE", color = FluxTextDim, fontSize = 8.wsp, fontWeight = FontWeight.Bold)
                            Text("$batteryLevel%", color = FluxTextMain, fontSize = 24.wsp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(2.dp))
                            Text(timeRemaining, color = FluxCyan, fontSize = 10.wsp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    FluxButton(
                        text = if (isAudioMode) "AUDIO: ON" else "AUDIO: OFF",
                        onClick = { isAudioMode = !isAudioMode; lastInteractionTime = System.currentTimeMillis() },
                        color = if (isAudioMode) FluxPink else FluxCyan,
                        modifier = Modifier.width(100.dp)
                    )
                }
            } else {
                val pName = when(activity.clinicalProfile) { 0 -> "PULSE"; 1 -> "GEIGER"; else -> "THROB" }
                Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(15.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp), Arrangement.SpaceBetween) {
                         FluxLabel("BPM", "${activity.clinicalBpm}", FluxCyan)
                         FluxLabel("INT", "${activity.clinicalIntensity}%", FluxCyan)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("PROG: ", color = FluxTextDim, fontSize = 10.wsp, fontWeight = FontWeight.Bold)
                        Text(pName, color = FluxTextMain, fontSize = 10.wsp, fontWeight = FontWeight.Bold)
                    }
                    if (activity.clinicalSleep) Text("[SLEEP MODE ACTIVE]", color = FluxPink, fontSize = 8.wsp, modifier = Modifier.padding(top = 2.dp))
                    else Spacer(Modifier.height(14.dp))
                    Spacer(Modifier.height(10.dp))
                    FluxButton(text = "INITIALIZE", onClick = { 
                        // Trigger Remote Start
                        activity.fluxService?.broadcastStartToPhone(
                            activity.clinicalBpm, 
                            activity.clinicalIntensity, 
                            activity.clinicalProfile
                        )
                        isClinicalActive = true 
                    }, color = FluxPink, modifier = Modifier.width(110.dp))
                }
            }
        }
        
        if (profileNameToast.isNotEmpty()) {
            Box(Modifier.fillMaxSize().zIndex(400f), Alignment.Center) {
                Box(Modifier.background(FluxDark.copy(alpha=0.9f), CutCornerShape(10.dp)).border(1.dp, FluxCyan, CutCornerShape(10.dp)).padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Text(profileNameToast, color = FluxCyan, fontWeight = FontWeight.Bold, fontSize = 14.wsp)
                }
            }
        }
        
        if (curtainAlpha > 0f) Box(Modifier.fillMaxSize().zIndex(100f).background(FluxBg.copy(alpha=curtainAlpha)))
        if (countdownValue > 0) Box(Modifier.fillMaxSize().zIndex(200f).background(FluxBg), Alignment.Center) { Text("$countdownValue", fontSize = 60.wsp, fontWeight = FontWeight.Black, color = FluxPink) }
        
        if (showExitDialog) {
            Box(Modifier.fillMaxSize().zIndex(300f).background(FluxBg.copy(0.95f)), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("TERMINATE?", color = FluxCyan, fontSize = 12.wsp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(15.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FluxButton("RESUME", { showExitDialog = false }, FluxCyan, Modifier.width(70.dp))
                        FluxButton("HALT", { activity.terminateApp() }, FluxPink, Modifier.width(60.dp))
                    }
                }
            }
        }
    }
}
