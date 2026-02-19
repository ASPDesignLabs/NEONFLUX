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

    private external fun startNative()
    private external fun stopNative()
    private external fun updateNative(freq: Float, amp: Float)
    private external fun setVolumeNative(vol: Float)
    private external fun pauseSensorsNative(paused: Boolean)
    external fun getSensorMagnitude(): Float 
    
    fun start() { 
        startNative()
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
