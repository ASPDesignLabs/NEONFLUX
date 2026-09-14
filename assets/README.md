# Landing page assets

`index.html` now recreates the Mission Control (phone) screen and the
Reactor/Clinical watch decks directly in HTML/CSS, built from the app's own
Compose source (real copy, real colors, real `CutCornerShape` values) — see
the `.phone-frame` / `.watch-frame` markup and the comment above the
`.cc-*` cut-corner utilities in `index.html`'s `<style>`. There's no
screenshot-wiring step for those anymore.

This folder is still here for real device captures that a static recreation
can't show — actual motion (the Reactor's haptic texture swap, the Clinical
sync handshake), or a real screenshot for extra credibility once the app has
a public release build:

- `screenshots/` — static screenshots, if you want to supplement (not
  replace) the recreations. Watch shots should be square; phone shots 9:16.
- `gifs/` — short recordings (e.g. the Reactor's haptic texture swap, the
  Clinical sync handshake).
- `social/` — the Open Graph / X card and its editable source.

Dropping a file in here doesn't do anything by itself — wiring one in means
adding markup for it in `index.html` (there's no lightbox/thumbnail
mechanism currently in the page; the previous placeholder-screenshot system
was removed when the phone/watch recreations replaced it).
