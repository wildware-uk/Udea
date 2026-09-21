package dev.wildware.moba.model

import dev.wildware.moba.MobaAssets
import dev.wildware.udea.assets.Model
import dev.wildware.udea.assets.ModelNode
import dev.wildware.udea.assets.Ref
import dev.wildware.udea.generated.Fox
import dev.wildware.udea.generated.GameAssets
import dev.wildware.udea.generated.Human
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A game lists a model's nodes from the `Ref<Model>` it holds, with no table of its own
 * (issue #271).
 *
 * Against the game's real bundle - the one `udeaPackBundle` wrote from `moba/game/assets/` - and
 * the game's real generated accessors. Each model's packed list is compared with its `Nodes.all`,
 * which the build generated from the same reading of the same file, so this fails if the pack
 * stops carrying the nodes, carries a different reading of them, or the two ever drift.
 *
 * The Fox is a `.glb`; the Human is an `.fbx`, whose nodes are the `.glb` the build converts it
 * to. Both routes are here because they read the file in two different ways.
 */
class ModelNodesFromRefTest {

    /** What a game writes: a reference it already holds, and a question about it. */
    private fun nodesOf(model: Ref<Model>): List<ModelNode> = MobaAssets.registry[model].nodes

    @Test
    fun `the fox's nodes from its reference are its generated accessors in file order`() {
        val nodes = nodesOf(GameAssets.models.fox)

        assertTrue(nodes.isNotEmpty(), "the Fox has named nodes")
        assertEquals(Fox.Nodes.all, nodes)
    }

    @Test
    fun `the fbx human's nodes from its reference are its generated accessors`() {
        val nodes = nodesOf(GameAssets.models.human)

        assertTrue(nodes.isNotEmpty(), "the Human has named nodes")
        assertEquals(Human.Nodes.all, nodes)
    }

    @Test
    fun `a node found by walking the list is the node named in code`() {
        // The two directions meet: a tool that lists a model's nodes and a system that names one
        // are holding the same value, so an editor's pick can be compared with a hard-coded mount.
        val head = nodesOf(GameAssets.models.fox).single { it.name == "b_Head_05" }

        assertEquals(Fox.Nodes.b_Head_05, head)
    }
}
