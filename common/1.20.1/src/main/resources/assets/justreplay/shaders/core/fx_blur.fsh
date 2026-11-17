#version 150

// One direction of a separable gaussian blur; the radius is in pixels of the render size.
// @author vyrriox

uniform sampler2D DiffuseSampler;
uniform vec2 OutSize;
uniform vec2 BlurDir;
uniform float Radius;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 texel = BlurDir / OutSize;
    float sigma = max(Radius * 0.5, 0.001);
    // Six taps on each side reach the radius; linear filtering averages the texels in between.
    float spacing = max(Radius / 6.0, 0.5);
    vec3 sum = texture(DiffuseSampler, texCoord).rgb;
    float total = 1.0;
    for (int i = 1; i <= 6; i++) {
        float d = float(i) * spacing;
        float w = exp(-0.5 * d * d / (sigma * sigma));
        sum += (texture(DiffuseSampler, texCoord + texel * d).rgb + texture(DiffuseSampler, texCoord - texel * d).rgb) * w;
        total += 2.0 * w;
    }
    fragColor = vec4(sum / total, 1.0);
}
