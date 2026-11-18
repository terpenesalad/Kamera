# Kamera

A film camera for Android. Load a roll, compose through a viewfinder that already shows the finished
picture, then shoot stills or motion. Every negative stays editable, so any frame can be developed
again on a different stock later.

![Every Kamera stock rendered by the app's own shaders](docs/film-contact-sheet.jpg)

## Install

1. Open the [latest release](../../releases/latest) on your phone.
2. Download the `Kamera-1.0.x.apk` file and open it.
3. Allow installs from your browser if Android asks, then tap **Install**.

Kamera needs Android 11 or newer and OpenGL ES 3.0. New releases install over the old one and keep
your library.

## What's inside

**Sixteen film stocks**

| Family | Stock | Leans towards |
| --- | --- | --- |
| Colour negative | Aura 160, Aura 400, Aura 800 | Portra 160 / 400 / 800 |
| | Meridian 400 | Pro 400H |
| | Vivid 100 | Ektar 100 |
| | Harvest 200 | Gold 200 |
| Motion picture | Cine 50D, Cine 250D, Cine 500T | Vision3 50D / 250D / 500T |
| | Nocturne 800T | CineStill 800T |
| Slide | Chroma 50, Chroma 100 | Velvia 50, Provia 100F |
| | Archive 64 | Kodachrome 64 |
| Black & white | Silver 100, Silver 400, Silver 3200 | Acros 100, Tri-X 400, P3200 |

The stocks are original interpretations tuned by eye against test charts. They are not measured
profiles and are not affiliated with the films they reference.

**Camera:** manual shutter, ISO, focus, exposure compensation and white balance (presets, Kelvin, or
balanced to the loaded film) · push and pull development · 3:2, 4:3, 1:1, 5:4, 16:9 and 65:24 formats ·
1080p and 4K video at 24 to 60 fps in 16:9, 1.85 and 2.39 · live histogram, zebras, focus peaking, grid and
level · volume-key shutter.

**Darkroom:** re-develop on any stock · exposure, contrast, highlights, shadows, whites, blacks, fade ·
temperature, tint, saturation, vibrance, seven-band colour mixer, split toning · grain, halation, bloom,
vignette, clarity, sharpening · crop, straighten, rotate, mirror · video trim and grade · import from your
gallery.

## How the viewfinder matches the final picture

One GLSL program (`app/src/main/assets/shaders/film.frag`) renders the viewfinder, the video encoder, the
photo developer and the editor. On top of that:

- **Same sensor path.** Stills are captured as YUV from the same ISP path as the preview, with exposure and
  white balance locked to what was on screen. Vendor still processing never runs.
- **Same colour.** Each stock is baked into one 3D LUT that every output samples.
- **Resolution-independent grain and glow.** Grain has a physical size on a virtual 35 mm frame, and halation
  runs in fixed-resolution buffers, so a 1080p preview and a 12 MP photo agree at the same viewing size.
- **Same framing.** The viewfinder crops to the exact format that will be saved.

Flash is the one exception: a viewfinder can't preview light that hasn't fired yet.

## Project layout

```
core/    Pure Kotlin film science: colour model, stocks, LUT baking, grain, geometry, recipes, tests
app/     Android app: Camera2, OpenGL ES 3 engine, recording, library, interface
tools/   Headless GL bench that runs the app's real shaders for look and match testing
docs/    Architecture notes and the contact sheet
```

The app has no third-party runtime dependencies. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Build

Pushes to `main` are tested and built by GitHub Actions, which publishes an installable release.

To build locally you need JDK 17 and the Android SDK (API 36):

```sh
./gradlew :core:test :app:assembleRelease
```

## Signing

Release builds are signed with `app/kamera-sideload.keystore`, whose password is in `app/build.gradle.kts`.
Android only installs updates signed with the same key, so a stable key keeps updates working. This key is
public because this repository is, so it proves nothing about who built an APK. **Before distributing
Kamera to anyone else, replace it** with a private key supplied through the `KAMERA_KEYSTORE_FILE`,
`KAMERA_KEYSTORE_PASSWORD`, `KAMERA_KEY_ALIAS` and `KAMERA_KEY_PASSWORD` environment variables.
