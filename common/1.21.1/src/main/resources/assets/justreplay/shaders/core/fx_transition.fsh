#version 150

// Picture transition of Just Replay: the frozen last frame of the outgoing shot (PrevSampler) gives way to the
// live incoming shot (DiffuseSampler), then the start and end fades of the export range are applied.
// @author vyrriox

uniform sampler2D DiffuseSampler;
uniform sampler2D PrevSampler;
uniform vec2 OutSize;
uniform int Type;
uniform float Progress;
uniform vec2 Direction;
uniform vec3 FadeColor;
uniform float FadeAmount;

in vec2 texCoord;

out vec4 fragColor;

const float SOFT = 0.08;

float ease(float t) {
    return t * t * (3.0 - 2.0 * t);
}

bool inside(vec2 uv) {
    return uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0;
}

vec3 zoomSample(sampler2D tex, vec2 uv, float scale, float blur) {
    vec2 p = 0.5 + (uv - 0.5) * scale;
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 12; i++) {
        float k = 1.0 - blur * float(i) / 11.0;
        sum += texture(tex, 0.5 + (p - 0.5) * k).rgb;
    }
    return sum / 12.0;
}

vec3 transition(vec2 uv) {
    float p = clamp(Progress, 0.0, 1.0);
    if (Type == 1) {
        return mix(texture(PrevSampler, uv).rgb, texture(DiffuseSampler, uv).rgb, ease(p));
    }
    if (Type == 2 || Type == 3) {
        vec3 through = Type == 2 ? vec3(0.0) : vec3(1.0);
        if (p < 0.5) {
            return mix(texture(PrevSampler, uv).rgb, through, ease(p * 2.0));
        }
        return mix(through, texture(DiffuseSampler, uv).rgb, ease(p * 2.0 - 1.0));
    }
    if (Type == 4) {
        float s = 0.5 + dot(uv - 0.5, Direction);
        float edge = p * (1.0 + SOFT);
        float m = 1.0 - smoothstep(edge - SOFT, edge, s);
        return mix(texture(PrevSampler, uv).rgb, texture(DiffuseSampler, uv).rgb, m);
    }
    if (Type == 5) {
        float e = ease(p);
        vec2 inUv = uv + (1.0 - e) * Direction;
        if (inside(inUv)) {
            return texture(DiffuseSampler, inUv).rgb;
        }
        return texture(PrevSampler, uv - e * Direction).rgb;
    }
    if (Type == 6) {
        float strength = sin(p * 3.14159265) * 0.3;
        vec3 outgoing = zoomSample(PrevSampler, uv, 1.0 - 0.35 * p, strength);
        vec3 incoming = zoomSample(DiffuseSampler, uv, 1.0 - 0.35 * (1.0 - p), strength);
        return mix(outgoing, incoming, smoothstep(0.35, 0.65, p));
    }
    if (Type == 7) {
        float aspect = OutSize.x / OutSize.y;
        float d = length((uv - 0.5) * vec2(aspect, 1.0));
        float maxR = 0.5 * sqrt(aspect * aspect + 1.0) + SOFT;
        float r = ease(p) * maxR;
        float m = 1.0 - smoothstep(r - SOFT, r, d);
        return mix(texture(PrevSampler, uv).rgb, texture(DiffuseSampler, uv).rgb, m);
    }
    return texture(DiffuseSampler, uv).rgb;
}

void main() {
    vec3 c = transition(texCoord);
    c = mix(c, FadeColor, clamp(FadeAmount, 0.0, 1.0));
    fragColor = vec4(c, 1.0);
}
