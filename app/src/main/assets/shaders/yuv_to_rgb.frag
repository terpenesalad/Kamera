// Converts a camera YUV_420_888 still to sRGB using the full-range BT.601 (JFIF) matrix that the
// Camera2 contract specifies for this format.
in vec2 vSrcUv;
out vec4 fragColor;

uniform highp sampler2D uLumaTex;
uniform highp sampler2D uChromaTex;

void main() {
    float y = texture(uLumaTex, vSrcUv).r;
    vec2 uv = texture(uChromaTex, vSrcUv).rg - 0.5;
    vec3 rgb = vec3(
        y + 1.402 * uv.y,
        y - 0.344136 * uv.x - 0.714136 * uv.y,
        y + 1.772 * uv.x);
    fragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
