// The one sanctioned dynamic form: forEach over a constant list of string literals, so the
// generated ids are still statically known to pass 1.
listOf("hit", "swoosh").forEach { kind ->
    soundCue(
        name = "melee_$kind",
        pitchVariance = 0.5f,
        volume = 0.5f,
        sounds = listOf("/sounds/effects/melee_$kind.ogg"),
    )
}
