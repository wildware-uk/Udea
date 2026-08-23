// The playable level: twenty-seven units, three sides, four clearings.
//
// Ported from `example/src/main/resources/assets/level/test_level.udea.kts`, which spawned the
// same roster - a player-controlled soldier, a priest, five orcs, ten skeletons and ten
// soldiers - and is what made the old demo feel alive.
//
// Two differences from the source, and both are rules this pipeline has that the old one did not:
//
//   * **No `systems = {}` block.** The old level named six system classes, so a level decided the
//     tick order of a simulation and two levels could disagree about what the game was.
//     `MobaModule` owns that now, once, for every scene - which is what makes "the server, the
//     client and the agent run the identical Simulation" a checkable sentence.
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

// Where the orcs stand, and where the player's soldier arrives. The old level put both at
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
        // The old level gave this one a `Player` component and `networkable(owner = -1)`. It is
        // named `player` here so the entity a control port has to find already exists, and is
        // already the one standing in the orc clearing where a game began with a fight in
        // progress.
        entity(name = "player", blueprint = reference("blueprint/soldier"), position = orcClearing)

        entity(name = "priest", blueprint = reference("blueprint/priest"), position = priestPost)

        repeat(5) {
            entity(name = "orc_$it", blueprint = reference("blueprint/orc"), position = orcClearing)
        }

        repeat(10) {
            entity(
                name = "skeleton_$it",
                blueprint = reference("blueprint/skeleton"),
                position = skeletonCamp,
            )
        }

        repeat(10) {
            entity(
                name = "soldier_$it",
                blueprint = reference("blueprint/soldier"),
                position = soldierCamp,
            )
        }
    },
)
