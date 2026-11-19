// Star filters: fine grooves in the glass diffract each bright light into rays.
in vec2 vFrameUv;
out vec4 fragColor;

uniform sampler2D uInput;
uniform vec2 uTexel;
uniform vec4 uAxesA;       // Up to four ray directions, two per vector.
uniform vec4 uAxesB;
uniform float uAxisCount;
uniform float uDecay;      // In texels.

const int STEPS = 28;
const float STAR_GAIN = 0.22;

vec3 rays(vec2 axis) {
    vec3 sum = vec3(0.0);
    vec2 step = axis * uTexel;
    for (int i = 1; i <= STEPS; i++) {
        float d = float(i);
        float w = exp(-d / uDecay);
        sum += (glowDecode(texture(uInput, vFrameUv + step * d).rgb) + glowDecode(texture(uInput, vFrameUv - step * d).rgb)) * w;
    }
    return sum;
}

void main() {
    vec3 sum = rays(uAxesA.xy);
    if (uAxisCount > 1.5) sum += rays(uAxesA.zw);
    if (uAxisCount > 2.5) sum += rays(uAxesB.xy);
    if (uAxisCount > 3.5) sum += rays(uAxesB.zw);
    fragColor = vec4(glowEncode(sum * STAR_GAIN), 1.0);
}
