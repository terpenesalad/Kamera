# Architecture

## The pipeline

```
Camera2 preview ─┐                                ┌─ viewfinder (window surface)
Camera2 YUV still ─ negative ─┐                   ├─ MediaCodec input surface (video)
Imported photo ───────────────┼─▶ FilmEngine ─────┼─ developed JPEG (darkroom)
Decoded video ────────────────┘                   └─ editor preview / export
```

`FilmEngine` runs two stages for every output:

1. **`updateGlow`** extracts light bright enough to scatter and blurs it in fixed-size buffers
   (270 and 90 pixels on the short edge).
2. **`drawFilm`** runs `film.frag`:
   display sRGB → scene-linear estimate → exposure and white balance → halation and bloom added as light →
   log encoding → clarity and vignette → grain as an exposure perturbation → 3D LUT → dither.

Grain is applied before the LUT, so the film's characteristic curve shapes it the way a real emulsion does.

## The colour model (`core`)

`FilmResponse` evaluates one stock under one grade:

1. Spectral sensitivity of each emulsion layer, reduced to a matrix over Rec.709 scene light
2. Interlayer (DIR coupler) effects in the log domain
3. A per-channel tone scale with soft toe and shoulder, solved to pass exactly through middle grey
4. `SpectralChain`: dye formation, light through the film, print paper exposure and print dyes. The result
   is compared with a neutral reference emulsion, so each stock differs only through its own chemistry
5. Hue, chroma and lightness character per hue band in Oklab
6. Split toning and gamut mapping

`LutBaker` samples this model into a 41³ `RGB10_A2` cube. Sliders that change colour rebake the cube on a
background thread; exposure, white balance and spatial effects are shader uniforms and update instantly.

Grain amplitude is derived from each stock's RMS granularity: density noise through a 48 µm aperture on a
24 mm frame. `tools/shader-lab` measures this back from rendered pixels. The grain tile holds two texels per
grain correlation length, so a full-resolution photo always minifies it (trilinear, smooth) rather than
magnifying near-white noise into blocky pixels.

## Threads

| Thread | Owns |
| --- | --- |
| Main | Interface |
| `Camera` | Camera2 session and capture state machine |
| `Viewfinder` | GL context, live rendering, encoder surface |
| `Darkroom` | Separate GL context, photo development |
| `VideoEncoder`, `AudioEncoder` | MediaCodec and microphone |
| `LutBake` | Cube baking |

## Storage

Photos and negatives are written by `core/.../codec/JpegEncoder`: baseline JPEG at quality 100 with no chroma
subsampling (4:4:4), encoded in parallel stripes joined by restart markers. Finished files carry no EXIF, and `Mp4Scrubber` clears dates and device tags from videos. Each library item lives in `files/library/<id>/`: `negative.jpg` (the upright undeveloped frame),
`recipe.txt`, `thumb.jpg` and `meta.txt`. Finished pictures are published to `DCIM/Kamera`. Clips recorded in
the app carry their film look in the footage, so the editor grades them without developing them twice.

## Verification

- `core/src/test` covers tone monotonicity, LUT accuracy, geometry, recipes, grain and shader parity.
- `tools/shader-lab` runs the real shaders headlessly (GLSL ES 3.00) to render contact sheets and compare a
  viewfinder-resolution render with a downscaled full-resolution render.
