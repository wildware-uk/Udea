package dev.wildware.udea.assets.compiler.model

import dev.wildware.udea.assets.Model
import dev.wildware.udea.assets.compiler.ResFile

/**
 * The files a `model(...)` may name, and the file a game is given for each (issue #244).
 *
 * A model the renderer draws is glTF 2.0 ([Model.EXTENSIONS]), and a script may also name an
 * `.fbx`, which the build converts to one. So the file a script names and the file the packed
 * `Model` names differ for an `.fbx` alone: `models/human/Human.fbx` is published as
 * `models/human/Human.glb`, which the pack writes into its converted-models directory at that
 * same relative path.
 */
internal object ModelSources {

    /** The extension of a model the build converts rather than reads as it is. */
    const val FBX: String = "fbx"

    /** Every extension a `model(...)` may name: glTF's two, and FBX. */
    val EXTENSIONS: Set<String> = Model.EXTENSIONS + FBX

    /** [file]'s extension, lower case, or empty when it has none. */
    fun extensionOf(file: ResFile): String = file.value.substringAfterLast('.', missingDelimiterValue = "").lowercase()

    /** True when [file] is converted by the build rather than published as it is. */
    fun isConverted(file: ResFile): Boolean = extensionOf(file) == FBX

    /** The glTF file a game is given for the model file [file] a script names. */
    fun runtimeFile(file: ResFile): ResFile =
        if (isConverted(file)) ResFile(file.value.substringBeforeLast('.') + "." + CONVERTED_EXTENSION) else file

    /** What a converted model becomes: binary glTF, one self-contained file. */
    private const val CONVERTED_EXTENSION = "glb"
}
