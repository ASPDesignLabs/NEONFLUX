package com.snakesan.neonflux

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import android.content.pm.ActivityInfo
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import com.snakesan.neonflux.R
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposeRenderEffect
import com.snakesan.neonflux.FluxService
import androidx.compose.ui.graphics.toArgb


// --- ENUMS & DATA MODELS ---
enum class UploadState { IDLE, UPLOADING, SUCCESS, ACTIVE }
enum class LabMode { SEQUENCER, NODES, MATRIX, RECORD, DRAW }
enum class StepMode { OFF, HIT, SUSTAIN }

data class PulseStep(
    var mode: StepMode = StepMode.OFF,
    var isOverride: Boolean = false,
    var customIntensity: Float = 80f
)

data class NeonPalette(
    val name: String,
    val primary: Color,
    val secondary: Color,
    val isMonochrome: Boolean = false,
    val l1: Color = Color.DarkGray, // DO NOT put MaterialTheme.colorScheme here
    val l2: Color = Color.Gray,     // DO NOT put MaterialTheme.colorScheme here
    val textMain: Color = Color.White,
    val bg: Color = Color(0xFF050505)
)

private const val PATH_FLUX_OVERSEER_STATE = "/flux_overseer_state"
private const val PATH_CUSTOM_PROFILE_META = "/custom_profile_meta"
private const val PATH_VISUAL_THEME_SYNC = "/flux_visual_theme"
private const val ACTION_PREPARE_LOCAL =
    "com.snakesan.neonflux.PREPARE_LOCAL"

private const val EXTRA_SEQUENCE_PAYLOAD = "sequence_payload"

private const val PATH_CLINICAL_PREPARE = "/clinical_prepare"
private const val PATH_CLINICAL_READY = "/clinical_ready"
private const val PATH_CLINICAL_ENGAGE = "/clinical_engage"

// Kept in sync with FluxService.kt's CLINICAL_START_LEAD_MS (unused here today,
// but duplicated under the same name - update both if either changes).
private const val CLINICAL_START_LEAD_MS = 3_000L
// GLSL Support
// Enhanced Multi-Layer LCD Display with Block-Tearing Glitch & Flicker
const val MultiLayerLcdShader = """
    uniform shader composable;
    uniform float2 resolution;
    uniform float time;

    // Theme Layer Colors 
    uniform half4 colorBg;
    uniform half4 colorL1;
    uniform half4 colorL2;
    uniform half4 colorPri;

    // Toggles mapping: x=L1, y=L2, z=Primary, w=Background
    uniform float4 flickerToggles;
    uniform float4 glitchToggles;

    uniform float flickerRate;
    uniform float flickerIntensity;
    
    uniform float glitchSpeed;
    uniform float glitchThickness;
    uniform float glitchMinShift;
    uniform float glitchMaxShift;

    uniform float depthOffset;
    uniform float gridDensity;
    uniform float overallIntensity;

    // Helper: Color distance
    float colorDist(half4 c1, half4 c2) {
        return length(c1.rgb - c2.rgb);
    }

    // Helper: Pseudo-random number generator
    float rand(float2 p) {
        return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
    }

    half4 main(float2 fragCoord) {
        half4 baseColor = composable.eval(fragCoord);
        
        // 1. Identify Layer
        float dBg = colorDist(baseColor, colorBg);
        float dL1 = colorDist(baseColor, colorL1);
        float dL2 = colorDist(baseColor, colorL2);
        float dPri = colorDist(baseColor, colorPri);
        
        float minDist = min(min(dBg, dL1), min(dL2, dPri));
        
        float isBg = step(dBg, minDist + 0.05);
        float isL1 = step(dL1, minDist + 0.05) * (1.0 - isBg);
        float isL2 = step(dL2, minDist + 0.05) * (1.0 - isBg) * (1.0 - isL1);
        float isPri = step(dPri, minDist + 0.05) * (1.0 - isBg) * (1.0 - isL1) * (1.0 - isL2);

        // 2. Hardware Scanline Displacer (Glitch)
        float activeGlitch = dot(glitchToggles, float4(isL1, isL2, isPri, isBg));
        float2 readCoord = fragCoord;
        
        if (activeGlitch > 0.5) {
            // Quantize Y coordinate to create discrete bands
            float blockY = floor(fragCoord.y / max(1.0, glitchThickness));
            
            // Time step to animate the tearing
            float timeStep = floor(time * glitchSpeed);
            
            // Random chance to tear this specific block (makes it look organically broken)
            float rChance = rand(float2(blockY, timeStep + 10.0));
            
            if (rChance > 0.6) { // 40% chance for block to shift
                float rShift = rand(float2(blockY, timeStep));
                float shift = mix(glitchMinShift, glitchMaxShift, rShift);
                readCoord.x += shift;
                baseColor = composable.eval(readCoord); // Resample at torn coordinate
            }
        }

        // 3. Voltage Flicker
        float activeFlicker = dot(flickerToggles, float4(isL1, isL2, isPri, isBg));
        if (activeFlicker > 0.5) {
            float blink = 1.0 - ((sin(time * flickerRate) * 0.5 + 0.5) * flickerIntensity);
            baseColor.rgb *= blink;
        }
        
        // 4. Physical Depth & Grid
        half4 shadowColor = composable.eval(readCoord + float2(depthOffset, depthOffset));
        shadowColor.rgb *= 0.3; 
        
        float gridX = mod(readCoord.x, max(1.0, gridDensity));
        float gridY = mod(readCoord.y, max(1.0, gridDensity));
        float gridMult = (gridX < 1.0 || gridY < 1.0) ? 0.85 : 1.0;
        
        half4 combined = half4(
            (baseColor.rgb * baseColor.a) + (shadowColor.rgb * (1.0 - baseColor.a)), 
            max(baseColor.a, shadowColor.a)
        );
        combined.rgb *= gridMult;
        
        return mix(composable.eval(fragCoord), combined, overallIntensity);
    }
"""

data class ShaderConfig(
    var isEnabled: Boolean = false,
    var depthOffset: Float = 6f,
    var gridDensity: Float = 4f,
    var overallIntensity: Float = 0.5f,

    // Flicker Params
    var flickerRate: Float = 15f,
    var flickerIntensity: Float = 0.3f,
    var flickerL1: Boolean = false,
    var flickerL2: Boolean = true,
    var flickerPri: Boolean = true,
    var flickerBg: Boolean = false,

    // Scanline Glitch Params
    var glitchSpeed: Float = 15f,
    var glitchThickness: Float = 10f,
    var glitchMinShift: Float = -20f,
    var glitchMaxShift: Float = 50f,
    var glitchL1: Boolean = false,
    var glitchL2: Boolean = false,
    var glitchPri: Boolean = true,
    var glitchBg: Boolean = false
)

val MonochromeBaseColors = listOf(
    "RUBY" to Color(0xFFFF0000), "TANGERINE" to Color(0xFFFF6600), "AMBER" to Color(0xFFFFCC00),
    "CITRUS" to Color(0xFFAAFF00), "NEON" to Color(0xFF00FF00), "MINT" to Color(0xFF00FA9A),
    "CYAN" to Color(0xFF00FFFF), "AZURE" to Color(0xFF0088FF), "COBALT" to Color(0xFF0000FF),
    "INDIGO" to Color(0xFF4B0082), "VIOLET" to Color(0xFF8A2BE2), "MAGENTA" to Color(0xFFFF00FF),
    "ROSE" to Color(0xFFFF007F), "CRIMSON" to Color(0xFFDC143C), "WHITE" to Color(0xFFFFFFFF),
    "PHOSPHOR" to Color(0xFFFFFDD0)
)


fun generateMonochromeLayers(baseColor: Color, isHdr: Boolean): List<Color> {
    val space = if (isHdr) ColorSpaces.ExtendedSrgb else ColorSpaces.Srgb
    val r = baseColor.red
    val g = baseColor.green
    val b = baseColor.blue
    val topMultiplier = if (isHdr) 1.5f else 1.0f

    return listOf(
        Color(r * 0.25f, g * 0.25f, b * 0.25f, 1f, space),
        Color(r * 0.50f, g * 0.50f, b * 0.50f, 1f, space),
        Color(r * 0.75f, g * 0.75f, b * 0.75f, 1f, space),
        Color(r * topMultiplier, g * topMultiplier, b * topMultiplier, 1f, space)
    )
}
val FluxPalettes = listOf(
    NeonPalette("DEFAULT CYBER", Color(0xFF00F3FF), Color(0xFFFF0055)),
    NeonPalette("TOXIC VENOM", Color(0xFF00FF41), Color(0xFFB900FF)),
    NeonPalette("SOLAR FLARE", Color(0xFFFF9900), Color(0xFF00F3FF)),
    NeonPalette("GHOST SHELL", Color(0xFFE0E0E0), Color(0xFF555555))
)



// --- ACCESSIBILITY HELPERS ---
fun smartContrast(baseColor: Color, isHighContrast: Boolean, isMonochromeActive: Boolean = false): Color {
    if (isMonochromeActive) return baseColor
    if (!isHighContrast) return baseColor
    return if (baseColor.luminance() <= 0.7f) Color.White else baseColor
}

@Composable
fun NeonTheme(palette: NeonPalette, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = palette.primary,
            secondary = palette.secondary,
            // We use surfaceVariant as our "DarkGray" / Dim layer
            surfaceVariant = palette.l1,
            // We use tertiary as our "Gray" / Mid layer
            tertiary = palette.l2,
            // Main text floating on black (White in standard, L4 in mono)
            onBackground = palette.textMain,
            // Text *inside* a UI element (Black for Mono, Gray/White for Standard)
            onSurfaceVariant = if (palette.isMonochrome) Color.Black else palette.l2,
            onPrimary = Color.Black,
            background = palette.bg,
            surface = palette.bg
        ), content = content
    )
}

class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = getSharedPreferences("FluxConfig", Context.MODE_PRIVATE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            // Read from properties directly inside the compose block to ensure reactive state
            var savedIdx by remember { mutableIntStateOf(prefs.getInt("theme_index", 0)) }
            var isMonochrome by remember { mutableStateOf(prefs.getBoolean("is_monochrome", false)) }
            var monoBaseIdx by remember { mutableIntStateOf(prefs.getInt("mono_base_index", 0)) }
            var isHdrIntense by remember { mutableStateOf(prefs.getBoolean("is_hdr_intense", false)) }

            // Dynamic Window Hardware Toggle (Must be called inside Compose Context via Effect)
            LaunchedEffect(isHdrIntense, isMonochrome) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    window.colorMode = if (isHdrIntense && isMonochrome) {
                        ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
                    } else {
                        ActivityInfo.COLOR_MODE_DEFAULT
                    }
                }
            }

            val activePalette = if (isMonochrome) {
                val pair = MonochromeBaseColors[monoBaseIdx.coerceIn(0, MonochromeBaseColors.size - 1)]
                val hexName = pair.first
                val baseCol = pair.second
                val layers = generateMonochromeLayers(baseCol, isHdrIntense)
                NeonPalette(
                    name = "${hexName} MONOCHROME",
                    primary = layers[3],
                    secondary = layers[2],
                    isMonochrome = true,
                    l1 = layers[0],
                    l2 = layers[1],
                    textMain = layers[3],
                    bg = Color.Black
                )
            } else {
                FluxPalettes[savedIdx.coerceIn(0, FluxPalettes.size - 1)]
            }

            NeonTheme(palette = activePalette) {
                FluxMobileUI(prefs)
            }
        }
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FluxMobileUI(prefs: SharedPreferences) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    // --- ACCESSIBILITY STATE ---
    var isHighContrast by remember { mutableStateOf(prefs.getBoolean("a11y_contrast", false)) }
    var phoneFontScale by remember { mutableFloatStateOf(prefs.getFloat("a11y_phone_font", 1.0f)) }
    var watchFontScale by remember { mutableFloatStateOf(prefs.getFloat("a11y_watch_font", 1.0f)) }

    // --- SHADER PROTOCOL STATE (Must be declared before Effects & Modifiers) ---
    var activeShaderBank by remember { mutableIntStateOf(prefs.getInt("active_shader_bank", 1)) }




    fun loadShaderConfig(bank: Int): ShaderConfig {
        val saved = prefs.getString("shader_config_$bank", null)
        if (saved != null) {
            val p = saved.split(",")
            if (p.size == 18) {
                return ShaderConfig(
                    isEnabled = p[0].toBoolean(), depthOffset = p[1].toFloat(),
                    gridDensity = p[2].toFloat(), overallIntensity = p[3].toFloat(),
                    flickerRate = p[4].toFloat(), flickerIntensity = p[5].toFloat(),
                    flickerL1 = p[6].toBoolean(), flickerL2 = p[7].toBoolean(),
                    flickerPri = p[8].toBoolean(), flickerBg = p[9].toBoolean(),
                    glitchSpeed = p[10].toFloat(), glitchThickness = p[11].toFloat(),
                    glitchMinShift = p[12].toFloat(), glitchMaxShift = p[13].toFloat(),
                    glitchL1 = p[14].toBoolean(), glitchL2 = p[15].toBoolean(),
                    glitchPri = p[16].toBoolean(), glitchBg = p[17].toBoolean()
                )
            }
        }
        return ShaderConfig()
    }

    var currentShaderConfig by remember(activeShaderBank) { mutableStateOf(loadShaderConfig(activeShaderBank)) }

    fun saveShaderConfig() {
        val c = currentShaderConfig
        val configStr = "${c.isEnabled},${c.depthOffset},${c.gridDensity},${c.overallIntensity},${c.flickerRate},${c.flickerIntensity},${c.flickerL1},${c.flickerL2},${c.flickerPri},${c.flickerBg},${c.glitchSpeed},${c.glitchThickness},${c.glitchMinShift},${c.glitchMaxShift},${c.glitchL1},${c.glitchL2},${c.glitchPri},${c.glitchBg}"
        prefs.edit().putString("shader_config_$activeShaderBank", configStr).putInt("active_shader_bank", activeShaderBank).apply()
    }



    val timeTicks = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(currentShaderConfig.isEnabled) {
        if (currentShaderConfig.isEnabled) {
            while(isActive) {
                withFrameMillis { frameTime -> timeTicks.floatValue = (frameTime % 100000L) / 1000f }
            }
        }
    }

    // --- EMERGENCY STATE ---
    var showEmergencyMenu by remember { mutableStateOf(false) }
    var emergencyUploadState by remember { mutableStateOf(UploadState.IDLE) }
    var activeEmergencyMode by remember { mutableIntStateOf(0) } // 0 = off, 1 = 5m, 2 = 7m, 3 = inf

    // --- Theme & Style Sheet State ---
    var activePaletteIndex by remember { mutableIntStateOf(prefs.getInt("theme_index", 0)) }
    var isMonoHdrToggled by remember { mutableStateOf(prefs.getBoolean("is_hdr_intense", false)) }
    var isMonochromeToggled by remember { mutableStateOf(prefs.getBoolean("is_monochrome", false)) }
    var monoBaseIndex by remember { mutableIntStateOf(prefs.getInt("mono_base_index", 0)) }

    var showInfDisclaimer by remember { mutableStateOf(false) }

    val activePalette = if (isMonochromeToggled) {
        val pair = MonochromeBaseColors[monoBaseIndex.coerceIn(0, MonochromeBaseColors.size - 1)]
        val layers = generateMonochromeLayers(pair.second, isMonoHdrToggled)
        NeonPalette(
            name = "${pair.first} MONOCHROME",
            primary = layers[3],
            secondary = layers[2],
            isMonochrome = true,
            l1 = layers[0],
            l2 = layers[1],
            textMain = layers[3],
            bg = Color.Black
        )
    } else {
        FluxPalettes[activePaletteIndex.coerceIn(0, FluxPalettes.size - 1)]
    }

    var showStyleSheet by remember { mutableStateOf(false) }
    var titleTapCount by remember { mutableIntStateOf(0) }
    var lastTitleTapTime by remember { mutableLongStateOf(0L) }

    var showSequencer by remember { mutableStateOf(false) }
    var intensity by remember { mutableFloatStateOf(prefs.getFloat("intensity", 50f)) }
    var bpm by remember { mutableFloatStateOf(prefs.getFloat("bpm", 60f)) }
    var selectedProfile by remember { mutableIntStateOf(prefs.getInt("profile", 0)) }
    var sleepMode by remember { mutableStateOf(prefs.getBoolean("sleep", false)) }

    fun sendCustomProfileMetadataToWatch() {
        if (selectedProfile != 3) {
            return
        }

        val bank = prefs.getInt("active_custom_bank", 1)

        val name = prefs.getString(
            "custom_name_$bank",
            "CUSTOM_$bank"
        ) ?: "CUSTOM_$bank"

        val nameBytes = name.encodeToByteArray()

        val payload = ByteBuffer.allocate(2 + nameBytes.size)
            .put(bank.toByte())
            .put(nameBytes.size.toByte())
            .put(nameBytes)
            .array()

        Wearable.getNodeClient(context).connectedNodes
            .addOnSuccessListener { nodes ->
                nodes.forEach { node ->
                    Wearable.getMessageClient(context).sendMessage(
                        node.id,
                        PATH_CUSTOM_PROFILE_META,
                        payload
                    )
                }
            }
    }

    fun sendVisualThemeToWatch() {
        val palette = activePalette

        val payload = ByteBuffer.allocate(27)
            .put(1)
            .put(if (palette.isMonochrome) 1 else 0)
            .put(if (isMonoHdrToggled) 1 else 0)
            .putInt(palette.primary.toArgb())
            .putInt(palette.secondary.toArgb())
            .putInt(palette.l1.toArgb())
            .putInt(palette.l2.toArgb())
            .putInt(palette.textMain.toArgb())
            .putInt(palette.bg.toArgb())
            .array()

        Wearable.getNodeClient(context).connectedNodes
            .addOnSuccessListener { nodes ->
                nodes.forEach { node ->
                    Wearable.getMessageClient(context).sendMessage(
                        node.id,
                        PATH_VISUAL_THEME_SYNC,
                        payload
                    )
                }
            }
    }

    var isSystemRunning by remember { mutableStateOf(false) }
    val beatHistory = remember { mutableStateListOf<Pair<Long, Float>>() }
    var buttonState by remember { mutableStateOf(UploadState.IDLE) }
    fun sendOverseerFluxState(isRunning: Boolean = isSystemRunning) {
        val customBank = prefs.getInt("active_custom_bank", 1)
        val customName = prefs.getString(
            "custom_name_$customBank",
            "CUSTOM_$customBank"
        ) ?: "CUSTOM_$customBank"

        val nameBytes = customName.encodeToByteArray()

        val packet = ByteBuffer.allocate(33 + nameBytes.size)
            .apply {
                put(1) // Protocol version
                put(if (isRunning) 1 else 0)
                put(selectedProfile.toByte())
                put(customBank.toByte())
                putInt(bpm.toInt())
                put(intensity.toInt().toByte())
                put(if (sleepMode) 1 else 0)
                put(if (activePalette.isMonochrome) 1 else 0)
                put(if (isMonoHdrToggled) 1 else 0)

                putInt(activePalette.primary.toArgb())
                putInt(activePalette.secondary.toArgb())
                putInt(activePalette.l1.toArgb())
                putInt(activePalette.l2.toArgb())
                putInt(activePalette.bg.toArgb())

                put(nameBytes.size.coerceAtMost(255).toByte())
                put(nameBytes.copyOf(nameBytes.size.coerceAtMost(255)))
            }
            .array()

        Wearable.getNodeClient(context).connectedNodes
            .addOnSuccessListener { nodes ->
                nodes.forEach { node ->
                    Wearable.getMessageClient(context).sendMessage(
                        node.id,
                        PATH_FLUX_OVERSEER_STATE,
                        packet
                    )
                }
            }
    }

    val lastBeat = beatHistory.lastOrNull()?.first ?: 0L
    val isSignalReceiving = (System.currentTimeMillis() - lastBeat) < 2500

    LaunchedEffect(isSignalReceiving, buttonState) {
        if (buttonState != UploadState.UPLOADING && buttonState != UploadState.SUCCESS) {
            buttonState = if (isSignalReceiving) UploadState.ACTIVE else UploadState.IDLE
        }
    }

    fun scaledSp(size: Number) = (size.toFloat() * phoneFontScale).sp

    fun sendEmergencyToWatch(mode: Int, intensityDb: Float, durationMin: Int) {
        val buffer = ByteBuffer.allocate(9)
        buffer.put(mode.toByte())
        buffer.putFloat(intensityDb)
        buffer.putInt(durationMin)

        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach {
                Wearable.getMessageClient(context).sendMessage(it.id, "/emergency_override", buffer.array())
            }
        }

        scope.launch {
            emergencyUploadState = UploadState.UPLOADING
            delay(2000)
            emergencyUploadState = UploadState.SUCCESS
            activeEmergencyMode = mode
            showEmergencyMenu = false
            delay(1000)
            emergencyUploadState = if (mode > 0) UploadState.ACTIVE else UploadState.IDLE
        }
    }

    fun getSequencePayload(): ByteArray {
        val bank = prefs.getInt("active_custom_bank", 1)
        val saved = prefs.getString("custom_seq_$bank", null) ?: return byteArrayOf(0)
        try {
            val parts = saved.split(";")
            val out = java.io.ByteArrayOutputStream()
            out.write(parts.size)
            parts.forEach {
                val stepData = it.split(",")
                val modeStr = stepData[0]
                val isOverride = stepData[1].toBoolean()
                val customInt = stepData[2].toFloat().toInt()

                val state = when (modeStr) {
                    "OFF", "false" -> 0
                    "HIT", "true" -> if (isOverride) 2 else 1
                    "SUSTAIN" -> if (isOverride) 4 else 3
                    else -> 0
                }
                out.write(state)
                out.write(customInt)
            }
            return out.toByteArray()
        } catch (e: Exception) { return byteArrayOf(0) }
    }

    fun sendConfigToWatch() {
        sendVisualThemeToWatch()
        sendCustomProfileMetadataToWatch()
        val seqPayload = if (selectedProfile == 3) getSequencePayload() else byteArrayOf()
        val buffer = ByteBuffer.allocate(7 + seqPayload.size)
        buffer.put(selectedProfile.toByte())
        buffer.putInt(bpm.toInt())
        buffer.put(intensity.toInt().toByte())
        buffer.put(if(sleepMode) 1.toByte() else 0.toByte())
        if (selectedProfile == 3) buffer.put(seqPayload)

        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { Wearable.getMessageClient(context).sendMessage(it.id, "/clinical_conf", buffer.array()) }
        }
    }

    fun sendA11yToWatch() {
        val buffer = ByteBuffer.allocate(5)
        buffer.put(if(isHighContrast) 1.toByte() else 0.toByte())
        buffer.putFloat(watchFontScale)

        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach {
                Wearable.getMessageClient(context).sendMessage(it.id, "/flux_a11y_sync", buffer.array())
            }
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
            val sequencePayload = if (selectedProfile == 3) {
                getSequencePayload()
            } else {
                byteArrayOf()
            }

            sendVisualThemeToWatch()
            sendCustomProfileMetadataToWatch()

            val serviceIntent = Intent(context, FluxService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            val prepareIntent = Intent(ACTION_PREPARE_LOCAL).apply {
                setPackage(context.packageName)
                putExtra("profile", selectedProfile)
                putExtra("bpm", bpm.toInt())
                putExtra("intensity", intensity.toInt())
                putExtra("sleep", sleepMode)
                putExtra(EXTRA_SEQUENCE_PAYLOAD, sequencePayload)
            }

            context.sendBroadcast(prepareIntent)
        } else {
            val stopIntent = Intent("com.snakesan.neonflux.STOP_LOCAL").apply {
                setPackage(context.packageName)
            }

            context.sendBroadcast(stopIntent)
            sendStopToWatch()
        }

        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    fun emergencyStop() {
        isSystemRunning = false
        val intent = Intent("com.snakesan.neonflux.STOP_LOCAL")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
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

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when(intent?.action) {
                    "com.snakesan.neonflux.BEAT_EVENT" -> {
                        val intensity = intent.getFloatExtra("intensity", 0f)
                            .coerceIn(0f, 1f)

                        val now = System.currentTimeMillis()

                        beatHistory.add(now to intensity)

                        // Keep the visualizer bounded and discard expired trace data.
                        while (
                            beatHistory.size > 64 ||
                            beatHistory.firstOrNull()?.first?.let { now - it > 5_000L } == true
                        ) {
                            beatHistory.removeAt(0)
                        }
                    }
                    "com.snakesan.neonflux.REMOTE_START_UI" -> isSystemRunning = true
                    "com.snakesan.neonflux.REMOTE_STOP_UI" -> isSystemRunning = false
                    "com.snakesan.neonflux.EMERGENCY_HALT_UI" -> {
                        activeEmergencyMode = 0
                        emergencyUploadState = UploadState.IDLE
                        showEmergencyMenu = false
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("com.snakesan.neonflux.BEAT_EVENT")
            addAction("com.snakesan.neonflux.REMOTE_START_UI")
            addAction("com.snakesan.neonflux.REMOTE_STOP_UI")
            addAction("com.snakesan.neonflux.EMERGENCY_HALT_UI")
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
        onResult = { perms ->
            if (perms.values.all { it }) {
                val i = Intent(context, FluxService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
            }
        }
    )

    LaunchedEffect(Unit) {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (perms.isNotEmpty()) launcher.launch(perms.toTypedArray()) else {
            val i = Intent(context, FluxService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
        }
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

    LaunchedEffect(
        bpm,
        intensity,
        selectedProfile,
        sleepMode,
        isSystemRunning,
        activePalette.primary,
        activePalette.secondary,
        activePalette.l1,
        activePalette.l2,
        activePalette.bg,
        activePalette.isMonochrome,
        isMonoHdrToggled
    ) {
        // LaunchedEffect cancels and restarts when any listed value changes.
        // Therefore, rapidly dragging a slider only sends after the user pauses.
        delay(250L)

        sendOverseerFluxState()
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

    // --- SMART LOCK MODIFIER ---
    val isEmergencyUIActive = showEmergencyMenu || activeEmergencyMode != 0

    val dimAndLockModifier = if (isEmergencyUIActive) {
        Modifier
            .drawWithContent {
                drawContent()
                drawRect(Color.Black.copy(alpha = 0.8f)) // 20% transparency overlay
            }
            .pointerInput(isEmergencyUIActive) {
                detectTapGestures { } // Traps and consumes all clicks
            }
    } else {
        Modifier
    }

    // --- SHADER MODIFIER ---
    val lcdShaderModifier = remember(currentShaderConfig, isHighContrast, timeTicks.floatValue, activePalette) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && currentShaderConfig.isEnabled && !isHighContrast) {
            val runtimeShader = android.graphics.RuntimeShader(MultiLayerLcdShader)

            runtimeShader.setFloatUniform("resolution", 1000f, 1000f)
            runtimeShader.setFloatUniform("time", timeTicks.floatValue)
            runtimeShader.setFloatUniform("depthOffset", currentShaderConfig.depthOffset)
            runtimeShader.setFloatUniform("gridDensity", currentShaderConfig.gridDensity)
            runtimeShader.setFloatUniform("overallIntensity", currentShaderConfig.overallIntensity)

            runtimeShader.setFloatUniform("colorBg", activePalette.bg.red, activePalette.bg.green, activePalette.bg.blue, activePalette.bg.alpha)
            runtimeShader.setFloatUniform("colorL1", activePalette.l1.red, activePalette.l1.green, activePalette.l1.blue, activePalette.l1.alpha)
            runtimeShader.setFloatUniform("colorL2", activePalette.l2.red, activePalette.l2.green, activePalette.l2.blue, activePalette.l2.alpha)
            runtimeShader.setFloatUniform("colorPri", activePalette.primary.red, activePalette.primary.green, activePalette.primary.blue, activePalette.primary.alpha)

            runtimeShader.setFloatUniform("flickerToggles", if(currentShaderConfig.flickerL1) 1f else 0f, if(currentShaderConfig.flickerL2) 1f else 0f, if(currentShaderConfig.flickerPri) 1f else 0f, if(currentShaderConfig.flickerBg) 1f else 0f)
            runtimeShader.setFloatUniform("glitchToggles", if(currentShaderConfig.glitchL1) 1f else 0f, if(currentShaderConfig.glitchL2) 1f else 0f, if(currentShaderConfig.glitchPri) 1f else 0f, if(currentShaderConfig.glitchBg) 1f else 0f)

            runtimeShader.setFloatUniform("flickerRate", currentShaderConfig.flickerRate)
            runtimeShader.setFloatUniform("flickerIntensity", currentShaderConfig.flickerIntensity)

            // New Scanline Uniforms
            runtimeShader.setFloatUniform("glitchSpeed", currentShaderConfig.glitchSpeed)
            runtimeShader.setFloatUniform("glitchThickness", currentShaderConfig.glitchThickness)
            runtimeShader.setFloatUniform("glitchMinShift", currentShaderConfig.glitchMinShift)
            runtimeShader.setFloatUniform("glitchMaxShift", currentShaderConfig.glitchMaxShift)

            Modifier.graphicsLayer { this.renderEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(runtimeShader, "composable").asComposeRenderEffect() }
        } else {
            Modifier
        }
    }


    // --- UI LAYOUT ---
    NeonTheme(palette = activePalette) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .systemBarsPadding()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 1. HEADER (DO NOT lock or dim this)
                Text(
                    "NEON // FLUX",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 4.sp,
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                val now = System.currentTimeMillis()
                                if (now - lastTitleTapTime > 2000) titleTapCount = 0
                                lastTitleTapTime = now
                                titleTapCount++
                                if (titleTapCount == 3) {
                                    view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                                    showStyleSheet = true
                                    titleTapCount = 0
                                }
                            }
                        )
                    }
                )
                Text(
                    "CLINICAL CONTROLLER",
                    color = smartContrast(MaterialTheme.colorScheme.tertiary, isHighContrast, isMonochromeToggled),
                    fontSize = scaledSp(12),
                    letterSpacing = 2.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 2. MAIN CONTROLS (Waveforms, Sliders, Memory Banks) wrapped in dimAndLockModifier
                Column(modifier = dimAndLockModifier.then(lcdShaderModifier).fillMaxWidth()) {
                    AnimatedVisibility(
                        visible = showSequencer,
                        enter = expandVertically(expandFrom = Alignment.Top),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top)
                    ) {
                        CyberSequencer(
                            globalBpm = bpm,
                            globalIntensity = intensity,
                            prefs = prefs,
                            onClose = { showSequencer = false },
                            onSelectProfile = { selectedProfile = 3 }
                        )
                    }

                    if (!showSequencer) {
                        Text("WAVEFORM", color = smartContrast(MaterialTheme.colorScheme.tertiary, isHighContrast, isMonochromeToggled), fontSize = scaledSp(10), fontWeight = FontWeight.Bold)
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            CyberButton("PULSE", selectedProfile == 0) { selectedProfile = 0 }
                            CyberButton("GEIGER", selectedProfile == 1) { selectedProfile = 1 }
                            CyberButton("THROB", selectedProfile == 2) { selectedProfile = 2 }
                            CyberButton("CUSTOM", selectedProfile == 3) {
                                selectedProfile = 3
                                showSequencer = true
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        CyberSlider("FREQUENCY (BPM)", bpm, 30f..160f, "") { bpm = it }

                        val isCustomSelected = selectedProfile == 3
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).alpha(if (isCustomSelected) 0.3f else 1f)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (isCustomSelected) "INTENSITY (OVERRIDDEN)" else "INTENSITY", color = MaterialTheme.colorScheme.primary, fontSize = scaledSp(12))
                                Text("${intensity.toInt()}%", color = MaterialTheme.colorScheme.onBackground, fontSize = scaledSp(12), fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = intensity,
                                onValueChange = { if (!isCustomSelected) intensity = it },
                                valueRange = 0f..100f,
                                thumb = {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp))
                                            .background(if (isCustomSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondary)
                                            .border(1.dp, if (isCustomSelected) Color.Transparent else MaterialTheme.colorScheme.onBackground.copy(alpha=0.5f), CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp))
                                    )
                                },
                                colors = SliderDefaults.colors(
                                    activeTrackColor = MaterialTheme.colorScheme.secondary,
                                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("SLEEP PROTOCOL", color = if(sleepMode) MaterialTheme.colorScheme.primary else smartContrast(MaterialTheme.colorScheme.tertiary, isHighContrast, isMonochromeToggled), fontSize = scaledSp(14), fontWeight = FontWeight.Bold)
                                Text("Disable sensors & screen during Clinical", color = smartContrast(MaterialTheme.colorScheme.surfaceVariant, isHighContrast, isMonochromeToggled), fontSize = scaledSp(10))
                            }

                            // Sharp Cyber Toggle
                            Box(
                                modifier = Modifier
                                    .width(60.dp)
                                    .height(28.dp)
                                    .clip(CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp))
                                    .background(if (sleepMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .border(1.dp, if (sleepMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp))
                                    .clickable { sleepMode = !sleepMode },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = if (sleepMode) "ON" else "OFF", color = if (sleepMode) Color.Black else smartContrast(MaterialTheme.colorScheme.tertiary, isHighContrast, isMonochromeToggled), fontSize = scaledSp(10), fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text("MEMORY BANKS", color = smartContrast(MaterialTheme.colorScheme.tertiary, isHighContrast, isMonochromeToggled), fontSize = scaledSp(10), fontWeight = FontWeight.Bold)
                        Text("LONG PRESS: WRITE // TAP: READ", color = smartContrast(MaterialTheme.colorScheme.surfaceVariant, isHighContrast, isMonochromeToggled), fontSize = scaledSp(8), modifier = Modifier.padding(bottom = 8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val mod = Modifier.weight(1f)
                            CyberPresetButton("ENGRAM", mod, phoneFontScale, isHighContrast, { loadPreset(0, "ENGRAM") }, { savePreset(0, "ENGRAM") })
                            CyberPresetButton("DAEMON", mod, phoneFontScale, isHighContrast, { loadPreset(1, "DAEMON") }, { savePreset(1, "DAEMON") })
                            CyberPresetButton("GHOST", mod, phoneFontScale, isHighContrast, { loadPreset(2, "GHOST") }, { savePreset(2, "GHOST") })

                            CyberLocalButton(
                                isActive = isSystemRunning,
                                modifier = mod,
                                fontScale = phoneFontScale,
                                isHighContrast = isHighContrast,
                                onTap = { toggleSystem() },
                                onLongPress = { emergencyStop() }
                            )
                        }
                    }
                } // End of locked Sector 2

                Spacer(modifier = Modifier.height(16.dp))

                // 3. EMERGENCY PROTOCOL SECTOR (DO NOT lock or dim this)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                ) {
                    EmergencyProtocolButton(
                        state = emergencyUploadState,
                        isActive = activeEmergencyMode != 0,
                        fontScale = phoneFontScale,
                        isHighContrast = isHighContrast,
                        themePalette = activePalette
                    ) {
                        if (emergencyUploadState == UploadState.IDLE || emergencyUploadState == UploadState.ACTIVE) {
                            showEmergencyMenu = !showEmergencyMenu
                        }
                    }

                    AnimatedVisibility(
                        visible = showEmergencyMenu,
                        enter = expandVertically(expandFrom = Alignment.Top),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top)
                    ) {
                        Column {
                            // UNLOCK PROMPT (If Custom Sequencer is currently overriding)
                            if (selectedProfile == 3) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                        .clip(CutCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { selectedProfile = 0 } // Switch to Pulse, freeing the slider
                                        .padding(12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "INTENSITY SLIDER LOCKED BY CUSTOM PROFILE\n[ TAP TO UNLOCK ]",
                                        color = activePalette.primary,
                                        fontSize = (10 * phoneFontScale).sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val emMod = Modifier.weight(1f).height(40.dp).clip(CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))

                                @Composable
                                fun EmButton(text: String, isActive: Boolean, onClick: () -> Unit) {
                                    Box(
                                        modifier = emMod
                                            .background(if (isActive) activePalette.secondary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.5f))
                                            .border(1.dp, if (isActive) activePalette.secondary else MaterialTheme.colorScheme.tertiary, CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                                            .clickable(onClick = onClick),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(text, color = if(isActive) Color.White else activePalette.textMain, fontSize = (12 * phoneFontScale).sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                // 5MIN, 7MIN, INF Buttons
                                EmButton("5MIN", activeEmergencyMode == 1) {
                                    sendEmergencyToWatch(1, intensity, 5)
                                }

                                EmButton("7MIN", activeEmergencyMode == 2) {
                                    sendEmergencyToWatch(2, intensity, 7)
                                }

                                EmButton("INF", activeEmergencyMode == 3) {
                                    showInfDisclaimer = true
                                }

                                // HALT
                                Box(
                                    modifier = emMod
                                        .background(Color.Red.copy(alpha = 0.2f))
                                        .border(1.dp, Color.Red, CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
                                        .clickable {
                                            sendEmergencyToWatch(0, 0f, 0)
                                            activeEmergencyMode = 0
                                            emergencyUploadState = UploadState.IDLE
                                            showEmergencyMenu = false
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("HALT", color = smartContrast(Color.Red, isHighContrast, isMonochromeToggled), fontSize = (12 * phoneFontScale).sp, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }
                } // End of Emergency Sector 3

                Spacer(modifier = Modifier.weight(1f))

                // 4. BOTTOM SECTOR (BioMonitor & Sync Button) wrapped in dimAndLockModifier
                if (!showSequencer) {
                    Column(modifier = dimAndLockModifier.fillMaxWidth()) {
                        BioFluxMonitor(beatHistory)
                        Spacer(modifier = Modifier.height(10.dp))

                        SmartSyncButton(
                            state = buttonState,
                            onClick = {
                                if (buttonState == UploadState.IDLE || buttonState == UploadState.ACTIVE) {
                                    sendConfigToWatch()
                                    sendOverseerFluxState()
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
            } // End of Main UI Layout Column

            // --- INDEFINITE PROTOCOL DISCLAIMER OVERLAY ---
            AnimatedVisibility(
                visible = showInfDisclaimer,
                enter = fadeIn() + scaleIn(initialScale = 0.95f),
                exit = fadeOut() + scaleOut(targetScale = 0.95f),
                modifier = Modifier.zIndex(200f) // Highest layer!
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.95f)) // Deep vignette
                        .clickable(enabled = false) {}, // Swallow clicks
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .clip(CutCornerShape(12.dp))
                            .background(Color(0xFF0A0A0A))
                            .border(2.dp, activePalette.primary, CutCornerShape(12.dp))
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // NFO HEADER
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .background(Color.Black)
                                .border(1.dp, activePalette.secondary)
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {

                            Image(
                                painter = painterResource(id = R.drawable.snakesannfo),
                                contentDescription = "Neon Flux Warning Graphic",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                                colorFilter = ColorFilter.tint(activePalette.primary)
                            )
                        }

Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "WARNING: INDEFINITE OVERRIDE",
                            color = Color.Red,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // DISCLAIMER TEXT
                        Text(
                            text = "You are authorizing an unbroken cyber-sustain sequence. \n\nContinuous haptic resonance for periods extending beyond structural limits may cause hardware exhaustion or sensory fatigue.\n\nOnly proceed if you require terminal grounding.",
                            color = activePalette.textMain,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 16.sp
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // ACTIONS
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Button(
                                onClick = { showInfDisclaimer = false },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                shape = CutCornerShape(8.dp)
                            ) {
                                Text("ABORT", color = activePalette.textMain)
                            }

                            Button(
                                onClick = {
                                    showInfDisclaimer = false
                                    sendEmergencyToWatch(3, intensity, -1)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f)),
                                shape = CutCornerShape(8.dp)
                            ) {
                                Text("I ACCEPT THE RISKS", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // --- UI STYLE SHEET SHOWCASE OVERLAY ---
            AnimatedVisibility(
                visible = showStyleSheet,
                enter = expandVertically(expandFrom = Alignment.Bottom),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom),
                modifier = Modifier.zIndex(100f)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().clickable(enabled = false) {}
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("DESIGN SYSTEM", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)

                        Spacer(modifier = Modifier.height(16.dp))

                        // MONOCHROME CONTROLS
                        Column(modifier = Modifier.fillMaxWidth().clip(CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp)).background(Color.DarkGray.copy(0.3f)).border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha=0.5f), CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp))) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    isMonochromeToggled = !isMonochromeToggled
                                    prefs.edit().putBoolean("is_monochrome", isMonochromeToggled).apply()
                                    sendVisualThemeToWatch()
                                }.padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("MONOCHROME PROTOCOL", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text(if (isMonochromeToggled) "ON" else "OFF", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                            }

                            AnimatedVisibility(visible = isMonochromeToggled) {
                                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("HDR INTENSITY (Hardware Gamut)", color = Color.LightGray, fontSize = 10.sp)
                                        Switch(
                                            checked = isMonoHdrToggled,
                                            onCheckedChange = {
                                                isMonoHdrToggled = it
                                                prefs.edit().putBoolean("is_hdr_intense", it).apply()
                                            }
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    val currentColorName = MonochromeBaseColors[monoBaseIndex].first
                                    val currentColorValue = MonochromeBaseColors[monoBaseIndex].second

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("BASE CORE", color = MaterialTheme.colorScheme.tertiary, fontSize = 10.sp)
                                        Text(currentColorName, color = currentColorValue, fontSize = 14.sp, fontWeight = FontWeight.Black)
                                    }

                                    Slider(
                                        value = monoBaseIndex.toFloat(),
                                        onValueChange = { newValue ->
                                            monoBaseIndex = newValue.toInt()
                                            prefs.edit().putInt("mono_base_index", monoBaseIndex).apply()
                                        },
                                        valueRange = 0f..(MonochromeBaseColors.size - 1).toFloat(),
                                        steps = MonochromeBaseColors.size - 2, // -2 because steps are *between* min/max
                                        colors = SliderDefaults.colors(
                                            activeTrackColor = currentColorValue,
                                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                            thumbColor = currentColorValue
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        var showThemeMenu by remember { mutableStateOf(false) }

                        Column(modifier = Modifier.fillMaxWidth().clip(CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp)).background(Color.DarkGray.copy(0.3f)).border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha=0.5f), CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp))) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { showThemeMenu = !showThemeMenu }.padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("ACTIVE THEME:", color = Color.Gray, fontSize = 10.sp)
                                    Text(activePalette.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                                Text(if (showThemeMenu) "▲" else "▼", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                            }

                            AnimatedVisibility(visible = showThemeMenu) {
                                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    FluxPalettes.forEachIndexed { index, palette ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable {
                                                activePaletteIndex = index
                                                prefs.edit().putInt("theme_index", index).apply()
                                                sendVisualThemeToWatch()
                                                Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
                                                    nodes.forEach {
                                                        Wearable.getMessageClient(context)
                                                            .sendMessage(it.id, "/flux_theme_sync", byteArrayOf(index.toByte()))
                                                    }
                                                }
                                            },
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(palette.name, color = if (index == activePaletteIndex) MaterialTheme.colorScheme.primary else Color.LightGray, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                            if (index == activePaletteIndex) Text("ACTIVE", color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))

                                    Button(
                                        onClick = { showStyleSheet = false },
                                        modifier = Modifier.fillMaxWidth().height(45.dp),
                                        shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                    ) {
                                        Text("EXIT DESIGN SYSTEM", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        var showShaderMenu by remember { mutableStateOf(false) }

                        // LCD SHADER PROTOCOL BLOCK
                        Column(modifier = Modifier.fillMaxWidth().clip(CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp)).background(Color.DarkGray.copy(0.3f)).border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha=0.5f), CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp))) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { showShaderMenu = !showShaderMenu }.padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("CYBER-LCD PROTOCOL", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text("Multi-layer aberration & voltage variants", color = Color.Gray, fontSize = 10.sp)
                                }
                                Text(if (showShaderMenu) "▲" else "▼", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                            }

                            AnimatedVisibility(visible = showShaderMenu) {
                                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {

                                    // ENABLE SWITCH
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("ENABLE EFFECT", color = Color.LightGray, fontSize = 10.sp)
                                        Switch(
                                            checked = currentShaderConfig.isEnabled,
                                            onCheckedChange = {
                                                currentShaderConfig = currentShaderConfig.copy(isEnabled = it)
                                                saveShaderConfig()
                                            }
                                        )
                                    }

                                    // MEMORY BANK SELECTOR
                                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                        (1..3).forEach { bank ->
                                            CyberButton(text = "BANK $bank", isSelected = activeShaderBank == bank, fontScale = phoneFontScale, isHighContrast = isHighContrast) {
                                                activeShaderBank = bank
                                            }
                                        }
                                    }

                                    // BASE LCD PARAMS
                                    Text("PHYSICAL MANIFESTATION", color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    CyberSlider("BACKPLANE DEPTH", currentShaderConfig.depthOffset, 0f..20f, "px", phoneFontScale, isHighContrast) {
                                        currentShaderConfig = currentShaderConfig.copy(depthOffset = it)
                                    }
                                    CyberSlider("MATRIX GRID DENSITY", currentShaderConfig.gridDensity, 1f..10f, "px", phoneFontScale, isHighContrast) {
                                        currentShaderConfig = currentShaderConfig.copy(gridDensity = it)
                                    }
                                    CyberSlider("BLEND INTENSITY", currentShaderConfig.overallIntensity * 100f, 0f..100f, "%", phoneFontScale, isHighContrast) {
                                        currentShaderConfig = currentShaderConfig.copy(overallIntensity = it / 100f)
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // VOLTAGE FLICKER PARAMS
                                    Text("VOLTAGE FLICKER", color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    CyberSlider("FLICKER RATE", currentShaderConfig.flickerRate, 1f..50f, "hz", phoneFontScale, isHighContrast) {
                                        currentShaderConfig = currentShaderConfig.copy(flickerRate = it)
                                    }
                                    CyberSlider("FLICKER INTENSITY", currentShaderConfig.flickerIntensity * 100f, 0f..100f, "%", phoneFontScale, isHighContrast) {
                                        currentShaderConfig = currentShaderConfig.copy(flickerIntensity = it / 100f)
                                    }
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                        CyberButton("L1", currentShaderConfig.flickerL1, phoneFontScale, isHighContrast) { currentShaderConfig = currentShaderConfig.copy(flickerL1 = !currentShaderConfig.flickerL1) }
                                        CyberButton("L2", currentShaderConfig.flickerL2, phoneFontScale, isHighContrast) { currentShaderConfig = currentShaderConfig.copy(flickerL2 = !currentShaderConfig.flickerL2) }
                                        CyberButton("PRI", currentShaderConfig.flickerPri, phoneFontScale, isHighContrast) { currentShaderConfig = currentShaderConfig.copy(flickerPri = !currentShaderConfig.flickerPri) }
                                        CyberButton("BG", currentShaderConfig.flickerBg, phoneFontScale, isHighContrast) { currentShaderConfig = currentShaderConfig.copy(flickerBg = !currentShaderConfig.flickerBg) }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // GLITCH ABERRATION PARAMS
                                    Text("HARDWARE SCANLINE DISPLACEMENT", color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(4.dp))

                                    CyberSlider("DISPLACEMENT SPEED", currentShaderConfig.glitchSpeed, 1f..60f, "hz", phoneFontScale, isHighContrast) {
                                        currentShaderConfig = currentShaderConfig.copy(glitchSpeed = it)
                                    }
                                    CyberSlider("BAND THICKNESS", currentShaderConfig.glitchThickness, 1f..100f, "px", phoneFontScale, isHighContrast) {
                                        currentShaderConfig = currentShaderConfig.copy(glitchThickness = it)
                                    }
                                    CyberSlider("MIN X-SHIFT", currentShaderConfig.glitchMinShift, -150f..150f, "px", phoneFontScale, isHighContrast) {
                                        currentShaderConfig = currentShaderConfig.copy(glitchMinShift = it)
                                    }
                                    CyberSlider("MAX X-SHIFT", currentShaderConfig.glitchMaxShift, -150f..150f, "px", phoneFontScale, isHighContrast) {
                                        currentShaderConfig = currentShaderConfig.copy(glitchMaxShift = it)
                                    }

                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                        CyberButton("L1", currentShaderConfig.glitchL1, phoneFontScale, isHighContrast) { currentShaderConfig = currentShaderConfig.copy(glitchL1 = !currentShaderConfig.glitchL1) }
                                        CyberButton("L2", currentShaderConfig.glitchL2, phoneFontScale, isHighContrast) { currentShaderConfig = currentShaderConfig.copy(glitchL2 = !currentShaderConfig.glitchL2) }
                                        CyberButton("PRI", currentShaderConfig.glitchPri, phoneFontScale, isHighContrast) { currentShaderConfig = currentShaderConfig.copy(glitchPri = !currentShaderConfig.glitchPri) }
                                        CyberButton("BG", currentShaderConfig.glitchBg, phoneFontScale, isHighContrast) { currentShaderConfig = currentShaderConfig.copy(glitchBg = !currentShaderConfig.glitchBg) }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // SAVE BUTTON
                                    Button(
                                        onClick = {
                                            saveShaderConfig()
                                            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                            Toast.makeText(context, "CYBER-LCD BANK $activeShaderBank ENCODED", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.fillMaxWidth().height(45.dp),
                                        shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                    ) {
                                        Text("COMMIT TO BANK $activeShaderBank", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }

                        // Remaining Accessibility / Style sheet controls remain exactly the same formatting...
                        Spacer(modifier = Modifier.height(24.dp))
                        Text("ACCESSIBILITY", color = MaterialTheme.colorScheme.primary, fontSize = scaledSp(12), modifier = Modifier.align(Alignment.Start))
                        Divider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 8.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("SMART HIGH CONTRAST", color = smartContrast(Color.Gray, isHighContrast), fontSize = scaledSp(12))
                            Switch(
                                checked = isHighContrast,
                                onCheckedChange = {
                                    isHighContrast = it
                                    prefs.edit().putBoolean("a11y_contrast", it).apply()
                                    sendA11yToWatch()
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary)
                            )
                        }

                        CyberSlider("PHONE FONT SCALE", phoneFontScale * 100, 100f..200f, "%") {
                            phoneFontScale = it / 100f
                            prefs.edit().putFloat("a11y_phone_font", phoneFontScale).apply()
                        }

                        CyberSlider("WATCH FONT SCALE", watchFontScale * 100, 100f..200f, "%") {
                            watchFontScale = it / 100f
                            prefs.edit().putFloat("a11y_watch_font", watchFontScale).apply()
                            sendA11yToWatch()
                        }

                        Spacer(modifier = Modifier.height(40.dp))
                    }
                }
            } // End of Style Sheet
        }
    }
}

// --- MODULAR UI COMPONENTS & SEQUENCER ---

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CyberSequencer(
    globalBpm: Float,
    globalIntensity: Float,
    prefs: SharedPreferences,
    onClose: () -> Unit,
    onSelectProfile: () -> Unit
) {
    val context = LocalContext.current
    val vibrator = remember<Vibrator> { (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator }

    var currentBank by remember { mutableIntStateOf(1) }
    var profileName by remember { mutableStateOf(prefs.getString("custom_name_$currentBank", "CUSTOM_$currentBank") ?: "CUSTOM_$currentBank") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var editingStepIndex by remember { mutableIntStateOf(-1) }


    fun loadSequenceFromPrefs(bank: Int): MutableList<PulseStep> {
        val saved = prefs.getString("custom_seq_$bank", null)
        if (saved != null && saved.isNotBlank()) {
            try {
                return saved.split(";").map {
                    val parts = it.split(",")
                    val loadedMode = when(parts[0]) {
                        "true" -> StepMode.HIT
                        "false" -> StepMode.OFF
                        else -> StepMode.valueOf(parts[0])
                    }
                    PulseStep(loadedMode, parts[1].toBoolean(), parts[2].toFloat())
                }.toMutableStateList()
            } catch (e: Exception) { e.printStackTrace() }
        }
        return mutableStateListOf<PulseStep>().apply { (0..7).forEach { add(PulseStep()) } }
    }

    fun saveSequenceToPrefs(bank: Int, seq: List<PulseStep>, name: String) {
        val serialized = seq.joinToString(";") { "${it.mode.name},${it.isOverride},${it.customIntensity}" }
        prefs.edit().putString("custom_seq_$bank", serialized).putString("custom_name_$bank", name).apply()
        Toast.makeText(context, "SAVED TO BANK $bank", Toast.LENGTH_SHORT).show()
    }

    val sequence = remember(currentBank) { loadSequenceFromPrefs(currentBank) }
    var isPlaying by remember { mutableStateOf(false) }
    var activeStep by remember { mutableIntStateOf(-1) }

    LaunchedEffect(isPlaying, globalBpm) {
        if (!isPlaying) { activeStep = -1; return@LaunchedEffect }
        val stepDelay = (60000f / globalBpm / 2f).toLong()
        var currentIndex = 0

        while (isPlaying) {
            if (sequence.isEmpty()) break
            activeStep = currentIndex
            val node = sequence[currentIndex]

            val isStartOfNote = node.mode == StepMode.HIT || (node.mode == StepMode.SUSTAIN && sequence[(currentIndex - 1 + sequence.size) % sequence.size].mode == StepMode.OFF)

            if (isStartOfNote) {
                var totalDuration = if (node.mode == StepMode.HIT) 40L else stepDelay
                if (node.mode == StepMode.HIT || node.mode == StepMode.SUSTAIN) {
                    var lookAheadIdx = (currentIndex + 1) % sequence.size
                    while (sequence[lookAheadIdx].mode == StepMode.SUSTAIN && lookAheadIdx != currentIndex) {
                        totalDuration += stepDelay
                        lookAheadIdx = (lookAheadIdx + 1) % sequence.size
                    }
                }

                val finalIntensity = if (node.isOverride) node.customIntensity else globalIntensity
                val amp = (finalIntensity * 255 / 100).toInt().coerceAtLeast(10)
                if (vibrator.hasAmplitudeControl()) vibrator.vibrate(VibrationEffect.createOneShot(totalDuration, amp))
            }

            currentIndex = (currentIndex + 1) % sequence.size
            delay(stepDelay)
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CutCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(2.dp, MaterialTheme.colorScheme.secondary, CutCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("CYBER-SEQUENCER", color = MaterialTheme.colorScheme.secondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("CLOSE X", color = MaterialTheme.colorScheme.tertiary, fontSize = 10.sp, modifier = Modifier.clickable { onClose() })
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..3).forEach { bank ->
                        val isSel = currentBank == bank
                        Box(
                            modifier = Modifier
                                .clip(CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp))
                                .background(if(isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    currentBank = bank
                                    profileName = prefs.getString("custom_name_$bank", "CUSTOM_$bank") ?: "CUSTOM_$bank"
                                    showDeleteConfirm = false
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("BANK $bank", color = if(isSel) Color.Black else MaterialTheme.colorScheme.tertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (showDeleteConfirm) {
                    Text("TAP TO CONFIRM WIPE", color = Color.Red, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                        prefs.edit().remove("custom_seq_$currentBank").remove("custom_name_$currentBank").apply()
                        sequence.clear()
                        (0..7).forEach { sequence.add(PulseStep()) }
                        profileName = "CUSTOM_$currentBank"
                        showDeleteConfirm = false
                        Toast.makeText(context, "BANK WIPED", Toast.LENGTH_SHORT).show()
                    })
                } else {
                    Text("WIPE", color = MaterialTheme.colorScheme.surfaceVariant, fontSize = 10.sp, modifier = Modifier.clickable { showDeleteConfirm = true })
                }
            }

            OutlinedTextField(
                value = profileName,
                onValueChange = { profileName = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                singleLine = true,
                shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text("Tap to activate. Long press for Override Mode (Pink).", color = MaterialTheme.colorScheme.surfaceVariant, fontSize = 10.sp)

            Spacer(modifier = Modifier.height(8.dp))

            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val racks = sequence.chunked(8)
                racks.forEachIndexed { rackIndex, rack ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        rack.forEachIndexed { stepIndex, step ->
                            val globalIndex = (rackIndex * 8) + stepIndex
                            val isHighlighted = activeStep == globalIndex

                            Box(
                                modifier = Modifier
                                    .width(36.dp)
                                    .height(100.dp)
                                    .clip(CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .border(1.dp, if(isHighlighted) MaterialTheme.colorScheme.onBackground else Color.Transparent, CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp))
                                    .combinedClickable(
                                        onClick = {
                                            val nextMode = when (step.mode) {
                                                StepMode.OFF -> StepMode.HIT
                                                StepMode.HIT -> StepMode.SUSTAIN
                                                StepMode.SUSTAIN -> StepMode.OFF
                                            }
                                            sequence[globalIndex] = step.copy(mode = nextMode)
                                        },
                                        onLongClick = {
                                            sequence[globalIndex] = step.copy(mode = if(step.mode == StepMode.OFF) StepMode.HIT else step.mode, isOverride = true)
                                            editingStepIndex = globalIndex
                                        }
                                    ),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                if (step.mode != StepMode.OFF) {
                                    val barColor = if (step.isOverride) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                                    val barHeight = if (step.isOverride) (step.customIntensity / 100f) * 100 else (globalIntensity / 100f) * 100
                                    val maxW = if (step.mode == StepMode.SUSTAIN) 1f else 0.5f
                                    Box(modifier = Modifier.fillMaxWidth(maxW).height(barHeight.dp).background(barColor))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (sequence.size < 32) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(30.dp).clip(CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.3f))
                        .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp)).clickable {
                            (0..7).forEach { sequence.add(PulseStep()) }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("+ ADD MEASURE", color = MaterialTheme.colorScheme.tertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Button(
                    onClick = { isPlaying = !isPlaying },
                    shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if(isPlaying) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.tertiary)
                ) {
                    Text(if (isPlaying) "STOP" else "TEST", color = MaterialTheme.colorScheme.onBackground, fontSize = 10.sp)
                }

                Button(
                    onClick = {
                        isPlaying = false
                        saveSequenceToPrefs(currentBank, sequence, profileName)
                        prefs.edit().putInt("active_custom_bank", currentBank).apply()
                        onSelectProfile()
                    },
                    shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if(isPlaying) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text("APPLY & SAVE TO WATCH", color = MaterialTheme.colorScheme.onBackground, fontSize = 10.sp)
                }
            }
        }

        if (editingStepIndex >= 0) {
            Box(
                modifier = Modifier.matchParentSize().clip(CutCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.85f)).clickable { editingStepIndex = -1 },
                contentAlignment = Alignment.Center
            ) {
                val step = sequence[editingStepIndex]

                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .clip(CutCornerShape(16.dp))
                        .background(Color(0xFF151515))
                        .border(2.dp, MaterialTheme.colorScheme.secondary, CutCornerShape(16.dp))
                        .clickable { /* Consume click */ }
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("OVERRIDE AMPLITUDE", color = MaterialTheme.colorScheme.secondary, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("STEP ${editingStepIndex + 1}", color = MaterialTheme.colorScheme.tertiary, fontSize = 10.sp)

                    Spacer(modifier = Modifier.height(24.dp))
                    Text("${step.customIntensity.toInt()}%", color = MaterialTheme.colorScheme.onBackground, fontSize = 32.sp, fontWeight = FontWeight.Black)

                    Slider(
                        value = step.customIntensity,
                        onValueChange = { sequence[editingStepIndex] = step.copy(customIntensity = it) },
                        valueRange = 0f..100f,
                        steps = 9,
                        thumb = {
                            Box(modifier = Modifier.size(20.dp).clip(CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp)).background(MaterialTheme.colorScheme.secondary).border(1.dp, MaterialTheme.colorScheme.onBackground, CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp)))
                        },
                        colors = SliderDefaults.colors(
                            activeTrackColor = MaterialTheme.colorScheme.secondary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                            activeTickColor = Color.Black.copy(alpha = 0.5f),
                            inactiveTickColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.padding(vertical = 12.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Button(
                            onClick = { sequence[editingStepIndex] = step.copy(isOverride = false); editingStepIndex = -1 },
                            shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) { Text("REVERT", color = MaterialTheme.colorScheme.onBackground, fontSize = 10.sp) }

                        Button(
                            onClick = { editingStepIndex = -1 },
                            shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) { Text("CONFIRM", color = MaterialTheme.colorScheme.onBackground, fontSize = 10.sp) }
                    }
                }
            }
        }
    }
}

@Composable
fun CautionStripes(
    color: Color,
    stripeWidth: Float = 20f,
    spacing: Float = 20f
) {
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val totalStripes = ((w + h) / (stripeWidth + spacing)).toInt() * 2

        val path = androidx.compose.ui.graphics.Path()
        for (i in -totalStripes..totalStripes) {
            val startX = i * (stripeWidth + spacing)
            path.moveTo(startX, 0f)
            path.lineTo(startX + stripeWidth, 0f)
            path.lineTo(startX - h + stripeWidth, h)
            path.lineTo(startX - h, h)
            path.close()
        }
        drawPath(path, color = color, alpha = 0.15f)
    }
}

@Composable
fun EmergencyProtocolButton(
    state: UploadState,
    isActive: Boolean,
    fontScale: Float = 1f,
    isHighContrast: Boolean = false,
    themePalette: NeonPalette,
    onClick: () -> Unit
) {
    val progress by animateFloatAsState(
        targetValue = if (state == UploadState.UPLOADING) 1f else 0f,
        animationSpec = tween(durationMillis = 2000, easing = LinearEasing),
        label = "emergencySync"
    )

    // Pulsing pink (or theme primary in monochrome)
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseColor = if (themePalette.isMonochrome) themePalette.primary else Color(0xFFFF0055)
    val pulseRatio by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseRatio"
    )

    val baseBgColor = if (isActive) {
        Color(
            red = pulseColor.red * pulseRatio,
            green = pulseColor.green * pulseRatio,
            blue = pulseColor.blue * pulseRatio,
            alpha = 1f
        )
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val containerColor = when (state) {
        UploadState.UPLOADING, UploadState.ACTIVE -> baseBgColor
        UploadState.SUCCESS -> pulseColor
        UploadState.IDLE -> baseBgColor
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .border(
                width = 2.dp,
                color = if (isActive || state == UploadState.ACTIVE) pulseColor else Color.Transparent,
                shape = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp)
            )
    ) {
        // Caution stripes layer
        CautionStripes(color = if (themePalette.isMonochrome) Color.Black else pulseColor)

        // Upload progress bar layer
        if (state == UploadState.UPLOADING) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(Color.White.copy(alpha = 0.3f))
            )
        }

        // Text layer
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = when (state) {
                    UploadState.IDLE -> "EMERGENCY PROTOCOL"
                    UploadState.UPLOADING -> "TRANSMITTING OVERRIDE..."
                    UploadState.SUCCESS -> "OVERRIDE ACCEPTED"
                    UploadState.ACTIVE -> "PROTOCOL ACTIVE"
                },
                fontWeight = FontWeight.Black,
                color = smartContrast(
                    if (isActive || state == UploadState.SUCCESS) Color.White else MaterialTheme.colorScheme.onBackground,
                    isHighContrast,
                    themePalette.isMonochrome
                ),
                fontSize = (16 * fontScale).sp,
                letterSpacing = 2.sp
            )
        }
    }
}

@Composable
fun SmartSyncButton(state: UploadState, fontScale: Float = 1f, isHighContrast: Boolean = false, onClick: () -> Unit) {
    val progress by animateFloatAsState(targetValue = if (state == UploadState.UPLOADING) 1f else 0f, animationSpec = tween(durationMillis = 3000, easing = LinearEasing), label = "uploadProgress")
    val containerColor by animateColorAsState(targetValue = when(state) {
        UploadState.UPLOADING -> MaterialTheme.colorScheme.surfaceVariant
        UploadState.ACTIVE -> MaterialTheme.colorScheme.surfaceVariant
        UploadState.SUCCESS -> Color(0xFF00FF41) // Keep the hardcoded success green, it's a structural necessity!
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
                fontWeight = FontWeight.Bold,
                color = if(state == UploadState.SUCCESS) Color.Black else MaterialTheme.colorScheme.onBackground,
                fontSize = (16 * fontScale).sp,
                letterSpacing = 1.sp
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CyberPresetButton(text: String, modifier: Modifier = Modifier, fontScale: Float = 1f, isHighContrast: Boolean = false, onTap: () -> Unit, onLongPress: () -> Unit) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.tertiary, CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = (10 * fontScale).sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CyberLocalButton(isActive: Boolean, modifier: Modifier = Modifier, fontScale: Float = 1f, isHighContrast: Boolean = false, onTap: () -> Unit, onLongPress: () -> Unit) {
    val bgColor = if (isActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant
    val borderColor = if (isActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
    val textColor = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .height(40.dp)
            .clip(CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        contentAlignment = Alignment.Center
    ) {
        Text(if(isActive) "ACTIVE" else "ENABLE", color = textColor, fontSize = (10 * fontScale).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun CyberButton(text: String, isSelected: Boolean, fontScale: Float = 1f, isHighContrast: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.width(100.dp).height(40.dp)
    ) {
        val textColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        Text(text, color = smartContrast(textColor, isHighContrast), fontSize = (10 * fontScale).sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CyberSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, fontScale: Float = 1f, isHighContrast: Boolean = false, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = MaterialTheme.colorScheme.primary, fontSize = (12 * fontScale).sp)
            Text("${value.toInt()}$unit", color = MaterialTheme.colorScheme.onBackground, fontSize = (12 * fontScale).sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            thumb = {
                Box(modifier = Modifier.size(16.dp).clip(CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp)).background(MaterialTheme.colorScheme.secondary).border(1.dp, MaterialTheme.colorScheme.tertiary, CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp)))
            },
            colors = SliderDefaults.colors(activeTrackColor = MaterialTheme.colorScheme.secondary, inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

@Composable
fun BioFluxMonitor(beatHistory: List<Pair<Long, Float>>, fontScale: Float = 1f, isHighContrast: Boolean = false) {
    val traceColor = MaterialTheme.colorScheme.secondary
    val timeSource = remember { mutableLongStateOf(0L) }
    val lastBeat = beatHistory.lastOrNull()?.first ?: 0L
    val isAlive = (System.currentTimeMillis() - lastBeat) < 2500

    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameMillis { timeSource.longValue = System.currentTimeMillis() }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("BIO-METRIC VISUALIZER", color = MaterialTheme.colorScheme.primary, fontSize = (10 * fontScale).sp, fontWeight = FontWeight.Bold)
            Text(if (isAlive) "SIGNAL LOCK" else "AWAITING TELEMETRY", color = if (isAlive) traceColor else MaterialTheme.colorScheme.tertiary, fontSize = (10 * fontScale).sp, fontWeight = FontWeight.Bold)
        }

        Box(
            // Bio Monitor bg stays true black always to emulate pure sensor canvas
            modifier = Modifier.fillMaxWidth().height(100.dp).background(Color.Black, shape = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp)).border(1.dp, if(isAlive) traceColor.copy(alpha=0.5f) else MaterialTheme.colorScheme.surfaceVariant, shape = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp)).clip(CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp))
        ) {

            // GRAB THE THEME COLOR HERE, OUTSIDE THE CANVAS
            val deadTraceColor = MaterialTheme.colorScheme.surfaceVariant

            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height; val midY = h / 2
                val now = timeSource.longValue; val scrollSpeed = 0.5f
                val path = androidx.compose.ui.graphics.Path().apply { moveTo(0f, midY) }

                for (x in 0 until w.toInt() step 2) {
                    val xPos = x.toFloat()
                    var totalYOffset = 0f

                    beatHistory.forEach { beatData ->
                        val beatTime = beatData.first
                        val amplitude = beatData.second

                        val timeSinceBeat = now - beatTime
                        if (timeSinceBeat > -500 && timeSinceBeat < 4000) {
                            val beatX = w - (timeSinceBeat * scrollSpeed)
                            val dist = xPos - beatX
                            if (dist > -50 && dist < 150) {
                                val p = (dist + 50) / 200f
                                var beatOffset = 0f
                                val scale = amplitude * 1.5f

                                if (p < 0.1) beatOffset = 0f
                                else if (p < 0.2) beatOffset = -10f * scale * (p-0.1f)/0.1f
                                else if (p < 0.3) beatOffset = -10f * scale
                                else if (p < 0.35) beatOffset = 20f * scale * (p-0.3f)/0.05f
                                else if (p < 0.45) beatOffset = (20f * scale) - (140f * scale * (p-0.35f)/0.1f)
                                else if (p < 0.55) beatOffset = (-120f * scale) + (140f * scale * (p-0.45f)/0.1f)
                                else if (p < 0.7) beatOffset = 10f * scale
                                else if (p < 0.9) beatOffset = -15f * scale * Math.sin((p-0.7)*Math.PI*5).toFloat()
                                else beatOffset = 0f

                                totalYOffset += beatOffset
                            }
                        }
                    }
                    val noise = Math.sin((xPos + now/2.0) / 20.0).toFloat() * 2f
                    if (x == 0) path.moveTo(xPos, midY + totalYOffset + noise)
                    else path.lineTo(xPos, midY + totalYOffset + noise)
                }
                // USE THE CAPTURED COLOR VARIABLE HERE
                drawPath(path = path, color = if(isAlive) traceColor else deadTraceColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
            }
            if (isAlive && (System.currentTimeMillis() / 500) % 2 == 0L) androidx.compose.foundation.Canvas(modifier = Modifier.padding(10.dp).align(Alignment.TopEnd).size(6.dp)) { drawCircle(Color.Red) }
        }
    }
}