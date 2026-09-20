package dev.wildware.udea.render.model

import de.fabmax.kool.math.Mat4f
import de.fabmax.kool.math.MutableMat4f
import de.fabmax.kool.math.MutableVec3f
import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.modules.gltf.GltfLoadConfig
import de.fabmax.kool.modules.gltf.GltfMaterialConfig
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.modules.ksl.KslShader
import de.fabmax.kool.pipeline.AttachmentConfig
import de.fabmax.kool.pipeline.ClearColorFill
import de.fabmax.kool.pipeline.CullMethod
import de.fabmax.kool.pipeline.FrameCopy
import de.fabmax.kool.pipeline.OffscreenPass2d
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.pipeline.shading.DepthShader
import de.fabmax.kool.scene.Camera
import de.fabmax.kool.scene.InstanceLayouts
import de.fabmax.kool.scene.Light
import de.fabmax.kool.scene.Lighting
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.MeshInstanceList
import de.fabmax.kool.scene.Model as KoolModel
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.OrthographicCamera
import de.fabmax.kool.scene.PerspectiveCamera
import de.fabmax.kool.scene.VertexLayouts
import de.fabmax.kool.math.spatial.BoundingBoxF
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.MutableColor
import de.fabmax.kool.util.MutableStructBufferView
import de.fabmax.kool.util.ShadowMap
import de.fabmax.kool.util.SimpleShadowMap
import dev.wildware.udea.core.Tick
import dev.wildware.udea.render.RenderResource
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteRegion
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.kool.ScenePasses
import dev.wildware.udea.render.view.WorldViewport
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The Kool half of [ModelRenderSystem]: a 3D pass with a depth buffer, a camera of whichever
 * projection the frame's [ModelCamera] asks for (issue #257), a
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

    /**
     * The two Kool cameras the capturable pass is drawn through, one per [ModelProjection] (issue
     * #257). Both are kept for the life of the stage and [aim] hands back whichever this frame's
     * [ModelCamera] asks for: a camera is a Kool `Node` with a scene graph behind it, so making one
     * per frame to change projection would be a per-frame allocation on the drawing path.
     */
    private val perspective = PerspectiveCamera("udea-model-camera")
    private val orthographic = OrthographicCamera("udea-model-camera-ortho")

    private val sun = Light.Directional()

    private val pass = modelPass(drawNode, width, height, "udea-models").apply {
        camera = this@ModelStage.perspective
        lighting = Lighting().apply { addLight(sun) }
    }

    private val shadow = SimpleShadowMap(perspective, drawNode, sun, SHADOW_MAP_SIZE, "udea-model-shadow")

    /**
     * The 3D depth of the frame, resolved into a texture a screen shader can sample (issue #266).
     *
     * A `FrameCopy` rather than the pass's own depth attachment, because this pass is
     * multisampled: Kool resolves a copy, and reading the multisampled attachment directly is not
     * the same picture. Kool makes the copy as part of drawing the pass, so it costs a resolve
     * whether or not a shader reads it - which is why the chain is the only thing that asks for
     * one, and a game with no screen shader pays for it all the same. That is the one cost this
     * feature adds to a game that does not use it, and it is a resolve of a depth buffer per frame.
     */
    private val depthCopy: FrameCopy = pass.copyOutput(isCopyColor = false, isCopyDepth = true)

    /**
     * The object mask (issues #259, #266): the same models, through the same camera, filtered to
     * the ones the game marked.
     *
     * The same kind of pass as the picture - [modelPass], colour cleared to transparent - so what
     * a screen shader reads is the **alpha** channel: `1` where a marked model was drawn and `0`
     * everywhere else. `udeaMasked` in the shader header is that read, named.
     *
     * A depth-only `DepthMapPass` was the first shape of this and it did not work: Kool's
     * `OffscreenPass2d` publishes a colour attachment as a sampleable texture, and the depth-only
     * pass handed back nothing a screen shader could sample, so every mask came out empty and
     * every outline drew nothing. A colour pass costs the marked models a second shading, which is
     * the price of a mask that can actually be read.
     *
     * The draw node is shared with the capturable pass, so this pass neither updates nor releases
     * it: the stage's own pass does both, and two passes doing it would place every model twice a
     * frame and release it from under the other. `ViewPass` does the same for the same reason.
     */
    private val maskPass: OffscreenPass2d = modelPass(drawNode, width, height, "udea-model-mask").apply {
        camera = this@ModelStage.perspective
        lighting = this@ModelStage.pass.lighting
        isUpdateDrawNode = false
        isReleaseDrawNode = false
    }

    /**
     * The Kool nodes drawn into the mask this frame: a run whose entities asked for it, and an
     * imported node whose entity did. Emptied by [begin] and filled by [show], so a model that
     * stops asking is out of the mask on the very next frame.
     */
    private val maskedNodes = HashSet<Node>()

    /**
     * Whether [node] is one the game marked, or sits under one.
     *
     * Walking up rather than testing membership alone because [maskedNodes] holds what [show] was
     * given - an instanced mesh, or the root node of an imported model - and an imported model's
     * root is not the thing that draws: its meshes are, several levels down. A filter that only
     * looked at the node it was handed would mask an instanced box and silently not mask a fox.
     * The walk stops at [drawNode], so it is the depth of one model, not of the scene.
     */
    private fun isMasked(node: Node): Boolean {
        var current: Node? = node
        while (current != null && current !== drawNode) {
            if (current in maskedNodes) return true
            current = current.parent
        }
        return false
    }

    /**
     * Every Scene view's preview node (issue #243): drawn by that view's pass and by no other. The
     * capturable pass and the shadow map skip them, so a preview reaches neither a capture nor a
     * shadow. Compared by identity: a Kool node has no equality of its own.
     */
    private val previewNodes = HashSet<Node>()

    init {
        pass.dependsOn(shadow)
        passes.addBeforeCapture(shadow)
        passes.addBeforeCapture(pass)
        // Before the capturable pass, because the screen effects run inside it and sample this.
        passes.addBeforeCapture(maskPass)
        pass.defaultView.drawFilter = { node -> node !in previewNodes }
        val castsShadow = shadow.defaultView.drawFilter
        shadow.defaultView.drawFilter = { node -> node !in previewNodes && castsShadow(node) }
        maskPass.defaultView.drawFilter = { node -> node === drawNode || isMasked(node) }
        // Read through lambdas: Kool replaces both textures when the frame is resized (#234).
        passes.setScreenInputs({ depthCopy.depthCopy2d }, { maskPass.colorTexture })
    }

    /** The pass's picture, for the 2D batch to draw into the capturable frame. */
    val image: SpriteRegion = SpriteRegion(
        SpriteTexture.ofPassColour(
            checkNotNull(pass.colorTexture) { "the model pass has no colour attachment" },
            width,
            height,
        ),
    )

    /**
     * Every run seen so far, looked up by mesh, then material, then whether it is masked: no key
     * object per lookup. See [runFor] for why the mask is part of the key.
     */
    private val runs = HashMap<ModelMesh, HashMap<ModelMaterial, Array<Run?>>>()
    private val allRuns = ArrayList<Run>()

    /** Every imported model seen so far, with the scene nodes made for it. */
    private val imports = HashMap<ImportedModel, Imports>()
    private val allImports = ArrayList<Imports>()

    /** Each open Scene view's pass over this stage's models. */
    private val views = HashMap<WorldViewport, ViewPass>()

    /**
     * Which entity each imported node shown this frame was drawn for, in the order [add] showed
     * them: the Fleks entity id at the same position as its node. Read by a preview and by
     * [skeleton], never per entity per frame. Emptied by [begin].
     */
    private var drawnEntities = IntArray(INITIAL_DRAWN)
    private val drawnNodes = ArrayList<Placed>()

    /** The pose a Scene view's preview shows. Reused. */
    private val previewPose = ClipPose()

    private val jointPosition = MutableVec3f()

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
        // The mask is sampled at the frame's own texel grid, so a mask of another shape would put
        // an outline a fraction of a pixel away from the silhouette it belongs to.
        maskPass.setSize(width, height)
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
        drawnNodes.clear()
        maskedNodes.clear()

        // The shadow map follows the pass's camera: both must be the projection this frame asked for,
        // or the shadows would be fitted to a frustum nothing is drawn through.
        val aimed = aim(view)
        // The mask is drawn through the same camera as the picture, whatever this frame asked for:
        // a mask from another projection would be an outline around where a model is not.
        if (maskPass.camera !== aimed) maskPass.camera = aimed
        if (pass.camera !== aimed) {
            pass.camera = aimed
            shadow.sceneCam = aimed
        }

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
        for (seen in views.values) seen.setPreviewAmbient()
    }

    /**
     * This frame's models seen from an editor's Scene view (issue #234), through its orbit camera:
     * a second pass over the same scene nodes, made the first time [view] asks and released when it
     * closes. The models are the ones [begin] and [add] placed this frame; nothing is placed again.
     *
     * The pass sits beside the capturable pass rather than before it, and only [view]'s own pass
     * waits on it, so a capture neither waits for it nor reads it. Render thread only.
     */
    fun imageFor(view: WorldViewport, game: ModelCamera, previewEntity: Int = NO_ENTITY): SpriteRegion {
        val editor = checkNotNull(view.camera) { "$view is the Game tab, which shows the capturable frame" }
        editor.adopt(game)
        val seen = views.getOrPut(view) { ViewPass(view) }
        seen.fit(view.width, view.height)
        editor.writeOrbit(orbit)
        // A Scene view is always perspective: it is the editor's own orbit camera (issue #234), not
        // the game's, and `EditorCamera` un-projects a gizmo drag through a field of view.
        place(seen.camera, orbit)
        seen.camera.fovY = orbit.fovYDegrees.deg
        when (val preview = view.modelPreview) {
            null -> seen.clearPreview()
            is ModelPreview.Pose -> {
                val drawn = drawnFor(previewEntity)
                if (drawn == null) seen.clearPreview() else seen.showPose(drawn, previewEntity, previewPose.hold(preview.clip, preview.at))
            }
            is ModelPreview.Asset -> {
                val clip = preview.clip
                val pose = if (clip == null) previewPose.set(null, Tick.ZERO, 0f) else previewPose.hold(clip, preview.at)
                seen.showAsset(preview.model, pose, preview.turnDegrees, orbit)
            }
        }
        return seen.image
    }

    /**
     * Writes into [out] the joints of the skinned model drawn this frame for Fleks entity [entity],
     * as [view] shows it - its preview node, when [view] is previewing that entity - or as the
     * capturable frame shows it when [view] is `null` (issue #243). Render thread, after this
     * frame's [add]s and [imageFor]s.
     *
     * Each joint's position is where Kool's skinning puts it: the skinned mesh's model matrix, times
     * the joint's skinning matrix, times its bind matrix, applied to the origin. That is the matrix
     * the GPU moves the joint's vertices by, so a joint sits where its part of the mesh is drawn.
     *
     * @return false, leaving [out] empty, when nothing was drawn for [entity] or what was has no skin.
     */
    fun skeleton(entity: Int, view: WorldViewport?, out: ModelSkeleton): Boolean {
        out.clear()
        val seen = view?.let { views[it] }
        // A model shown on its own hides every entity's from that view, so none has a skeleton there.
        if (seen != null && seen.showingAsset) return false
        val placed = seen?.previewFor(entity) ?: drawnFor(entity) ?: return false
        return placed.writeSkeleton(out)
    }

    /**
     * Writes into [out] where node [node] of the model drawn this frame for Fleks entity [entity]
     * is, in the world's frame, and answers true; false, leaving [out] alone, when that entity
     * drew no imported model this frame or the model has no such node (issue #260).
     *
     * This is the *live* node: an animated bone or a turning mount is where the animation has put
     * it this frame, so a part mounted on it follows the animation - which the simulation, which
     * has the rest pose and no keyframes, cannot do. Kool computes model matrices when it next
     * updates the scene, so the node's are brought up to this frame's placement first.
     *
     * Render thread, after the parent's own [add] this frame.
     */
    fun socket(entity: Int, node: Int, out: MutableMat4f): Boolean {
        if (node < 0) return false
        val placed = drawnFor(entity) ?: return false
        val koolNode = placed.nodeAt(node) ?: return false
        placed.refreshMatrices()
        // The node's matrix is in the file's frame, which is drawn a quarter turn about X; turning
        // it back is what makes a socket read the way `Transform3D` does.
        out.set(koolNode.modelMatF).rotate(Z_UP_TO_Y_UP, Vec3f.X_AXIS)
        return true
    }

    /** The node [add] showed for [entity] this frame, or `null`. A scan: once per preview per frame. */
    private fun drawnFor(entity: Int): Placed? {
        if (entity == NO_ENTITY) return null
        for (index in drawnNodes.indices) if (drawnEntities[index] == entity) return drawnNodes[index]
        return null
    }

    /**
     * The Kool camera [view]'s projection asks for, pointed the way [view] says, Z up (issue #257).
     *
     * An orthographic camera's left and right edges are left to Kool: `isKeepAspectRatio` is on by
     * default, so it widens the box to the viewport's shape every frame, which is what keeps a
     * picture that is resized from stretching.
     */
    private fun aim(view: ModelCamera): Camera = when (view.projection) {
        ModelProjection.Perspective -> perspective.also {
            place(it, view)
            it.fovY = view.fovYDegrees.deg
        }
        ModelProjection.Orthographic -> orthographic.also {
            place(it, view)
            it.setCentered(view.viewHeight, view.near, view.far)
        }
    }

    /** Points [camera] at what [view] looks at, from where [view] is, Z up. */
    private fun place(camera: Camera, view: ModelCamera) {
        eye.set(view.eyeX, view.eyeY, view.eyeZ)
        target.set(view.targetX, view.targetY, view.targetZ)
        camera.setupCamera(position = eye, up = Vec3f.Z_AXIS, lookAt = target)
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
        entity: Int = NO_ENTITY,
        mask: Boolean = false,
    ) {
        // Translate, then turn about Z, Y, X - so X is applied to the model first - then scale.
        matrix.place(x, y, z, rotationX, rotationY, rotationZ, axisScale.set(scaleX, scaleY, scaleZ))
        show(source, pose, entity, mask)
    }

    /**
     * Draws [source] at [world] this frame, in [pose]: [add] with the transform already built,
     * which is what a part mounted on a socket has (issue #260). Render thread only.
     */
    fun addAt(
        source: ModelSource,
        world: Mat4f,
        pose: ClipPose,
        entity: Int = NO_ENTITY,
        mask: Boolean = false,
    ) {
        matrix.set(world)
        show(source, pose, entity, mask)
    }

    /**
     * Draws [source] at whatever [matrix] holds, in [pose], for Fleks entity [entity].
     *
     * [mask] puts what is drawn into the object mask a screen shader reads (issue #266). A
     * built-in shape goes in through its own run, because instances of one run share a mesh and
     * the mask is filtered per Kool node: two entities with the same shape and material but
     * different answers are two runs, which is why [runFor] is keyed on the flag as well.
     */
    private fun show(source: ModelSource, pose: ClipPose, entity: Int, mask: Boolean) {
        when (source) {
            is MeshModel -> {
                val run = runFor(source.mesh, source.material, mask)
                run.instances.addInstance(writeMatrix)
                run.mesh.isVisible = true
                if (mask) maskedNodes += run.mesh
            }
            // The file is Y-up: turned onto the world's Z-up before anything else is applied.
            is ImportedModel -> {
                val placed = importsFor(source).show(matrix.rotate(Y_UP_TO_Z_UP, Vec3f.X_AXIS), pose)
                if (mask) maskedNodes += placed.node
                if (drawnNodes.size == drawnEntities.size) drawnEntities = drawnEntities.copyOf(drawnEntities.size * 2)
                drawnEntities[drawnNodes.size] = entity
                drawnNodes += placed
            }
        }
    }

    /**
     * The run for this shape, material and mask answer, made the first time it is asked for.
     *
     * Three keys rather than two: a run is one Kool mesh with one instance list, and the mask pass
     * filters whole meshes, so a shape drawn both in and out of the mask has to be two meshes. A
     * game that never sets the flag has exactly the runs it had before issue #266.
     */
    private fun runFor(mesh: ModelMesh, material: ModelMaterial, mask: Boolean): Run {
        val byMaterial = runs.getOrPut(mesh) { HashMap() }
        val slots = byMaterial.getOrPut(material) { arrayOfNulls(MASK_ANSWERS) }
        val slot = if (mask) MASKED else UNMASKED
        slots[slot]?.let { return it }
        val run = Run(mesh, material, allRuns.size)
        run.shader.ambientFactor = ambient
        slots[slot] = run
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

        val config = gltfLoadConfig(model, listOf(shadow))

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
        fun show(transform: MutableMat4f, pose: ClipPose): Placed {
            val placed = if (shown < nodes.size) nodes[shown] else make()
            shown++
            placed.node.transform.setMatrix(transform)
            placed.node.applyPose(pose)
            placed.node.isVisible = true
            placed.placed()
            return placed
        }

        private fun make(): Placed = Placed(model, model.gltf.makeModel(config).withOwnShadowSkins()).also { placed ->
            placed.setAmbient()
            nodes += placed
            drawNode.addNode(placed.node)
        }
    }

    /**
     * One scene node made from a glTF file, with its shaders listed once so that a frame sets
     * their ambient light without walking Kool's maps.
     */
    private inner class Placed(val model: ImportedModel, val node: KoolModel) {
        private val shaders = node.meshes.values.mapNotNull { it.shader as? KslPbrShader }

        /** The skin's joints, read the first time a skeleton is asked for: most nodes never are. */
        private val joints: List<SkinJoint> by lazy { skinJointsOf(node, model.gltf) }

        /**
         * The file's node index to Kool's node, looked up once each: Kool keys its nodes by name,
         * and the file's index is what an `AttachedTo` carries, so the name is read off the parsed
         * file. A miss is remembered too - a node with no name, or one Kool did not keep - so a
         * mount on a node that is not there costs one map lookup a frame rather than a scan.
         */
        private val nodesByIndex = HashMap<Int, Node?>()

        /** Whether Kool's model matrices are older than this node's current placement. */
        private var matricesStale = true

        /** Kool's node for the file's node [index], or `null` if this model has none. */
        fun nodeAt(index: Int): Node? {
            if (index in nodesByIndex) return nodesByIndex[index]
            val found = model.gltf.nodes.getOrNull(index)?.name?.let { node.nodes[it] }
            nodesByIndex[index] = found
            return found
        }

        /** Says this node has just been placed, so its model matrices are a frame behind. */
        fun placed() {
            matricesStale = true
        }

        /** Brings Kool's model matrices up to this frame's placement, at most once per placement. */
        fun refreshMatrices() {
            if (!matricesStale) return
            node.updateModelMatRecursive()
            matricesStale = false
        }

        fun setAmbient() {
            for (index in shaders.indices) shaders[index].ambientFactor = ambient
        }

        /** See [skeleton]. */
        fun writeSkeleton(out: ModelSkeleton): Boolean {
            val joints = joints
            if (joints.isEmpty()) return false
            // Kool computes model matrices when it next updates the scene, so until then they are
            // last frame's; this frame's transform was set by `show`, so bring them up to it.
            refreshMatrices()
            for (index in joints.indices) {
                val joint = joints[index]
                jointPosition.set(joint.bindOrigin)
                joint.skinNode.jointTransform.transform(jointPosition)
                joint.mesh.modelMatF.transform(jointPosition)
                out.add(jointPosition.x, jointPosition.y, jointPosition.z, joint.parent)
            }
            return true
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

        /** This view's own node for a preview, made the first time one is shown and kept for reuse. */
        private var preview: Placed? = null

        /** The simulated node this view hides while previewing its entity, or `null`. */
        private var hidden: Node? = null

        /** The entity [preview] stands in for, or [NO_ENTITY] for an asset preview or none. */
        private var previewEntity = NO_ENTITY

        /** Whether [preview] is a model shown on its own ([showAsset]), with every other model hidden. */
        var showingAsset = false
            private set

        private val placing = MutableMat4f()

        /** The preview node's extent at rest in its own frame, measured when it is made. */
        private val previewRest = BoundingBoxF()
        private val scaling = MutableVec3f()

        init {
            // Everything but the node this view hides, and no preview node but its own.
            // A model shown on its own is shown alone: every other model under the draw node is left out.
            pass.defaultView.drawFilter = { node ->
                node === preview?.node ||
                    (node !== hidden && node !in previewNodes && !(showingAsset && node.parent === drawNode))
            }
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

        /** Shows the world as simulated: the preview node hidden, nothing filtered out. */
        fun clearPreview() {
            preview?.node?.isVisible = false
            hidden = null
            previewEntity = NO_ENTITY
            showingAsset = false
        }

        /** Stands a node of this view's own in for [real], drawn for [entity], in [pose]. */
        fun showPose(real: Placed, entity: Int, pose: ClipPose) {
            val shown = previewNode(real.model)
            shown.node.transform.setMatrix(real.node.transform.matrixF)
            shown.node.applyPose(pose)
            shown.node.isVisible = true
            hidden = real.node
            previewEntity = entity
            showingAsset = false
        }

        /**
         * Shows [model] on its own at [orbit]'s centre, turned [turnDegrees] about Z, in [pose],
         * scaled so its largest side is [ASSET_FILL] of the view's height at the orbit's distance, or
         * of its width in a view narrower than it is tall.
         */
        fun showAsset(model: ImportedModel, pose: ClipPose, turnDegrees: Float, orbit: ModelCamera) {
            val shown = previewNode(model)
            val bounds = previewRest
            val largest = if (bounds.isEmpty) 0f else maxOf(bounds.size.x, bounds.size.y, bounds.size.z)
            val dx = orbit.eyeX - orbit.targetX
            val dy = orbit.eyeY - orbit.targetY
            val dz = orbit.eyeZ - orbit.targetZ
            val distance = sqrt(dx * dx + dy * dy + dz * dz)
            // The view is 2 d tan(fov / 2) high at the orbit's distance d, and its aspect times that
            // wide: the model fits the narrower of the two.
            val viewHeight = 2f * distance * tan(orbit.fovYDegrees.deg.rad / 2f)
            val wanted = ASSET_FILL * viewHeight * minOf(1f, width.toFloat() / height)
            val scale = if (largest > 0f) wanted / largest else 1f
            placing.setIdentity()
                .translate(orbit.targetX, orbit.targetY, orbit.targetZ)
                .rotate(turnDegrees.deg, Vec3f.Z_AXIS)
                .scale(scaling.set(scale, scale, scale))
                .rotate(Y_UP_TO_Z_UP, Vec3f.X_AXIS)
            if (!bounds.isEmpty) placing.translate(-bounds.center.x, -bounds.center.y, -bounds.center.z)
            shown.node.transform.setMatrix(placing)
            shown.node.applyPose(pose)
            shown.node.isVisible = true
            hidden = null
            previewEntity = NO_ENTITY
            showingAsset = true
        }

        fun setPreviewAmbient() {
            preview?.setAmbient()
        }

        /** This view's preview node when it stands in for [entity], or `null`. */
        fun previewFor(entity: Int): Placed? = if (entity != NO_ENTITY && entity == previewEntity) preview else null

        /** This view's preview node for [model], made - or remade, for another model - on demand. */
        private fun previewNode(model: ImportedModel): Placed {
            preview?.let { if (it.model === model) return it }
            dropPreview()
            val made = Placed(model, model.gltf.makeModel(importsFor(model).config).withOwnShadowSkins())
            made.setAmbient()
            restBoundsOf(made.node, previewRest)
            previewNodes += made.node
            drawNode.addNode(made.node)
            preview = made
            return made
        }

        private fun dropPreview() {
            val old = preview ?: return
            preview = null
            previewNodes.remove(old.node)
            drawNode.removeNode(old.node)
            old.node.release()
        }

        fun release() {
            if (released) return
            released = true
            passes.remove(pass)
            pass.release()
            dropPreview()
        }
    }

    /** Takes this stage's passes off the scene and releases them, meshes and shaders with the draw node. */
    override fun release() {
        for (seen in views.values) seen.release()
        views.clear()
        passes.remove(pass)
        passes.remove(shadow)
        passes.remove(maskPass)
        // Before the pass it copies: a frame copy holds the textures the pass resolves into.
        depthCopy.release()
        pass.release()
        shadow.release()
        maskPass.release()
    }

    private companion object {
        /** Whether a run is in the object mask; [runFor]'s third key. */
        const val MASK_ANSWERS = 2
        const val UNMASKED = 0
        const val MASKED = 1

        /** Texels along each side of the shadow map: sharp shadow edges at a modest memory cost. */
        const val SHADOW_MAP_SIZE = 2048

        /** No entity: what `add` is told for a model drawn for none, and what matches no preview. */
        const val NO_ENTITY = -1

        /** Room for this many imported nodes a frame before the entity list grows. */
        const val INITIAL_DRAWN = 16

        /** A model shown on its own has its largest side this share of the view's height. */
        const val ASSET_FILL = 0.75f
    }
}

/**
 * Writes into [out] the extent of [node]'s meshes at rest, in [node]'s own frame: each mesh's
 * geometry bounds through the node tree below [node], with [node] itself unplaced.
 *
 * Not Kool's own `Node.bounds`: read on a view's preview node it stayed empty frame after frame, so
 * the preview was drawn at the file's own size (issue #243). Nor a glTF mesh's `geometryBounds` as
 * it comes: empty on the Fox's mesh, so each is computed from its vertices here, once per node.
 */
internal fun restBoundsOf(node: KoolModel, out: BoundingBoxF) {
    out.clear()
    node.updateModelMatRecursive()
    val toNode = MutableMat4f()
    node.modelMatF.invert(toNode)
    val meshToNode = MutableMat4f()
    val corner = MutableVec3f()
    for (mesh in node.meshes.values) {
        mesh.updateGeometryBounds()
        val bounds = mesh.geometryBounds
        if (bounds.isEmpty) continue
        meshToNode.set(toNode).mul(mesh.modelMatF)
        for (index in 0 until BOX_CORNERS) {
            corner.set(
                if (index and 1 == 0) bounds.min.x else bounds.max.x,
                if (index and 2 == 0) bounds.min.y else bounds.max.y,
                if (index and 4 == 0) bounds.min.z else bounds.max.z,
            )
            out.add(meshToNode.transform(corner, 1f))
        }
    }
}

private const val BOX_CORNERS = 8

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
    // A socket is an empty node - a Blender Empty, a bone tip - and Kool's loader drops every
    // node that has no mesh and no children unless it is told not to (`removeEmptyNodes`
    // defaults to true in 0.19.0). Dropping them would delete every mounting point in the file
    // before anything could be mounted on one (issue #260).
    removeEmptyNodes = false,
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
