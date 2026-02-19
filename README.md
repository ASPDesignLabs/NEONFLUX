# // NEONFLUX: HAPTIC SYNCHRONIZATION PROTOCOL

> **Invisible. Reactive. Ready when you are.**

**NEONFLUX** is a combined Wear OS and Android experience that harnesses movement and haptic playback to assist with grounding and regulation.

*   **Requirements:** Android Studio (to build) or ADB (to sideload).

---

## // CORE PHILOSOPHY

NEONFLUX is designed around two distinct decks, each serving a specific regulatory function:

1.  **REACTIVE FEEDBACK [REACTOR DECK]:** For active stimming and grounding. It acts as an instrument, translating physical kinetic energy into sensory feedback.
2.  **RHYTHMIC ENTRAINMENT [CLINICAL DECK]:** For passive focus and regulation. It acts as a precise metronome for the nervous system.

---

## // INITIALIZATION

### **1. Phone Setup (Mission Control)**
Launch **NEONFLUX Mobile**.
*   The **"Bio-Metric Visualizer"** will initially read `AWAITING TELEMETRY`.
*   Ensure Bluetooth is active. **No manual pairing is required**; the application utilizes the Wearable Data Layer to automatically handshake with the nearest node.

### **2. Watch Initialization (The Node)**
Launch **NEONFLUX Wear**.
*   You will be greeted by the **REACTOR** deck.
*   The central ring displays your **CORE** status. Note that this differs from your device battery meter, as the haptic engine draws power differently depending on the active profile.

---

## // DECK 01: REACTOR [ACTIVE GROUNDING]

The **Reactor Deck** is the default state. It turns movement into data you can feel.

### **How it Works**
*   **The Loop:** The watch monitors linear acceleration. When you move (shake, tilt, or flick), that energy is instantly translated into haptic vibration.
*   **Stealth vs. Audio:** By default, the system operates in **STEALTH** mode (Haptics only). Tap `AUDIO: OFF` to toggle `AUDIO: ON`. Movement will now generate a rising sci-fi synth tone proportional to your kinetic intensity.
*   **The Mirror:** The phone app's **Bio-Metric Visualizer** mirrors your movement in real-time, creating a closed sensory loop between physical input and visual confirmation.

### **Haptic Texture Swapping**
The Reactor isn't just one sensation. **Double-Tap** anywhere on the background to hot-swap the feedback texture:
*   **PULSE:** Standard linear feedback. Smooth and predictable.
*   **GEIGER:** Probabilistic clicking. Denser clicks as intensity rises. Excellent for "fidgeting."
*   **THROB:** Heavy, boosted impact (80ms). Best for deep pressure grounding.

---

## // TRANSITIONING DECKS

To prevent accidental mode switches during active use, the interface uses physical rotary input.

**Action:** Rotate the digital crown (or physical bezel).
**Feedback:** You will feel a mechanical "click" after sufficient rotation.
**Result:** The interface slides from **REACTOR** (Cyan) to **CLINICAL** (Red/Cyan).

---

## // DECK 02: CLINICAL [PASSIVE REGULATION]

While Reactor is about input, **Clinical** is about output. It forces a precise, rhythmic haptic beat to help regulate breathing or maintain focus.

### **Configuration (Mission Control)**
Use the phone app to program the watch's firmware for this mode.
*   **Waveform:** Choose PULSE, GEIGER, or THROB.
*   **Frequency:** Slider controls BPM (Beats Per Minute). 60 BPM is standard for calming.
*   **Intensity:** Controls motor strength.
*   **Sleep Protocol:** Toggle to force the watch screen and sensors off during the beat. Crucial for meditation or sleep without light pollution.

### **The Sync Event**
1.  Tap **"UPLOAD CONFIGURATION"** on the phone.
2.  **Phone:** Animates `SYNCHRONIZING...`
3.  **Watch:** Displays `[ FIRMWARE UPDATING ]` overlay.
4.  **Result:** The watch automatically loads the new settings and switches to the Clinical Deck.

### **Engagement**
Tap **INITIALIZE** on the watch.
*   A **3-2-1** countdown initiates.
*   The haptic engine begins. It uses an absolute-time drift correction algorithm to ensure it never "drags" or "rushes," staying perfectly on beat for hours.

---

## // SAFETY & LOCKDOWN

### **1. The Curtain (Focus Mode)**
If movement stops in Reactor Deck, or if Clinical Mode is active, a "Dark Curtain" fades in.
*   **Why:** Saves OLED screens from burn-in and reduces visual overstimulation.
*   **Wake:** Tap the screen once to lift the curtain. (If Sleep Protocol is active, use the Lockdown Gesture).

### **2. Lockdown Gesture (Emergency Stop)**
If sensation becomes overwhelming or you cannot navigate the UI:
*   **Action:** Place **two fingers** on the screen and hold for **3 seconds**.
*   **Result:** **HARD STOP.** Motors kill, audio cuts, and a confirmation buzz indicates the system is safe.

### **3. Safe Mode**
To protect battery health, the app will refuse to run if the watch battery is below **15%**.

### **4. Exit Dialog**
Swiping "Back" triggers a `TERMINATE?` dialog to prevent accidental closure.
*   **RESUME:** Return to the flux.
*   **HALT:** Kill the app and return to the watch face.

---

*NeonFlux is an experimental tool. Use responsibly.*
