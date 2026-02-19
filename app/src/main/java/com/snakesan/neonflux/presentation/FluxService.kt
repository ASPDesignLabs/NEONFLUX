package com.snakesan.neonflux

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import kotlin.random.Random

class FluxService : Service(), MessageClient.OnMessageReceivedListener {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private lateinit var synth: SynthEngine
    private lateinit var vibrator: Vibrator
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Engine State
    private var isRunning = false
    private var currentMode = "STANDBY" 
    private var activeProfile = 0
    private var clinicalJob: Job? = null
    
    private val binder = LocalBinder()
    
    inner class LocalBinder : Binder() {
        fun getService(): FluxService = this@FluxService
    }

    // --- OVERSEER KILL SWITCH ---
    private val killReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.snakesan.overseer.KILL_COMMAND") {
                Log.w("FluxService", "OVERSEER KILL COMMAND RECEIVED. TERMINATING.")
                haltService()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        synth = SynthEngine()
        
        // Init Vibrator
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // Init WakeLock (Safety net for Silent Mode)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NeonFlux:CoreLock")
        
        // Listen to Phone
        Wearable.getMessageClient(this).addListener(this)
        
        // Register Kill Switch
        val filter = IntentFilter("com.snakesan.overseer.KILL_COMMAND")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(killReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(killReceiver, filter)
        }
        
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            haltService()
            return START_NOT_STICKY
        }

        // Promote to Foreground (Media Playback type for Android 14+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(99, createNotification("NeonFlux Engine Active"), 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(99, createNotification("NeonFlux Engine Active"))
        }
        
        // Acquire lock if not held
        if (wakeLock?.isHeld == false) wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24hr timeout
        
        return START_STICKY
    }

    // --- PHONE COMMUNICATION HANDLER ---
    override fun onMessageReceived(event: MessageEvent) {
        
        // CASE 1: JUST SYNC SETTINGS (DO NOT START)
        if (event.path == "/clinical_conf") {
            try {
                val buffer = ByteBuffer.wrap(event.data)
                val profile = buffer.get().toInt()
                val bpm = buffer.getInt()
                val intensity = buffer.get().toInt()
                val sleep = buffer.get().toInt() == 1
                
                Log.d("FluxService", "RX Config Sync: $bpm BPM")

                // Update Local Service State
                activeProfile = profile
                
                // Broadcast to UI (MainActivity) so it can animate and save prefs
                broadcastConfigToUI(profile, bpm, intensity, sleep)
                
                vibrateAck()
                
            } catch (e: Exception) { Log.e("FluxService", "Config Error", e) }
        }
        
        // CASE 2: ENGAGE MOTOR (MIRRORED START)
        else if (event.path == "/clinical_engage") {
            try {
                val buffer = ByteBuffer.wrap(event.data)
                val profile = buffer.get().toInt()
                val bpm = buffer.getInt()
                val intensity = buffer.get().toInt()
                val sleep = buffer.get().toInt() == 1
                val targetTime = buffer.getLong() // Extract Timestamp

                Log.d("FluxService", "RX Engage: Start at $targetTime")

                // Update UI first
                broadcastConfigToUI(profile, bpm, intensity, sleep)

                // Stop any existing loop
                synth.stop()
                clinicalJob?.cancel()
                
                // Start with precision delay
                startClinicalWithDelay(bpm, intensity, profile, targetTime)
                vibrateAck()

            } catch (e: Exception) { Log.e("FluxService", "Engage Error", e) }
        }

        // CASE 3: REMOTE STOP
        else if (event.path == "/clinical_stop") {
            haltLoops()
            vibrateAck()
        }
    }

    private fun broadcastConfigToUI(profile: Int, bpm: Int, intensity: Int, sleep: Boolean) {
        val intent = Intent("com.snakesan.neonflux.REMOTE_CONFIG")
        intent.putExtra("profile", profile)
        intent.putExtra("bpm", bpm)
        intent.putExtra("intensity", intensity)
        intent.putExtra("sleep", sleep)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    // --- CONTROL METHODS ---
    
    // NEW: Called by UI "INITIALIZE" button to start EVERYTHING
    fun broadcastStartToPhone(bpm: Int, intensity: Int, profile: Int) {
        // 1. Calculate future start time for sync (e.g. 500ms from now)
        val targetTime = System.currentTimeMillis() + 500
        
        // 2. Start Local Engine (Delayed)
        startClinicalWithDelay(bpm, intensity, profile, targetTime)
        
        // 3. Send Message to Phone
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isEmpty()) return@addOnSuccessListener
            
            // Payload: [Profile, BPM, Intensity, Sleep(unused), TargetTime]
            val buffer = ByteBuffer.allocate(15)
            buffer.put(profile.toByte())
            buffer.putInt(bpm)
            buffer.put(intensity.toByte())
            buffer.put(0.toByte()) // Sleep flag not relevant for phone
            buffer.putLong(targetTime)
            
            val payload = buffer.array()
            
            nodes.forEach { node ->
                Wearable.getMessageClient(this).sendMessage(node.id, "/clinical_engage", payload)
            }
        }
    }
    
    // NEW: Called by UI "Crash" gesture to stop EVERYTHING
    fun broadcastStopToPhone() {
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                Wearable.getMessageClient(this).sendMessage(node.id, "/clinical_stop", byteArrayOf())
            }
        }
        haltLoops()
    }

    fun setReactorMode(audioEnabled: Boolean, profile: Int) {
        clinicalJob?.cancel()
        isRunning = true
        activeProfile = profile
        synth.start()
        val modeStr = if (audioEnabled) "REACTOR (AUDIO)" else "REACTOR (SILENT)"
        updateState(modeStr)
        scope.launch { reactorLoop(audioEnabled, profile) }
    }
    
    // Remote Start from Phone (Delayed for Sync)
    private fun startClinicalWithDelay(bpm: Int, intensity: Int, profile: Int, targetTime: Long) {
        clinicalJob?.cancel()
        isRunning = true
        activeProfile = profile
        synth.stop() 
        updateState("CLINICAL ($bpm BPM)")
        
        clinicalJob = scope.launch {
            // Wait for sync time
            val wait = targetTime - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            
            clinicalLoop(bpm, intensity, profile)
        }
    }
    
    fun haltLoops() {
        isRunning = false
        clinicalJob?.cancel()
        synth.isStandby = true
        synth.update()
        updateState("STANDBY")
    }

    fun haltService() {
        isRunning = false
        clinicalJob?.cancel()
        synth.stop()
        updateState("STANDBY")
        if (wakeLock?.isHeld == true) wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- STATE PUBLISHER ---
    private fun updateState(modeText: String) {
        if (currentMode == modeText) return
        currentMode = modeText
        
        // 1. Broadcast to Overseer (Legacy Bridge)
        val overseerIntent = Intent("com.snakesan.overseer.UPDATE_STATUS")
        overseerIntent.setPackage("com.snakesan.overseer")
        overseerIntent.putExtra("source_app", "FLUX")
        overseerIntent.putExtra("flux_mode", modeText)
        overseerIntent.putExtra("is_active", isRunning)
        sendBroadcast(overseerIntent)

        // 2. Broadcast to Local UI
        val localIntent = Intent("com.snakesan.neonflux.STATE_CHANGE")
        localIntent.putExtra("mode", modeText)
        sendBroadcast(localIntent)

        // 3. Update Data Layer (Persistence)
        scope.launch {
            try {
                val putDataReq = PutDataMapRequest.create("/flux_status").apply {
                    dataMap.putString("flux_mode", modeText)
                    dataMap.putLong("timestamp", System.currentTimeMillis()) 
                }
                Wearable.getDataClient(this@FluxService).putDataItem(putDataReq.asPutDataRequest().setUrgent())
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    // --- ENGINES ---
    
    private suspend fun reactorLoop(audioEnabled: Boolean, profile: Int) {
        var lastNetSend = 0L
        
        // Get Phone Nodes
        var phoneNodes = emptyList<String>()
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes -> phoneNodes = nodes.map { it.id } }

        while (isRunning) {
            val rawMag = synth.getSensorMagnitude()
            val intensity = (rawMag / 8.0f).coerceIn(0f, 1f)

            if (audioEnabled) {
                synth.amplitude = 0.2 + (intensity * 0.6)
                synth.isStandby = false
            } else {
                synth.isStandby = true
            }
            synth.update()

            // TELEMETRY TO PHONE
            val now = System.currentTimeMillis()
            if (now - lastNetSend > 100 && intensity > 0.1f) {
                if (phoneNodes.isNotEmpty()) {
                    val modeByte = if (audioEnabled) 3.toByte() else 1.toByte()
                    val intensityByte = (intensity * 100).toInt().toByte()
                    val payload = byteArrayOf(modeByte, 0, intensityByte)
                    phoneNodes.forEach { nodeId -> Wearable.getMessageClient(this@FluxService).sendMessage(nodeId, "/flux_sync", payload) }
                    lastNetSend = now
                } else if (now % 5000 < 100) {
                     Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes -> phoneNodes = nodes.map { it.id } }
                }
            }

            if (intensity > 0.05f) {
                 when (profile) {
                    0 -> { 
                        delay(60); val amp = (intensity * 200).toInt().coerceAtLeast(10)
                        if (vibrator.hasAmplitudeControl()) vibrator.vibrate(VibrationEffect.createOneShot(30, amp))
                        else vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                    }
                    1 -> { 
                        if (Random.nextFloat() < (intensity * 0.45f)) { vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)); delay(40) } 
                        else delay(30)
                    }
                    2 -> {
                        delay(60); val heavyInt = (intensity * 1.5f).coerceAtMost(1f)
                        val amp = (heavyInt * 255).toInt().coerceAtLeast(20)
                        if (vibrator.hasAmplitudeControl()) vibrator.vibrate(VibrationEffect.createOneShot(80, amp))
                        else vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
                    }
                }
            } else {
                delay(40)
            }
        }
    }
    
    private suspend fun clinicalLoop(bpm: Int, intensity: Int, profile: Int) {
        val periodMs = 60000.0 / bpm
        var beatCount = 0L
        val startTime = System.currentTimeMillis()

        while (isRunning) {
            if (intensity > 0) {
                 val amp = (intensity / 100f * 255).toInt().coerceAtLeast(10)
                 if (vibrator.hasAmplitudeControl()) {
                    when(profile) {
                        0 -> vibrator.vibrate(VibrationEffect.createOneShot(50, amp))
                        1 -> vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                        2 -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100), intArrayOf(0, amp), -1))
                    }
                } else vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            }
            beatCount++
            val nextBeatTime = startTime + (beatCount * periodMs).toLong()
            val sleepTime = nextBeatTime - System.currentTimeMillis()
            if (sleepTime > 0) delay(sleepTime)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(killReceiver) } catch (e: Exception) {}
        Wearable.getMessageClient(this).removeListener(this)
        isRunning = false
        synth.stop()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        scope.cancel()
        super.onDestroy()
    }
    
    private fun vibrateAck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
        } else {
            vibrator.vibrate(100)
        }
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel("FLUX_CHANNEL", "NeonFlux Core", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(text: String): Notification {
        return Notification.Builder(this, "FLUX_CHANNEL")
            .setContentTitle("NeonFlux")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }
}
