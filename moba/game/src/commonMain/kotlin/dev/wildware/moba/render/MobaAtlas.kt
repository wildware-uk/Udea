package dev.wildware.moba.render

import dev.wildware.moba.MobaAssets
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.draw.SpriteRegion
import dev.wildware.udea.render.draw.SpriteTexture

/**
 * The packed atlas, uploaded once and cut into the frames every renderer draws from.
 *
 * ## Why there is one of these and there used to be two
 *
 * `CharacterRenderSystem` and `VfxRenderSystem` each carried a private `loadFrames`, and the
 * second said so: *"A copy of `CharacterRenderSystem`'s loader, which is `private` to it.
 * Duplicated rather than shared because making it `internal` and hoisting the page cache is an
 * edit to `MobaScene.kt`."* Issue #212 is editing `MobaScene.kt` anyway - every draw call in it
 * changed - so the copy goes, and with it the "copy-pasted logic that differs only in a constant"
 * that `docs/engineering-standards.md` section 8 rejects. The two really did differ by one
 * constant: the character loader passed its pages through a linear filter and the effects loader
 * did not, and neither choice survives - [SpriteTexture] samples nearest for every texture it
 * makes, which is what the character sheets always wanted.
 */
internal object MobaAtlas {

    /**
     * Every atlas page uploaded, and every sheet's frames cut out of them.
     *
     * All pages, not only the ones the first frame draws from: a page the atlas declares and
     * nobody uploads is a "region on a page that was not loaded" failure the moment a unit enters
     * a state whose sheet landed there - a bug that appears seconds into a session rather than at
     * boot, which is the worst kind to attribute.
     *
     * @throws IllegalStateException when the atlas holds no regions at all. Loud, because the
     *   alternative - drawing nothing - is a bug that looks like art direction, and it means the
     *   pack and the graph disagree, which is a packer defect rather than an authoring one.
     */
    fun frames(resources: RenderResources): Map<AssetId, Array<SpriteRegion>> {
        val bundle = MobaAssets.bundle
        val atlas = bundle.atlas
        check(atlas.size > 0) {
            "the bundle packed no atlas regions at all, so there is nothing to draw; " +
                "`:moba:game:udeaPackBundle` reports the sheet count it packed"
        }
        val pages = List(atlas.pages.size) { page ->
            // The pixels are this function's to hand over and the texture's to keep:
            // `SpriteTexture.fromRgba` copies them, so the decode buffer is garbage as soon as
            // this returns rather than a native allocation somebody has to remember to free.
            resources.own(Png.texture(bundle.atlasPage(page), "moba-atlas-$page"))
        }
        return atlas.sheets.associate { sheet ->
            val id = AssetId(sheet)
            val regions = atlas.framesOf(id)
            check(regions.isNotEmpty()) {
                "the atlas names sheet " + sheet + " and holds no regions for it"
            }
            id to Array(regions.size) { at ->
                val region = regions[at]
                SpriteRegion(pages[region.page], region.x, region.y, region.width, region.height)
            }
        }
    }
}
