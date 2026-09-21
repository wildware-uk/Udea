// moba's own screen effect: every other row of the finished frame is dimmed.
//
// Declared as an asset in `shaders.udea.kts`, so the build reads this file, checks it and packs
// the text; `MobaScreenEffects` names it as `GameAssets.shaders.scanlines` and never as a path.
//
// No version pragma here. The engine writes that line itself, per backend - see `UdeaShader`.
// Do not write one in a comment either: the build's check is deliberately blind to comments,
// so a commented pragma fails the build with UDEA0041 exactly as a real one does.
//
// `uColor` and `uResolution` are the engine's and are declared nowhere here. `uStrength` is the
// game's, declared in Kotlin beside this file's name.
uniform float uStrength;

vec4 udeaMain(vec2 uv) {
    // The row this pixel is on. A pixel centre samples at row + 0.5, so the floor is the row
    // index, and dimming one parity leaves the other byte-identical - which is what lets
    // `ShaderAssetProof` say "these rows and no others" rather than "the picture looks different".
    // Not "half the rows": a row of black sky is dimmed to the same black, so the proof asserts
    // that a row moved if and only if it had colour in it.
    float row = floor(uv.y * uResolution.y);
    float dim = mod(row, 2.0) < 1.0 ? 1.0 : 1.0 - uStrength;
    return vec4(texture(uColor, uv).rgb * dim, 1.0);
}
