# Branding

The Printer Addon logo and the Discord images, made from code so they stay alike.

- `logo.py` → `logo.svg`: a white box two thirds built, an arrow printing into it, on a cyan to indigo gradient. Full bleed (Discord crops it to a circle or a rounded square).
- `render-all.js logo.svg` → `logo.png` (1024 px). The mod icon is this at 256 px (`src/main/resources/assets/printer_addon/icon.png`).
- `discord_images.py` → `discord/*.html`, then `node render-pages.js discord` → `discord/*.png`: the welcome banner, a header per channel, the install steps, the hotkeys, the report steps and the release card (`release(...)` at the bottom: change the version and its lines for a new release).

Needs Python 3 and Node with Playwright (Chromium). The font is Inter. The icons in `icons/` are [Lucide](https://lucide.dev) (ISC license).
