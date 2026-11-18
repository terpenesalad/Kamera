// Shows the untouched negative for the editor's before/after comparison.
in vec2 vSrcUv;
out vec4 fragColor;

uniform SOURCE_SAMPLER uSource;

void main() {
    fragColor = vec4(texture(uSource, vSrcUv).rgb, 1.0);
}
