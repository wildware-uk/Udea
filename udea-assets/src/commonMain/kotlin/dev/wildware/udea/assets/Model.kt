package dev.wildware.udea.assets

/**
 * A 3D model imported from a glTF 2.0 file: `models/fox/Fox.glb` (issue #240).
 *
 * Data, like every asset here: a path, not a mesh. The file's meshes, materials and textures are
 * read by `udea-render`, which owns everything with a GL handle in it; this module never sees a
 * vertex and never names the library that loads one (UDEA-MG-002).
 *
 * A game names one with a `Ref<Model>` - `reference<Model>("models/fox")`, or the generated
 * accessor - and never with the string alone, so a misspelled id is a build error with a
 * did-you-mean rather than a missing model at run time.
 *
 * The build checks the file before anything draws it: absent is `UDEA0032`, and present but not a
 * glTF 2.0 `.glb` or `.gltf` is `UDEA0038`. The extension is checked here too, because a `Model`
 * can be made in code without passing through the build.
 */
public data class Model(
    override val id: AssetId,
    /** The `.glb` or `.gltf` file, relative to the asset root. */
    public val file: ResPath,
) : AssetData {

    init {
        require(file.extension in EXTENSIONS) {
            "model '$id' names '$file'; a model is a glTF 2.0 file, one of ${EXTENSIONS.sorted()}"
        }
    }

    public companion object {
        /** The file extensions a model may have: binary glTF and JSON glTF. */
        public val EXTENSIONS: Set<String> = setOf("glb", "gltf")
    }
}
