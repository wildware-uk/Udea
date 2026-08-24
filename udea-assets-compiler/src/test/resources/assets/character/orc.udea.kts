// The implicit-receiver form issue #86 introduces: no `bundle { }` wrapper, no return value.
// The file is the bundle, and every top-level call is a member of AssetScope.
//
// The sheets are `<state>_sheet` and the animations are `<state>`, which they were not until
// `character` became a published kind: this file used to hand `character(animations = ...)` a
// list of *sprite sheets*, and nothing complained, because an unpublishable declaration's
// references were never kind-checked. Publishing the kind reported it as `UDEA0013` on the first
// run, which is the check working on the corpus that motivated writing it.
val scale = 0.02f

character(
    name = "orc",
    size = 0.3f,
    health = 500f,
    animationMap = mapOf(
        "idle" to reference("character/orc_idle"),
        "walk" to reference("character/orc_walk"),
    ),
    sounds = mapOf(
        "attack" to reference("character/orc_attack_cue"),
        "death" to reference("character/orc_death_cue"),
    ),
)

spriteAnimation(name = "orc_idle", sheet = reference("character/orc_idle_sheet"))
spriteAnimation(name = "orc_walk", sheet = reference("character/orc_walk_sheet"), loop = false)

spriteSheet(name = "orc_idle_sheet", spritePath = "/sprites/orc/idle.png", rows = 1, columns = 6, scale = scale)
spriteSheet(name = "orc_walk_sheet", spritePath = "/sprites/orc/walk.png", rows = 1, columns = 8, scale = scale)

soundCue(name = "orc_attack_cue", pitchVariance = 0.8f, sounds = listOf("/sounds/orc/attack.ogg"))
soundCue(name = "orc_death_cue", pitchVariance = 0.3f, sounds = listOf("/sounds/orc/death.ogg"))
