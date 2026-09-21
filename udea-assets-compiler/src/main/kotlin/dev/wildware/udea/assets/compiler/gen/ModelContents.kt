package dev.wildware.udea.assets.compiler.gen

import dev.wildware.udea.assets.compiler.DeclaredAsset
import dev.wildware.udea.assets.compiler.ResFile
import dev.wildware.udea.assets.compiler.validate.ModelFileValidator
import java.nio.file.Path

/**
 * Reads the file behind every `model(...)` and puts what a game may ask about it into the
 * declaration: its named nodes and its extras (issue #271).
 *
 * ## Why they are copied into the asset at all
 *
 * So a game can ask a model it holds. `GameAssets.models.chassis` is a `Ref<Model>`, and before
 * this nothing mapped it to the nodes the `Chassis.Nodes` object names - a game with sixty models
 * would have had to hand-write that table, or read the `.glb` itself at build time, which is what
 * robot-game was doing. Packed here, the answer comes out of `BundleReader` in `commonMain`, on
 * every target, as `Model.nodes` and `Model.extras`.
 *
 * ## One reading, two consumers
 *
 * The nodes are read by [ModelFileSource.readFile], which is what the accessors pass reads them
 * with too, so the packed list and the generated accessors are the same values by construction.
 *
 * ## Why it happens in pass 2
 *
 * For `ShaderSources`' reason, and beside it: `AssetCompiler.compile` and
 * `TranspiledAssetLoader.load` are the doors every driver of the passes goes through - the Gradle
 * pipeline and the dev daemon - so a hot reload's `Model` carries the same nodes a built bundle's
 * does.
 *
 * ## This reports nothing
 *
 * A model file that cannot be read gets neither field, and packs with no nodes and no extras.
 * What is wrong with it is reported where the declaration has a span to hang a line number on:
 * `ModelFileValidator`'s `UDEA0032`, `UDEA0038` or `UDEA0039` in pass 3 for a file that is
 * absent, not glTF 2.0 or not convertible, and `udeaGenerateAccessors`' `UDEA0027` for one whose
 * nodes cannot be placed - parents in a circle, a node with two parents - which [ModelFileSource]
 * reads with this same code. Either fails a Gradle build, so a model missing its nodes never
 * ships. The failure is dropped here and not carried, because both of those already say it.
 */
internal object ModelContents {

    /** The field this fills with the model's nodes: `GraphPacker.model` packs it as `Model.nodes`. */
    const val NODES_FIELD: String = "nodes"

    /** The field this fills with the model's own extras, packed as `Model.extras`. */
    const val EXTRAS_FIELD: String = "extras"

    /**
     * [declared], with every `model(...)` whose file reads carrying its nodes and extras as
     * plain values - they cross the worker boundary, which carries nothing else.
     *
     * A declaration that is not a model is passed through as the same object, so this is a
     * no-op for a tree with no models in it.
     */
    fun fill(assetRoot: Path, declared: List<DeclaredAsset>): List<DeclaredAsset> {
        if (declared.none { it.kind == ModelFileValidator.KIND }) return declared
        return declared.map { asset ->
            if (asset.kind != ModelFileValidator.KIND) return@map asset
            val path = asset.fields[ModelFileValidator.FILE_FIELD] as? ResFile ?: return@map asset
            // Pass 3 reports every way this can fail, with the declaration's line; see the KDoc.
            val contents = ModelFileSource.readFile(assetRoot, path.value).getOrNull() ?: return@map asset
            asset.copy(
                fields = asset.fields +
                    (NODES_FIELD to contents.nodes.map(::fieldsOf)) +
                    (EXTRAS_FIELD to contents.extras),
            )
        }
    }

    /** One node as the plain fields `GraphPacker.model` packs and `AssetCodecs` reads back. */
    private fun fieldsOf(node: GltfNode): Map<String, Any> = linkedMapOf(
        "index" to node.index,
        "name" to node.name,
        "x" to node.x,
        "y" to node.y,
        "z" to node.z,
        "qx" to node.qx,
        "qy" to node.qy,
        "qz" to node.qz,
        "qw" to node.qw,
        "scaleX" to node.scaleX,
        "scaleY" to node.scaleY,
        "scaleZ" to node.scaleZ,
        EXTRAS_FIELD to node.extras,
    )
}
