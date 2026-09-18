package dev.wildware.udea.render.input

import dev.wildware.udea.render.bytecode.ClassRefScanner
import dev.wildware.udea.render.support.RepoLayout
import org.objectweb.asm.ClassReader
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * No simulation system in this module names a device. Checked in the **bytecode**, not by review.
 *
 * ## What it is guarding
 *
 * `ControllerSystem` was a Fleks `IntervalSystem` that called `Gdx.input` from inside `onTick`
 * (`common/.../ecs/system/ControllerSystem.kt:29`). Everything that made a recorded input stream
 * unreplayable, an agent's synthesised input a second code path, and a headless server unable to
 * run the client's simulation followed from that one line. `IntentSampleSystem` is the system
 * that replaces it and is *also* the one system in the tree whose whole job is input, so it is
 * exactly where the line would come back.
 *
 * A comment saying "this must not name `Gdx`" is worth nothing against that; a bytecode scan is
 * what notices the day somebody adds `if (Gdx.input.isKeyPressed(...))` for a debug key.
 *
 * ## What it covers, precisely
 *
 * Every class in **this module's** compiled `main` output whose superclass is
 * `dev.wildware.udea.core.SimSystem`. That is `IntentSampleSystem` and `InterpSnapshotSystem`
 * today.
 *
 * It does **not** cover a game's own simulation systems - `moba`'s classes are not on this
 * module's test classpath and adding a `:moba:classes` dependency here would make a renderer's
 * test suite wait on a game's code generation. The rule wants to be a `udeaVerifyDeterminism`
 * gate over every simulation source set (spec 6, Phase 7), scanning the same way and reporting
 * the same `UdeaDiagnostic`; this is that rule's first module, stated as such rather than
 * presented as the whole of it.
 */
class NoDeviceInSimTest {

    @Test
    fun `no SimSystem in udea-render references a device`() {
        val offences = ArrayList<String>()
        var scanned = 0

        RepoLayout.classFiles(MODULE).forEach { file ->
            val bytes = file.readBytes()
            if (ClassReader(bytes).superName != SIM_SYSTEM) return@forEach
            scanned++
            ClassRefScanner.scan(bytes)
                .filter { use -> BANNED.any { use.owner == it || use.owner.startsWith("$it$") } }
                .forEach { use ->
                    offences += "${use.className}.${use.member} names ${use.owner}" +
                        (use.ownerMember?.let { ".$it" } ?: "") +
                        " at ${use.sourceFile}:${use.line}"
                }
        }

        assertTrue(
            scanned > 0,
            "no SimSystem was found in $MODULE's compiled output, so this gate scanned nothing. " +
                "Either the module stopped contributing simulation systems - in which case delete " +
                "this test - or the classes were not built.",
        )
        assertTrue(
            offences.isEmpty(),
            "a simulation system reads a device. Input must reach the tick as an Intent " +
                "(see IntentState), or the simulation cannot be replayed, an agent cannot drive " +
                "it, and a headless server cannot run it:\n  " + offences.joinToString("\n  "),
        )
    }

    private companion object {

        const val MODULE: String = "udea-render"

        const val SIM_SYSTEM: String = "dev/wildware/udea/core/SimSystem"

        /**
         * The device handles, by internal name.
         *
         * `com/badlogic/gdx/Gdx` alone is enough for `Gdx.input`, and `Input`/`InputProcessor`
         * are here because a simulation system that so much as holds one has taken a device
         * reference by another route.
         */
        val BANNED: List<String> = listOf(
            "com/badlogic/gdx/Gdx",
            "com/badlogic/gdx/Input",
            "com/badlogic/gdx/InputProcessor",
            "com/badlogic/gdx/InputMultiplexer",
            "com/badlogic/gdx/controllers/Controllers",
        )
    }
}
