// Energy-preserving 2×2 box step between glow chain levels.
in vec2 vFrameUv;
out vec4 fragColor;

uniform sampler2D uInput;
uniform vec2 uTexel;

void main() {
    vec4 a = texture(uInput, vFrameUv + uTexel * vec2(-1.0, -1.0));
    vec4 b = texture(uInput, vFrameUv + uTexel * vec2( 1.0, -1.0));
    vec4 c = texture(uInput, vFrameUv + uTexel * vec2(-1.0,  1.0));
    vec4 d = texture(uInput, vFrameUv + uTexel * vec2( 1.0,  1.0));
    vec3 light = (glowDecode(a.rgb) + glowDecode(b.rgb) + glowDecode(c.rgb) + glowDecode(d.rgb)) * 0.25;
    fragColor = vec4(glowEncode(light), (a.a + b.a + c.a + d.a) * 0.25);
}
