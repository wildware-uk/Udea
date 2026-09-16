package dev.wildware.udea.gas

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.level.LevelSection
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * An [Attributes] component's table, in a level file: its attribute names, checked on load and
 * resolved to the running game's own table (issue #191).
 *
 * The table is not level content - it is built from the game's modules at startup - but an
 * `AttributeVector` means nothing without it, because the vector is positional. So the level
 * records the names in index order, and a load refuses a level saved against any other table
 * rather than restoring one game's strength into another game's armour.
 */
internal class AttributeTableReference(private val table: AttributeTable) : KSerializer<AttributeTable> {

    private val names: List<String> = List(table.count) { table.nameOf(AttributeId(it)) }

    private val codec = ListSerializer(String.serializer())

    override val descriptor: SerialDescriptor = codec.descriptor

    override fun serialize(encoder: Encoder, value: AttributeTable) {
        if (value !== table) {
            throw SerializationException(
                "an Attributes component is indexed by a table this game did not build; a level " +
                    "can only reference the game's own table",
            )
        }
        encoder.encodeSerializableValue(codec, names)
    }

    override fun deserialize(decoder: Decoder): AttributeTable {
        val saved = decoder.decodeSerializableValue(codec)
        if (saved != names) {
            throw SerializationException(
                "the level was saved against attributes $saved, and this game declares $names",
            )
        }
        return table
    }
}

/** [HandleAllocator]'s state in a level file. */
@Serializable
internal class SavedEffectHandles(val next: Int, val liveCount: Int)

/**
 * The effect-handle counter, saved with a level.
 *
 * Not derivable from the world: handles are never reused, so the counter usually sits above the
 * highest handle any live effect holds. A load that reset it, or rebuilt it from the live effects,
 * would give the next effect applied a different handle from the one the saved match would have
 * given it - and a handle is replicated state, so the loaded match would diverge from the saved
 * one on its first cast.
 */
internal class EffectHandleSection(private val handles: HandleAllocator) : LevelSection<SavedEffectHandles> {

    override val name: String get() = "udea-gas.effectHandles"

    override val serializer: KSerializer<SavedEffectHandles> get() = SavedEffectHandles.serializer()

    override fun save(world: World): SavedEffectHandles = SavedEffectHandles(handles.next, handles.liveCount)

    override fun load(world: World, saved: SavedEffectHandles) {
        val state = HandleAllocatorState()
        state.next = saved.next
        state.liveCount = saved.liveCount
        handles.restoreFrom(state)
    }
}
