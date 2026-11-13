#version 450
layout(location = 0) in vec2 textureCoord;
layout(location = 0) out vec4 outColor;

void main() {
    // Procedural CPU-frame placeholder: colored test bars and a fine grid.
    // This validates full-screen UV mapping before the Vulkan texture upload path.
    vec2 uv = textureCoord;
    float grid = step(0.985, fract(uv.x * 16.0)) + step(0.985, fract(uv.y * 12.0));
    float bars = floor(uv.x * 6.0);
    vec3 colors[6] = vec3[](
        vec3(0.90, 0.12, 0.10), vec3(0.95, 0.65, 0.08),
        vec3(0.12, 0.75, 0.22), vec3(0.08, 0.65, 0.85),
        vec3(0.16, 0.25, 0.90), vec3(0.72, 0.16, 0.82)
    );
    vec3 color = colors[int(clamp(bars, 0.0, 5.0))];
    color *= 0.72 + 0.28 * uv.y;
    color = mix(color, vec3(1.0), clamp(grid, 0.0, 0.22));
    outColor = vec4(color, 1.0);
}
