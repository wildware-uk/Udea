// The playable level: twenty-seven units, three sides, four clearings.
//
// Ported from `example/src/main/resources/assets/level/test_level.udea.kts`, which spawned the
// same roster - a player, a priest, orcs, ten skeletons and ten soldiers - and is what made the
// old demo feel alive. All six characters the art tree packs are on the field: the player is the
// elite orc the old `blueprint/player` inherited from, and the wizard stands with the soldiers.
//
// Two differences from the source, and both are rules this pipeline has that the old one did not:
//
//   * **No `systems = {}` block.** The old level named six system classes, so a level decided the
//     tick order of a simulation and two levels could disagree about what the game was.
//     `MobaModule` owns that now, once, for every scene - which is what makes "the server, the
//     client and the agent run the identical Simulation" a checkable sentence.
//
// A third difference, and the one this file changes with the two asset roots becoming one: every
// entity names a **`character/`** and not a `blueprint/`. That is what the migrated corpus wrote,
// because the old DSL's `character(...)` returned a blueprint - and it is what packing that corpus
// could not do, because `EntityDefinition.blueprint` was a `Ref<Blueprint>` and `character` had no
// runtime type at all. All twenty-seven references were reported `UDEA0013` and dropped, so the
// bundle held a level that spawned nothing and this game shipped a second, reduced asset root with
// six `blueprint(...)` stand-ins in it. `Character` is a `SpawnRecipe` now, the stand-ins are gone
// with `blueprint/units.udea.kts`, and the roster is declared once - beside the art and the stats
// it wears, in `character/`.
//
//   * **No `Random.nextFloat()`.** The source scattered every unit at pack time, so two builds of
//     identical sources produced different packs; `DeterminismValidator` bans the name outright
//     (UDEA0034). The positions below are the *cluster centres*, and the scatter around them is
//     drawn at spawn time from the `Spawn` RNG stream - see `TestLevelScene`. A seeded stream
//     rewinds with the snapshot ring and is unaffected by how combat rolls, so this layout
//     survives a gameplay change that a shared generator would have shifted.
//
// The positions are in world units, where a unit sprite is about sixteen tall. The old file
// wrote them in a world where a character was about one across; these are the same four
// clearings, scaled to this one.

// Where the orcs stand, and where the player arrives. The old level put both at
// `randomPos().sub(5F, 0F)`, which is why a game began with a fight already in progress.
val orcClearing = vec(-50F, 0F)

/** The priest, alone in the middle, as it was. */
val priestPost = vec(0F, 0F)

/** The skeletons, across the field: `randomPos().sub(-10F, 0F)` in the source. */
val skeletonCamp = vec(100F, 0F)

/** The soldier line: `randomPos().sub(0F, 5F)`. */
val soldierCamp = vec(0F, -50F)

level(
    name = "test_level",
    entities = {
        // The player is an **elite orc**, as `blueprint/player` was in the old game: it declared
        // `inherits = reference("blueprint/orc_elite")`, so the unit a human drove was the one
        // with five hundred health and `ability/orc_elite_spin` in its second slot. Restoring
        // that is what makes `OrcSpinExec` reachable in a running process at all - it is the only
        // exec in `MobaAbilities` whose ability no other unit is granted.
        //
        // It stands in the orc clearing, which is where the old level put it, and it is on
        // `Team.ORC` because `MobaBlueprints` reads the side off the kind rather than off the
        // entity - so a game now begins with one elite and four orcs against eleven soldiers, a
        // priest, a wizard and ten skeletons.
        entity(name = "player", blueprint = reference("character/orc_elite"), position = orcClearing)

        entity(name = "priest", blueprint = reference("character/priest"), position = priestPost)

        // The wizard. Its art was packed and unreachable for the same reason the elite's was; the
        // sheets are the corrected ones (`orc.udea.kts`' sibling `wizard.udea.kts` explains why
        // the source corpus' wizard pointed at priest PNGs and was invisible).
        entity(name = "wizard", blueprint = reference("character/wizard"), position = soldierCamp)

        // Four, not five. The player took the fifth orc's place in the clearing as an elite, so
        // `Team.ORC` still fields five bodies - which is what `MobaLevelTest` counts, and it
        // counts the *team* rather than the blueprint precisely so a roster can be recomposed
        // without the test becoming a list of blueprint names.
        repeat(4) {
            entity(name = "orc_$it", blueprint = reference("character/orc"), position = orcClearing)
        }

        repeat(10) {
            entity(
                name = "skeleton_$it",
                blueprint = reference("character/skeleton"),
                position = skeletonCamp,
            )
        }

        repeat(10) {
            entity(
                name = "soldier_$it",
                blueprint = reference("character/soldier"),
                position = soldierCamp,
            )
        }
    },
)
