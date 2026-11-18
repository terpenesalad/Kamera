// Separable Gaussian blur in light-linear space.
in vec2 vFrameUv;
out vec4 fragColor;

uniform sampler2D uInput;
uniform vec2 uDirection;   // One texel along the blur axis.
uniform float uSigma;      // In texels.

const int RADIUS = 12;

void main() {
    float sigma = max(uSigma, 0.35);
    float denominator = 2.0 * sigma * sigma;
    vec4 centre = texture(uInput, vFrameUv);
    vec3 light = glowDecode(centre.rgb);
    float detail = centre.a;
    float total = 1.0;
    for (int i = 1; i <= RADIUS; i++) {
        float d = float(i);
        float w = exp(-d * d / denominator);
        vec4 p = texture(uInput, vFrameUv + uDirection * d);
        vec4 n = texture(uInput, vFrameUv - uDirection * d);
        light += (glowDecode(p.rgb) + glowDecode(n.rgb)) * w;
        detail += (p.a + n.a) * w;
        total += 2.0 * w;
    }
    fragColor = vec4(glowEncode(light / total), detail / total);
}
