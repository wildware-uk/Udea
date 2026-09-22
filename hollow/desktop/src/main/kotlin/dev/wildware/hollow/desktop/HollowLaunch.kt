package dev.wildware.hollow.desktop

import dev.wildware.hollow.FoxWaves
import dev.wildware.hollow.HollowAssets
import dev.wildware.hollow.HollowGame
import dev.wildware.hollow.HollowHost
import dev.wildware.hollow.HollowLevel
import dev.wildware.hollow.Prop
import dev.wildware.hollow.render.HollowScene
import dev.wildware.udea.assets.Model
import dev.wildware.udea.core.NetRole
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.generated.GameAssets
import dev.wildware.udea.physics2d.Physics2DModule
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.input.DeviceIntent
import dev.wildware.udea.render.input.IntentState
import dev.wildware.udea.render.kool.KoolKeyboard
import dev.wildware.udea.render.kool.KoolPointer
import dev.wildware.udea.render.model.FileModelLibrary
import dev.wildware.udea.render.model.ImportedModel
import dev.wildware.udea.render.model.loadModel
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.EnumMap

/**
 * The desktop half of starting Hollow: the models, the window, the backend and the boot order.
 * `MobaLaunch` is the same thing for `moba`, with more to it.
 */
internal object HollowLaunch {

    /** The game's asset root, absolute. Set by every run task in `hollow/desktop/build.gradle.kts`. */
    const val ASSET_ROOT_PROPERTY: String = "hollow.assets.root"

    /**
     * Where `:hollow:game:udeaPackBundle` wrote the `.glb` it converted each `.fbx` to (issue #244).
     *
     * The clearing's props ship as `.glb` and are read from the asset root; the human ships as
     * `models/human/Human.fbx`, and the bundle names `models/human/Human.glb`, which only exists
     * under the build directory. No `.fbx` is read here and nothing that could read one is on this
     * classpath (`UDEA-MG-013`).
     */
    const val CONVERTED_MODELS_PROPERTY: String = "hollow.assets.converted"

    /** A `.udealevel` to play instead of the bundled clearing. Set by `-Plevel=<path>`. */
    const val LEVEL_PROPERTY: String = "hollow.level"

    /**
     * A Kool backend in [mode] drawing [HollowScene], and a host over it as [role] playing the
     * launch level, not yet seeded and not yet driven.
     *
     * The order is forced rather than chosen, and it is `MobaLaunch`'s: **definition, scene,
     * backend, host**. The camera resolves the character it follows through the definition's
     * `NetIdIndex`, so the definition has to exist before the scene; `KoolBackend.start` builds its
     * pipeline out of a registry that is already complete, so the scene has to be registered before
     * the backend; and a `GameHost` builds its presentation from the backend.
     *
     * The caller closes [Started].
     */
    fun start(
        mode: RenderMode,
        role: NetRole = NetRole.Standalone,
        waves: FoxWaves? = FoxWaves.DEFAULT,
    ): Started {
        require(mode != RenderMode.Headless) { "RenderMode.Headless has no Kool backend" }
        val models = loadModels(assetRoot())
        val human = loadHuman()
        // A client's world is a replicated view and opens no solver; `HollowGame` says why.
        val physics = if (role.isAuthoritative) Physics2DModule(HollowGame.PHYSICS) else null
        var opened: KoolBackend? = null
        try {
            val definition = HollowGame.definition(physics, levelBytes(), role, waves)
            // Every fox names its model with `Drawn` (issue #251), and this is what turns the name
            // into the file: the same asset root the props are read from.
            val library = FileModelLibrary(assetRoot(), HollowAssets.registry)
            // The HUD (issue #252) is drawn from this definition's own combat tables, the wave
            // schedule the session it will join plays, and the desktop's font rasteriser. The
            // combat is read off the definition because the scene is built before the host is.
            val hud = HollowScene.Hud(HollowGame.combatOf(definition), waves, ::hollowHudFonts)
            val scene = HollowScene({ prop -> models.getValue(prop) }, { human }, definition.core.netIds, library, hud)
            val registry = RenderRegistry()
            scene.register(registry)
            val backend = KoolBackend.start(mode, WindowConfig(title = "Hollow"), registry)
            opened = backend
            return Started(backend, HollowHost(GameHost(mode, definition, backend), physics), scene)
        } catch (failure: RuntimeException) {
            opened?.close()
            physics?.close()
            throw failure
        }
    }

    /** A running backend, the game it draws, and the scene it draws it through. */
    class Started(
        val backend: KoolBackend,
        val opened: HollowHost,
        val scene: HollowScene,
    ) : AutoCloseable {

        val host: GameHost get() = opened.host

        /** The window's keys and mouse, built by [play] because they join Kool's input stack. */
        private var keyboard: KoolKeyboard? = null
        private var pointer: KoolPointer? = null

        /**
         * Points the camera at [character] and gives the game the window's keyboard and mouse. Does
         * nothing the second time, so a driver may call it every frame until the character is known.
         *
         * **Render thread.** `KoolKeyboard` and `KoolPointer` join Kool's input stack, the camera rig
         * exists only after `KoolBackend.start` has built the pipeline, and both are that thread's.
         *
         * `DeviceIntent` is the only thing in this engine that reads a device; everything below it
         * sees an `Intent` and cannot tell a key from an agent's tool call. What it is wrapped in is
         * the scene's camera-relative source, so W walks away from the camera.
         */
        /**
         * Points the camera at [character] without giving anything the window's devices.
         *
         * What [play] does minus the hands, for a driver that supplies its own `IntentSource` - a
         * shot walking a scripted route, or a test. **Render thread**, like [play]: the rig exists
         * only once `KoolBackend.start` has built the pipeline.
         */
        fun follow(character: NetId) {
            val rig = checkNotNull(scene.rig) { "the pipeline has not been built, so there is no camera" }
            rig.target = character
        }

        fun play(character: NetId) {
            follow(character)
            if (keyboard != null) return
            val rig = checkNotNull(scene.rig) { "the pipeline has not been built, so there is no camera" }
            val keys = KoolKeyboard()
            val mouse = KoolPointer()
            keyboard = keys
            pointer = mouse
            rig.motion = mouse
            rig.buttons = mouse
            // Right-drag turns the view, so the cursor stays usable for anything else and a click
            // the interface took never swings the camera (issue #227).
            rig.turnButton = TURN_BUTTON
            val input = host.ctx[IntentState.KEY]
            input.source = scene.cameraRelative(DeviceIntent(input.bindings, keyboard = keys))
        }

        override fun close() {
            keyboard?.close()
            pointer?.close()
            opened.close()
            backend.close()
        }
    }

    /**
     * Every prop's model, read once from its `.glb` under [root]: each is shared by every entity
     * drawing it, which is what `ImportedModel` is for.
     */
    fun loadModels(root: Path): Map<Prop, ImportedModel> =
        Prop.entries.associateWithTo(EnumMap(Prop::class.java)) { prop ->
            loadModel(root, HollowAssets.modelOf(prop))
        }

    /**
     * The human every character is drawn with, out of the bundle by its generated accessor.
     *
     * @throws IllegalStateException when the converted `.glb` is not where the asset build puts it,
     *   which means this process was launched without `:hollow:game:udeaPackBundle` having run.
     */
    fun loadHuman(): ImportedModel {
        val model: Model = HollowAssets.registry[GameAssets.models.human]
        check(model.file.value.endsWith(".glb")) {
            "the bundle names ${model.file} for the human; an .fbx is published as the .glb it converts to"
        }
        val root = convertedRoot()
        check(Files.isRegularFile(root.resolve(model.file.value))) {
            "no converted ${model.file} under $root; run :hollow:game:udeaPackBundle"
        }
        return loadModel(root, model)
    }

    private fun assetRoot(): Path = Path.of(
        checkNotNull(System.getProperty(ASSET_ROOT_PROPERTY)) {
            "-D$ASSET_ROOT_PROPERTY is not set; the run tasks in hollow/desktop/build.gradle.kts set it " +
                "to hollow/game/assets, where the models are"
        },
    )

    private fun convertedRoot(): Path = Path.of(
        checkNotNull(System.getProperty(CONVERTED_MODELS_PROPERTY)) {
            "-D$CONVERTED_MODELS_PROPERTY is not set; the run tasks in hollow/desktop/build.gradle.kts " +
                "set it to :hollow:game's build/udea/converted, where the converted .fbx models are"
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

    /** Kool's right mouse button. */
    private const val TURN_BUTTON: Int = 1
}
