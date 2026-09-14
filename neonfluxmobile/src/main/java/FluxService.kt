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

private const val ACTION_PREPARE_LOCAL =
    "com.snakesan.neonflux.PREPARE_LOCAL"

private const val EXTRA_SEQUENCE_PAYLOAD = "sequence_payload"

private const val PATH_CLINICAL_PREPARE = "/clinical_prepare"
private const val PATH_CLINICAL_READY = "/clinical_ready"
private const val PATH_CLINICAL_ENGAGE = "/clinical_engage"

// Matches the watch's 3-2-1 countdown duration (see app-side FluxService's
// broadcastStartToPhone) so a phone-initiated engage shows/feels the same
// lead time as a watch-initiated one.
private const val CLINICAL_START_LEAD_MS = 3_000L



class FluxService : Service(), MessageClient.OnMessageReceivedListener {

    private lateinit var vibrator: Vibrator
    private var clinicalJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null



    private data class PendingClinicalStart(
        val profile: Int,
        val bpm: Int,
        val intensity: Int,
        val sleep: Boolean,
        val sequencePayload: ByteArray
    )

    private var pendingClinicalStart: PendingClinicalStart? = null

    // --- Local Receiver for Phone UI Commands ---
    private val localReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.snakesan.neonflux.PREPARE_LOCAL" -> {
                    val profile = intent.getIntExtra("profile", 0)
                    val bpm = intent.getIntExtra("bpm", 60)
                    val intensity = intent.getIntExtra("intensity", 50)
                    val sleep = intent.getBooleanExtra("sleep", false)
                    val sequencePayload = intent.getByteArrayExtra(EXTRA_SEQUENCE_PAYLOAD)
                        ?: byteArrayOf()

                    prepareClinicalStart(
                        profile = profile,
                        bpm = bpm,
                        intensity = intensity,
                        sleep = sleep,
                        sequencePayload = sequencePayload
                    )
                }
                "com.snakesan.neonflux.START_LOCAL" -> {
                    val profile = intent.getIntExtra("profile", 0)
                    val bpm = intent.getIntExtra("bpm", 60)
                    val intensity = intent.getIntExtra("intensity", 50)
                    val targetTime = intent.getLongExtra("target_time", System.currentTimeMillis())
                    
                    startPrecisionMetronome(profile, bpm, intensity, targetTime)
                }
                "com.snakesan.neonflux.STOP_LOCAL" -> {
                    stopPrecisionMetronome()
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

        
        Wearable.getMessageClient(this).addListener(this)
        
        val filter = IntentFilter().apply {
            addAction("com.snakesan.neonflux.START_LOCAL")
            addAction("com.snakesan.neonflux.STOP_LOCAL")
            addAction(ACTION_PREPARE_LOCAL)
        }

        // Explicitly use RECEIVER_NOT_EXPORTED for local broadcasts
        registerReceiver(localReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        startForegroundService()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(localReceiver)
        Wearable.getMessageClient(this).removeListener(this)
        stopPrecisionMetronome()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == "/flux_sync") {
            // Watch -> Phone (Reactor Mode)
            val bytes = event.data
            // Explicitly cast to Int to fix type inference errors
            val mode = bytes[0].toInt()
            if (mode == 3 || mode == 1) { 
                if (clinicalJob?.isActive == true) stopPrecisionMetronome()
                val profile = bytes[1].toInt()
                val intensity = bytes[2].toInt() / 100f
                playOneShot(profile, intensity)
                broadcastBeat(intensity)
            }
        }

        else if (event.path == PATH_CLINICAL_READY) {
            engagePreparedClinicalStart()
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
            stopPrecisionMetronome()
            val intent = Intent("com.snakesan.neonflux.REMOTE_STOP_UI")
            intent.setPackage(packageName)
            sendBroadcast(intent)
        }

        // WATCH -> PHONE (EMERGENCY HALT)
        else if (event.path == "/emergency_halt") {
            val intent = Intent("com.snakesan.neonflux.EMERGENCY_HALT_UI")
            intent.setPackage(packageName)
            sendBroadcast(intent)
        }
    }

    private fun acquireMetronomeWakeLock() {
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(60 * 60 * 1000L)
        }
    }

    private fun releaseMetronomeWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    private fun stopPrecisionMetronome() {
        clinicalJob?.cancel()
        clinicalJob = null

        vibrator.cancel()
        releaseMetronomeWakeLock()
    }

    private fun prepareClinicalStart(
        profile: Int,
        bpm: Int,
        intensity: Int,
        sleep: Boolean,
        sequencePayload: ByteArray
    ) {
        pendingClinicalStart = PendingClinicalStart(
            profile = profile,
            bpm = bpm,
            intensity = intensity,
            sleep = sleep,
            sequencePayload = sequencePayload
        )

        val configPayload = ByteBuffer.allocate(7 + sequencePayload.size)
            .apply {
                put(profile.toByte())
                putInt(bpm)
                put(intensity.toByte())
                put(if (sleep) 1.toByte() else 0.toByte())
                put(sequencePayload)
            }
            .array()

        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    val targetTime = System.currentTimeMillis() + 300L

                    startPrecisionMetronome(
                        profile = profile,
                        bpm = bpm,
                        intensityInt = intensity,
                        startTime = targetTime
                    )

                    pendingClinicalStart = null
                    return@addOnSuccessListener
                }

                nodes.forEach { node ->
                    Wearable.getMessageClient(this).sendMessage(
                        node.id,
                        PATH_CLINICAL_PREPARE,
                        configPayload
                    )
                }
            }
    }

    private fun engagePreparedClinicalStart() {
        val pending = pendingClinicalStart ?: return

        val targetTime = System.currentTimeMillis() + CLINICAL_START_LEAD_MS

        val payload = ByteBuffer.allocate(15 + pending.sequencePayload.size)
            .apply {
                put(pending.profile.toByte())
                putInt(pending.bpm)
                put(pending.intensity.toByte())
                put(if (pending.sleep) 1.toByte() else 0.toByte())
                putLong(targetTime)
                put(pending.sequencePayload)
            }
            .array()

        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                nodes.forEach { node ->
                    Wearable.getMessageClient(this).sendMessage(
                        node.id,
                        PATH_CLINICAL_ENGAGE,
                        payload
                    )
                }
            }

        startPrecisionMetronome(
            profile = pending.profile,
            bpm = pending.bpm,
            intensityInt = pending.intensity,
            startTime = targetTime
        )

        pendingClinicalStart = null
    }

    private fun startPrecisionMetronome(
        profile: Int,
        bpm: Int,
        intensityInt: Int,
        startTime: Long
    ) {
        clinicalJob?.cancel()

        acquireMetronomeWakeLock()

        clinicalJob = scope.launch {
            val intensity = intensityInt / 100f
            val wait = startTime - System.currentTimeMillis()

            if (wait > 0) {
                delay(wait)
            }

            if (profile == 3) {
                runCustomSequencer(
                    bpm = bpm,
                    baseIntensity = intensity
                )
            } else {
                runClinicalMetronome(
                    bpm = bpm,
                    intensity = intensity,
                    profile = profile
                )
            }
        }
    }

    private suspend fun runClinicalMetronome(
        bpm: Int,
        intensity: Float,
        profile: Int
    ) {
        if (intensity <= 0.05f) {
            return
        }

        val periodMs = 60_000.0 / bpm
        val startTime = System.currentTimeMillis()
        var beatCount = 0L

        while (currentCoroutineContext().isActive) {
            playOneShot(profile, intensity)
            broadcastBeat(intensity)

            beatCount++

            val nextBeatTime = startTime + (beatCount * periodMs).toLong()
            val sleepTime = nextBeatTime - System.currentTimeMillis()

            if (sleepTime > 0) {
                delay(sleepTime)
            }
        }
    }

    private fun sequenceHasNoOffSteps(
        sequence: List<Pair<Int, Float>>
    ): Boolean {
        return sequence.isNotEmpty() &&
                sequence.none { it.first == 0 }
    }

    private fun vibrateCustomSustainChain(
        sequence: List<Pair<Int, Float>>,
        startIndex: Int,
        stepPeriodMs: Long
    ) {
        val timings = mutableListOf<Long>()
        val amplitudes = mutableListOf<Int>()

        var index = startIndex
        var firstStep = true

        do {
            val step = sequence[index]
            val mode = step.first
            val intensity = step.second

            if (mode != 2) {
                break
            }

            val amplitude = (intensity * 255f)
                .toInt()
                .coerceIn(10, 255)

            timings.add(stepPeriodMs)
            amplitudes.add(amplitude)

            index = (index + 1) % sequence.size
            firstStep = false
        } while (index != startIndex || firstStep)

        if (timings.isEmpty()) {
            return
        }

        val attributes = VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)
            .build()

        if (vibrator.hasAmplitudeControl()) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    timings.toLongArray(),
                    amplitudes.toIntArray(),
                    -1
                ),
                attributes
            )
        } else {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    timings.sum(),
                    VibrationEffect.DEFAULT_AMPLITUDE
                ),
                attributes
            )
        }
    }

    private suspend fun runCustomSequencer(
        bpm: Int,
        baseIntensity: Float
    ) {
        val prefs = getSharedPreferences("FluxConfig", Context.MODE_PRIVATE)
        val bank = prefs.getInt("active_custom_bank", 1)
        val savedStr = prefs.getString("custom_seq_$bank", "") ?: ""

        val sequence = mutableListOf<Pair<Int, Float>>()

        if (savedStr.isNotBlank()) {
            savedStr.split(";").forEach { encodedStep ->
                val parts = encodedStep.split(",")

                if (parts.size < 3) {
                    return@forEach
                }

                val modeInt = when (parts[0]) {
                    "true", "HIT" -> 1
                    "false", "OFF" -> 0
                    "SUSTAIN" -> 2
                    else -> 0
                }

                val isOverride = parts[1].toBoolean()
                val customIntensity = parts[2].toFloatOrNull()?.div(100f)
                    ?: baseIntensity

                val finalIntensity = if (isOverride) {
                    customIntensity
                } else {
                    baseIntensity
                }

                sequence.add(modeInt to finalIntensity)
            }
        }

        if (sequence.isEmpty()) {
            return
        }

        val stepDelay = (60_000.0 / bpm / 2f).toLong()
        val sequenceStart = System.currentTimeMillis()
        var stepCount = 0L

        while (currentCoroutineContext().isActive) {
            val stepIndex = (stepCount % sequence.size).toInt()
            val step = sequence[stepIndex]

            val mode = step.first
            val finalIntensity = step.second

            val previousIndex = (stepIndex - 1 + sequence.size) % sequence.size
            val previousMode = sequence[previousIndex].first

            val isSustain = mode == 2

            val isFirstStepOfLoop = stepIndex == 0

            val isStartOfNote = when {
                mode == 1 -> true

                isSustain && previousMode == 0 -> true

                isSustain && isFirstStepOfLoop && sequenceHasNoOffSteps(sequence) -> true

                else -> false
            }

            if (isStartOfNote) {
                if (mode == 1) {
                    val amplitude = (finalIntensity * 255f)
                        .toInt()
                        .coerceIn(10, 255)

                    val attributes = VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)
                        .build()

                    if (vibrator.hasAmplitudeControl()) {
                        vibrator.vibrate(
                            VibrationEffect.createOneShot(40L, amplitude),
                            attributes
                        )
                    } else {
                        vibrator.vibrate(
                            VibrationEffect.createOneShot(
                                40L,
                                VibrationEffect.DEFAULT_AMPLITUDE
                            ),
                            attributes
                        )
                    }
                } else {
                    vibrateCustomSustainChain(
                        sequence = sequence,
                        startIndex = stepIndex,
                        stepPeriodMs = stepDelay
                    )
                }

                broadcastBeat(finalIntensity)
            } else if (mode == 2) {
                broadcastBeat(finalIntensity)
            }

            stepCount++

            val nextStepTime = sequenceStart + (stepCount * stepDelay)
            val sleepTime = nextStepTime - System.currentTimeMillis()

            if (sleepTime > 0) {
                delay(sleepTime)
            }
        }
    }

    private fun broadcastBeat(intensity: Float) {
        val intent = Intent("com.snakesan.neonflux.BEAT_EVENT")
        intent.setPackage(packageName)
        intent.putExtra("intensity", intensity)
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
