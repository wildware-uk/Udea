// A screen shader in the pack corpus, so the round-trip and reproducibility tests carry GLSL
// through the real writer and the real reader rather than only through a hand-made graph.
//
// No version pragma: the engine writes that line per backend, and UDEA0041 refuses one here.
uniform float uAmount;

vec4 udeaMain(vec2 uv) {
    vec3 colour = texture(uColor, uv).rgb;
    return vec4(mix(colour, vec3(1.0, 0.8, 0.6), uAmount), 1.0);
}
