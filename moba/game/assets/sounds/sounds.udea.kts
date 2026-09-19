// The game's sound events, ported from `example/src/main/resources/assets/sounds/sounds.udea.kts`
// onto the packed pipeline - and extended from two cues to seven, because the old bundle only ever
// declared the two the old `MeleeDamageCue` reached for while the other twenty-one recordings sat
// in the tree unnamed.
//
// ## What a `soundCue` is and is not
//
// It is authored *data*: which recordings may play, how loud, and how much to vary the pitch.
// `SoundCue`'s own KDoc explains why it holds `ResPath`s rather than `Sound`s - the old one held
// `by lazy { gameManager.assetManager.getAsset<Sound>(it) }`, so an asset value reached a global
// to resolve itself and could not be read without LibGDX. `udea-audio` loads the files and
// `dev.wildware.moba.audio` decides which cue plays which of these; nothing here knows about
// either.
//
// ## Why the volumes differ so much
//
// They are the old bundle's, carried across unchanged where it had an opinion. A swoosh happens on
// every swing of every unit on the field and a hit only on the ones that connect, so a swoosh
// mixed at a hit's level is a wall of wind. The two the old file declared are `0.5F` and `0.2F`
// and those are kept; the five new ones are set relative to them and nothing else.
//
// ## Pitch variance now means what it says
//
// Every cue the old file declared used `pitchVariance = 0.5F` against a `SoundSystem` that applied
// half of it (`pitch + (random * variance - variance / 2)`), so the audible range was 0.875x to
// 1.125x. `CueAudio` implements the range `SoundCue`'s KDoc states - a fraction *either side* of
// unit pitch - so a carried-across `0.5F` would now be 0.5x to 1.5x, which is a different sound
// rather than a varied one. The values below are the ranges the old game actually played, written
// as what they are.

soundCue(
    name = "melee_hit",
    pitchVariance = 0.25F,
    volume = 0.5F,
    sounds = listOf(
        "sounds/effects/melee_hit_1.ogg",
        "sounds/effects/melee_hit_2.ogg",
        "sounds/effects/melee_hit_3.ogg",
    ),
)

soundCue(
    name = "melee_swoosh",
    pitchVariance = 0.25F,
    volume = 0.2F,
    sounds = listOf(
        "sounds/effects/melee_swoosh_1.ogg",
        "sounds/effects/melee_swoosh_2.ogg",
        "sounds/effects/melee_swoosh_3.ogg",
        "sounds/effects/melee_swoosh_4.ogg",
        "sounds/effects/melee_swoosh_5.ogg",
    ),
)

// An arrow leaving a bow is the same family of sound as a blade through air, and this pack has no
// bow recording. Declared as its own cue rather than pointed at `melee_swoosh` so that the day
// somebody records a bow, one line here changes and no code does - and so the mix can differ,
// which it does: an arrow launch is quieter and pitched up against a two-handed swing.
soundCue(
    name = "arrow_fired",
    pitchVariance = 0.15F,
    volume = 0.15F,
    sounds = listOf(
        "sounds/effects/melee_swoosh_1.ogg",
        "sounds/effects/melee_swoosh_3.ogg",
        "sounds/effects/melee_swoosh_5.ogg",
    ),
)

// Likewise: an arrow landing in a body is a hit, mixed under a melee blow because it carries less
// weight behind it.
soundCue(
    name = "arrow_hit",
    pitchVariance = 0.2F,
    volume = 0.3F,
    sounds = listOf(
        "sounds/effects/melee_hit_1.ogg",
        "sounds/effects/melee_hit_2.ogg",
    ),
)

// The five hurt recordings the old tree shipped and never declared.
soundCue(
    name = "hurt",
    pitchVariance = 0.2F,
    volume = 0.35F,
    sounds = listOf(
        "sounds/orc/orc_hurt_1.ogg",
        "sounds/orc/orc_hurt_2.ogg",
        "sounds/orc/orc_hurt_3.ogg",
        "sounds/orc/orc_hurt_4.ogg",
        "sounds/orc/orc_hurt_5.ogg",
    ),
)

// Four deaths, and no pitch variance worth speaking of: a death is heard once per unit per match,
// so there is nothing for variance to hide.
soundCue(
    name = "death",
    pitchVariance = 0.1F,
    volume = 0.5F,
    sounds = listOf(
        "sounds/orc/orc_death_1.ogg",
        "sounds/orc/orc_death_2.ogg",
        "sounds/orc/orc_death_3.ogg",
        "sounds/orc/orc_death_4.ogg",
    ),
)

// The elite orc's spin: the old `orc_elite_big_shout_cue`, which was a shout and a swoosh together.
// One cue with both recordings in it is "pick one", not "play both" - `CueAudio` picks - so the
// big grunt and the elite swoosh alternate rather than layering. Layering would need two cues on
// one id, which `AudioBindings` refuses on purpose.
soundCue(
    name = "spin",
    pitchVariance = 0.1F,
    volume = 0.6F,
    sounds = listOf(
        "sounds/orc/orc_big_grunt.ogg",
        "sounds/orc/orc_elite_swoosh.ogg",
    ),
)

// A shove. The five grunts were in the tree and unnamed, same as the hurts.
soundCue(
    name = "knockback",
    pitchVariance = 0.25F,
    volume = 0.3F,
    sounds = listOf(
        "sounds/orc/orc_grunt_1.ogg",
        "sounds/orc/orc_grunt_2.ogg",
        "sounds/orc/orc_grunt_3.ogg",
        "sounds/orc/orc_grunt_4.ogg",
        "sounds/orc/orc_grunt_5.ogg",
    ),
)

// The priest's heal, and the one cue in this file with no recording behind it.
//
// The pack is twenty-four melee and orc recordings - three hits, five swooshes, five hurts, five
// grunts, four deaths, a big grunt and an elite swoosh - and none of them is a heal. The old game
// made no heal sound at all: `PriestHealCue` spawned `effects/heal_effect` and nothing else. So
// this is a **placeholder**, said plainly: two of the airier swooshes at a third of a swing's
// volume, which reads as a soft magical whoosh rather than as a blade.
//
// It is a cue of its own rather than `MobaCues.HEAL` pointed at `melee_swoosh`, for the reason
// `arrow_fired` is: the day somebody records a heal, one line here changes and no code does - and
// the mix has to differ, because `ability/heal_over_time` re-emits this cue every fifteen ticks
// for its whole duration and a swoosh at swing volume four times a second is a siren.
//
// `pitchVariance` is high for the same reason: four in a row at one pitch is a machine, and the
// same recording spread over 0.7x to 1.3x is four different whooshes.
soundCue(
    name = "heal",
    pitchVariance = 0.3F,
    volume = 0.12F,
    sounds = listOf(
        "sounds/effects/melee_swoosh_2.ogg",
        "sounds/effects/melee_swoosh_4.ogg",
    ),
)
