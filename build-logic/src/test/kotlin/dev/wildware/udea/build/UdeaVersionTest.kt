package dev.wildware.udea.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rule that decides what version the engine publishes under (issue #265).
 *
 * Every branch is exercised here rather than through a build, because the two callers are build
 * scripts and a `doLast` block is not reachable from a test. The last test is the one that keeps
 * the two callers honest: `build-logic` publishes its own plugins and cannot use this class - a
 * build script cannot use a class the project it configures compiles - so it carries the default
 * as a literal, and that literal has to be this rule's answer.
 */
class UdeaVersionTest {
    @Test
    fun `the property wins over the tag`() {
        assertEquals(
            "2.5.0",
            UdeaVersion.resolve(asked = "2.5.0", described = "v1.0.0"),
            "the release workflow names the version it is building, and a release is compiled " +
                "before it is tagged",
        )
    }

    @Test
    fun `a blank property is not an answer`() {
        assertEquals(
            "1.0.0",
            UdeaVersion.resolve(asked = "   ", described = "v1.0.0"),
            "-PudeaVersion= with nothing after it is a property that is set and empty",
        )
    }

    @Test
    fun `the property is trimmed`() {
        assertEquals("2.5.0", UdeaVersion.resolve(asked = " 2.5.0\n", described = "v1.0.0"))
    }

    @Test
    fun `a commit that is exactly a tag publishes that release`() {
        assertEquals("1.4.2", UdeaVersion.resolve(asked = null, described = "v1.4.2"))
    }

    @Test
    fun `a commit after a tag publishes the next minor as a snapshot`() {
        assertEquals(
            "1.5.0-SNAPSHOT",
            UdeaVersion.resolve(asked = null, described = "v1.4.2-7-gdeadbee"),
            "0.1.0-SNAPSHOT after 0.1.0 exists on Central is a mutable version wearing the name " +
                "of an immutable one",
        )
    }

    @Test
    fun `a dirty tree on a tag is still after that tag`() {
        assertEquals("1.5.0-SNAPSHOT", UdeaVersion.resolve(asked = null, described = "v1.4.2-dirty"))
    }

    @Test
    fun `a repository with no tag at all publishes the first snapshot`() {
        assertEquals(UdeaVersion.FIRST_SNAPSHOT, UdeaVersion.resolve(asked = null, described = "a45636c-dirty"))
        assertEquals(UdeaVersion.FIRST_SNAPSHOT, UdeaVersion.resolve(asked = null, described = ""))
    }

    @Test
    fun `a tag that is not a version is not read as one`() {
        assertEquals(
            UdeaVersion.FIRST_SNAPSHOT,
            UdeaVersion.resolve(asked = null, described = "vintage-2-gdeadbee"),
            "git describe answers with whatever tag is nearest, and not every tag is a release",
        )
    }

    @Test
    fun `build-logic publishes its plugins at the same default this rule gives`() {
        val script = File("build.gradle.kts")
        assertTrue(script.isFile, "expected build-logic's own build script at ${script.absolutePath}")
        val text = script.readText()
        val default = UdeaVersion.resolve(asked = null, described = "")
        assertTrue(
            text.contains("\"$default\""),
            "build-logic's build script must carry $default as its default version, because it " +
                "cannot call UdeaVersion.resolve - it is the build that compiles it. It reads " +
                "-PudeaVersion like everything else, so the two can only differ when nobody " +
                "passed one, and this is what stops that drifting in silence.",
        )
    }
}
