// Fullscreen quad. vFrameUv addresses the output frame; vSrcUv addresses the source picture.
layout(location = 0) in vec2 aPosition;

uniform mat4 uSourceMatrix;
uniform float uFlipY;

out vec2 vFrameUv;
out vec2 vSrcUv;

void main() {
    vFrameUv = aPosition * 0.5 + 0.5;
    vSrcUv = (uSourceMatrix * vec4(vFrameUv, 0.0, 1.0)).xy;
    gl_Position = vec4(aPosition.x, aPosition.y * uFlipY, 0.0, 1.0);
}
