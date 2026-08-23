val scale = 0.02f

spriteSheet(name = "orc_idle", spritePath = "/sprites/orc/idle.png", rows = 1, columns = 6, scale = scale)
spriteSheet(name = "orc_walk", spritePath = "/sprites/orc/walk.png", rows = 1, columns = 8, scale = scale)

spriteAnimation(name = "orc_idle_anim", sheet = reference("character/orc_idle"))
spriteAnimation(name = "orc_walk_anim", sheet = reference("character/orc_walk"), loop = false, interruptable = false)

soundCue(name = "orc_attack_cue", pitchVariance = 0.8f, volume = 0.5f, sounds = listOf("/sounds/orc/attack.ogg"))

// A kind the runtime has no type for. It stays in the graph and reads back as an OpaqueAsset;
// nothing typed references it, which is what keeps this corpus kind-correct.
character(
    name = "orc",
    size = 0.3f,
    health = 500f,
    animations = listOf(reference("character/orc_idle_anim")),
    sounds = mapOf("attack" to reference("character/orc_attack_cue")),
)
