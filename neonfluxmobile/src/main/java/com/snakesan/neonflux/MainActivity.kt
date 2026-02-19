package com.snakesan.neonflux

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

enum class UploadState { IDLE, UPLOADING, SUCCESS, ACTIVE }

class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = getSharedPreferences("FluxConfig", Context.MODE_PRIVATE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { NeonTheme { FluxMobileUI() } }
    }

    @Composable
    fun NeonTheme(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Color(0xFF00F3FF),
                secondary = Color(0xFFFF0055),
                background = Color(0xFF050505),
                surface = Color(0xFF121212)
            ), content = content
        )
    }

    @Composable
    fun FluxMobileUI() {
        val context = LocalContext.current
        val view = LocalView.current
        val scope = rememberCoroutineScope()
        
        var intensity by remember { mutableFloatStateOf(prefs.getFloat("intensity", 50f)) }
        var bpm by remember { mutableFloatStateOf(prefs.getFloat("bpm", 60f)) }
        var selectedProfile by remember { mutableIntStateOf(prefs.getInt("profile", 0)) }
        var sleepMode by remember { mutableStateOf(prefs.getBoolean("sleep", false)) }
        
        // System Toggle State (Controls both Phone AND Watch)
        var isSystemRunning by remember { mutableStateOf(false) }
        
        val beatHistory = remember { mutableStateListOf<Long>() }
        
        var buttonState by remember { mutableStateOf(UploadState.IDLE) }
        
        val lastBeat = beatHistory.lastOrNull() ?: 0L
        val isSignalReceiving = (System.currentTimeMillis() - lastBeat) < 2500

        // Visual Feedback for Sync Button
        LaunchedEffect(isSignalReceiving, buttonState) {
            if (buttonState != UploadState.UPLOADING && buttonState != UploadState.SUCCESS) {
                buttonState = if (isSignalReceiving) UploadState.ACTIVE else UploadState.IDLE
            }
        }
        
        // --- HELPER FUNCTIONS ---

        fun sendConfigToWatch() {
            val buffer = ByteBuffer.allocate(7)
            buffer.put(selectedProfile.toByte())
            buffer.putInt(bpm.toInt())
            buffer.put(intensity.toInt().toByte())
            buffer.put(if(sleepMode) 1.toByte() else 0.toByte())
            
            Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
                nodes.forEach { Wearable.getMessageClient(context).sendMessage(it.id, "/clinical_conf", buffer.array()) }
            }
        }

        fun sendStopToWatch() {
            Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
                nodes.forEach { Wearable.getMessageClient(context).sendMessage(it.id, "/clinical_stop", byteArrayOf()) }
            }
        }

        fun toggleSystem() {
            isSystemRunning = !isSystemRunning
            
            if (isSystemRunning) {
                // START SEQUENCE
                val targetStartTime = System.currentTimeMillis() + 500
                
                // 1. Send ENGAGE to Watch
                val buffer = ByteBuffer.allocate(15)
                buffer.put(selectedProfile.toByte())
                buffer.putInt(bpm.toInt())
                buffer.put(intensity.toInt().toByte())
                buffer.put(if(sleepMode) 1.toByte() else 0.toByte())
                buffer.putLong(targetStartTime)

                Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
                    nodes.forEach { node -> 
                        Wearable.getMessageClient(context).sendMessage(node.id, "/clinical_engage", buffer.array()) 
                    }
                }

                // 2. Start Local Service
                val intent = Intent("com.snakesan.neonflux.START_LOCAL")
                intent.setPackage(context.packageName)
                intent.putExtra("profile", selectedProfile)
                intent.putExtra("bpm", bpm.toInt())
                intent.putExtra("intensity", intensity.toInt())
                intent.putExtra("target_time", targetStartTime) 
                context.sendBroadcast(intent)
                
            } else {
                // STOP SEQUENCE
                val intent = Intent("com.snakesan.neonflux.STOP_LOCAL")
                intent.setPackage(context.packageName)
                context.sendBroadcast(intent)
                sendStopToWatch()
            }
            
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
        
        fun emergencyStop() {
            isSystemRunning = false
            
            // Kill Local
            val intent = Intent("com.snakesan.neonflux.STOP_LOCAL") // Using standard stop
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)
            
            // Kill Watch
            sendStopToWatch()
            
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
            Toast.makeText(context, "SYSTEM HALT EXECUTED", Toast.LENGTH_LONG).show()
        }

        fun savePreset(slot: Int, name: String) {
            prefs.edit().apply {
                putFloat("preset_${slot}_bpm", bpm)
                putFloat("preset_${slot}_int", intensity)
                putInt("preset_${slot}_prof", selectedProfile)
                apply()
            }
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            Toast.makeText(context, "$name SEQUENCE ENCODED", Toast.LENGTH_SHORT).show()
        }

        fun loadPreset(slot: Int, name: String) {
            if (prefs.contains("preset_${slot}_bpm")) {
                bpm = prefs.getFloat("preset_${slot}_bpm", 60f)
                intensity = prefs.getFloat("preset_${slot}_int", 50f)
                selectedProfile = prefs.getInt("preset_${slot}_prof", 0)
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                Toast.makeText(context, "$name SEQUENCE LOADED", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "EMPTY BANK", Toast.LENGTH_SHORT).show()
            }
        }

        // --- LIFECYCLE & PERMISSIONS ---

        DisposableEffect(Unit) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when(intent?.action) {
                        "com.snakesan.neonflux.BEAT_EVENT" -> {
                            val now = System.currentTimeMillis()
                            beatHistory.add(now)
                            beatHistory.removeAll { it < now - 5000 }
                        }
                        "com.snakesan.neonflux.REMOTE_START_UI" -> isSystemRunning = true
                        "com.snakesan.neonflux.REMOTE_STOP_UI" -> isSystemRunning = false
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction("com.snakesan.neonflux.BEAT_EVENT")
                addAction("com.snakesan.neonflux.REMOTE_START_UI")
                addAction("com.snakesan.neonflux.REMOTE_STOP_UI")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            onDispose { context.unregisterReceiver(receiver) }
        }

        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
            onResult = { perms -> if (perms.values.all { it }) { val i = Intent(context, FluxService::class.java); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i) } }
        )
        
        LaunchedEffect(Unit) {
            val perms = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            if (perms.isNotEmpty()) launcher.launch(perms.toTypedArray()) else { val i = Intent(context, FluxService::class.java); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i) }
        }

        LaunchedEffect(intensity, bpm, selectedProfile, sleepMode) {
            prefs.edit().apply {
                putFloat("intensity", intensity)
                putFloat("bpm", bpm)
                putInt("profile", selectedProfile)
                putBoolean("sleep", sleepMode)
                apply()
            }
        }

        // --- UI LAYOUT ---

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .systemBarsPadding() 
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("NEON // FLUX", color = MaterialTheme.colorScheme.primary, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
            Text("CLINICAL CONTROLLER", color = Color.Gray, fontSize = 12.sp, letterSpacing = 2.sp)
            
            Spacer(modifier = Modifier.height(16.dp))

            Text("WAVEFORM", color = Color.LightGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                CyberButton("PULSE", selectedProfile == 0) { selectedProfile = 0 }
                CyberButton("GEIGER", selectedProfile == 1) { selectedProfile = 1 }
                CyberButton("THROB", selectedProfile == 2) { selectedProfile = 2 }
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            CyberSlider("FREQUENCY (BPM)", bpm, 30f..160f, "") { bpm = it }
            CyberSlider("INTENSITY", intensity, 0f..100f, "%") { intensity = it }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SLEEP PROTOCOL", color = if(sleepMode) MaterialTheme.colorScheme.primary else Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Disable sensors & screen during Clinical", color = Color.DarkGray, fontSize = 10.sp)
                }
                Switch(checked = sleepMode, onCheckedChange = { sleepMode = it }, colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary))
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            Text("MEMORY BANKS", color = Color.LightGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("LONG PRESS: WRITE // TAP: READ", color = Color.DarkGray, fontSize = 8.sp, modifier = Modifier.padding(bottom = 8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(), 
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val mod = Modifier.weight(1f)
                CyberPresetButton("ENGRAM", mod, { loadPreset(0, "ENGRAM") }, { savePreset(0, "ENGRAM") })
                CyberPresetButton("DAEMON", mod, { loadPreset(1, "DAEMON") }, { savePreset(1, "DAEMON") })
                CyberPresetButton("GHOST", mod, { loadPreset(2, "GHOST") }, { savePreset(2, "GHOST") })
                
                // MASTER SWITCH (Local + Watch)
                CyberLocalButton(
                    isActive = isSystemRunning,
                    modifier = mod,
                    onTap = { toggleSystem() },
                    onLongPress = { emergencyStop() }
                )
            }
            
            Spacer(modifier = Modifier.weight(1f))

            BioFluxMonitor(beatHistory)
            
            Spacer(modifier = Modifier.height(10.dp))

            // The big button now only serves as "Upload Settings" if the system isn't running
            SmartSyncButton(
                state = buttonState,
                onClick = {
                    if (buttonState == UploadState.IDLE || buttonState == UploadState.ACTIVE) {
                        sendConfigToWatch()
                        scope.launch {
                            buttonState = UploadState.UPLOADING
                            delay(3000) 
                            buttonState = UploadState.SUCCESS
                            delay(1000) 
                            buttonState = if(isSignalReceiving) UploadState.ACTIVE else UploadState.IDLE
                        }
                    }
                }
            )
        }
    }

    @Composable
    fun SmartSyncButton(state: UploadState, onClick: () -> Unit) {
        val progress by animateFloatAsState(targetValue = if (state == UploadState.UPLOADING) 1f else 0f, animationSpec = tween(durationMillis = 3000, easing = LinearEasing), label = "uploadProgress")
        val containerColor by animateColorAsState(targetValue = when(state) {
            UploadState.UPLOADING -> Color.DarkGray 
            UploadState.ACTIVE -> Color.DarkGray 
            UploadState.SUCCESS -> Color(0xFF00FF41) 
            UploadState.IDLE -> MaterialTheme.colorScheme.secondary
        }, label = "btnColor")

        Box(
            modifier = Modifier.fillMaxWidth().height(60.dp).clip(CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp)).background(containerColor)
                .clickable(onClick = onClick)
                .border(width = 2.dp, color = if(state == UploadState.ACTIVE) Color(0xFFFF0055) else Color.Transparent, shape = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp))
        ) {
            if (state == UploadState.UPLOADING) Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(progress).background(Color(0xFFFF9900)))
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = when(state) {
                        UploadState.IDLE -> "UPLOAD CONFIGURATION"
                        UploadState.UPLOADING -> "SYNCHRONIZING..."
                        UploadState.SUCCESS -> "SUCCESS"
                        UploadState.ACTIVE -> "LINK ESTABLISHED"
                    },
                    fontWeight = FontWeight.Bold, color = if(state == UploadState.SUCCESS) Color.Black else Color.White, fontSize = 16.sp, letterSpacing = 1.sp
                )
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun CyberPresetButton(text: String, modifier: Modifier = Modifier, onTap: () -> Unit, onLongPress: () -> Unit) {
        Box(
            modifier = modifier
                .height(40.dp)
                .clip(CutCornerShape(8.dp))
                .background(Color.DarkGray.copy(alpha=0.5f))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.3f), CutCornerShape(8.dp))
                .combinedClickable(onClick = onTap, onLongClick = onLongPress),
            contentAlignment = Alignment.Center
        ) {
            Text(text, color = Color.LightGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
    
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun CyberLocalButton(isActive: Boolean, modifier: Modifier = Modifier, onTap: () -> Unit, onLongPress: () -> Unit) {
        val bgColor = if (isActive) MaterialTheme.colorScheme.secondary else Color.DarkGray.copy(alpha=0.5f)
        val borderColor = if (isActive) MaterialTheme.colorScheme.secondary else Color.Gray.copy(alpha=0.3f)
        val textColor = if (isActive) Color.Black else Color.LightGray
        
        Box(
            modifier = modifier
                .height(40.dp)
                .clip(CutCornerShape(8.dp))
                .background(bgColor)
                .border(1.dp, borderColor, CutCornerShape(8.dp))
                .combinedClickable(onClick = onTap, onLongClick = onLongPress),
            contentAlignment = Alignment.Center
        ) {
            Text(if(isActive) "ACTIVE" else "ENABLE", color = textColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    fun BioFluxMonitor(beatHistory: List<Long>) {
        val traceColor = Color(0xFFFF0055) 
        val gridColor = Color(0xFF1A0A0F)
        val timeSource = remember { mutableLongStateOf(0L) }
        val lastBeat = beatHistory.lastOrNull() ?: 0L
        val isAlive = (System.currentTimeMillis() - lastBeat) < 2500

        LaunchedEffect(Unit) {
            while (isActive) {
                withFrameMillis { timeSource.longValue = System.currentTimeMillis() }
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("BIO-METRIC VISUALIZER", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(if (isAlive) "SIGNAL LOCK" else "AWAITING TELEMETRY", color = if (isAlive) traceColor else Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }

            Box(
                modifier = Modifier.fillMaxWidth().height(100.dp).background(Color.Black, shape = CutCornerShape(topEnd = 20.dp, bottomStart = 20.dp)).border(1.dp, if(isAlive) traceColor.copy(alpha=0.5f) else Color.DarkGray, shape = CutCornerShape(topEnd = 20.dp, bottomStart = 20.dp)).clip(CutCornerShape(topEnd = 20.dp, bottomStart = 20.dp))
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width; val h = size.height; val midY = h / 2
                    val now = timeSource.longValue; val scrollSpeed = 0.5f
                    val path = Path().apply { moveTo(0f, midY) }

                    for (x in 0 until w.toInt() step 2) {
                        val xPos = x.toFloat()
                        var totalYOffset = 0f
                        beatHistory.forEach { beatTime ->
                            val timeSinceBeat = now - beatTime
                            if (timeSinceBeat > -500 && timeSinceBeat < 4000) {
                                val beatX = w - (timeSinceBeat * scrollSpeed)
                                val dist = xPos - beatX
                                if (dist > -50 && dist < 150) {
                                     val p = (dist + 50) / 200f
                                     var beatOffset = 0f
                                     if (p < 0.1) beatOffset = 0f
                                     else if (p < 0.2) beatOffset = -10f * (p-0.1f)/0.1f 
                                     else if (p < 0.3) beatOffset = -10f 
                                     else if (p < 0.35) beatOffset = 20f * (p-0.3f)/0.05f 
                                     else if (p < 0.45) beatOffset = 20f - (140f * (p-0.35f)/0.1f) 
                                     else if (p < 0.55) beatOffset = -120f + (140f * (p-0.45f)/0.1f) 
                                     else if (p < 0.7) beatOffset = 10f 
                                     else if (p < 0.9) beatOffset = -15f * Math.sin((p-0.7)*Math.PI*5).toFloat()
                                     else beatOffset = 0f
                                     totalYOffset += beatOffset
                                }
                            }
                        }
                        val noise = Math.sin((xPos + now/2.0) / 20.0).toFloat() * 2f
                        if (x == 0) path.moveTo(xPos, midY + totalYOffset + noise)
                        else path.lineTo(xPos, midY + totalYOffset + noise)
                    }
                    drawPath(path = path, color = if(isAlive) traceColor else Color.DarkGray, style = Stroke(width = 3f))
                }
                if (isAlive && (System.currentTimeMillis() / 500) % 2 == 0L) Canvas(modifier = Modifier.padding(10.dp).align(Alignment.TopEnd).size(6.dp)) { drawCircle(Color.Red) }
            }
        }
    }

    @Composable
    fun CyberButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
        Button(onClick = onClick, shape = CutCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.DarkGray), modifier = Modifier.width(100.dp).height(40.dp)) {
            Text(text, color = if (isSelected) Color.Black else Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    fun CyberSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, onValueChange: (Float) -> Unit) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                Text("${value.toInt()}$unit", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Slider(value = value, onValueChange = onValueChange, valueRange = range, colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.secondary, activeTrackColor = MaterialTheme.colorScheme.secondary, inactiveTrackColor = Color.DarkGray))
        }
    }
}
