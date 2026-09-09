# Landing page assets

Drop real screenshots and GIFs of NeonFlux here — for `index.html` at the
repo root.

- `screenshots/` — static screenshots. Watch shots should be square (native
  Wear OS aspect); phone shots should be 9:16.
- `gifs/` — short recordings (e.g. the Reactor's haptic texture swap, the
  Clinical sync handshake).
- `social/` — the Open Graph / X card and its editable source.

Dropping a file in here doesn't wire it up by itself — `index.html` still
needs `data-shot-src="assets/screenshots/<filename>"` set on that item's
`.shot-thumb` button. Current placeholder slots and their expected filenames:

| Slot | Expected filename | Notes |
| --- | --- | --- |
| Reactor deck (watch) | `screenshots/watch-reactor.png` | The CORE ring + AUDIO toggle, square |
| Clinical deck (watch) | `screenshots/watch-clinical.png` | BPM/INT readout + INITIALIZE button, square |
| Mission Control (phone) | `screenshots/phone-controller.png` | Waveform/BPM/intensity controls, 9:16 |
| Bio-Metric Visualizer (phone) | `screenshots/phone-biometric.png` | The live trace with SIGNAL LOCK, 9:16 |
| Haptic texture swap | `gifs/reactor-texture-swap.gif` | Double-tap cycling PULSE/GEIGER/THROB |
| Sync event | `gifs/sync-event.gif` | UPLOAD CONFIGURATION → SYNCHRONIZING → watch reload |
| Social/OG card | `social/og-card.png` | 1200×630, rendered from `social/og-card.source.html` |

Until a real file is dropped in and wired up, every screenshot placeholder
renders as a clearly-labeled "Screenshot coming soon" panel — never a broken
image icon.
