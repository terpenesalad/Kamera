// The Kamera film pipeline. The viewfinder, video encoder, photo developer and editor all run this
// exact program, so what is framed is what is delivered.
in vec2 vFrameUv;
in vec2 vSrcUv;
out vec4 fragColor;

uniform SOURCE_SAMPLER uSource;
uniform highp sampler3D uLut;
uniform highp sampler2D uGrain;
uniform highp sampler2D uGlowNear;
uniform highp sampler2D uGlowFar;
uniform highp sampler2D uDiffuse;
uniform highp sampler2D uStar;

uniform vec3 uInputGain;
uniform float uLutSize;

uniform float uGrainAmount;
uniform float uGrainChroma;
uniform vec2 uGrainDensity;
uniform vec2 uGrainUvScale;
uniform vec2 uGrainOffset;
uniform float uGrainLod;

uniform float uHalation;
uniform vec3 uHalationTint;
uniform float uBloom;

uniform float uDiffusion;
uniform float uDiffusionSpot;
uniform float uPastel;
uniform float uStarStrength;

uniform float uVignette;
uniform float uClarity;
uniform float uAcutance;
uniform vec2 uSrcStepX;
uniform vec2 uSrcStepY;

#ifdef VIEWFINDER
uniform float uZebra;
uniform float uPeaking;
#endif

const float GRAIN_RANGE = 3.0;

// Unit-variance film grain, built from a fine layer and a sparser layer of larger clumps.
vec3 filmGrain() {
    vec2 p = vFrameUv * uGrainUvScale + uGrainOffset;
    vec3 fine = textureLod(uGrain, p, uGrainLod).rgb - 0.5;
    vec2 q = mat2(0.8, -0.6, 0.6, 0.8) * p * 0.47 + vec2(0.37, 0.11);
    vec3 coarse = textureLod(uGrain, q, max(uGrainLod - 1.09, 0.0)).gbr - 0.5;
    vec3 n = (fine * 0.86 + coarse * 0.51) * (2.0 * GRAIN_RANGE);
    float shared_ = (n.r + n.g + n.b) * 0.57735;
    return mix(vec3(shared_), n, uGrainChroma);
}

void main() {
    vec3 display = texture(uSource, vSrcUv).rgb;

    // Film never resolves a digital sensor's edge contrast; this applies its acutance.
    if (uAcutance != 0.0) {
        vec3 around = texture(uSource, vSrcUv + uSrcStepX).rgb + texture(uSource, vSrcUv - uSrcStepX).rgb
                    + texture(uSource, vSrcUv + uSrcStepY).rgb + texture(uSource, vSrcUv - uSrcStepY).rgb;
        display = clamp(display + (display - around * 0.25) * uAcutance, 0.0, 1.0);
    }

    vec3 scene = lensFilters(displayToScene(display) * uInputGain, vFrameUv);

    // Diffusion filters scatter part of the light into a soft halo around everything.
    if (uDiffusion > 0.0) {
        vec3 scattered = logGlowDecode(texture(uDiffuse, vFrameUv).rgb);
        float amount = uDiffusion;
        if (uDiffusionSpot > 0.0) {
            float r = length((vFrameUv - 0.5) * uFrameAspect);
            amount *= smoothstep(0.2, 0.42, r);
        }
        scene = mix(scene, scattered, amount);
        if (uPastel > 0.0) {
            float l = dot(scene, LUMA);
            scene = mix(scene, vec3(l), uPastel * 0.5) + uPastel * 0.03;
        }
    }
    if (uStarStrength > 0.0) {
        scene += glowDecode(texture(uStar, vFrameUv).rgb) * uStarStrength;
    }

    // Halation and bloom are added as light, before the film responds to it.
    vec4 near = texture(uGlowNear, vFrameUv);
    vec3 far = glowDecode(texture(uGlowFar, vFrameUv).rgb);
    float scattered = dot(glowDecode(near.rgb), LUMA);
    scene += uHalationTint * scattered * uHalation + far * uBloom;

    vec3 encoded = sceneToLog(scene);

    float level = dot(encoded, LUMA);
    if (uClarity != 0.0) {
        float midtones = 1.0 - smoothstep(0.12, 0.30, abs(level - 0.57));
        encoded += (level - near.a) * uClarity * 1.5 * midtones;
    }

    vec2 corner = (vFrameUv - 0.5) * 2.0;
    float radius = dot(corner, corner) * 0.5;
    encoded -= uVignette * 2.2 * pow(radius, 1.3) / LOG_RANGE_STOPS;

    // Grain perturbs exposure, so the characteristic curve shapes it the way a real emulsion does.
    float stops = level * LOG_RANGE_STOPS + LOG_MIN_STOPS;
    // Granularity grows with the square root of the density formed (Selwyn's law).
    float weight = sqrt(clamp(uGrainDensity.x + uGrainDensity.y * stops, 0.15, 2.5));
    encoded += filmGrain() * uGrainAmount * weight / LOG_RANGE_STOPS;

    // Sample at texel centres so 0 and 1 land exactly on the first and last LUT nodes.
    vec3 lutCoord = clamp(encoded, 0.0, 1.0) * (uLutSize - 1.0) / uLutSize + 0.5 / uLutSize;
    vec3 color = texture(uLut, lutCoord).rgb;

#ifdef VIEWFINDER
    float lightness = dot(color, LUMA);
    if (uZebra > 0.0 && lightness > 0.965) {
        float stripe = step(0.5, fract((gl_FragCoord.x + gl_FragCoord.y) / 14.0));
        color = mix(color, vec3(stripe), 0.7);
    }
    if (uPeaking > 0.0) {
        float c = dot(texture(uSource, vSrcUv).rgb, LUMA);
        float e = dot(texture(uSource, vSrcUv + uSrcStepX).rgb, LUMA);
        float w = dot(texture(uSource, vSrcUv - uSrcStepX).rgb, LUMA);
        float n = dot(texture(uSource, vSrcUv + uSrcStepY).rgb, LUMA);
        float s = dot(texture(uSource, vSrcUv - uSrcStepY).rgb, LUMA);
        float edge = abs(4.0 * c - e - w - n - s);
        color = mix(color, vec3(1.0, 0.23, 0.12), smoothstep(0.16, 0.32, edge));
    }
#endif

    // Half-LSB dither keeps soft skies free of banding after 8-bit encoding.
    float dither = texelFetch(uGrain, ivec2(gl_FragCoord.xy) & 511, 0).a - 0.5;
    fragColor = vec4(color + dither * (GRAIN_RANGE * 2.0) / 255.0 * 0.3, 1.0);
}
