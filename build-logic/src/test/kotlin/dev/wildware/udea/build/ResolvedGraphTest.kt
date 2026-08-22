package dev.wildware.udea.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The resolution path is the whole reason these gates read the resolved graph: the failure
 * being guarded against is a dependency nobody declared, and "`:common` is on your classpath"
 * without a path is a message that sends the reader grepping build files it is not in.
 */
class ResolvedGraphTest {

    private val diamond = ResolvedGraph(
        root = ":moba",
        edges = listOf(
            DependencyEdge(":moba", ":udea-core"),
            DependencyEdge(":moba", ":udea-render"),
            DependencyEdge(":udea-render", ":udea-core"),
            DependencyEdge(":udea-core", ":common"),
        ),
    )

    @Test
    fun `components includes the root and everything reachable`() {
        assertEquals(
            setOf(":moba", ":udea-core", ":udea-render", ":common"),
            diamond.components(),
        )
    }

    @Test
    fun `the path names every hop from the root to the offender`() {
        assertEquals(listOf(":moba", ":udea-core", ":common"), diamond.pathFromRoot(":common"))
    }

    @Test
    fun `the shortest path is reported when several reach the same component`() {
        // :udea-core is reachable directly and via :udea-render. The direct hop is the one a
        // reader can act on; the long way round would send them to the wrong build file.
        assertEquals(listOf(":moba", ":udea-core"), diamond.pathFromRoot(":udea-core"))
    }

    @Test
    fun `the root's own path is itself`() {
        assertEquals(listOf(":moba"), diamond.pathFromRoot(":moba"))
    }

    @Test
    fun `an unreachable component has no path`() {
        assertTrue(diamond.pathFromRoot(":udea-net").isEmpty())
    }

    @Test
    fun `a cycle terminates instead of being followed forever`() {
        // Gradle can present mutually dependent components; a depth-first walk with no visited
        // set would hang the build here rather than fail it.
        val cyclic = ResolvedGraph(
            root = ":a",
            edges = listOf(
                DependencyEdge(":a", ":b"),
                DependencyEdge(":b", ":c"),
                DependencyEdge(":c", ":b"),
                DependencyEdge(":c", ":common"),
            ),
        )
        assertEquals(listOf(":a", ":b", ":c", ":common"), cyclic.pathFromRoot(":common"))
    }
}
