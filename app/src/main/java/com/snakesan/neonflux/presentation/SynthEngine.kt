package com.snakesan.neonflux

class SynthEngine {
    init { 
        try { 
            System.loadLibrary("neonflux") 
        } catch (e: Exception) {
            e.printStackTrace()
        } 
    }
    
    var frequency = 440.0
    var amplitude = 0.0
    var isStandby = true 

    private external fun startNative(audioEnabled: Boolean)
    private external fun stopNative()
    private external fun updateNative(freq: Float, amp: Float)
    private external fun setVolumeNative(vol: Float)
    private external fun pauseSensorsNative(paused: Boolean)
    external fun getSensorMagnitude(): Float

    // audioEnabled=false skips opening the audio stream entirely (haptics still
    // need the sensor engine, but there's no reason to power the amp for it).
    fun start(audioEnabled: Boolean = true) {
        startNative(audioEnabled)
        pauseSensorsNative(false)
    }
    
    fun stop() { 
        stopNative() 
    }
    
    fun update() {
        val targetAmp = if (isStandby) 0.0f else amplitude.toFloat()
        updateNative(frequency.toFloat(), targetAmp)
    }
    
    fun setVolume(vol: Float) { 
        setVolumeNative(vol) 
    }
}
