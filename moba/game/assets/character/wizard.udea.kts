// The wizard. See `orc.udea.kts` for the state contract.
//
// Two defects in the source corpus, diagnosed by the issue #93 migration and carried across here
// rather than reintroduced:
//
//  1. every sheet in `example/.../character/wizard.udea.kts` named a **priest** PNG, so the
//     wizard resolved nothing and was invisible in the old game. Repointed at the real
//     `Wizard-*.png`, and `wizard_attack` corrected from 9 columns to the 6 that
//     `Wizard-Attack01.png` (600x100) actually holds;
//  2. `wizard_heal` had no art at all and its ability slot pointed at an `ability/wizard_heal`
//     nothing declares. Dropped; the priest is this tree's healer.
//
// The art is flattened out of `sprites/wizard/Wizard/` into `sprites/wizard/`: one directory
// level whose only purpose was to repeat its parent's name.

val wizardScale = 1.50F

spriteAnimationSet(
    name = "wizard_animation_set",
    animations = listOf(
        reference("character/wizard_idle"),
        reference("character/wizard_walk"),
        reference("character/wizard_attack"),
        reference("character/wizard_hit"),
        reference("character/wizard_death"),
    ),
)

spriteAnimation(name = "wizard_idle", sheet = reference("character/wizard_idle_sheet"))

spriteAnimation(name = "wizard_walk", sheet = reference("character/wizard_walk_sheet"))

spriteAnimation(
    name = "wizard_attack",
    sheet = reference("character/wizard_attack_sheet"),
    loop = false,
    notifies = mapOf("attack_hit" to 5),
)

spriteAnimation(
    name = "wizard_hit",
    sheet = reference("character/wizard_hit_sheet"),
    loop = false,
    interruptable = false,
)

spriteAnimation(
    name = "wizard_death",
    sheet = reference("character/wizard_death_sheet"),
    loop = false,
    interruptable = false,
)

spriteSheet(
    name = "wizard_idle_sheet",
    spritePath = "sprites/wizard/Wizard-Idle.png",
    rows = 1,
    columns = 6,
    scale = wizardScale,
)

spriteSheet(
    name = "wizard_walk_sheet",
    spritePath = "sprites/wizard/Wizard-Walk.png",
    rows = 1,
    columns = 8,
    scale = wizardScale,
)

spriteSheet(
    name = "wizard_attack_sheet",
    spritePath = "sprites/wizard/Wizard-Attack01.png",
    rows = 1,
    columns = 6,
    scale = wizardScale,
)

spriteSheet(
    name = "wizard_hit_sheet",
    spritePath = "sprites/wizard/Wizard-Hurt.png",
    rows = 1,
    columns = 4,
    scale = wizardScale,
)

spriteSheet(
    name = "wizard_death_sheet",
    spritePath = "sprites/wizard/Wizard-Death.png",
    rows = 1,
    columns = 4,
    scale = wizardScale,
)

// --- the gameplay half, which this root could not carry until `character` was a published kind
//
// `character(...)` was `AssetKind.Unpublishable`, so a declaration made with it packed as an
// opaque record with no runtime type, and `EntityDefinition.blueprint` - a `Ref<Blueprint>` -
// refused it. That is why this game had two asset roots: `moba/src/main/assets` held the migrated
// corpus with its `character(...)` calls and could not be packed, and this root held the half that
// could. `Character` is a `SpawnRecipe` now, so the two are one root and this is the half that
// came back.
//
// `size` is left at its default: this game's world scale is per *sheet* (`wizardScale` below),
// because the frames differ in how much transparent margin they carry, and a second scale on the
// character that no renderer reads would be decoration.

character(
    name = "wizard",
    health = 50F,
    spriteAnimationSet = reference("character/wizard_animation_set"),
    animationMap = mapOf(
        "idle" to reference("character/wizard_idle"),
        "walk" to reference("character/wizard_walk"),
        "attack" to reference("character/wizard_attack"),
        "hit" to reference("character/wizard_hit"),
        "death" to reference("character/wizard_death"),
    ),
    sounds = mapOf(
        "attack" to reference("sounds/melee_swoosh"),
        "hit" to reference("sounds/hurt"),
        "death" to reference("sounds/death"),
    ),
    attributes = mapOf(
        "health" to 50F,
        "mana" to 100F,
        "strength" to 10F,
    ),
    abilitySpecs = {
        abilitySpec(ability = reference("ability/npc_melee"), tags = listOf("Slot.A"))
    },
    components = {
        component("dev.wildware.moba.Position")
        component("dev.wildware.moba.level.GameUnit")
        component("dev.wildware.moba.CharacterView")
    },
)
