package dev.wildware.udea.codegen.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `net-protocol.lock` is the wire contract, so the properties tested here are the ones CI
 * relies on when it diffs the file: the same components always produce the same bytes, a
 * change that moves the wire moves the hash, and a change that does not, does not.
 *
 * The old generator had none of this. It named its cross-module index
 * `UdeaSerializerRegistry_${'$'}{System.currentTimeMillis()}`, so no two builds of the same source
 * agreed on the wire format even in principle, and nothing existed to notice.
 */
class ProtocolLockTest {

    private fun components(): List<LockedComponent> = listOf(
        LockedComponent(
            id = 0,
            qualifiedName = "moba.Health",
            fields = listOf(
                LockedField(0, "current", "f32:32"),
                LockedField(1, "maximum", "f32:32"),
            ),
        ),
        LockedComponent(
            id = 1,
            qualifiedName = "moba.Transform",
            fields = listOf(
                LockedField(0, "position.x", "q:16:-1024.0:1024.0"),
                LockedField(1, "position.y", "q:16:-1024.0:1024.0"),
                LockedField(2, "settledAt", "tick:64"),
            ),
        ),
    )

    private fun succeed(text: String): NetProtocolLock =
        when (val parsed = ProtocolLock.parse(text)) {
            is ProtocolLock.Parse.Success -> parsed.lock
            is ProtocolLock.Parse.Failure -> fail("expected a parse, got ${parsed.problems}")
        }

    private fun problems(text: String): List<String> =
        when (val parsed = ProtocolLock.parse(text)) {
            is ProtocolLock.Parse.Success -> fail("expected a failure, parsed ${parsed.lock}")
            is ProtocolLock.Parse.Failure -> parsed.problems
        }

    // --- determinism -------------------------------------------------------------------------

    @Test
    fun `the same components render byte-identically however they were ordered`() {
        // "Deterministic across module build orders" is a Phase 0 exit criterion, and the only
        // thing a build order can change is the order components reach this function.
        val forwards = ProtocolLock.render(ProtocolLock.build(components()))
        val backwards = ProtocolLock.render(ProtocolLock.build(components().reversed()))

        assertEquals(forwards, backwards)
        assertEquals(forwards, ProtocolLock.render(ProtocolLock.build(components())))
    }

    @Test
    fun `the rendered file is readable without tooling`() {
        val text = ProtocolLock.render(ProtocolLock.build(components()))

        assertTrue("THE WIRE CONTRACT" in text, "the header must say what the file is:\n$text")
        assertTrue("component 0 moba.Health" in text, text)
        assertTrue("field 0 position.x q:16:-1024.0:1024.0" in text, text)
        assertTrue("lockFormat ${ProtocolLock.FORMAT_VERSION}" in text, text)
    }

    // --- the hash tracks the wire and nothing else --------------------------------------------

    @Test
    fun `protoHash is a u16`() {
        assertTrue(ProtocolLock.build(components()).protoHash in 0..0xFFFF)
    }

    @Test
    fun `protoHash changes when a field is added`() {
        val extended = components().map { component ->
            if (component.id == 0) {
                component.copy(fields = component.fields + LockedField(2, "shielded", "bool:1"))
            } else {
                component
            }
        }

        assertNotEquals(
            ProtocolLock.build(components()).protoHash,
            ProtocolLock.build(extended).protoHash,
        )
    }

    @Test
    fun `protoHash changes when two fields swap indices`() {
        // Reordering is the change that is invisible in a round-trip test and fatal on the
        // wire: both peers write the same number of bits, in a different order.
        val swapped = components().map { component ->
            if (component.id != 0) {
                component
            } else {
                component.copy(
                    fields = listOf(
                        LockedField(0, "maximum", "f32:32"),
                        LockedField(1, "current", "f32:32"),
                    ),
                )
            }
        }

        assertNotEquals(
            ProtocolLock.build(components()).protoHash,
            ProtocolLock.build(swapped).protoHash,
        )
    }

    @Test
    fun `protoHash changes when a field's declared width changes`() {
        val widened = components().map { component ->
            if (component.id != 1) {
                component
            } else {
                component.copy(fields = component.fields.map { it.copy(wire = "f32:32") })
            }
        }

        assertNotEquals(
            ProtocolLock.build(components()).protoHash,
            ProtocolLock.build(widened).protoHash,
        )
    }

    @Test
    fun `protoHash does not change when only a comment changes`() {
        // The acceptance criterion is stated as "a comment or KDoc change must not move the
        // protocol". Comments are the only thing in the file that is not on the wire, so the
        // hash is taken over the non-comment content and this is what pins that.
        val rendered = ProtocolLock.render(ProtocolLock.build(components()))
        val recommented = rendered.lines().joinToString("\n") { line ->
            if (line.startsWith("#")) "# a completely different remark" else line
        } + "\n# and a trailing one\n"

        assertEquals(succeed(rendered).protoHash, succeed(recommented).protoHash)
    }

    // --- reading the file back ---------------------------------------------------------------

    @Test
    fun `a rendered lock parses back to the same components and hash`() {
        val built = ProtocolLock.build(components())

        assertEquals(built, succeed(ProtocolLock.render(built)))
    }

    @Test
    fun `two components on one id fails naming both`() {
        // Unreachable from `build`, which assigns densely from sorted names — and the first
        // thing a bad merge of two branches produces.
        val text = """
            lockFormat ${ProtocolLock.FORMAT_VERSION}
            protoHash 0x0000
            component 3 moba.Health
              field 0 current f32:32
            component 3 moba.Mana
              field 0 current f32:32
        """.trimIndent()

        val problem = problems(text).single()
        assertTrue("moba.Health" in problem, problem)
        assertTrue("moba.Mana" in problem, problem)
        assertTrue("3" in problem, problem)
    }

    @Test
    fun `one component on two ids fails naming it`() {
        val text = """
            lockFormat ${ProtocolLock.FORMAT_VERSION}
            protoHash 0x0000
            component 3 moba.Health
              field 0 current f32:32
            component 4 moba.Health
              field 0 current f32:32
        """.trimIndent()

        val problem = problems(text).single()
        assertTrue("moba.Health" in problem, problem)
        assertTrue("3" in problem && "4" in problem, problem)
    }

    @Test
    fun `a hand-edited field fails the hash check rather than being read as the contract`() {
        // The realistic accident: somebody widens a field in the lock file to "fix" a desync
        // instead of regenerating. Without this the file silently stops describing the build.
        val rendered = ProtocolLock.render(ProtocolLock.build(components()))
        val edited = rendered.replace("field 0 current f32:32", "field 0 current i32:32")

        val problem = problems(edited).single()
        assertTrue("edited by hand" in problem, problem)
        // The remedy has to be a mechanism that exists. This once named
        // `gradlew udeaWriteProtocolLock` while no task by that name was registered anywhere
        // in the build, so the one message a developer reads at the moment the lock stops
        // describing their build sent them to run nothing. The task is real now — registered
        // by `registerNetProtocolLock` in build-logic — and `UdeaProtocolLockTest` fails if
        // this string and the registered task name ever part company again.
        assertTrue("udeaWriteProtocolLock" in problem, problem)
        assertFalse("udea.updateProtocolLock" in problem, problem)
    }

    @Test
    fun `a lock with its protoHash line deleted is refused, not silently rehashed`() {
        // The cheapest way to make the hand-edit check stop complaining is to delete the one
        // line that can fail it. Without this the file parses, recomputes its hash from
        // whatever it now says and reports agreement — so the hash guards everything except
        // the edit that removes the hash.
        val rendered = ProtocolLock.render(ProtocolLock.build(components()))
        val decapitated = rendered.lines()
            .filterNot { it.startsWith("protoHash") }
            .joinToString(separator = System.lineSeparator())

        val problem = problems(decapitated).single()
        assertTrue("protoHash" in problem, problem)
        assertTrue("udeaWriteProtocolLock" in problem, problem)
    }

    // --- the tokens that carry meaning rather than width -------------------------------------

    @Test
    fun `protoHash changes when a quantised field's range changes but its width does not`() {
        // Bits alone say what a field costs; the range says what those bits mean. A lock that
        // recorded `q:12` hashed identically across a full-scale wire break.
        val rescaled = components().map { component ->
            if (component.id != 1) {
                component
            } else {
                component.copy(
                    fields = component.fields.map {
                        if (it.wire.startsWith("q:")) it.copy(wire = "q:16:-100.0:100.0") else it
                    },
                )
            }
        }

        assertNotEquals(
            ProtocolLock.build(components()).protoHash,
            ProtocolLock.build(rescaled).protoHash,
        )
    }

    @Test
    fun `protoHash changes when an enum's constants are reordered`() {
        // capture writes `.ordinal`, apply reads `entries[ordinal]`: the constant order *is*
        // the wire mapping, and both spellings below are `enum:32`.
        val standing = withStance("enum:32:Standing,Crouching,Sprinting")
        val crouching = withStance("enum:32:Crouching,Standing,Sprinting")

        assertNotEquals(
            ProtocolLock.build(standing).protoHash,
            ProtocolLock.build(crouching).protoHash,
        )
    }

    private fun withStance(wire: String): List<LockedComponent> = components().map { component ->
        if (component.id != 0) {
            component
        } else {
            component.copy(fields = component.fields + LockedField(2, "stance", wire))
        }
    }

    @Test
    fun `an older lock format is a failure telling the developer to regenerate`() {
        val text = """
            lockFormat ${ProtocolLock.FORMAT_VERSION + 1}
            protoHash 0x0000
        """.trimIndent()

        assertTrue(problems(text).single().contains("regenerate"), problems(text).toString())
    }

    @Test
    fun `a file that is not a lock is rejected rather than read as an empty protocol`() {
        // An empty protocol would parse happily and hash to a constant, so every peer would
        // agree they were speaking the same nothing.
        assertTrue(
            problems("hello\nworld\n").any { "not a ${ProtocolLock.FILE_NAME}" in it },
            problems("hello\nworld\n").toString(),
        )
    }
}
