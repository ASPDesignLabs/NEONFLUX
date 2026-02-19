package com.snakesan.neonflux

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import java.nio.ByteBuffer

class FluxService : Service(), MessageClient.OnMessageReceivedListener {

    private lateinit var vibrator: Vibrator
    private var clinicalJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null 

    // --- Local Receiver for Phone UI Commands ---
    private val localReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.snakesan.neonflux.START_LOCAL" -> {
                    val profile = intent.getIntExtra("profile", 0)
                    val bpm = intent.getIntExtra("bpm", 60)
                    val intensity = intent.getIntExtra("intensity", 50)
                    val targetTime = intent.getLongExtra("target_time", System.currentTimeMillis())
                    
                    startPrecisionMetronome(profile, bpm, intensity, targetTime)
                }
                "com.snakesan.neonflux.STOP_LOCAL" -> {
                    clinicalJob?.cancel()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        // Removed SDK check (minSdk 31+)
        val ctx = createAttributionContext("flux_vibrations")
        vibrator = (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NeonFlux:MetronomeLock")
        wakeLock?.acquire(60*60*1000L) 
        
        Wearable.getMessageClient(this).addListener(this)
        
        val filter = IntentFilter().apply {
            addAction("com.snakesan.neonflux.START_LOCAL")
            addAction("com.snakesan.neonflux.STOP_LOCAL")
        }

        // Explicitly use RECEIVER_NOT_EXPORTED for local broadcasts
        registerReceiver(localReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        startForegroundService()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(localReceiver)
        Wearable.getMessageClient(this).removeListener(this)
        clinicalJob?.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == "/flux_sync") {
            // Watch -> Phone (Reactor Mode)
            val bytes = event.data
            // Explicitly cast to Int to fix type inference errors
            val mode = bytes[0].toInt()
            if (mode == 3 || mode == 1) { 
                if (clinicalJob?.isActive == true) clinicalJob?.cancel()
                val profile = bytes[1].toInt()
                val intensity = bytes[2].toInt() / 100f
                playOneShot(profile, intensity)
                broadcastBeat()
            }
        } 
        
        // WATCH -> PHONE (CLINICAL START)
        else if (event.path == "/clinical_engage") {
            try {
                val buffer = ByteBuffer.wrap(event.data)
                val profile = buffer.get().toInt()
                val bpm = buffer.getInt()
                val intensity = buffer.get().toInt()
                @Suppress("UNUSED_VARIABLE")
                val sleep = buffer.get().toInt() == 1 // Unused on phone, but part of protocol
                val targetTime = buffer.getLong()
                
                // Notify UI to flip the switch
                val intent = Intent("com.snakesan.neonflux.REMOTE_START_UI")
                intent.setPackage(packageName)
                sendBroadcast(intent)
                
                startPrecisionMetronome(profile, bpm, intensity, targetTime)
            } catch (e: Exception) { e.printStackTrace() }
        } 
        
        // WATCH -> PHONE (CLINICAL STOP)
        else if (event.path == "/clinical_stop") {
            clinicalJob?.cancel()
            val intent = Intent("com.snakesan.neonflux.REMOTE_STOP_UI")
            intent.setPackage(packageName)
            sendBroadcast(intent)
        }
    }

    private fun startPrecisionMetronome(profile: Int, bpm: Int, intensityInt: Int, startTime: Long) {
        clinicalJob?.cancel()
        clinicalJob = scope.launch {
            val periodMs = 60000.0 / bpm
            val intensity = intensityInt / 100f
            
            val wait = startTime - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            
            var beatCount = 0L
            
            while(isActive) {
                playOneShot(profile, intensity)
                broadcastBeat()
                beatCount++
                val nextBeatTime = startTime + (beatCount * periodMs).toLong()
                val sleepTime = nextBeatTime - System.currentTimeMillis()
                if (sleepTime > 0) delay(sleepTime)
            }
        }
    }

    private fun broadcastBeat() {
        val intent = Intent("com.snakesan.neonflux.BEAT_EVENT")
        intent.setPackage(packageName) 
        sendBroadcast(intent)
    }

    private fun playOneShot(profile: Int, intensity: Float) {
        if (intensity <= 0.05f) return
        val attrs = VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK).build()
        val amp = (intensity * 255).toInt().coerceAtLeast(10)

        // MinSdk 31 guarantees hasAmplitudeControl check isn't strictly necessary for API level, 
        // but hardware support varies, so we keep the check logic simple.
        if (vibrator.hasAmplitudeControl()) {
            when (profile) {
                0 -> vibrator.vibrate(VibrationEffect.createOneShot(40, amp), attrs)
                1 -> vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK), attrs)
                2 -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 100), intArrayOf(0, amp/2, 0, amp), -1), attrs)
            }
        } else {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK), attrs)
        }
    }

    private fun startForegroundService() {
        val channel = NotificationChannel("FluxBackgroundChannel", "Flux Service", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startForeground(1, NotificationCompat.Builder(this, "FluxBackgroundChannel")
            .setContentTitle("NeonFlux Active").setSmallIcon(android.R.drawable.ic_dialog_info).build())
    }
}
