#version 450
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec4 inColor;
layout(location = 0) out vec4 vertexColor;
void main() {
    // N64 projected Z is in the -1..1 clip range. Vulkan's default
    // depth clip range is 0..1, so remap Z before rasterization.
    gl_Position = vec4(inPosition.x, inPosition.y,
                       (inPosition.z + 1.0) * 0.5, 1.0);
    vertexColor = inColor;
}
