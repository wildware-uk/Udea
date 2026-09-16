package dev.wildware.udea.core.level

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Snapshot
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.HandleState
import dev.wildware.udea.core.identity.NetId
import kotlin.reflect.KClass
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/**
 * The first thing read from a level file, and the only thing read before its version is known.
 *
 * Decoded on its own, with unknown keys ignored, so a file from a future format is refused by
 * number with [LevelFormatException] instead of failing somewhere inside a layout this build
 * cannot parse.
 */
@Serializable
internal class LevelHeader(val formatVersion: Int)

/**
 * The whole of a `.udealevel` file.
 *
 * ## What is in it, and why each part
 *
 * - [world] - Fleks' own `world.snapshot()`, unchanged in shape. `loadSnapshot` recreates every
 *   entity with its exact id and version, so `Entity` and `NetId` values held inside components
 *   stay correct with no remapping.
 * - [netIds] and [handles] - which `NetId` names which entity, and the allocator's free queue and
 *   watermarks. `NetId` is not a component, so Fleks' snapshot does not carry it, and a load that
 *   only rebuilt the bindings would hand the next spawn an id a saved component already refers to.
 * - [tick] and [rng] - the clock and every named random stream. A saved component holds absolute
 *   ticks (a respawn's ready tick, an effect's start, a cooldown), so a level loaded onto a
 *   different clock is not the level that was saved: every timer in it would be wrong by the
 *   difference.
 * - [sections] - each module's [LevelSection], by name, each encoded on its own so a reader that
 *   does not know a section can skip it whole.
 *
 * Field names are written into the file (CBOR maps), which is what lets a component gain a field
 * and still load an old level with that field's default, or lose one and skip it.
 */
@Serializable
internal class LevelDocument(
    val formatVersion: Int,
    val tick: Tick,
    val rng: LongArray,
    val world: Map<Entity, Snapshot>,
    val netIds: List<LevelNetId>,
    val handles: LevelHandles,
    val sections: Map<String, ByteArray>,
)

/** A section's state decoded from a level, paired with the section that puts it back. */
internal class LoadedSection<T>(private val section: LevelSection<T>, private val value: T) {
    fun load(world: World) {
        section.load(world, value)
    }
}

/** One `NetId` binding. */
@Serializable
internal class LevelNetId(val entity: Entity, val netId: NetId)

/** [HandleState], as data. */
@Serializable
internal class LevelHandles(
    val nextFresh: Int,
    val highWater: Int,
    val freeIndices: IntArray,
    val freeGenerations: IntArray,
) {
    fun toHandleState(): HandleState = HandleState().also { state ->
        state.nextFresh = nextFresh
        state.highWater = highWater
        for (position in freeIndices.indices) state.addFree(freeIndices[position], freeGenerations[position])
    }

    companion object {
        fun of(state: HandleState): LevelHandles = LevelHandles(
            nextFresh = state.nextFresh,
            highWater = state.highWater,
            freeIndices = IntArray(state.freeCount) { state.freeIndexAt(it) },
            freeGenerations = IntArray(state.freeCount) { state.freeGenerationAt(it) },
        )
    }
}

/**
 * The CBOR codec a level is written and read with, built once from the saveable components and
 * the modules' live-object references.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class LevelFormat(modules: List<LevelComponentModule>, hooks: LevelHooks) {

    private val byClass: Map<KClass<*>, LevelComponent<*>> = buildMap {
        for (module in modules) {
            for (component in module.components) {
                val previous = put(component.type, component)
                require(previous == null) {
                    "${component.type.qualifiedName} is listed as a level component by more than one " +
                        "module, the second being '${module.moduleName}'"
                }
            }
        }
    }

    private val cbor: Cbor = Cbor {
        ignoreUnknownKeys = true
        // Written even when equal to the default: a level is saved content, and a default that
        // changes in code must not silently change what an old level loads as.
        encodeDefaults = true
        serializersModule = SerializersModule {
            polymorphic(Component::class) {
                for (component in byClass.values.sortedBy(LevelComponent<*>::serialName)) {
                    component.registerInto(this)
                }
            }
            hooks.registerReferences(this)
        }
    }

    fun encode(document: LevelDocument): ByteArray = cbor.encodeToByteArray(LevelDocument.serializer(), document)

    fun decodeHeader(bytes: ByteArray): LevelHeader = cbor.decodeFromByteArray(LevelHeader.serializer(), bytes)

    fun decode(bytes: ByteArray): LevelDocument = cbor.decodeFromByteArray(LevelDocument.serializer(), bytes)

    fun <T> encodeSection(section: LevelSection<T>, value: T): ByteArray =
        cbor.encodeToByteArray(section.serializer, value)

    fun <T> decodeSection(section: LevelSection<T>, bytes: ByteArray): LoadedSection<T> =
        LoadedSection(section, cbor.decodeFromByteArray(section.serializer, bytes))

    /** The name [component] is saved under, or null when no module lists its class. */
    fun serialNameOf(component: Component<*>): String? = byClass[component::class]?.serialName

    companion object {
        /** Bumped when a change to [LevelDocument] cannot be read by the previous reader. */
        const val VERSION: Int = 1
    }
}
