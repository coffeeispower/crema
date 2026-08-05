#version 450

layout(push_constant) uniform PushConstants {
    vec2  rectMin;
    vec2  rectSize;
    vec4  color;
    vec2  fbSize;
    float radius;
    float borderWidth;
    float borderMode;
} pc;

layout(location = 0) out vec4 outColor;

// Signed distance to a rounded box centered at the origin with half-extents b.
float sdRoundBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

// Anti-aliased coverage step: 0 outside, 1 inside, ~1px transition.
float aaStep(float x) {
    return smoothstep(0.0, 1.0, x);
}

void main() {
    vec2 halfExtents = pc.rectSize * 0.5;
    float d = sdRoundBox(gl_FragCoord.xy - (pc.rectMin + halfExtents), halfExtents, pc.radius);
    int borderMode = int(pc.borderMode);

    float coverage;
    if (borderMode == 0) {
        // Solid fill.
        coverage = 1.0 - aaStep(d);
    } else {
        // Border ring: a band between d = -inset (inner edge) and d = +outset
        // (outer edge), where inset/outset place the border Outside, Middle or
        // Inside the rectangle.
        float inset, outset;
        if (borderMode == 1) {
            inset = 0.0;
            outset = pc.borderWidth;
        } else if (borderMode == 2) {
            inset = pc.borderWidth * 0.5;
            outset = pc.borderWidth * 0.5;
        } else {
            inset = pc.borderWidth;
            outset = 0.0;
        }
        // Shift the inner AA edge one more pixel toward the rect so the ramp
        // renders over the fill instead of leaving a half-transparent line
        // between the fill and the border.
        coverage = aaStep(d + inset + 1.0) - aaStep(d - outset);
    }

    outColor = vec4(pc.color.rgb, pc.color.a * coverage);
}
