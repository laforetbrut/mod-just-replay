#version 150

// Full screen quad of the Just Replay effect passes: positions are 0..1 and double as texture coordinates.
// @author vyrriox

in vec3 Position;

out vec2 texCoord;

void main() {
    gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
    texCoord = Position.xy;
}
