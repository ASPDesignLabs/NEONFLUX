NEON // FLUX - Haptics for Grounding | Your Self Regulation Metronome User Guide
Snakesan
@Snakesan
·
Jan 15
·

NEON FLUX is a combined Wear OS and Android experience that harnesses movement and haptic playback to assist with grounding. Invisible. Reactive. Ready when you are.

Download the project files here:
https://github.com/ASPDesignLabs/NEONFLUX
(You will need Android Studio to build, ADB to sideload to a Wear OS device.)

Direct link to Beta APKs:
https://github.com/ASPDesignLabs/NEONFLUX/releases/tag/v1.0-beta.1

NEON FLUX is designed around two core philosophies: Reactive Feedback (Reactor Deck) for active stimming and grounding, and Rhythmic Entrainment (Clinical Deck) for passive focus and regulation.
Getting started is easy:
NEON FLUX Mobile, at initial start
1. Phone Setup (Neon Flux Mobile):
Launch the NEON FLUX app on your phone.
The "Bio-Metric Visualizer" at the bottom will initially read "AWAITING TELEMETRY".
Ensure Bluetooth is active. You do not need to manually pair; the apps communicate via the Wearable Data Layer automatically.
2. Watch Initialization (NEON FLUX):
Launch the app on your watch.
You will be greeted by the REACTOR deck. The central ring displays your CORE status, or time remaining in the experience. This is different from your device battery meter, as each mode draws different amounts of power over time.
Note: The app manages its own screen-on rules. In active use, the screen reduces on time. In [CLINICAL] mode it respects user settings.
Starting NEON FLUX opens the app in the REACTOR Deck
Part 1: The Reactor Deck (Active Grounding)
The Reactor Deck is your default state. It is an "instrument" that turns your movement into sensory feedback. It is designed for active stimming, using physical motion to generate grounding sensations.
How it works:
The Loop: The watch monitors linear acceleration. When you move (shake, tilt, or flick your wrist), it translates that energy into haptic vibration and synthesized audio.
Audio/Stealth: By default, the system is in STEALTH mode (Haptics only). Tap the button labeled AUDIO: OFF to toggle AUDIO: ON. Now, movement generates a rising sci-fi synth tone proportional to your intensity.
0:00 / 0:12
NEON FLUX Reactor demonstration, with audio playback
The Mirror: While active, the phone app’s Bio-Metric Visualizer mirrors your movement in real-time on a scrolling graph. This provides visual confirmation of your physical input, creating a closed sensory loop.
A 100 BPM profile as depicted in the visualizer
Changing Feedback Profiles:
The Reactor Deck isn't just one sensation. You can hot-swap the "texture" of the feedback:
Switching between haptic textures is a two tap process
Double-Tap anywhere on the background (not a button).
The watch will buzz and display the new profile name:
PULSE: Standard linear feedback. Smooth and predictable. Good for general movement.
GEIGER: Probabilistic clicking. The harder you move, the denser the "radiation" clicks become. Excellent for "fidgeting" or small, sharp movements.
THROB: Heavy, boosted impact. Vibrations are longer (80ms) and stronger. Best for deep pressure grounding.

Part 2: Transitioning Decks
The interface uses physical rotary input to prevent accidental mode switches.
Action: Rotate the digital crown (or physical bezel) of your watch.
Feedback: You will feel a mechanical "click" after sufficient rotation.
Result: The interface slides from REACTOR (Cyan) to CLINICAL (Red/Cyan).
The CLINICAL Deck interface
Part 3: The Clinical Deck (Passive Regulation)
While Reactor is about input, Clinical is about output. It is a metronome for your nervous system, forcing a precise, rhythmic haptic beat to help you regulate breathing or maintain focus during tasks. Rotate the watch crown in either direction to switch decks.
Configuration (The Phone's Role):
While you can simply hit "INITIALIZE" on the watch to use the last settings, the Neon Flux phone app is your Mission Control for this deck.
Waveform Selection: Choose between PULSE (Short), GEIGER (Sharp Tick), or THROB (Waveform) on the phone.
Frequency: Slider controls the BPM (Beats Per Minute). 60 BPM is standard for calming; higher BPMs work for walking or high-focus work.
Intensity: Controls the strength of the vibration.
Sleep Protocol: Toggle this switch if you want the watch screen and  ambient light sensors to turn off completely while the Clinical beat plays. This is crucial for sleeping or meditating without light distraction.
Uploading our device "firmware" with our custom haptic profile
The Sync Event:
Tap the large "UPLOAD CONFIGURATION" button on the phone.
On Phone: The button animates "SYNCHRONIZING..." as settings are sent to the watch.
On Watch: The watch is overtaken by a [FIRMWARE UPDATING] overlay. A progress bar fills the screen.
Completion: The watch automatically switches to the Clinical Deck with your new settings loaded.
Engagement:
Tap INITIALIZE on the watch.
You will get a 3-2-1 countdown.
The haptic beat begins. It uses an absolute-time drift correction algorithm, meaning it will never "drag" or "rush." It stays perfectly on beat for hours if necessary.
To stop, tap the screen or use the Emergency Halt (see below).
Safety & Lockdown Features
1. The Curtain (Focus Mode):
While Clinical Mode is running with Sleep Protocol active, a "Dark Curtain" fades in, dimming the UI to near-black. Outside of Sleep Protocol, the deck UI stays up so it's always clear what's running.
Why? Saves OLED screens from burn in, preserves battery life, while reducing visual overstimulation.
Wake: Use the Lockdown gesture to stop the session - there's no separate "just peek" wake while Sleep Protocol is active.
2. Lockdown Gesture (Emergency Stop):
If the sensation becomes overwhelming or you cannot look at the screen to find a button:
Action: Place two fingers on the screen and hold for 3 seconds.
Result: The system executes an immediate HARD STOP. The motors kill, the audio cuts, and a confirmation buzz lets you know the system is safe.
3. Exit Dialog:
Swipe "Back" (or press the physical back button) does not close the app immediately (to prevent accidental closures). Instead, it brings up a TERMINATE? dialog.
RESUME: Go back to the flux.
HALT: Kill the app and return to the watch face.
Safeguards:
SAFE MODE: In order to help protect your watch battery from high power draw at low power the app will refuse to run below 15%. If the watch OS reports less while the app is running it will close it. Future attempts to open the app will fail until charged.