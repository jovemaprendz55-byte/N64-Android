#version 450
layout(location = 0) out vec3 vertexColor;
void main() {
    const vec2 positions[3] = vec2[](vec2(0.0, -0.72), vec2(0.72, 0.62), vec2(-0.72, 0.62));
    const vec3 colors[3] = vec3[](vec3(1.0, 0.18, 0.12), vec3(0.12, 0.95, 0.35), vec3(0.18, 0.42, 1.0));
    gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
    vertexColor = colors[gl_VertexIndex];
}
