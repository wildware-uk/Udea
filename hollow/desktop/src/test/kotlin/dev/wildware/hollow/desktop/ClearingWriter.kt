package dev.wildware.hollow.desktop

import dev.wildware.hollow.HollowGame
import dev.wildware.hollow.Scenery
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.spatial.Transform3D
import java.io.File

/**
 * `sh gradlew :hollow:desktop:udeaWriteClearing`: builds [ClearingLayout] in a headless Hollow world
 * and saves it, through the game's own `LevelService`, as the `.udealevel` named by the first
 * argument. Every entity gets a `Transform3D` and a `NetId`; each prop a `Scenery`, and the sun a
 * `Sunlight`.
 */
internal object ClearingWriter {

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(requireNotNull(args.firstOrNull()) { "usage: ClearingWriter <out.udealevel>" })
        // The level this host would play is never loaded: the writer makes one, it does not play
        // one. Empty bytes, because the file it would otherwise read is the file being written.
        val opened = HollowGame.build(RenderMode.Headless, level = ByteArray(0))
        val host = opened.host
        val netIds = host.ctx[CoreModule.NET_IDS]
        val world = host.world
        for (placed in ClearingLayout.props()) {
            val entity = world.entity {
                it += Transform3D(
                    x = placed.x,
                    y = placed.y,
                    rotationZ = placed.heading,
                    scaleX = placed.scale,
                    scaleY = placed.scale,
                    scaleZ = placed.scale,
                )
                it += Scenery(placed.prop)
            }
            netIds.allocate(entity)
        }
        val sun = world.entity {
            it += Transform3D(z = ClearingLayout.SUN_HEIGHT)
            it += ClearingLayout.SUN
        }
        netIds.allocate(sun)
        val bytes = host.game.levels.saveNow()
        out.parentFile.mkdirs()
        out.writeBytes(bytes)
        println("[hollow.clearing] ${world.numEntities} entities, ${bytes.size} bytes -> ${out.absolutePath}")
        opened.close()
    }
}
