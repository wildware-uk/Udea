import dev.wildware.udea.build.udeaLibrary

/**
 * The GL-allowed convention. Applying this is the single visible marker that a module is
 * permitted to see LWJGL3/GL, and `udea-render` is the only module that may apply it
 * (spec 4: "the only module that touches GL").
 *
 * The graphics dependencies are `implementation`, not `api`, so GL does not leak onto the
 * compile classpath of anything that consumes this module.
 */

plugins {
    id("udea.kotlin-library")
}

dependencies {
    implementation(udeaLibrary("gdx"))
    implementation(udeaLibrary("gdx-backend-lwjgl3"))
}
