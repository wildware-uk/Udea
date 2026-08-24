// The hit, heal and spell flashes the old game spawned as short-lived entities, brought back.
//
// ## What was lost and what this restores
//
// `example/src/main/resources/assets/effects/heal_effect.udea.kts` declared a `heal_effect_sheet`,
// a `heal_effect_set` and an `effect(...)` record carrying a duration; `blueprint/effect` gave the
// spawned entity a `spriteRenderer` and an `animations()` holder, and `PriestHealCue` spawned one
// parented to the healed unit. None of that was migrated, so damage in this build is a number on
// a bar and nothing else - every blow, arrow and heal lands with no picture at all.
//
// Three sheets, three animations and - since `effect` became a published kind - three
// `effect(...)` records. The comment that used to stand here said the opposite:
//
//   > `effect` is `AssetKind.Unpublishable`, so a record declared with it packs as an opaque blob
//   > no loader reads [...] The one field that record carried - the duration - is therefore a
//   > Kotlin constant on `dev.wildware.moba.EffectKind`.
//
// `Effect` exists now, so the duration is authored again. `EffectKind` still holds the *tick*
// count, because a lifetime the simulation counts down must be ticks and this file is in seconds;
// `MobaAuthoredContentTest` converts one to the other and fails if they disagree, so the number a
// designer edits and the number the entity lives for are one number.
//
// The scale was 1.43 - a character scale's neighbour, on the reasoning that a 100px effect frame
// beside a 100px character frame should share it. Measured on a real capture, that reasoning is
// wrong, and the number below is what a screenshot says rather than what the arithmetic said:
// a *character* frame is mostly transparent margin and its drawn body is about 30 world units,
// while an effect frame is filled edge to edge, so 1.43 put a 143-unit flash on a 30-unit
// soldier. In an eleven-unit melee the flashes covered the fight they were describing. 0.30 is
// about one body wide, which is the size a hit flash reads as a hit rather than as a screen wipe.
val effectScale = 0.30F

// --- the priest's heal, the one effect the old game actually spawned ------------------------

spriteSheet(
    name = "heal_effect_sheet",
    spritePath = "effects/Priest-Heal_Effect.png",
    rows = 1,
    columns = 4,
    scale = effectScale,
)

// Looping, because it is drawn for as long as the heal-over-time it stands for is ticking - which
// is `PriestHealExec.HEAL_DURATION_TICKS`, five seconds, and the old asset's `duration = 5.0F`.
spriteAnimation(name = "heal_effect", sheet = reference("effects/heal_effect_sheet"), loop = true)

// --- a blow landing --------------------------------------------------------------------------

spriteSheet(
    name = "hit_effect_sheet",
    spritePath = "effects/Priest-Attack_Effect.png",
    rows = 1,
    columns = 5,
    scale = effectScale,
)

// Not looping: a hit flash that repeated would read as a unit being hit over and over.
spriteAnimation(
    name = "hit_effect",
    sheet = reference("effects/hit_effect_sheet"),
    loop = false,
)

// --- the wizard's bolt, kept because the corpus had it ----------------------------------------
//
// Nothing spawns this yet: no unit in `level/test_level` is a wizard, and `MobaUnits.kinds`' wizard
// has only `ability/npc_melee`. It is declared because the sheet is here and a packed, addressable
// sheet is what a later wave needs; a reader should know it is unspawned rather than assume it is
// wired.

spriteSheet(
    name = "spell_effect_sheet",
    spritePath = "effects/Wizard-Attack01_Effect.png",
    rows = 1,
    columns = 10,
    scale = effectScale,
)

spriteAnimation(
    name = "spell_effect",
    sheet = reference("effects/spell_effect_sheet"),
    loop = false,
)

// --- the records the spawner's lifetimes come from -------------------------------------------
//
// `animationSet` is required by `Effect` and each of these has exactly one animation in it, which
// is what an effect is: a set is the unit of art, so naming an animation outside one would let a
// bundle hold an effect whose frames are in another atlas page.

spriteAnimationSet(name = "heal_effect_set", animations = listOf(reference("effects/heal_effect")))

spriteAnimationSet(name = "hit_effect_set", animations = listOf(reference("effects/hit_effect")))

spriteAnimationSet(name = "spell_effect_set", animations = listOf(reference("effects/spell_effect")))

// 24 ticks at 60Hz - `EffectKind.Heal.lifeTicks`. Not the old corpus's `duration = 5.0F`: that
// asked for one five-second flash, and `ability/heal_over_time` re-emits `MobaCues.HEAL` every
// period for its whole duration, so this is respawned instead and lives one pass of its own
// four-frame animation.
effect(
    name = "heal",
    animationSet = reference("effects/heal_effect_set"),
    animation = "heal_effect",
    duration = 0.4F,
)

// 30 ticks: five frames at the default 0.1s frameTime, which is the whole animation exactly once.
// A flash that outlived its own frames would sit on the last one.
effect(
    name = "hit",
    animationSet = reference("effects/hit_effect_set"),
    animation = "hit_effect",
    duration = 0.5F,
)

// 60 ticks. Spawned by nothing today - see the sheet above - and declared so the next wave has a
// name for it rather than a grep.
effect(
    name = "spell",
    animationSet = reference("effects/spell_effect_set"),
    animation = "spell_effect",
    duration = 1.0F,
)
