/*
 * The root build script of a game that lives outside the Udea repository.
 *
 * It holds no sources. Its whole job is to apply Udea's build gates to this build and tell them
 * what this build is: which project ships, and which of its code is simulation.
 */

plugins {
    // Every gate that is about a build rather than about one module: the module-graph rules on
    // each project that has a build script, the determinism scan over the scopes declared below,
    // and the release scan on the project named by `ships`. The engine's own root build script
    // applies this same plugin and writes this same block.
    id("dev.wildware.udea.game-gates")
}

udeaGates {
    // The project that produces the artifact a player runs. `udeaVerifyRelease` scans its jar and
    // its runtime classpath on a `-Pudea.release=true` build, and fails if the agent surface is
    // in either.
    ships(":game")

    // What `udeaVerifyDeterminism` reads. Narrowed to the package that simulates: a wall-clock
    // read or an unseeded `Random` is a defect there and is perfectly correct in a renderer or a
    // HUD, and a gate that failed on both is a gate people switch off.
    simulation(
        project = ":game",
        packagePrefixes = listOf("com.example.newgame.sim"),
        why = "The game's own rules: what moves, what it collides with and what decides the " +
            "outcome of a tick. Everything the player sees rather than plays lives outside " +
            "this package and is presentation.",
    )
}
