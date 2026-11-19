// First step of the glow chain: isolate light bright enough to scatter, and record log luminance
// in alpha for the clarity control.
in vec2 vSrcUv;
in vec2 vFrameUv;
out vec4 fragColor;

uniform SOURCE_SAMPLER uSource;
uniform vec3 uInputGain;
uniform vec2 uSrcTexel;

void main() {
    vec3 sum = vec3(0.0);
    sum += texture(uSource, vSrcUv + uSrcTexel * vec2(-1.0, -1.0)).rgb;
    sum += texture(uSource, vSrcUv + uSrcTexel * vec2( 1.0, -1.0)).rgb;
    sum += texture(uSource, vSrcUv + uSrcTexel * vec2(-1.0,  1.0)).rgb;
    sum += texture(uSource, vSrcUv + uSrcTexel * vec2( 1.0,  1.0)).rgb;
    vec3 scene = lensFilters(displayToScene(sum * 0.25) * uInputGain, vFrameUv);

    float luminance = dot(scene, LUMA);
#ifdef LOG_GLOW
    float knee = 1.0;
#else
    float knee = smoothstep(1.8, 5.0, luminance);
#endif
    float encodedLuma = clamp((log2(max(luminance, 1e-5) / MID_GREY) - LOG_MIN_STOPS) / LOG_RANGE_STOPS, 0.0, 1.0);
    fragColor = vec4(glowEncode(scene * knee), encodedLuma);
}
