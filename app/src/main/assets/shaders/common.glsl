// Shared colour encoding. Every constant mirrors app.kamera.film.FilmMath; ShaderParityTest keeps
// the two in step so the GPU and the colour model can never silently disagree.
const float MID_GREY = 0.18;
const float LOG_MIN_STOPS = -8.0;
const float LOG_RANGE_STOPS = 14.0;
const float HIGHLIGHT_EXPANSION = 0.90;
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

vec3 srgbToLinear(vec3 c) {
    vec3 low = c / 12.92;
    vec3 high = pow((c + 0.055) / 1.055, vec3(2.4));
    return mix(high, low, vec3(lessThanEqual(c, vec3(0.04045))));
}

// Display-referred sRGB to estimated scene-linear light (inverse Reinhard highlight expansion).
vec3 displayToScene(vec3 display) {
    vec3 l = min(srgbToLinear(clamp(display, 0.0, 1.0)), vec3(1.0));
    return (1.0 - MID_GREY * HIGHLIGHT_EXPANSION) * l / (1.0 - HIGHLIGHT_EXPANSION * l);
}

vec3 sceneToLog(vec3 scene) {
    vec3 stops = log2(max(scene, vec3(1e-5)) / MID_GREY);
    return clamp((stops - LOG_MIN_STOPS) / LOG_RANGE_STOPS, 0.0, 1.0);
}

// Glow buffers hold square-root-encoded light scaled into 0..1 so 8-bit targets do not band.
const float GLOW_RANGE = 16.0;
vec3 glowDecode(vec3 v) { return v * v * GLOW_RANGE; }
vec3 glowEncode(vec3 v) { return sqrt(clamp(v / GLOW_RANGE, 0.0, 1.0)); }
