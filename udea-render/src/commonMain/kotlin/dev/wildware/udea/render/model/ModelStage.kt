package dev.wildware.udea.render.model

import de.fabmax.kool.math.MutableMat4f
import de.fabmax.kool.math.MutableVec3f
import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.math.rad
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.pipeline.AttachmentConfig
import de.fabmax.kool.pipeline.ClearColorFill
import de.fabmax.kool.pipeline.OffscreenPass2d
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.scene.InstanceLayouts
import de.fabmax.kool.scene.Light
import de.fabmax.kool.scene.Lighting
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.MeshInstanceList
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.PerspectiveCamera
import de.fabmax.kool.scene.VertexLayouts
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.MutableColor
import de.fabmax.kool.util.MutableStructBufferView
import de.fabmax.kool.util.SimpleShadowMap
import dev.wildware.udea.render.RenderResource
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteRegion
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.kool.ScenePasses

/**
 * The Kool half of [ModelRenderSystem]: a 3D pass with a depth buffer, a perspective camera, a
 * directional light with a shadow map, and one instanced mesh per mesh-and-material pair drawn
 * with Kool's PBR shader.
 *
 * Everything here is Kool's own - the mesh builders, `KslPbrShader`, `Lighting`,
 * `SimpleShadowMap` - and this class only fills them in from Udea's Kool-free types once per
 * frame. It is `internal` so that no Kool type reaches a game (UDEA-MG-002).
 *
 * ## Per frame, without per-entity garbage
 *
 * [begin] hides every mesh and empties its instance list; [add] writes one model matrix into the
 * instance list of the entity's mesh-and-material pair and shows that mesh. A mesh no entity drew
 * this frame stays hidden, so a model that loses its `ModelRenderer` disappears on the very next
 * frame. The matrix, the vectors and the colours are fields reused every frame, and the instance
 * writer is one lambda made once, so a frame allocates only when a mesh-and-material pair is seen
 * for the first time.
 */
internal class ModelStage(
    private val passes: ScenePasses,
    width: Int,
    height: Int,
) : RenderResource {

    private val drawNode = Node("udea-models")

    private val camera = PerspectiveCamera("udea-model-camera")

    private val sun = Light.Directional()

    private val pass = OffscreenPass2d(
        drawNode = drawNode,
        attachmentConfig = AttachmentConfig {
            // Transparent where no model is, so whatever the 2D pass drew beneath shows through.
            addColor(TexFormat.RGBA, clearColor = ClearColorFill(Color(0f, 0f, 0f, 0f)))
            defaultDepth()
        },
        initialSize = Vec2i(width, height),
        name = "udea-models",
        numSamples = MSAA_SAMPLES,
    ).apply {
        camera = this@ModelStage.camera
        lighting = Lighting().apply { addLight(sun) }
    }

    private val shadow = SimpleShadowMap(camera, drawNode, sun, SHADOW_MAP_SIZE, "udea-model-shadow")

    init {
        pass.dependsOn(shadow)
        passes.addBeforeCapture(shadow)
        passes.addBeforeCapture(pass)
    }

    /** The pass's picture, for the 2D batch to draw into the capturable frame. */
    val image: SpriteRegion = SpriteRegion(
        SpriteTexture.ofPassColour(
            checkNotNull(pass.colorTexture) { "the model pass has no colour attachment" },
            width,
            height,
        ),
    )

    /** Every pair seen so far, looked up by mesh then material: no key object per lookup. */
    private val runs = HashMap<ModelMesh, HashMap<ModelMaterial, Run>>()
    private val allRuns = ArrayList<Run>()

    private val eye = MutableVec3f()
    private val target = MutableVec3f()
    private val direction = MutableVec3f()
    private val lightColor = MutableColor()
    private val ambient = MutableColor()

    private val matrix = MutableMat4f()
    private val axisScale = MutableVec3f()
    private val writeMatrix: (MutableStructBufferView<InstanceLayouts.ModelMat>, InstanceLayouts.ModelMat) -> Unit =
        { view, layout -> view.set(layout.modelMat, matrix) }

    /** Forgets the last frame's models and takes this frame's camera and light. Render thread only. */
    fun begin(view: ModelCamera, light: ModelLight) {
        for (index in allRuns.indices) {
            val run = allRuns[index]
            run.instances.clear()
            run.mesh.isVisible = false
        }

        eye.set(view.eyeX, view.eyeY, view.eyeZ)
        target.set(view.targetX, view.targetY, view.targetZ)
        camera.setupCamera(position = eye, up = Vec3f.Z_AXIS, lookAt = target)
        camera.fovY = view.fovYDegrees.deg
        camera.setClipRange(view.near, view.far)

        direction.set(light.directionX, light.directionY, light.directionZ).norm()
        sun.setup(direction)
        lightColor.set(light.color)
        sun.setColor(lightColor, light.intensity)

        // The shadow map covers the camera's view from its near plane out to the shadow distance:
        // the shorter that is, the more of the map's texels land on what is close, and the
        // sharper the shadows there.
        shadow.clipNear = view.near
        shadow.clipFar = light.shadowDistance

        ambient.set(light.ambient)
        for (index in allRuns.indices) allRuns[index].shader.ambientFactor = ambient
    }

    /** Draws [mesh] in [material] with the given transform this frame. Render thread only. */
    fun add(
        mesh: ModelMesh,
        material: ModelMaterial,
        x: Float, y: Float, z: Float,
        rotationX: Float, rotationY: Float, rotationZ: Float,
        scaleX: Float, scaleY: Float, scaleZ: Float,
    ) {
        val run = runFor(mesh, material)
        // Translate, then turn about Z, Y, X - so X is applied to the model first - then scale.
        matrix.setIdentity()
            .translate(x, y, z)
            .rotate(rotationZ.rad, Vec3f.Z_AXIS)
            .rotate(rotationY.rad, Vec3f.Y_AXIS)
            .rotate(rotationX.rad, Vec3f.X_AXIS)
            .scale(axisScale.set(scaleX, scaleY, scaleZ))
        run.instances.addInstance(writeMatrix)
        run.mesh.isVisible = true
    }

    private fun runFor(mesh: ModelMesh, material: ModelMaterial): Run {
        val byMaterial = runs.getOrPut(mesh) { HashMap() }
        byMaterial[material]?.let { return it }
        val run = Run(mesh, material, allRuns.size)
        run.shader.ambientFactor = ambient
        byMaterial[material] = run
        allRuns += run
        drawNode.addNode(run.mesh)
        return run
    }

    /** One mesh-and-material pair: Kool's geometry, its instances and its PBR shader. */
    private inner class Run(shape: ModelMesh, material: ModelMaterial, index: Int) {
        val instances = MeshInstanceList(InstanceLayouts.ModelMat)

        val shader = KslPbrShader {
            vertices { instancedModelMatrix() }
            // sRGB texels, linearised before lighting; Kool's shader converts back on output.
            color { textureColor() }
            roughness(material.roughness)
            metallic(material.metallic)
            lighting {
                addShadowMap(shadow)
                uniformAmbientLight(Color.WHITE)
            }
        }.apply {
            colorMap = material.albedo.kool()
        }

        val mesh = Mesh(VertexLayouts.PositionNormalTexCoord, instances, name = "udea-model-$index").apply {
            shader = this@Run.shader
            // Instances move every frame; Kool's bounds are the geometry's, not the instances'.
            isFrustumChecked = false
            generate { shape.build(this) }
        }
    }

    /** Takes both passes off the scene and releases them, meshes and shaders with the draw node. */
    override fun release() {
        passes.remove(pass)
        passes.remove(shadow)
        pass.release()
        shadow.release()
    }

    private companion object {
        /** Texels along each side of the shadow map: sharp shadow edges at a modest memory cost. */
        const val SHADOW_MAP_SIZE = 2048

        /** Samples per pixel: smooth model edges, where one sample leaves them stair-stepped. */
        const val MSAA_SAMPLES = 4
    }
}

/** Kool's colour from Udea's. */
private fun MutableColor.set(color: Rgba): MutableColor = set(color.r, color.g, color.b, color.a)
