#version 150

// Colour grading pass of the Just Replay effect stack. Sharpen and chromatic aberration sample the source first,
// then the colour operations run in the order of the stack, then the letterbox.
// @author vyrriox

uniform sampler2D DiffuseSampler;
uniform sampler2D LutSampler;
uniform vec2 OutSize;
uniform int OpCount;
uniform float OpCode[16];
uniform float OpA[16];
uniform float OpB[16];
uniform float Sharpen;
uniform float Chroma;
// x: target aspect ratio (0: none), y: amount
uniform vec2 Letterbox;
uniform float Seed;
// x: cube size, y: texture width, z: texture height
uniform vec3 LutInfo;

in vec2 texCoord;

out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec3 hueShift(vec3 c, float degrees) {
    float a = radians(degrees);
    // Rotation around the grey axis.
    vec3 k = vec3(0.57735);
    float cosA = cos(a);
    return c * cosA + cross(k, c) * sin(a) + k * dot(k, c) * (1.0 - cosA);
}

vec3 lutSlice(vec3 c, float slice) {
    float n = LutInfo.x;
    float perRow = LutInfo.y / n;
    vec2 tile = vec2(mod(slice, perRow), floor(slice / perRow));
    vec2 px = tile * n + vec2(c.r, c.g) * (n - 1.0) + 0.5;
    return texture(LutSampler, px / LutInfo.yz).rgb;
}

vec3 lut(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    float b = c.b * (LutInfo.x - 1.0);
    float s0 = floor(b);
    float s1 = min(s0 + 1.0, LutInfo.x - 1.0);
    return mix(lutSlice(c, s0), lutSlice(c, s1), b - s0);
}

void main() {
    vec2 uv = texCoord;
    vec3 c;
    if (Chroma > 0.0) {
        vec2 off = (uv - 0.5) * 2.0 * Chroma / OutSize;
        c = vec3(texture(DiffuseSampler, uv + off).r, texture(DiffuseSampler, uv).g, texture(DiffuseSampler, uv - off).b);
    } else {
        c = texture(DiffuseSampler, uv).rgb;
    }
    if (Sharpen > 0.0) {
        vec2 t = 1.0 / OutSize;
        vec3 around = texture(DiffuseSampler, uv + vec2(t.x, 0.0)).rgb + texture(DiffuseSampler, uv - vec2(t.x, 0.0)).rgb
                + texture(DiffuseSampler, uv + vec2(0.0, t.y)).rgb + texture(DiffuseSampler, uv - vec2(0.0, t.y)).rgb;
        c += (c - around * 0.25) * Sharpen;
    }
    float aspect = OutSize.x / OutSize.y;
    for (int i = 0; i < OpCount; i++) {
        int op = int(OpCode[i] + 0.5);
        float a = OpA[i];
        float b = OpB[i];
        if (op == 1) {
            c = c * exp2(a) + b;
        } else if (op == 2) {
            c = (c - 0.5) * a + 0.5;
        } else if (op == 3) {
            c = mix(vec3(dot(c, LUMA)), c, a);
        } else if (op == 4) {
            float l = dot(c, LUMA);
            c *= vec3(1.0 + 0.3 * a + 0.1 * b, 1.0 - 0.2 * b, 1.0 - 0.3 * a + 0.1 * b);
            c *= l / max(dot(c, LUMA), 0.0001);
        } else if (op == 5) {
            c = hueShift(c, a);
        } else if (op == 6) {
            vec2 p = (uv - 0.5) * vec2(mix(1.0, aspect, 0.5), 1.0);
            float d = length(p) * 1.4142;
            float falloff = smoothstep(0.85 - b * 0.85, 0.85 + b * 0.5, d);
            c *= 1.0 - a * falloff;
        } else if (op == 7) {
            vec2 cell = floor(uv * OutSize / max(b, 1.0));
            float n = hash(cell + Seed * 61.7) + hash(cell * 1.37 + Seed * 17.3) - 1.0;
            float l = dot(c, LUMA);
            c += n * a * 0.25 * (0.4 + 0.6 * (1.0 - abs(l * 2.0 - 1.0)));
        } else if (op == 8) {
            c = mix(c, vec3(dot(c, LUMA)), a);
        } else if (op == 9) {
            vec3 s = vec3(dot(c, vec3(0.393, 0.769, 0.189)), dot(c, vec3(0.349, 0.686, 0.168)), dot(c, vec3(0.272, 0.534, 0.131)));
            c = mix(c, s, a);
        } else if (op == 10) {
            if (LutInfo.x >= 2.0) {
                c = mix(c, lut(c), a);
            }
        }
    }
    if (Letterbox.x > 0.0 && Letterbox.y > 0.0) {
        if (Letterbox.x > aspect) {
            float bar = (1.0 - aspect / Letterbox.x) * 0.5 * Letterbox.y;
            if (uv.y < bar || uv.y > 1.0 - bar) {
                c = vec3(0.0);
            }
        } else {
            float bar = (1.0 - Letterbox.x / aspect) * 0.5 * Letterbox.y;
            if (uv.x < bar || uv.x > 1.0 - bar) {
                c = vec3(0.0);
            }
        }
    }
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
