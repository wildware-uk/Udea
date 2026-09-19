// Two cues written out. This used to be a `listOf("hit", "swoosh").forEach { }`, which pass 1 can
// fold to its two ids; the K2 loop checker refuses it all the same, because `forEach` may run its
// lambda more than once and assets may not loop (issue #192).
soundCue(
    name = "melee_hit",
    pitchVariance = 0.5f,
    volume = 0.5f,
    sounds = listOf("/sounds/effects/melee_hit.ogg"),
)
soundCue(
    name = "melee_swoosh",
    pitchVariance = 0.5f,
    volume = 0.5f,
    sounds = listOf("/sounds/effects/melee_swoosh.ogg"),
)
