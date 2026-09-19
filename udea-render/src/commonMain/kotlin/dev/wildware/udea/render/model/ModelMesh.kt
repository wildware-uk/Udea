package dev.wildware.udea.render.model

import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.scene.geometry.MeshBuilder
import de.fabmax.kool.scene.VertexLayouts

/**
 * The shape of a model: which geometry a [ModelRenderer] draws.
 *
 * Each shape is built by Kool's own mesh builder the first time it is drawn, with normals for the
 * lighting and texture coordinates for the material's albedo. Two equal shapes are one geometry:
 * the renderer keys its meshes by value, so a thousand `box(1f, 1f, 1f)`s are one mesh drawn a
 * thousand times.
 *
 * The axes are the world's, and the world is **Z-up** (see `Transform3D`): a box's `sizeZ` is its
 * height, and a [plane] lies flat on the ground facing up.
 */
public sealed class ModelMesh {

    /** Writes this shape into [builder]. Render thread only. */
    internal abstract fun build(builder: MeshBuilder<VertexLayouts.PositionNormalTexCoord>)

    internal data class Box(val sizeX: Float, val sizeY: Float, val sizeZ: Float) : ModelMesh() {
        override fun build(builder: MeshBuilder<VertexLayouts.PositionNormalTexCoord>) {
            // Kool's cube: centred on the origin, the whole texture on each of the six faces.
            builder.cube {
                size.set(sizeX, sizeY, sizeZ)
            }
        }
    }

    internal data class Sphere(val radius: Float, val steps: Int) : ModelMesh() {
        override fun build(builder: MeshBuilder<VertexLayouts.PositionNormalTexCoord>) {
            builder.uvSphere {
                radius = this@Sphere.radius
                steps = this@Sphere.steps
            }
        }
    }

    internal data class Plane(val sizeX: Float, val sizeY: Float) : ModelMesh() {
        override fun build(builder: MeshBuilder<VertexLayouts.PositionNormalTexCoord>) {
            builder.grid {
                sizeX = this@Plane.sizeX
                sizeY = this@Plane.sizeY
                // X across, Y along: the grid's normal is X cross Y, which is +Z - up.
                xDir.set(Vec3f.X_AXIS)
                yDir.set(Vec3f.Y_AXIS)
            }
        }
    }

    public companion object {

        /** A box centred on the origin, [sizeX] by [sizeY] on the ground and [sizeZ] tall. */
        public fun box(sizeX: Float, sizeY: Float, sizeZ: Float): ModelMesh {
            require(sizeX > 0f && sizeY > 0f && sizeZ > 0f) { "a box is ${sizeX}x${sizeY}x$sizeZ" }
            return Box(sizeX, sizeY, sizeZ)
        }

        /**
         * A sphere centred on the origin.
         *
         * @param steps rings from pole to pole; more is rounder and costs more triangles.
         */
        public fun sphere(radius: Float, steps: Int = 32): ModelMesh {
            require(radius > 0f) { "a sphere's radius is $radius" }
            require(steps >= 3) { "a sphere needs at least 3 steps, was $steps" }
            return Sphere(radius, steps)
        }

        /** A flat rectangle on the ground plane, centred on the origin and facing up (+Z). */
        public fun plane(sizeX: Float, sizeY: Float): ModelMesh {
            require(sizeX > 0f && sizeY > 0f) { "a plane is ${sizeX}x$sizeY" }
            return Plane(sizeX, sizeY)
        }
    }
}
