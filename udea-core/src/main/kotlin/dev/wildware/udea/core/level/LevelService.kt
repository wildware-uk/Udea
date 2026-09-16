package dev.wildware.udea.core.level

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Snapshot
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.HandleState
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.loop.BarrierAction
import dev.wildware.udea.core.loop.SimBarrier
import dev.wildware.udea.core.rng.CapturableRng
import kotlinx.serialization.SerializationException

/**
 * Saves a whole world to a level file and loads one back exactly (issue #191).
 *
 * ## A level is saved content, not a runtime snapshot
 *
 * Rewind, replication and desync reports stay on `Replicator<T>` and the snapshot ring: they run
 * every few ticks, allocation-free, over a registry of lowered fields. A level is written when a
 * person or an agent asks for one, is kept on disk across builds, and has to survive a component
 * gaining or losing a field - so it is Fleks' own `world.snapshot()`, encoded with kotlinx CBOR,
 * which stores field names. The two never share a code path, and `AGENTS.md` states the split.
 *
 * ## Saving
 *
 * [save] refuses, with [LevelSaveException] naming the class, a world holding any component no
 * generated [LevelComponentModule] lists - one that is not `@Serializable`, or whose module does
 * not run `udea-codegen`. It never drops one: a level that silently lost a component is a level
 * that loads into a different game. Entity tags are refused the same way, because Fleks' tags are
 * not listed anywhere a level could name them from.
 *
 * A save is queued on the [SimBarrier] like a load, for the reason [save] gives, so it is safe to
 * ask for from any thread; its result arrives when the barrier drains.
 *
 * ## Loading
 *
 * [read] decodes and validates the whole file first, so every way a file can be wrong is found
 * before the world is touched. [load] then queues the replacement on the [SimBarrier]; it applies
 * at the top of the next `step()`, or at the next explicit drain, and no system observes the world
 * half-loaded. Applying restores the entities, rebuilds the `NetIdIndex` (which `loadSnapshot`
 * cannot fill, because a `NetId` is not a component), moves the clock and the random streams,
 * rebuilds physics bodies from their components, and finally puts back every module's [LevelSection].
 */
public class LevelService internal constructor(
    private val world: World,
    private val ctx: GameContext,
    private val netIds: NetIdIndex,
    private val hooks: LevelHooks,
    modules: () -> List<LevelComponentModule>,
) {

    // Lazy, so building a game - which every test does, many times - never pays for a
    // serializers module it will not use.
    private val format: LevelFormat by lazy { LevelFormat(modules(), hooks) }

    private val streams: CapturableRng
        get() = ctx.rng as? CapturableRng ?: throw LevelSaveException(
            "levels carry the random streams, and ${ctx.rng::class.simpleName} does not implement " +
                "CapturableRng",
        )

    /**
     * Queues a save of the world on [barrier].
     *
     * Through the barrier, and not a direct read, because between two ticks the world is not yet
     * the world the next tick will see: a spawn requested during the last tick is still queued on
     * the barrier. A level saved beside it would load a match missing that entity, and the loaded
     * match would part from the saved one on its very next tick. Queued behind it, the save sees the
     * world the next `step()` would have started from.
     *
     * @return the queued action. Its [LevelAction.outcome] carries the level bytes once drained,
     *   or a [LevelSaveException] naming the component or tag a level cannot carry.
     */
    public fun save(barrier: SimBarrier): LevelAction<ByteArray> =
        LevelAction("save level") { encode() }.also(barrier::submit)

    internal fun encode(): ByteArray {
        val format = format
        val live = world.snapshot()
        val entities = LinkedHashMap<Entity, Snapshot>(live.size)
        for (entity in live.keys.sortedBy(Entity::id)) {
            entities[entity] = saveable(format, entity, live.getValue(entity))
        }

        val bindings = ArrayList<LevelNetId>(entities.size)
        netIds.forEachLive { netId, entity -> bindings += LevelNetId(entity, netId) }

        val rng = streams
        val words = LongArray(rng.stateWords)
        rng.saveInto(words, 0)

        val handles = HandleState()
        netIds.saveInto(handles)

        val sections = LinkedHashMap<String, ByteArray>()
        for (section in hooks.sections) sections[section.name] = savedSection(format, section)

        val document = LevelDocument(
            formatVersion = LevelFormat.VERSION,
            tick = ctx.clock.tick,
            rng = words,
            world = entities,
            netIds = bindings,
            handles = LevelHandles.of(handles),
            sections = sections,
        )
        return format.encode(document)
    }

    private fun <T> savedSection(format: LevelFormat, section: LevelSection<T>): ByteArray =
        format.encodeSection(section, section.save(world))

    /**
     * Decodes and checks [bytes] without touching the world.
     *
     * @throws LevelFormatException if the bytes are not a level this build can load: another format
     *   version, a component class this game does not have, a live-object reference that does not
     *   match, or bindings and streams inconsistent with this game.
     */
    public fun read(bytes: ByteArray): Level {
        val format = format
        val header = decoding { format.decodeHeader(bytes) }
        if (header.formatVersion != LevelFormat.VERSION) {
            throw LevelFormatException(
                "level format version ${header.formatVersion} cannot be read by this build, which " +
                    "reads version ${LevelFormat.VERSION}",
            )
        }
        val document = decoding { format.decode(bytes) }
        validate(document)
        val sections = hooks.sections.map { section ->
            val saved = document.sections[section.name] ?: throw LevelFormatException(
                "level has no '${section.name}' section, which this game saves with every level",
            )
            decoding { format.decodeSection(section, saved) }
        }
        return Level(document, sections)
    }

    /**
     * Queues [level] to replace the world at the next drain of [barrier].
     *
     * @return the queued action, whose [LevelAction.outcome] completes with [level] once it applied.
     */
    public fun load(level: Level, barrier: SimBarrier): LevelAction<Level> =
        LevelAction("load level saved at ${level.tick}") { level.also(::apply) }.also(barrier::submit)

    internal fun apply(level: Level) {
        val document = level.document
        world.loadSnapshot(document.world)
        netIds.restoreFrom(document.handles.toHandleState())
        for (binding in document.netIds) netIds.bind(binding.entity, binding.netId)
        ctx.clock.moveTo(document.tick)
        streams.restoreFrom(document.rng, 0)
        // Box2D bodies are never level content, exactly as they are never snapshot content: a
        // body's handle is `@Transient` and the bodies come back from `PhysicsBody` and the shape
        // components in one deterministic pass, after every component is in place.
        ctx.physics.rebuildFrom(world, netIds)
        for (section in level.sections) section.load(world)
    }

    private fun saveable(format: LevelFormat, entity: Entity, snapshot: Snapshot): Snapshot {
        if (snapshot.tags.isNotEmpty()) {
            throw LevelSaveException(
                "entity $entity carries tag ${snapshot.tags.first()::class.qualifiedName}; level " +
                    "files do not carry Fleks entity tags",
            )
        }
        val named = ArrayList<Pair<String, Component<out Any>>>(snapshot.components.size)
        for (component in snapshot.components) {
            val name = format.serialNameOf(component) ?: throw LevelSaveException(
                "entity $entity holds ${component::class.qualifiedName}, which no generated level " +
                    "component list names. Mark it @Serializable in a module that runs " +
                    "udea-codegen, or keep it out of worlds that are saved as levels.",
            )
            named += name to component
        }
        // Fleks orders a snapshot's components by component-type id, which is assigned in the
        // order the types were first touched in this JVM. Sorting by saved name makes the file
        // depend on the world alone.
        named.sortBy { it.first }
        return Snapshot(named.map { it.second }, snapshot.tags)
    }

    private fun validate(document: LevelDocument) {
        val rng = streams
        if (document.rng.size != rng.stateWords) {
            throw LevelFormatException(
                "level carries ${document.rng.size} random-stream words and this game has ${rng.stateWords}",
            )
        }
        val handles = document.handles
        if (handles.freeIndices.size != handles.freeGenerations.size) {
            throw LevelFormatException(
                "level's NetId free queue has ${handles.freeIndices.size} indices and " +
                    "${handles.freeGenerations.size} generations",
            )
        }
        val capacity = netIds.capacity
        if (handles.nextFresh !in 0..capacity || handles.highWater !in 0..capacity) {
            throw LevelFormatException(
                "level's NetId watermarks (next ${handles.nextFresh}, high ${handles.highWater}) " +
                    "do not fit this game's $capacity ids",
            )
        }
        // A flag per index rather than a hash set: nothing here needs an order, and `validate`
        // runs on the simulation thread, where `udeaVerifyDeterminism` refuses a hash-ordered one.
        val claimed = BooleanArray(capacity)
        for (index in handles.freeIndices) {
            if (index !in 0 until capacity || claimed[index]) {
                throw LevelFormatException("level's NetId free queue holds index $index twice or out of range")
            }
            claimed[index] = true
        }
        val bound = LinkedHashSet<Entity>()
        for (binding in document.netIds) {
            val index = binding.netId.index
            if (binding.netId.isNone || index >= capacity || claimed[index]) {
                throw LevelFormatException("level binds ${binding.netId}, which is none, free, repeated or out of range")
            }
            claimed[index] = true
            if (binding.entity !in document.world || !bound.add(binding.entity)) {
                throw LevelFormatException(
                    "level binds ${binding.netId} to ${binding.entity}, which the level does not " +
                        "hold or binds twice",
                )
            }
        }
    }

    private inline fun <T> decoding(block: () -> T): T = try {
        block()
    } catch (failure: SerializationException) {
        throw LevelFormatException("not a level this game can load: ${failure.message}", failure)
    } catch (failure: IllegalArgumentException) {
        // A serializer that validates as it reads - `NetId.ofRaw`, a live-object reference that
        // does not match - reports through `require`.
        throw LevelFormatException("not a level this game can load: ${failure.message}", failure)
    }

    public companion object {
        /** The extension a level file is saved under: `<name>.udealevel`. */
        public const val FILE_EXTENSION: String = "udealevel"
    }
}

/** A decoded, checked level, ready to [LevelService.load]. */
public class Level internal constructor(
    internal val document: LevelDocument,
    internal val sections: List<LoadedSection<*>>,
) {

    /** The tick the level was saved at, and the tick the clock reads once it is loaded. */
    public val tick: Tick get() = document.tick

    /** How many entities loading it creates. */
    internal val entityCount: Int get() = document.world.size

    override fun toString(): String = "Level(tick=$tick, entities=$entityCount)"
}

/**
 * A level save or load queued on the barrier. See [LevelService.save] and [LevelService.load].
 *
 * One type for both because the two differ only in what they hand back, and a caller - a test, or
 * an editor tool waiting on a drain - asks each the same question: has it happened, and did it work.
 */
public class LevelAction<T : Any> internal constructor(
    override val label: String,
    private val work: () -> T,
) : BarrierAction {

    /** [LevelOutcome.Pending] until the barrier drains it. */
    public var outcome: LevelOutcome<T> = LevelOutcome.Pending
        private set

    override fun apply(world: World, ctx: GameContext) {
        outcome = try {
            LevelOutcome.Completed(work())
        } catch (thrown: Exception) {
            // Recorded and rethrown, never swallowed: the barrier still logs it under this label
            // and counts it, and the caller reads a typed failure rather than an action that
            // silently did not happen.
            outcome = LevelOutcome.Failed(thrown)
            throw thrown
        }
    }
}

/** Where a [LevelAction] has got to. */
public sealed interface LevelOutcome<out T : Any> {

    /** Still queued on the barrier. */
    public data object Pending : LevelOutcome<Nothing>

    /** Done: the saved bytes, or the level the world now is. */
    public class Completed<T : Any>(public val value: T) : LevelOutcome<T> {
        override fun toString(): String = "Completed($value)"
    }

    /** It threw [cause]; for a save, a [LevelSaveException] naming what could not be saved. */
    public class Failed(public val cause: Exception) : LevelOutcome<Nothing> {
        override fun toString(): String = "Failed($cause)"
    }
}

/** A world holds something a level file cannot carry. The message names it. */
public class LevelSaveException(message: String) : IllegalStateException(message)

/** Bytes that are not a level this game can load. */
public class LevelFormatException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
