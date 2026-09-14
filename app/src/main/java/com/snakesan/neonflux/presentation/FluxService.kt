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

// Emergency Protocol playback styles. Shared with the watch UI
// (MainActivity.kt, same package) for its sticky status screen and the
// phone's TEXTURE picker (see sendEmergencyToWatch()) - keep both in sync
// with this list if it grows. This is actively being A/B tested, so the
// set here is deliberately not treated as final.
object EmergencyTexture {
    const val STEADY = 0
    const val PULSE = 1

    fun label(texture: Int): String = when (texture) {
        PULSE -> "PULSE"
        else -> "STEADY"
    }
}

// PULSE's hardware-looped on/off timing - see startEmergency() in FluxService.
private const val EMERGENCY_PULSE_ON_MS = 90L
private const val EMERGENCY_PULSE_OFF_MS = 90L

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

    // Emergency Protocol State - separate from isRunning/clinicalJob so it can
    // seize control regardless of whatever Reactor/Clinical was doing, and so
    // haltEmergency()/finishEmergency() never race with an unrelated
    // clinical_stop or reactor mode switch.
    private var isEmergencyActive = false
    private var emergencyJob: Job? = null
    
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
            // Safe Mode applies here too - the watch UI already refuses to open the
            // INITIALIZE button below the battery threshold, but a remote engage
            // reaches this handler directly, bypassing the UI entirely.
            if (!isBatterySafe()) {
                Log.w("FluxService", "Declining remote engage - battery below safe threshold")
                return
            }
            // Emergency Protocol takes over regardless of activity or state -
            // an unrelated remote engage arriving mid-override must not be
            // able to hijack the motor away from it.
            if (isEmergencyActive) {
                Log.w("FluxService", "Declining remote engage - Emergency Protocol is active")
                return
            }
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
                // Flip the watch UI into the running state (countdown + two-finger
                // lockdown gesture) even though this device didn't initiate the
                // session - otherwise a phone-initiated session runs with no
                // on-watch emergency stop available.
                broadcastRemoteEngageToUI()

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

        // CASE 4: PHONE-INITIATED PREPARE (2-phase handshake for the phone's
        // local "ENABLE" control) - config-only, mirrors CASE 1, but replies
        // /clinical_ready so the phone's engagePreparedClinicalStart() can
        // proceed to send /clinical_engage.
        else if (event.path == "/clinical_prepare") {
            if (!isBatterySafe()) {
                Log.w("FluxService", "Declining clinical_prepare - battery below safe threshold")
                return
            }
            try {
                val buffer = ByteBuffer.wrap(event.data)
                val profile = buffer.get().toInt()
                val bpm = buffer.getInt()
                val intensity = buffer.get().toInt()
                val sleep = buffer.get().toInt() == 1

                Log.d("FluxService", "RX Prepare: $bpm BPM")

                activeProfile = profile
                broadcastConfigToUI(profile, bpm, intensity, sleep)

                Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, "/clinical_ready", byteArrayOf())
                vibrateAck()

            } catch (e: Exception) { Log.e("FluxService", "Prepare Error", e) }
        }

        // CASE 5: ACCESSIBILITY SYNC (High Contrast + Font Scale). Wire format
        // from the phone's sendA11yToWatch(): [highContrast:1][fontScale:4].
        else if (event.path == "/flux_a11y_sync") {
            try {
                val buffer = ByteBuffer.wrap(event.data)
                val highContrast = buffer.get().toInt() == 1
                val fontScale = buffer.getFloat()

                getSharedPreferences("FluxWatchConfig", Context.MODE_PRIVATE).edit().apply {
                    putBoolean("a11y_contrast", highContrast)
                    putFloat("a11y_font_scale", fontScale)
                    apply()
                }

                val intent = Intent("com.snakesan.neonflux.REMOTE_A11Y")
                intent.putExtra("high_contrast", highContrast)
                intent.putExtra("font_scale", fontScale)
                intent.setPackage(packageName)
                sendBroadcast(intent)
            } catch (e: Exception) { Log.e("FluxService", "A11y Sync Error", e) }
        }

        // CASE 6: VISUAL THEME SYNC. Wire format from the phone's
        // sendVisualThemeToWatch(): [version:1][mono:1][monoHdr:1][primary:4]
        // [secondary:4][l1:4][l2:4][textMain:4][bg:4] as ARGB ints (27 bytes).
        else if (event.path == "/flux_visual_theme") {
            try {
                val buffer = ByteBuffer.wrap(event.data)
                buffer.get() // version - unused, reserved for future wire changes
                val isMonochrome = buffer.get().toInt() == 1
                buffer.get() // monoHdr - not applicable on the watch's simpler UI
                val primary = buffer.getInt()
                val secondary = buffer.getInt()
                val l1 = buffer.getInt()
                val l2 = buffer.getInt()
                val textMain = buffer.getInt()
                val bg = buffer.getInt()

                getSharedPreferences("FluxWatchConfig", Context.MODE_PRIVATE).edit().apply {
                    putBoolean("theme_mono", isMonochrome)
                    putInt("theme_primary", primary)
                    putInt("theme_secondary", secondary)
                    putInt("theme_l1", l1)
                    putInt("theme_l2", l2)
                    putInt("theme_text_main", textMain)
                    putInt("theme_bg", bg)
                    apply()
                }

                val intent = Intent("com.snakesan.neonflux.REMOTE_THEME")
                intent.putExtra("mono", isMonochrome)
                intent.putExtra("primary", primary)
                intent.putExtra("secondary", secondary)
                intent.putExtra("l1", l1)
                intent.putExtra("l2", l2)
                intent.putExtra("text_main", textMain)
                intent.putExtra("bg", bg)
                intent.setPackage(packageName)
                sendBroadcast(intent)
            } catch (e: Exception) { Log.e("FluxService", "Theme Sync Error", e) }
        }

        // CASE 7: CUSTOM PROFILE METADATA. Wire format from the phone's
        // sendCustomProfileMetadataToWatch(): [bank:1][nameLen:1][name bytes].
        // The watch doesn't run the custom sequencer (profile 3) itself, so
        // this just persists the name rather than losing it silently.
        else if (event.path == "/custom_profile_meta") {
            try {
                val buffer = ByteBuffer.wrap(event.data)
                val bank = buffer.get().toInt()
                val nameLen = buffer.get().toInt() and 0xFF
                val nameBytes = ByteArray(nameLen)
                buffer.get(nameBytes)
                val name = nameBytes.decodeToString()

                getSharedPreferences("FluxWatchConfig", Context.MODE_PRIVATE).edit().apply {
                    putInt("custom_bank", bank)
                    putString("custom_name", name)
                    apply()
                }
            } catch (e: Exception) { Log.e("FluxService", "Custom Profile Meta Error", e) }
        }

        // CASE 8: EMERGENCY PROTOCOL OVERRIDE. Wire format from the phone's
        // sendEmergencyToWatch(): [mode:1][intensity 0-100:4 float][durationMin:4 int][texture:1].
        // mode 0 is the phone's HALT button (durationMin/texture unused); mode
        // 1/2/3 (5MIN/7MIN/INF) all mean "on" - durationMin carries the real
        // timer (-1 for INF), texture picks the playback style (see
        // EmergencyTexture below). This was previously unhandled entirely,
        // which is why Emergency Protocol never did anything on the watch -
        // the phone was sending this correctly the whole time.
        else if (event.path == "/emergency_override") {
            try {
                val buffer = ByteBuffer.wrap(event.data)
                val mode = buffer.get().toInt()
                val intensityPct = buffer.getFloat()
                val durationMin = buffer.getInt()
                val texture = if (buffer.hasRemaining()) buffer.get().toInt() else EmergencyTexture.STEADY

                if (mode == 0) {
                    haltEmergency()
                } else {
                    startEmergency(intensityPct, durationMin, texture)
                }
            } catch (e: Exception) { Log.e("FluxService", "Emergency Override Error", e) }
        }
    }

    private fun isBatterySafe(): Boolean {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) >= SAFE_MODE_BATTERY_THRESHOLD
    }

    private fun broadcastRemoteEngageToUI() {
        val intent = Intent("com.snakesan.neonflux.REMOTE_ENGAGE")
        intent.setPackage(packageName)
        sendBroadcast(intent)
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
        // Lead time must match the watch UI's 3-2-1 countdown (3x1000ms, see
        // MainActivity's LaunchedEffect(isClinicalActive)) so the haptic beat
        // starts as the countdown hits zero instead of ~2.5s before it finishes.
        val targetTime = System.currentTimeMillis() + 3000
        
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
                // /clinical_stop is the routine remote-stop the phone already
                // handles reliably - keep sending it so a normal stop still
                // works even if anything below has an issue.
                Wearable.getMessageClient(this).sendMessage(node.id, "/clinical_stop", byteArrayOf())
                // /emergency_halt is the distinct "physical emergency gesture"
                // signal - the phone already listens for it (EMERGENCY_HALT_UI)
                // but nothing on the watch was ever sending it.
                Wearable.getMessageClient(this).sendMessage(node.id, "/emergency_halt", byteArrayOf())
            }
        }
        haltLoops()
    }

    fun setReactorMode(audioEnabled: Boolean, profile: Int) {
        // Defense-in-depth: the watch UI already avoids reaching this while
        // Emergency Protocol owns the screen, but never let anything hijack
        // the motor away from it.
        if (isEmergencyActive) return
        clinicalJob?.cancel()
        isRunning = true
        activeProfile = profile
        synth.start(audioEnabled)
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
        // Fully tear down the audio stream + native sensor thread instead of
        // just silencing them - otherwise both keep running (and draining
        // power) in the background for as long as the session sits idle.
        synth.stop()
        updateState("STANDBY")
    }

    fun haltService(reason: String? = null) {
        isRunning = false
        clinicalJob?.cancel()
        synth.stop()
        isEmergencyActive = false
        emergencyJob?.cancel()
        vibrator.cancel()
        updateState("STANDBY", reason)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- EMERGENCY PROTOCOL ---
    // A distraction/grounding profile, distinct from the Lockdown Gesture's
    // panic-stop: haptics at a user-selected strength that take over from
    // Reactor/Clinical regardless of what was running, for a fixed duration
    // (5/7 min) or indefinitely until halted. The playback style itself
    // (EmergencyTexture) is user-selectable and being actively A/B tested -
    // see sendEmergencyToWatch() on the phone - so keep this list easy to
    // extend rather than assuming STEADY is the only "real" one.

    // Ceiling on a single indefinite-mode vibrate() call, mirroring the 24hr
    // wakeLock timeout above - "indefinite" should still never mean truly
    // unbounded on hardware in continuous contact with skin.
    private val EMERGENCY_MAX_DURATION_MS = 24 * 60 * 60 * 1000L

    fun startEmergency(intensityPct: Float, durationMin: Int, texture: Int) {
        // Safe Mode applies here too, and matters more than anywhere else in
        // this file - this is the single most power-hungry, highest-amplitude
        // profile the watch has.
        if (!isBatterySafe()) {
            Log.w("FluxService", "Declining emergency override - battery below safe threshold")
            return
        }

        // Seize control: Reactor/Clinical's own loops just check `isRunning`
        // each iteration and exit on their own (same mechanism haltLoops()
        // already relies on), so flipping it off here is enough to take over
        // regardless of whichever deck/activity was active.
        isRunning = false
        clinicalJob?.cancel()
        synth.stop()
        emergencyJob?.cancel()

        isEmergencyActive = true
        val hasAmp = vibrator.hasAmplitudeControl()
        val amp = (intensityPct / 100f * 255).toInt().coerceAtLeast(10).coerceAtMost(255)
        val onAmp = if (hasAmp) amp else VibrationEffect.DEFAULT_AMPLITUDE
        // createOneShot()/createWaveform() throw for a duration/timing <= 0 -
        // floor it defensively so a malformed/unexpected durationMin can
        // never silently kill the whole call.
        val vibrateMs = (if (durationMin >= 0) durationMin * 60_000L else EMERGENCY_MAX_DURATION_MS).coerceAtLeast(1000L)

        // Every other vibrate() call in this file branches on hasAmplitudeControl()
        // before passing a custom amplitude - the original STEADY-only version of
        // this method didn't, which is the likely reason haptics weren't firing on
        // hardware without amplitude control (an amplitude-scaled effect can
        // silently no-op there instead of falling back). DEFAULT_AMPLITUDE still
        // gives real, felt vibration at the motor's default strength.
        val effect = when (texture) {
            // PULSE: a single hardware-looped on/off waveform (repeat = loop the
            // whole array from index 0) - one vibrate() call runs for as long as
            // it's not cancelled, no coroutine re-issuing it needed, unlike
            // Reactor/Clinical's loops (which exist for movement/BPM timing this
            // doesn't have).
            EmergencyTexture.PULSE -> VibrationEffect.createWaveform(
                longArrayOf(EMERGENCY_PULSE_ON_MS, EMERGENCY_PULSE_OFF_MS),
                intArrayOf(onAmp, 0),
                0
            )
            // STEADY (default/unrecognized): today's unbroken sustain.
            else -> VibrationEffect.createOneShot(vibrateMs, onAmp)
        }
        vibrator.vibrate(effect)

        updateState("EMERGENCY OVERRIDE")
        broadcastEmergencyToUI(active = true, durationMin = durationMin, texture = texture)

        // Always schedule the auto-halt, even in INF mode - vibrateMs is the
        // 24hr safety ceiling in that case, and this keeps the watch/phone UI
        // in sync with the motor if that ceiling is ever actually reached.
        // vibrator.cancel() in finishEmergency() stops either effect above the
        // same way, regardless of which texture was playing.
        emergencyJob = scope.launch {
            delay(vibrateMs)
            if (isEmergencyActive) finishEmergency()
        }
    }

    // Phone-initiated HALT (mode 0 of /emergency_override).
    fun haltEmergency() {
        if (!isEmergencyActive) return
        finishEmergency()
    }

    // Watch-initiated stop - the dedicated single-tap control on the Emergency
    // Protocol screen (not the 2-finger Lockdown Gesture: coordinating a
    // 3-second two-finger hold while under continuous max-strength vibration
    // is an unreasonable ask). Reuses the existing /emergency_halt wire path
    // so the phone's already-working EMERGENCY_HALT_UI receiver resets its
    // Emergency Protocol UI exactly as it does for a phone-initiated HALT.
    fun haltEmergencyFromWatch() {
        finishEmergency()
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                Wearable.getMessageClient(this).sendMessage(node.id, "/emergency_halt", byteArrayOf())
            }
        }
    }

    // Shared teardown for both a manual HALT and the timer running out -
    // always lands back in STANDBY so the watch UI's own "return to whichever
    // deck was active before, but idle" logic (see MainActivity) has a clean
    // state to restore onto.
    private fun finishEmergency() {
        isEmergencyActive = false
        emergencyJob?.cancel()
        vibrator.cancel()
        updateState("STANDBY")
        broadcastEmergencyToUI(active = false, durationMin = 0, texture = EmergencyTexture.STEADY)
        vibrateAck()
    }

    private fun broadcastEmergencyToUI(active: Boolean, durationMin: Int, texture: Int) {
        val intent = Intent("com.snakesan.neonflux.EMERGENCY_STATE")
        intent.putExtra("active", active)
        intent.putExtra("duration_min", durationMin)
        intent.putExtra("texture", texture)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    // --- STATE PUBLISHER ---
    // reason is an optional extra cause tag (e.g. "LOW_BATTERY") for Overseer's
    // UPDATE_STATUS bridge - source_app/flux_mode/is_active stay untouched so the
    // existing contract doesn't change for anyone not looking at halt_reason.
    private fun updateState(modeText: String, reason: String? = null) {
        if (currentMode == modeText) return
        currentMode = modeText

        // 1. Broadcast to Overseer (Legacy Bridge)
        val overseerIntent = Intent("com.snakesan.overseer.UPDATE_STATUS")
        overseerIntent.setPackage("com.snakesan.overseer")
        overseerIntent.putExtra("source_app", "FLUX")
        overseerIntent.putExtra("flux_mode", modeText)
        overseerIntent.putExtra("is_active", isRunning)
        if (reason != null) overseerIntent.putExtra("halt_reason", reason)
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
        isEmergencyActive = false
        vibrator.cancel()
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
