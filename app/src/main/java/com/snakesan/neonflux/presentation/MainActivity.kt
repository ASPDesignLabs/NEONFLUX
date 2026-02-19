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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.wear.compose.material.*
import kotlinx.coroutines.*
import kotlin.math.*

// --- ENUMS & THEMES ---
enum class FluxState { MONITOR, ACTIVE }
enum class Deck { REACTOR, CLINICAL }

val FluxCyan = Color(0xFF00F3FF)
val FluxPink = Color(0xFFFF0055)
val FluxDark = Color(0xFF121212)
val FluxBg = Color(0xFF050505)

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
    
    fun terminateApp() {
        fluxService?.haltService()
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
        Text(text = text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

@Composable
fun FluxLabel(title: String, value: String, color: Color = Color.White) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
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
    var batteryLevel by remember { mutableIntStateOf(100) }
    var timeRemaining by remember { mutableStateOf("CALC...") }
    var isAudioMode by remember { mutableStateOf(false) }
    var fluxState by remember { mutableStateOf(FluxState.MONITOR) }
    var isClinicalActive by remember { mutableStateOf(false) }
    var countdownValue by remember { mutableIntStateOf(0) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var scrollAccumulator by remember { mutableFloatStateOf(0f) }
    var visualMotionMag by remember { mutableFloatStateOf(0f) }
    
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
            context.registerReceiver(syncReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(syncReceiver, filter)
        }
        onDispose { try { context.unregisterReceiver(syncReceiver) } catch (e: Exception) {} }
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
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { try { context.unregisterReceiver(receiver) } catch (e: Exception) {} }
    }

    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val accelerometer = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }

    DisposableEffect(Unit) {
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

    // BATTERY MON
    LaunchedEffect(isClinicalActive, isAudioMode, fluxState) {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        while(isActive) {
            val lvl = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            batteryLevel = lvl
            val burnRate = if(isClinicalActive) 0.3 else if (isAudioMode) 0.8 else 0.5 
            val minsLeft = (lvl / burnRate).toInt()
            timeRemaining = "${minsLeft / 60}h ${minsLeft % 60}m"
            delay(5000)
        }
    }
    
    LaunchedEffect(profileNameToast) { if (profileNameToast.isNotEmpty()) { delay(1500); profileNameToast = "" } }

    val isLockedDown = isClinicalActive && countdownValue == 0
    val shouldDarken = isLockedDown || (fluxState == FluxState.ACTIVE && currentDeck == Deck.REACTOR && !showExitDialog)
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
        if (currentDeck == Deck.REACTOR && !isClinicalActive && !activity.isSyncing) {
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
                    Text("[ FIRMWARE UPDATING ]", color = FluxPink, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("RX: CONFIG_PACKET_01", color = Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold)
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
                fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp)
            )

            if (currentDeck == Deck.REACTOR) {
                Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(110.dp)) {
                        CircularProgressIndicator(progress = 1f, indicatorColor = FluxDark, strokeWidth = 6.dp, modifier = Modifier.fillMaxSize())
                        val batCol = if(batteryLevel < 20) FluxPink else if(batteryLevel < 50) Color(0xFFFF9900) else FluxCyan
                        CircularProgressIndicator(progress = batteryLevel / 100f, indicatorColor = batCol, strokeWidth = 6.dp, modifier = Modifier.fillMaxSize())
                        val fluxVis = (visualMotionMag / 10f).coerceIn(0f, 1f)
                        if (fluxVis > 0.1f) CircularProgressIndicator(progress = fluxVis, indicatorColor = Color.White.copy(alpha=0.5f), strokeWidth = 2.dp, modifier = Modifier.fillMaxSize().padding(8.dp))

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("CORE", color = Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                            Text("$batteryLevel%", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(2.dp))
                            Text(timeRemaining, color = FluxCyan, fontSize = 10.sp)
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
                        Text("PROG: ", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(pName, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    if (activity.clinicalSleep) Text("[SLEEP MODE ACTIVE]", color = FluxPink, fontSize = 8.sp, modifier = Modifier.padding(top = 2.dp))
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
                    Text(profileNameToast, color = FluxCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
        
        if (curtainAlpha > 0f) Box(Modifier.fillMaxSize().zIndex(100f).background(FluxBg.copy(alpha=curtainAlpha)))
        if (countdownValue > 0) Box(Modifier.fillMaxSize().zIndex(200f).background(FluxBg), Alignment.Center) { Text("$countdownValue", fontSize = 60.sp, fontWeight = FontWeight.Black, color = FluxPink) }
        
        if (showExitDialog) {
            Box(Modifier.fillMaxSize().zIndex(300f).background(FluxBg.copy(0.95f)), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("TERMINATE?", color = FluxCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
