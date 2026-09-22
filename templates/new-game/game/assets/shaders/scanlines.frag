// A screen effect: every other row of the finished frame is dimmed by uStrength.
//
// Write the body and nothing around it. The engine prepends the version and precision lines for
// the backend, so this file must not state one - not even in a comment, because the build's check
// does not read comments and refuses this file with UDEA0041 if one appears anywhere in it.
//
// `uColor` and `uResolution` are the engine's and are declared nowhere here. `uStrength` is the
// game's, declared in Kotlin where the shader is built (`NewGameScene`).
uniform float uStrength;

vec4 udeaMain(vec2 uv) {
    float row = floor(uv.y * uResolution.y);
    float dim = mod(row, 2.0) < 1.0 ? 1.0 : 1.0 - uStrength;
    return vec4(texture(uColor, uv).rgb * dim, 1.0);
}
