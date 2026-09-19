package dev.wildware.udea.render.model

import dev.wildware.udea.render.draw.SpriteTexture

/**
 * How a model's surface looks: a physically based material, drawn by Kool's PBR shader.
 *
 * Every property here reaches the shader; there is no parameter that is accepted and ignored.
 *
 * The material does not own [albedo]. A texture is released by whoever made it - typically a
 * system's `RenderResources.own` - because one texture may serve several materials.
 *
 * Materials are compared by identity: two materials with the same values are still two shaders,
 * so make one and share it.
 */
public class ModelMaterial(
    /**
     * The surface colour, top row first, as sRGB: the shader linearises it before lighting.
     * Sampled nearest and clamped, as every [SpriteTexture] is.
     */
    public val albedo: SpriteTexture,
    /** 0 is a mirror-sharp highlight, 1 is chalk. */
    public val roughness: Float = 0.5f,
    /** 0 is a dielectric (plastic, stone, wood), 1 is bare metal. */
    public val metallic: Float = 0f,
) {

    init {
        require(roughness in 0f..1f) { "roughness must be in [0, 1], was $roughness" }
        require(metallic in 0f..1f) { "metallic must be in [0, 1], was $metallic" }
    }

    override fun toString(): String = "ModelMaterial($albedo, roughness=$roughness, metallic=$metallic)"
}
