package dev.wildware.udea.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceSpanTest {

    @Test
    fun `accepts a repo-relative path`() {
        val span = SourceSpan("moba/src/main/kotlin/Health.kt", 12, 5, 12, 24)
        assertEquals("moba/src/main/kotlin/Health.kt", span.path)
        assertEquals("moba/src/main/kotlin/Health.kt:12:5", span.toString())
    }

    @Test
    fun `rejects a posix absolute path`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            SourceSpan("/home/ci/udea/moba/src/main/kotlin/Health.kt", 1, 1, 1, 1)
        }
        assertTrue("absolute" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `rejects a windows drive-qualified path`() {
        assertFailsWith<IllegalArgumentException> { SourceSpan("C:/Users/shaun/udea/a.kt", 1, 1, 1, 1) }
        // Drive-relative, which is still machine-specific.
        assertFailsWith<IllegalArgumentException> { SourceSpan("C:a.kt", 1, 1, 1, 1) }
    }

    @Test
    fun `rejects a UNC path`() {
        assertFailsWith<IllegalArgumentException> { SourceSpan("//build-host/share/a.kt", 1, 1, 1, 1) }
    }

    @Test
    fun `rejects a path containing a parent segment`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            SourceSpan("moba/../common/src/Health.kt", 1, 1, 1, 1)
        }
        assertTrue(".." in failure.message.orEmpty(), failure.message.orEmpty())
        assertFailsWith<IllegalArgumentException> { SourceSpan("../outside.kt", 1, 1, 1, 1) }
    }

    @Test
    fun `allows dots inside a file name`() {
        assertEquals("moba/assets/orc..idle.png", SourceSpan("moba/assets/orc..idle.png", 1, 1, 1, 1).path)
    }

    @Test
    fun `rejects a backslash-separated path so two producers cannot disagree`() {
        assertFailsWith<IllegalArgumentException> {
            SourceSpan("moba\\src\\main\\kotlin\\Health.kt", 1, 1, 1, 1)
        }
    }

    @Test
    fun `rejects a current-directory segment so one location has one spelling`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            SourceSpan("moba/src/./Health.kt", 12, 5, 12, 24)
        }
        assertTrue("normalized" in failure.message.orEmpty(), failure.message.orEmpty())
        assertFailsWith<IllegalArgumentException> { SourceSpan("./Health.kt", 1, 1, 1, 1) }
    }

    @Test
    fun `rejects a repeated separator so one location has one spelling`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            SourceSpan("moba/src//Health.kt", 12, 5, 12, 24)
        }
        assertTrue("normalized" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `of canonicalises dot segments and repeated separators to one span`() {
        // The dedupe property the class exists for: three spellings of one location from
        // three producers must be one `data class` value, not three.
        val canonical = SourceSpan("moba/src/Health.kt", 1, 1, 1, 1)
        assertEquals(canonical, SourceSpan.of("/ci/udea", "/ci/udea/moba/src/./Health.kt", 1, 1))
        assertEquals(canonical, SourceSpan.of("/ci/udea", "/ci/udea/moba//src/Health.kt", 1, 1))
        assertEquals(canonical, SourceSpan.of("/ci/udea", "/ci/udea/moba\\src\\Health.kt", 1, 1))
    }

    @Test
    fun `relativize canonicalises a relative path it passes through`() {
        assertEquals("moba/src/Health.kt", SourceSpan.relativize("/ci/udea", "moba/src/./Health.kt"))
        assertEquals("moba/src/Health.kt", SourceSpan.relativize("/ci/udea", "moba//src/Health.kt"))
    }

    @Test
    fun `a dot segment in the repo root does not defeat relativize`() {
        assertEquals(
            "moba/src/Health.kt",
            SourceSpan.relativize("/ci/./udea", "/ci/udea/moba/src/Health.kt"),
        )
    }

    @Test
    fun `rejects a blank path`() {
        assertFailsWith<IllegalArgumentException> { SourceSpan("   ", 1, 1, 1, 1) }
    }

    @Test
    fun `of relativizes a windows absolute path against the repo root`() {
        val span = SourceSpan.of(
            repoRoot = "C:\\Users\\shaun\\Workspace\\udea",
            absolutePath = "C:\\Users\\shaun\\Workspace\\udea\\moba\\src\\main\\kotlin\\Health.kt",
            startLine = 12,
            startColumn = 5,
        )
        assertEquals("moba/src/main/kotlin/Health.kt", span.path)
        assertEquals(12, span.endLine)
        assertEquals(5, span.endColumn)
    }

    @Test
    fun `relativize ignores drive letter case and a trailing separator`() {
        assertEquals(
            "moba/src/Health.kt",
            SourceSpan.relativize("c:/users/shaun/udea/", "C:/Users/shaun/udea/moba/src/Health.kt"),
        )
    }

    @Test
    fun `relativize leaves an already relative path alone`() {
        assertEquals("moba/src/Health.kt", SourceSpan.relativize("/ci/udea", "moba/src/Health.kt"))
    }

    @Test
    fun `relativize refuses a path outside the repo root`() {
        assertFailsWith<IllegalArgumentException> {
            SourceSpan.relativize("/ci/udea", "/ci/other/Health.kt")
        }
        // A path equal to the root is not a file inside it.
        assertFailsWith<IllegalArgumentException> {
            SourceSpan.relativize("/ci/udea", "/ci/udea")
        }
    }

    @Test
    fun `an absolute path cannot survive of either`() {
        // The only sanctioned constructor still enforces the invariant afterwards: a root that
        // does not strip the whole absolute prefix must fail rather than yield an absolute span.
        assertFailsWith<IllegalArgumentException> {
            SourceSpan.of("/ci", "/elsewhere/a.kt", 1, 1)
        }
    }

    @Test
    fun `equality is structural so the sink can dedupe on it`() {
        assertEquals(SourceSpan("a/b.kt", 1, 2, 3, 4), SourceSpan("a/b.kt", 1, 2, 3, 4))
        assertEquals(
            SourceSpan("a/b.kt", 1, 2, 3, 4).hashCode(),
            SourceSpan("a/b.kt", 1, 2, 3, 4).hashCode(),
        )
    }
}
