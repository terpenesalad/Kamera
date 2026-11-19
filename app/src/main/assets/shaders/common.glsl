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
// The diffusion buffer covers every tone, so it is stored logarithmically instead.
const float GLOW_RANGE = 16.0;
vec3 logGlowDecode(vec3 v) { return exp2(v * 16.0 - 12.0); }
vec3 logGlowEncode(vec3 v) { return clamp((log2(max(v, vec3(1.0 / 4096.0))) + 12.0) / 16.0, 0.0, 1.0); }
#ifdef LOG_GLOW
vec3 glowDecode(vec3 v) { return logGlowDecode(v); }
vec3 glowEncode(vec3 v) { return logGlowEncode(v); }
#else
vec3 glowDecode(vec3 v) { return v * v * GLOW_RANGE; }
vec3 glowEncode(vec3 v) { return sqrt(clamp(v / GLOW_RANGE, 0.0, 1.0)); }
#endif

// Filters in the holder act on light before it reaches the film.
uniform vec3 uGradTint;
uniform float uGradStrength;
uniform vec2 uGradDir;       // Towards the top of the picture, in frame space.
uniform float uGradPos;
uniform float uGradSoft;
uniform vec2 uFrameAspect;   // (width / height, 1)
uniform float uPolariser;

vec3 lensFilters(vec3 scene, vec2 frameUv) {
    if (uGradStrength > 0.0) {
        vec2 p = (frameUv - 0.5) * uFrameAspect;
        float extent = 0.5 * (abs(uGradDir.x) * uFrameAspect.x + abs(uGradDir.y) * uFrameAspect.y);
        float t = dot(p, uGradDir) / extent;
        float g = smoothstep(uGradPos - uGradSoft, uGradPos + uGradSoft, t);
        scene *= exp2(log2(uGradTint) * g * uGradStrength);
    }
    if (uPolariser > 0.0) {
        // Skylight is polarised; a polariser darkens it and cuts the haze that dulls colour.
        float l = max(dot(scene, LUMA), 1e-5);
        float sky = smoothstep(0.05, 0.45, (scene.b - max(scene.r, scene.g) * 0.85) / l);
        scene *= mix(1.0, 0.5, sky * min(uPolariser, 1.0));
        float l2 = dot(scene, LUMA);
        scene = max(vec3(l2) + (scene - l2) * (1.0 + 0.18 * uPolariser), vec3(0.0));
    }
    return scene;
}
