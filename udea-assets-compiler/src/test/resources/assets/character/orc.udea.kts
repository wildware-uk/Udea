// The implicit-receiver form issue #86 introduces: no `bundle { }` wrapper, no return value.
// The file is the bundle, and every top-level call is a member of AssetScope.
val scale = 0.02f

character(
    name = "orc",
    size = 0.3f,
    health = 500f,
    animations = listOf(
        reference("character/orc_idle"),
        reference("character/orc_walk"),
    ),
    sounds = mapOf(
        "attack" to reference("character/orc_attack_cue"),
        "death" to reference("character/orc_death_cue"),
    ),
)

spriteSheet(name = "orc_idle", spritePath = "/sprites/orc/idle.png", rows = 1, columns = 6, scale = scale)
spriteSheet(name = "orc_walk", spritePath = "/sprites/orc/walk.png", rows = 1, columns = 8, scale = scale)

soundCue(name = "orc_attack_cue", pitchVariance = 0.8f, sounds = listOf("/sounds/orc/attack.ogg"))
soundCue(name = "orc_death_cue", pitchVariance = 0.3f, sounds = listOf("/sounds/orc/death.ogg"))
