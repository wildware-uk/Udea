// Migrated from example/src/main/resources/assets/character/wizard.udea.kts (issue #93).
//
// Two defects in the source corpus were fixed here rather than tolerated by the validator:
//
//  1. Every one of the six sheets named a **priest** PNG under `sprites/wizard/`
//     (`sprites/wizard/Wizard/Wizard-Idle.png` and friends). No such file exists — the wizard art is at
//     `sprites/wizard/Wizard/Wizard-*.png` — so the old loader resolved nothing and the wizard
//     was invisible. Repointed at the real files, and `wizard_attack` corrected from 9 columns
//     to 6, which is what `Wizard-Attack01.png` (600x100) actually holds.
//  2. `wizard_heal` had no art at all (there is no `Wizard-Heal.png`) and pointed its ability
//     slot at `ability/wizard_heal`, which nothing in the corpus declares. The animation, its
//     sheet and the ability spec are dropped; the priest is the healer in this tree.

val wizardScale = 0.02F

character(
    name = "wizard",
    size = 0.2F,
    spriteAnimationSet = reference("character/wizard_animation_set"),
    animationMap = mapOf(
        "idle" to reference("character/wizard_idle"),
        "walk" to reference("character/wizard_walk"),
        "run" to reference("character/wizard_walk"),
        "attack" to reference("character/wizard_attack"),
        "hit" to reference("character/wizard_hit"),
        "death" to reference("character/wizard_death"),
    ),
    attributes = mapOf(
        "health" to 50F,
        "mana" to 100F,
    ),
    abilitySpecs = {
        abilitySpec(ability = reference("ability/npc_melee"), tags = listOf("Slot.A"))
    },
    components = {
        component("dev.wildware.udea.ecs.component.base.Networkable")
        component("dev.wildware.udea.example.component.Team", "team" to "SoldierTeam")
        component("dev.wildware.udea.example.component.GameUnit")
        component("dev.wildware.udea.ecs.component.base.Debug")
    },
)

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
    spritePath = "sprites/wizard/Wizard/Wizard-Idle.png",
    rows = 1,
    columns = 6,
    scale = wizardScale,
)

spriteSheet(
    name = "wizard_walk_sheet",
    spritePath = "sprites/wizard/Wizard/Wizard-Walk.png",
    rows = 1,
    columns = 8,
    scale = wizardScale,
)

spriteSheet(
    name = "wizard_attack_sheet",
    spritePath = "sprites/wizard/Wizard/Wizard-Attack01.png",
    rows = 1,
    columns = 6,
    scale = wizardScale,
)

spriteSheet(
    name = "wizard_hit_sheet",
    spritePath = "sprites/wizard/Wizard/Wizard-Hurt.png",
    rows = 1,
    columns = 4,
    scale = wizardScale,
)

spriteSheet(
    name = "wizard_death_sheet",
    spritePath = "sprites/wizard/Wizard/Wizard-Death.png",
    rows = 1,
    columns = 4,
    scale = wizardScale,
)
