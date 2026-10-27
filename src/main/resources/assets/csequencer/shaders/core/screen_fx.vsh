#version 150

in vec3 Position;
in vec2 UV0;

out vec2 texCoord;

// Full-screen pass: positions are already in clip space.
void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
    texCoord = UV0;
}
