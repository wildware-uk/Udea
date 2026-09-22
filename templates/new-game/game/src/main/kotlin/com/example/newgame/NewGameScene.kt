package com.example.newgame

import dev.wildware.udea.assets.AssetRegistry
import dev.wildware.udea.generated.GameAssets
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.model.FileModelLibrary
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelLight
import dev.wildware.udea.render.model.ModelRenderSystem
import dev.wildware.udea.render.shader.FloatUniform
import dev.wildware.udea.render.shader.UdeaShader
import dev.wildware.udea.render.sky.SkyBackground
import java.nio.file.Path

/**
 * What the window draws, declared once, before the window opens.
 *
 * None of it is simulation. The rovers say which model they are and where they stand (see
 * `RoverSystem`); this is the camera they are seen through, the light, the sky behind them and one
 * screen effect over the finished frame. A dedicated server never builds it.
 *
 * Nothing here names a type from the graphics library the engine draws with. `udea-render` keeps
 * that library to itself, so a game written against it compiles on every platform the engine
 * draws on.
 */
public object NewGameScene {

    /** How much of a dimmed row's brightness the scanline effect takes. */
    private const val SCANLINE_STRENGTH: Float = 0.2f

    /**
     * Declares the scene on [registry].
     *
     * @param assetRoot the game's `assets/` directory. The packed bundle carries each model's
     *   record - its name, its file, its clips - and not the file's bytes, so the file is read from
     *   here the first time a rover is drawn.
     * @param assets the packed asset graph, where the generated accessors are resolved.
     * @return the scanline effect's strength, which a settings screen would write.
     */
    public fun register(registry: RenderRegistry, assetRoot: Path, assets: AssetRegistry): FloatUniform {
        // Behind and above the field, looking down across the three lanes.
        val camera = ModelCamera().apply { lookAt(0f, -9f, 6f, 0f, 1.5f, 0f) }
        val light = ModelLight(directionX = -0.4f, directionY = 0.7f, directionZ = -1f)
        val models = FileModelLibrary(assetRoot, assets)
        registry.register(RenderPhase.World, { ModelRenderSystem(it, camera, light, models = models) })

        registry.sky.background = SkyBackground.Gradient(
            top = Rgba.of(0.18f, 0.32f, 0.55f),
            bottom = Rgba.of(0.72f, 0.80f, 0.86f),
        )

        lateinit var strength: FloatUniform
        val scanlines = UdeaShader.fragment(GameAssets.shaders.scanlines, assets) {
            strength = float("uStrength", SCANLINE_STRENGTH)
        }
        registry.screenPass(scanlines)
        return strength
    }
}
