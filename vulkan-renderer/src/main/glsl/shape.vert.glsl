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

void main() {
    // Generate a unit quad from the vertex index (TRIANGLE_STRIP, 4 vertices).
    vec2 corner = vec2(float(gl_VertexIndex & 1), float((gl_VertexIndex >> 1) & 1));
    // Border modes paint a band that extends up to max(inset, outset) beyond
    // the rect, plus a 1px AA margin; the quad must be expanded or the
    // rasterizer clips those fragments away.
    float expand;
    int borderMode = int(pc.borderMode);
    if (borderMode == 1 || borderMode == 3) {
        expand = pc.borderWidth + 1.0;
    } else if (borderMode == 2) {
        expand = pc.borderWidth * 0.5 + 1.0;
    } else {
        expand = 0.0;
    }
    vec2 pos = (pc.rectMin - expand) + corner * (pc.rectSize + 2.0 * expand);
    vec2 clip = pos / pc.fbSize * 2.0 - 1.0;
    gl_Position = vec4(clip, 0.0, 1.0);
}
