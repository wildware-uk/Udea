package dev.wildware.hollow.desktop

import dev.wildware.hollow.HollowAssets
import dev.wildware.hollow.HollowGame
import dev.wildware.hollow.HollowLevel
import dev.wildware.hollow.Prop
import dev.wildware.hollow.render.HollowScene
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.model.ImportedModel
import dev.wildware.udea.render.model.loadModel
import java.io.File
import java.nio.file.Path
import java.util.EnumMap

/**
 * The desktop half of starting Hollow: the models, the window, the backend and the boot order.
 * `MobaLaunch` is the same thing for `moba`, with more to it.
 */
internal object HollowLaunch {

    /** The game's asset root, absolute. Set by every run task in `hollow/desktop/build.gradle.kts`. */
    const val ASSET_ROOT_PROPERTY: String = "hollow.assets.root"

    /** A `.udealevel` to play instead of the bundled clearing. Set by `-Plevel=<path>`. */
    const val LEVEL_PROPERTY: String = "hollow.level"

    /**
     * A Kool backend in [mode] drawing [HollowScene], and a host over it playing the launch level,
     * not yet seeded and not yet driven.
     *
     * The order is forced: the scene's registry must be complete before `KoolBackend.start` builds
     * the pipeline from it, and a `GameHost` builds its presentation from the backend. The caller
     * closes [Started.backend].
     */
    fun start(mode: RenderMode): Started {
        require(mode != RenderMode.Headless) { "RenderMode.Headless has no Kool backend" }
        val models = loadModels(assetRoot())
        val scene = HollowScene { prop -> models.getValue(prop) }
        val registry = RenderRegistry()
        scene.register(registry)
        val backend = KoolBackend.start(mode, WindowConfig(title = "Hollow"), registry)
        return try {
            Started(backend, GameHost(mode, HollowGame.definition(levelBytes()), backend))
        } catch (failure: RuntimeException) {
            backend.close()
            throw failure
        }
    }

    /** A running backend and the host it draws. */
    class Started(val backend: KoolBackend, val host: GameHost)

    /**
     * Every prop's model, read once from its `.glb` under [root]: each is shared by every entity
     * drawing it, which is what `ImportedModel` is for.
     */
    fun loadModels(root: Path): Map<Prop, ImportedModel> =
        Prop.entries.associateWithTo(EnumMap(Prop::class.java)) { prop ->
            loadModel(root, HollowAssets.modelOf(prop))
        }

    private fun assetRoot(): Path = Path.of(
        checkNotNull(System.getProperty(ASSET_ROOT_PROPERTY)) {
            "-D$ASSET_ROOT_PROPERTY is not set; the run tasks in hollow/desktop/build.gradle.kts set it " +
                "to hollow/game/assets, where the models are"
        },
    )

    /**
     * The launch level: the file `-Plevel` named, or the bundled clearing.
     *
     * @throws IllegalArgumentException naming the path when it is not a file: a typo on the launch
     *   line must not quietly boot the clearing instead.
     */
    fun levelBytes(named: String? = System.getProperty(LEVEL_PROPERTY)): ByteArray {
        val path = named?.trim().orEmpty()
        if (path.isEmpty()) return HollowLevel.bundledBytes()
        val file = File(path)
        require(file.isFile) { "-D$LEVEL_PROPERTY names $path, which is not a file (resolved to ${file.absolutePath})" }
        return file.readBytes()
    }
}
