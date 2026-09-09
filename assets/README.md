# Landing page assets

Drop real screenshots and video/GIFs of NEON FLUX here — for `index.html` at
the repo root. Captions below are quoted from Snakesan's own write-up:
https://x.com/Snakesan/status/2011993821996106131

- `screenshots/` — static screenshots. Watch shots should be square (native
  Wear OS aspect); phone shots should be 9:16.
- `gifs/` — short recordings.
- `social/` — the Open Graph / X card and its editable source.

Dropping a file in here doesn't wire it up by itself — `index.html` still
needs `data-shot-src="assets/screenshots/<filename>"` set on that item's
`.shot-thumb` button. Current placeholder slots and their expected filenames:

| Slot (article caption) | Expected filename | Section |
| --- | --- | --- |
| "NEON FLUX Mobile, at initial start" | `screenshots/mobile-initial-start.png` | Getting Started |
| "Starting NEON FLUX opens the app in the REACTOR Deck" | `screenshots/watch-reactor-start.png` | Getting Started |
| "NEON FLUX Reactor demonstration, with audio playback" | `gifs/reactor-demo-audio.gif` | Reactor — this was a 0:12 video in the original post; convert to GIF, or ask if you'd rather host it as an actual `<video>` and we can wire that up instead |
| "A 100 BPM profile as depicted in the visualizer" | `screenshots/visualizer-100bpm.png` | Reactor |
| "Switching between haptic textures is a two tap process" | `screenshots/texture-swap-process.png` | Reactor |
| "The CLINICAL Deck interface" | `screenshots/clinical-deck-interface.png` | Transitioning Decks |
| "Uploading our device 'firmware' with our custom haptic profile" | `screenshots/firmware-upload.png` | Clinical |
| Social/OG card | `social/og-card.png` | 1200×630, rendered from `social/og-card.source.html` |

Until a real file is dropped in and wired up, every screenshot placeholder
renders as a clearly-labeled "Screenshot coming soon" panel — never a broken
image icon.
