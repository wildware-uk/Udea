package dev.wildware.udea.core.module

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.ServiceKey
import dev.wildware.udea.core.serviceKey

/** One system in a resolved order, as an agent and a golden file see it. */
public data class SystemManifestEntry(
    public val phase: SimPhase,
    /** Fully qualified system class name. */
    public val name: String,
    /** The `before(...)` constraints it declared, fully qualified, in declaration order. */
    public val before: List<String>,
    /** The `after(...)` constraints it declared, fully qualified, in declaration order. */
    public val after: List<String>,
)

/**
 * The resolved running order of a world's simulation systems.
 *
 * Two jobs, and the second is why it is a value rather than a log line. An agent asking "what
 * runs this tick, in what order" gets an answer without a debugger — the MCP introspection the
 * spec's `/health` and describe tools need. And [render] is stable text, so the order can be
 * pinned by a checked-in golden file: a module that quietly changes when it runs then fails a
 * diff instead of changing behaviour silently.
 *
 * `world.systemManifest()` reads it back off the context the world was built with.
 */
public class SystemManifest(
    /** Every system in execution order. */
    public val entries: List<SystemManifestEntry>,
) {

    public val size: Int get() = entries.size

    /** The systems in [phase], in execution order. */
    public fun inPhase(phase: SimPhase): List<SystemManifestEntry> = entries.filter { it.phase == phase }

    /**
     * The manifest as stable, diffable text: one line per system, LF-terminated.
     *
     * `<phase> <name>` plus any constraints, so a golden diff points at the system that moved
     * rather than at an opaque hash.
     */
    public fun render(): String = buildString {
        for (entry in entries) {
            append(entry.phase.name).append(' ').append(entry.name)
            if (entry.before.isNotEmpty()) append(" before=").append(entry.before.joinToString(","))
            if (entry.after.isNotEmpty()) append(" after=").append(entry.after.joinToString(","))
            append('\n')
        }
    }

    override fun toString(): String = "SystemManifest(${entries.size} systems)"

    public companion object {
        /**
         * The key a built world's manifest is registered under.
         *
         * A [ServiceKey] rather than a field on `GameContext`: the context holds a small fixed
         * set of engine services, and its documented extension point is exactly this.
         */
        public val KEY: ServiceKey<SystemManifest> = serviceKey("SystemManifest")
    }
}

/**
 * The resolved system order of this world.
 *
 * @throws dev.wildware.udea.core.MissingServiceException if the world was configured by hand
 *   rather than built from a [UdeaGameDef], in which case there is no declared order to
 *   report — `world.systems` is then the only truth and it carries no phases.
 */
public fun World.systemManifest(): SystemManifest =
    inject<GameContext>(GameContext.INJECT_NAME)[SystemManifest.KEY]
