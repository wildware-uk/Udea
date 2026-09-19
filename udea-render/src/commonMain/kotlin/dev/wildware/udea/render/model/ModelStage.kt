package dev.wildware.udea.render.model

import de.fabmax.kool.math.MutableMat4f
import de.fabmax.kool.math.MutableVec3f
import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.math.rad
import de.fabmax.kool.modules.gltf.GltfLoadConfig
import de.fabmax.kool.modules.gltf.GltfMaterialConfig
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.modules.ksl.KslShader
import de.fabmax.kool.pipeline.AttachmentConfig
import de.fabmax.kool.pipeline.ClearColorFill
import de.fabmax.kool.pipeline.CullMethod
import de.fabmax.kool.pipeline.OffscreenPass2d
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.pipeline.shading.DepthShader
import de.fabmax.kool.scene.InstanceLayouts
import de.fabmax.kool.scene.Light
import de.fabmax.kool.scene.Lighting
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.MeshInstanceList
import de.fabmax.kool.scene.Model as KoolModel
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.PerspectiveCamera
import de.fabmax.kool.scene.VertexLayouts
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.MutableColor
import de.fabmax.kool.util.MutableStructBufferView
import de.fabmax.kool.util.ShadowMap
import de.fabmax.kool.util.SimpleShadowMap
import dev.wildware.udea.render.RenderResource
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteRegion
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.kool.ScenePasses
import dev.wildware.udea.render.view.WorldViewport

/**
 * The Kool half of [ModelRenderSystem]: a 3D pass with a depth buffer, a perspective camera, a
 * directional light with a shadow map, one instanced mesh per mesh-and-material pair drawn
 * with Kool's PBR shader, and one Kool scene node per entity drawing an [ImportedModel].
 *
 * Everything here is Kool's own - the mesh builders, `KslPbrShader`, `Lighting`,
 * `SimpleShadowMap`, the glTF reader's `makeModel` - and this class only fills them in from
 * Udea's Kool-free types once per frame. It is `internal` so that no Kool type reaches a game
 * (UDEA-MG-002).
 *
 * ## Per frame, without per-entity garbage
 *
 * [begin] hides every mesh and empties its instance list; [add] writes one model matrix into the
 * instance list of the entity's mesh-and-material pair and shows that mesh. A mesh no entity drew
 * this frame stays hidden, so a model that loses its `ModelRenderer` disappears on the very next
 * frame. The matrix, the vectors and the colours are fields reused every frame, and the instance
 * writer is one lambda made once, so a frame allocates only when a mesh-and-material pair is seen
 * for the first time.
 *
 * ## Imported models
 *
 * An [ImportedModel] is not instanced the way a built-in shape is. Each entity drawing one gets a
 * scene node of its own, made by Kool's glTF reader with the file's materials on Kool's PBR shader
 * and kept for the life of the stage: a frame with three foxes shows the first three nodes made
 * for that model and hides the rest. A node per entity rather than an instance per entity because
 * a skinned model animates per entity, and one instance list cannot hold two poses: each node is
 * posed from its entity's `Animator` as it is shown ([applyPose], issue #242). An editor's Scene
 * view draws the same nodes, so it sees the same pose without posing anything again. The nodes
 * share the file's textures, which Kool caches on the parsed file.
 *
 * Kool decodes a glTF texture on its loader threads after the node is made, and until it has, Kool
 * itself does not draw the mesh: its GL backend refuses a draw whose texture has no pixels yet
 * (`MappedUniformTex.checkLoadingState` in 0.19.0), so an untextured model never reaches a capture.
 */
internal class ModelStage(
    private val passes: ScenePasses,
    width: Int,
    height: Int,
) : RenderResource {

    private val drawNode = Node("udea-models")

    private val camera = PerspectiveCamera("udea-model-camera")

    private val sun = Light.Directional()

    private val pass = modelPass(drawNode, width, height, "udea-models").apply {
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

    /** Every imported model seen so far, with the scene nodes made for it. */
    private val imports = HashMap<ImportedModel, Imports>()
    private val allImports = ArrayList<Imports>()

    /** Each open Scene view's pass over this stage's models. */
    private val views = HashMap<WorldViewport, ViewPass>()

    /** Where a Scene view's orbit is written before it aims its pass. Reused. */
    private val orbit = ModelCamera()

    private val eye = MutableVec3f()
    private val target = MutableVec3f()
    private val direction = MutableVec3f()
    private val lightColor = MutableColor()
    private val ambient = MutableColor()

    private val matrix = MutableMat4f()
    private val axisScale = MutableVec3f()
    private val writeMatrix: (MutableStructBufferView<InstanceLayouts.ModelMat>, InstanceLayouts.ModelMat) -> Unit =
        { view, layout -> view.set(layout.modelMat, matrix) }

    /** The size the models' pass was last made. */
    private var width = width
    private var height = height

    /**
     * Makes the models' pass [width] x [height] pixels, the capturable frame's size, if it is not
     * already: an editor's Game tab can resize the frame (issue #234), and a pass of another shape
     * would come out stretched when drawn into it. Render thread only.
     */
    fun fit(width: Int, height: Int) {
        if (width == this.width && height == this.height) return
        pass.setSize(width, height)
        this.width = width
        this.height = height
    }

    /** Forgets the last frame's models and takes this frame's camera and light. Render thread only. */
    fun begin(view: ModelCamera, light: ModelLight) {
        for (index in allRuns.indices) {
            val run = allRuns[index]
            run.instances.clear()
            run.mesh.isVisible = false
        }
        for (index in allImports.indices) allImports[index].hideAll()

        aim(camera, view)

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
        for (index in allImports.indices) allImports[index].setAmbient()
    }

    /**
     * This frame's models seen from an editor's Scene view (issue #234), through its orbit camera:
     * a second pass over the same scene nodes, made the first time [view] asks and released when it
     * closes. The models are the ones [begin] and [add] placed this frame; nothing is placed again.
     *
     * The pass sits beside the capturable pass rather than before it, and only [view]'s own pass
     * waits on it, so a capture neither waits for it nor reads it. Render thread only.
     */
    fun imageFor(view: WorldViewport, game: ModelCamera): SpriteRegion {
        val editor = checkNotNull(view.camera) { "$view is the Game tab, which shows the capturable frame" }
        editor.adopt(game)
        val seen = views.getOrPut(view) { ViewPass(view) }
        seen.fit(view.width, view.height)
        editor.writeOrbit(orbit)
        aim(seen.camera, orbit)
        return seen.image
    }

    /** Points [camera] the way [view] says, Z up. */
    private fun aim(camera: PerspectiveCamera, view: ModelCamera) {
        eye.set(view.eyeX, view.eyeY, view.eyeZ)
        target.set(view.targetX, view.targetY, view.targetZ)
        camera.setupCamera(position = eye, up = Vec3f.Z_AXIS, lookAt = target)
        camera.fovY = view.fovYDegrees.deg
        camera.setClipRange(view.near, view.far)
    }

    /**
     * Draws [source] with the given transform this frame, an imported model in [pose]; a built-in
     * shape has no joints and ignores it. Render thread only.
     */
    fun add(
        source: ModelSource,
        x: Float, y: Float, z: Float,
        rotationX: Float, rotationY: Float, rotationZ: Float,
        scaleX: Float, scaleY: Float, scaleZ: Float,
        pose: ClipPose,
    ) {
        // Translate, then turn about Z, Y, X - so X is applied to the model first - then scale.
        matrix.setIdentity()
            .translate(x, y, z)
            .rotate(rotationZ.rad, Vec3f.Z_AXIS)
            .rotate(rotationY.rad, Vec3f.Y_AXIS)
            .rotate(rotationX.rad, Vec3f.X_AXIS)
            .scale(axisScale.set(scaleX, scaleY, scaleZ))
        when (source) {
            is MeshModel -> {
                val run = runFor(source.mesh, source.material)
                run.instances.addInstance(writeMatrix)
                run.mesh.isVisible = true
            }
            // The file is Y-up: turned onto the world's Z-up before anything else is applied.
            is ImportedModel -> importsFor(source).show(matrix.rotate(Y_UP_TO_Z_UP, Vec3f.X_AXIS), pose)
        }
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

    private fun importsFor(model: ImportedModel): Imports = imports.getOrPut(model) {
        Imports(model).also { allImports += it }
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

    /** The scene nodes made so far for one [ImportedModel], and how many this frame has shown. */
    private inner class Imports(private val model: ImportedModel) {

        private val config = gltfLoadConfig(model, listOf(shadow))

        private val nodes = ArrayList<Placed>()
        private var shown = 0

        fun hideAll() {
            for (index in nodes.indices) nodes[index].node.isVisible = false
            shown = 0
        }

        fun setAmbient() {
            for (index in nodes.indices) nodes[index].setAmbient()
        }

        /**
         * Shows the next free node at [transform] in [pose], making one if every node is in use.
         * The pose is set on every node shown, every frame: a node goes to whichever entity is
         * drawn in its turn, so it must not keep the pose of the entity it drew last.
         */
        fun show(transform: MutableMat4f, pose: ClipPose) {
            val placed = if (shown < nodes.size) nodes[shown] else make()
            shown++
            placed.node.transform.setMatrix(transform)
            placed.node.applyPose(pose)
            placed.node.isVisible = true
        }

        private fun make(): Placed = Placed(model.gltf.makeModel(config).withOwnShadowSkins()).also { placed ->
            placed.setAmbient()
            nodes += placed
            drawNode.addNode(placed.node)
        }
    }

    /**
     * One scene node made from a glTF file, with its shaders listed once so that a frame sets
     * their ambient light without walking Kool's maps.
     */
    private inner class Placed(val node: KoolModel) {
        private val shaders = node.meshes.values.mapNotNull { it.shader as? KslPbrShader }

        fun setAmbient() {
            for (index in shaders.indices) shaders[index].ambientFactor = ambient
        }
    }

    /** An editor view's pass over [drawNode]: its own camera, the stage's light and shadow map. */
    private inner class ViewPass(view: WorldViewport) {

        val camera = PerspectiveCamera("udea-model-view-camera")

        private val pass = modelPass(drawNode, view.width, view.height, "udea-models-view").apply {
            camera = this@ViewPass.camera
            lighting = this@ModelStage.pass.lighting
            // The stage's own pass updates and releases the shared nodes; this one only draws them.
            isUpdateDrawNode = false
            isReleaseDrawNode = false
        }

        val image: SpriteRegion = SpriteRegion(
            SpriteTexture.ofPassColour(
                checkNotNull(pass.colorTexture) { "a view's model pass has no colour attachment" },
                view.width,
                view.height,
            ),
        )

        private var released = false

        private var width = view.width
        private var height = view.height

        init {
            pass.dependsOn(shadow)
            passes.addBeside(pass)
            view.dependsOn(pass)
            view.onClose {
                views.remove(view)
                release()
            }
        }

        /** Makes the pass [width] x [height], the view's size, if it is not already. */
        fun fit(width: Int, height: Int) {
            if (width == this.width && height == this.height) return
            pass.setSize(width, height)
            this.width = width
            this.height = height
        }

        fun release() {
            if (released) return
            released = true
            passes.remove(pass)
            pass.release()
        }
    }

    /** Takes both passes off the scene and releases them, meshes and shaders with the draw node. */
    override fun release() {
        for (seen in views.values) seen.release()
        views.clear()
        passes.remove(pass)
        passes.remove(shadow)
        pass.release()
        shadow.release()
    }

    private companion object {
        /** Texels along each side of the shadow map: sharp shadow edges at a modest memory cost. */
        const val SHADOW_MAP_SIZE = 2048

        /** A quarter turn about X takes a glTF file's +Y, its up, to the world's +Z. */
        val Y_UP_TO_Z_UP = 90f.deg
    }
}

/**
 * How [ModelStage] makes a scene node from [model]'s file: its materials on Kool's PBR shader,
 * casting into [shadowMaps], and its clips and skin, skinned on the GPU by Kool's armature shader
 * (issue #242). A file with no skin or no clips gives a node with none, drawn as it stands.
 */
internal fun gltfLoadConfig(model: ImportedModel, shadowMaps: List<ShadowMap>): GltfLoadConfig = GltfLoadConfig(
    // The reader computes normals for a file that has none - the Fox has none - and the lighting
    // needs them.
    generateNormals = true,
    applyMaterials = true,
    materialConfig = GltfMaterialConfig(shadowMaps = shadowMaps),
    // Posed every frame from the entity's `Animator` by `applyPose`.
    loadAnimations = true,
    applySkins = true,
    applyMorphTargets = false,
    assetLoader = model.loader,
    // The file's material, with the same uniform ambient light the built-in shapes get; its
    // strength is set every frame from the `ModelLight`.
    pbrBlock = { _ -> lighting { uniformAmbientLight(Color.WHITE) } },
)

/**
 * Gives each skinned mesh of this node a depth shader of its own in the shadow pass, so its shadow
 * is skinned with its own joints (#242, reopened).
 *
 * Kool's shadow pass draws a mesh that names no depth shader with one it shares between every mesh
 * of the same layout and shader configuration, and a skin's joint matrices are a uniform of that
 * shader: every skinned mesh sharing it cast its shadow in the pose of whichever was drawn last.
 * The glTF reader names a depth shader configuration only for an alpha-masked material. So every
 * skinned mesh without one is given Kool's own configuration for it - `DepthShader.Config.forMesh`,
 * skinned because the mesh has a skin - and the pass makes a shader per mesh from it. The cull
 * method is the one the shared shader would have used: the mesh's own material's.
 */
private fun KoolModel.withOwnShadowSkins(): KoolModel = apply {
    for (mesh in meshes.values) {
        if (mesh.skin == null || mesh.depthShaderConfig != null) continue
        val cull = (mesh.shader as? KslShader)?.pipelineConfig?.cullMethod ?: CullMethod.CULL_BACK_FACES
        mesh.depthShaderConfig = DepthShader.Config.forMesh(mesh, cull)
    }
}

/**
 * A pass that draws a stage's models: colour transparent where no model is, so whatever the 2D pass
 * drew beneath shows through, a depth buffer, and [ModelStage]'s multisampling.
 */
private fun modelPass(drawNode: Node, width: Int, height: Int, name: String): OffscreenPass2d =
    OffscreenPass2d(
        drawNode = drawNode,
        attachmentConfig = AttachmentConfig {
            addColor(TexFormat.RGBA, clearColor = ClearColorFill(Color(0f, 0f, 0f, 0f)))
            defaultDepth()
        },
        initialSize = Vec2i(width, height),
        name = name,
        numSamples = MODEL_MSAA_SAMPLES,
    )

/** Samples per pixel: smooth model edges, where one sample leaves them stair-stepped. */
private const val MODEL_MSAA_SAMPLES = 4

/** Kool's colour from Udea's. */
private fun MutableColor.set(color: Rgba): MutableColor = set(color.r, color.g, color.b, color.a)
