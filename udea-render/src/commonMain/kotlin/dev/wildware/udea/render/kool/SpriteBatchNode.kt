package dev.wildware.udea.render.kool

import de.fabmax.kool.math.MutableMat4f
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.modules.ksl.KslUnlitShader
import de.fabmax.kool.modules.ksl.blocks.ColorBlockConfig
import de.fabmax.kool.modules.ksl.blocks.TexCoordAttributeBlock
import de.fabmax.kool.modules.ksl.lang.KslFloat1
import de.fabmax.kool.modules.ksl.lang.KslFloat2
import de.fabmax.kool.modules.ksl.lang.KslInterStageVector
import de.fabmax.kool.modules.ksl.lang.plus
import de.fabmax.kool.modules.ksl.lang.times
import de.fabmax.kool.modules.ksl.lang.xy
import de.fabmax.kool.modules.ksl.lang.zw
import de.fabmax.kool.pipeline.BlendMode
import de.fabmax.kool.pipeline.CullMethod
import de.fabmax.kool.pipeline.DepthCompareOp
import de.fabmax.kool.scene.InstanceLayouts
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.MeshInstanceList
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.VertexLayouts
import de.fabmax.kool.util.MemoryLayout
import de.fabmax.kool.util.Struct
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteBatch2D
import dev.wildware.udea.render.draw.SpriteRecord

/**
 * Turns a [SpriteRecord] - what a [SpriteBatch2D] recorded - into Kool meshes: one instanced quad
 * per run of draws that share a texture, in the order the runs were drawn.
 *
 * It syncs in its own `onUpdate`, which Kool runs while it collects the frame - after the render
 * pipeline has finished recording and before anything is drawn - so the meshes always show the
 * frame the pipeline just recorded, on the render thread, with no hand-off between threads.
 *
 * Meshes are pooled and never removed: a run that is not needed this frame is hidden, so a frame
 * that draws fewer runs than the last allocates nothing and a frame that draws more allocates only
 * the difference.
 */
internal class SpriteBatchNode(
    private val record: SpriteRecord,
    name: String,
) : Node(name) {

    private val meshes = ArrayList<RunMesh>()

    /** Reused per instance: the quad's model matrix. */
    private val model = MutableMat4f()

    init {
        onUpdate += { sync() }
    }

    /** Copies the record's runs into the pooled meshes. Render thread only. */
    fun sync() {
        val runs = record.runCount
        while (meshes.size < runs) {
            val mesh = RunMesh(meshes.size)
            meshes += mesh
            addNode(mesh.mesh)
        }
        for (run in meshes.indices) {
            val mesh = meshes[run]
            if (run >= runs) {
                mesh.mesh.isVisible = false
                mesh.instances.clear()
                continue
            }
            mesh.mesh.isVisible = true
            mesh.shader.colorMap = record.runTexture(run).kool()
            fill(mesh.instances, record.runStart(run), record.runEnd(run))
        }
    }

    private fun fill(instances: MeshInstanceList<SpriteInstanceLayout>, start: Int, end: Int) {
        instances.clear()
        val floats = record.floats
        val tints = record.tints
        instances.addInstances(end - start) { buffer ->
            for (index in start until end) {
                val at = index * SpriteBatch2D.FLOATS_PER_INSTANCE
                val x = floats[at + SpriteBatch2D.X]
                val y = floats[at + SpriteBatch2D.Y]
                val width = floats[at + SpriteBatch2D.WIDTH]
                val height = floats[at + SpriteBatch2D.HEIGHT]
                val originX = floats[at + SpriteBatch2D.ORIGIN_X]
                val originY = floats[at + SpriteBatch2D.ORIGIN_Y]
                val rotation = floats[at + SpriteBatch2D.ROTATION]
                // Unit quad -> scaled about its corner -> rotated about the origin -> placed.
                model.setIdentity()
                    .translate(x + originX, y + originY, 0f)
                    // `rotate(angle: AngleF, axis: Vec3f)`: Kool has no four-float overload, and
                    // an untyped `Float` there is exactly the bare-angle mistake `.deg` exists
                    // to rule out.
                    .rotate(rotation.deg, Vec3f.Z_AXIS)
                    .translate(-originX, -originY, 0f)
                    // No three-float overload: `scale(Vec3f)` or `scale(Float)` only.
                    .scale(Vec3f(width, height, 1f))
                val tint = Rgba(tints[index])
                buffer.put {
                    set(it.modelMat, model)
                    set(it.color, tint.r, tint.g, tint.b, tint.a)
                    set(
                        it.uvRect,
                        floats[at + SpriteBatch2D.U0],
                        floats[at + SpriteBatch2D.V0],
                        floats[at + SpriteBatch2D.DU],
                        floats[at + SpriteBatch2D.DV],
                    )
                }
            }
        }
    }

    /** One pooled run: a unit quad, its instance list and the shader that samples the run's texture. */
    private class RunMesh(index: Int) {
        val instances = MeshInstanceList(SpriteInstanceLayout)
        val shader = spriteShader()
        val mesh = Mesh(VertexLayouts.PositionTexCoord, instances, name = "sprite-run-$index").apply {
            shader = this@RunMesh.shader
            // Instanced meshes are not frustum checked by Kool anyway; a sprite is never culled
            // here because the pass camera is the whole target.
            isFrustumChecked = false
            generate {
                // The unit quad, counter-clockwise from the bottom left. The texture coordinate
                // runs top-down (v = 1 - y) so that `uvRect` can speak in top-left texels, the
                // convention `SpriteRegion` uses.
                val bottomLeft = geometry.addVertex {
                    set(it.position, 0f, 0f, 0f)
                    set(it.texCoord, 0f, 1f)
                }
                val bottomRight = geometry.addVertex {
                    set(it.position, 1f, 0f, 0f)
                    set(it.texCoord, 1f, 1f)
                }
                val topRight = geometry.addVertex {
                    set(it.position, 1f, 1f, 0f)
                    set(it.texCoord, 1f, 0f)
                }
                val topLeft = geometry.addVertex {
                    set(it.position, 0f, 1f, 0f)
                    set(it.texCoord, 0f, 0f)
                }
                geometry.addTriIndices(bottomLeft, bottomRight, topRight)
                geometry.addTriIndices(bottomLeft, topRight, topLeft)
            }
        }
    }

    companion object {

        /**
         * Kool's `KslUnlitShader`, instanced, sampling the run's texture and multiplying by the
         * instance tint, with one change: the texture coordinate is remapped per instance.
         *
         * `KslUnlitShader` reads a texture coordinate straight from the vertex; a sprite needs the
         * region of the atlas page it was cut from. The shader's `modelCustomizer` runs after the
         * program is built, so it appends one assignment to the vertex stage that overwrites the
         * interpolated coordinate with `uv * rect.zw + rect.xy`. The last write to a vertex
         * output is the one the fragment stage sees, so the rest of the shader is Kool's, unchanged.
         */
        fun spriteShader(): KslUnlitShader = KslUnlitShader {
            vertices { instancedModelMatrix() }
            color {
                // Gamma 1: a sprite's texels are the colours to draw, not sRGB to linearise.
                textureColor(gamma = 1f)
                instanceColor(SpriteInstanceLayout.color, blendMode = ColorBlockConfig.BlendMode.Multiply)
            }
            pipeline {
                // 2D: painter's order, no depth buffer, and a mirrored sprite is not a back face.
                cullMethod = CullMethod.NO_CULLING
                depthTest = DepthCompareOp.ALWAYS
                isWriteDepth = false
                blendMode = BlendMode.BLEND_MULTIPLY_ALPHA
            }
            modelCustomizer = {
                vertexStage {
                    // `KslShaderStage.main`'s scope only publishes its enclosing stage as
                    // `parentStage: KslShaderStage`; `instanceAttribFloat4`/`vertexAttribFloat2`
                    // are declared on `KslVertexStage` specifically. Held here, on the vertex
                    // stage's own receiver, rather than cast inside `main` from `parentStage`.
                    val stage = this
                    main {
                        val texCoords = checkNotNull(parentStage.findBlock<TexCoordAttributeBlock>()) {
                            "KslUnlitShader built no texture coordinate block to remap"
                        }
                        val sampled = texCoords.getTextureCoords()
                        @Suppress("UNCHECKED_CAST")
                        val uv = parentStage.interStageVars.first { it.output === sampled }
                            as KslInterStageVector<KslFloat2, KslFloat1>
                        val rect = stage.instanceAttribFloat4(SpriteInstanceLayout.uvRect.name)
                        val vertexUv = stage.vertexAttribFloat2(VertexLayouts.TexCoord.texCoord.name)
                        uv.input set rect.xy + vertexUv * rect.zw
                    }
                }
            }
        }
    }
}

/** Per-sprite instance data: Kool's model matrix and colour, and the texture rectangle. */
internal object SpriteInstanceLayout : Struct("udea_sprite_instance", MemoryLayout.TightlyPacked) {
    val modelMat = include(InstanceLayouts.ModelMat.modelMat)
    val color = include(InstanceLayouts.Color.color)

    /** `u0, v0, du, dv`: top-left corner and extent, in texture coordinates. */
    val uvRect = float4("udea_instattr_uv_rect")
}
