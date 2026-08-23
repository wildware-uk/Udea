package dev.wildware.udea.agent.state

/**
 * The Tier-0 digest budgets, in one place so changing one is a diff a reviewer sees.
 *
 * ## The arithmetic these numbers come from
 *
 * The reference implementation inlined every entity into its state document every frame -
 * `DebugInspector.kt:466` writes about twenty fields per body. At MOBA scale that is roughly
 * 80KB of JSON, rebuilt on the simulation thread every tick and re-read on every agent poll.
 * Two separate costs, and the second is the one that decides whether a debugging session fits
 * in one context window:
 *
 * | document | bytes | tokens per poll |
 * |---|---|---|
 * | every entity inlined | ~80KB | ~20 000 |
 * | Tier-0 digest | ~2KB | ~500 |
 * | a targeted `query_entities` | ~600B | ~150 |
 *
 * So `/state` is a capped always-on summary and **all** entity detail moves behind an explicit
 * query. That is what [MAX_BYTES] and the per-section caps below enforce, and the invariant
 * underneath them is simpler than any of the numbers: *no entity list, ever, at any world
 * size.*
 *
 * ## Why the time budget is a gate and not a print
 *
 * Spec 6 makes *digest <0.3ms at 500 entities* a Phase 1 exit criterion. The digest is read
 * constantly - the bridge polls it while it waits for `completedCommandId` - and it is built
 * on the simulation thread, so an over-budget digest is not slow tooling, it is a slow game.
 * `DigestBudgetTest` is wired into `check` the way the Phase 0 budgets are, and the same rule
 * applies: **do not loosen a number here and do not disable the task.**
 */
public object DigestBudgets {

    /** The world size every digest budget is quoted at (spec 6, Phase 1 exit). */
    public const val ENTITIES: Int = 500

    /** Median wall time for one build at [ENTITIES] entities: 0.3ms. */
    public const val BUILD_NANOS: Long = 300_000L

    /**
     * Bytes a warm build may allocate before the document itself.
     *
     * Zero, and it means zero: rendering goes into one reused `StringBuilder` through
     * [dev.wildware.udea.agent.Json]. The published `String` is a separate, unavoidable
     * allocation - something immutable has to cross to the HTTP thread - and is measured
     * separately by `DigestAllocationTest`, which asserts the build allocates *that and
     * nothing else*.
     */
    public const val RENDER_ALLOCATED_BYTES: Long = 0L

    /** Hard ceiling on the rendered document. */
    public const val MAX_BYTES: Int = 2048

    /**
     * Ticks between rebuilds.
     *
     * Two, because the digest is a summary and an agent reading it twice in one 33ms window
     * learns nothing from the second read that it could act on. Combined with the read flag
     * (`AgentBridge.readSinceLastPublish`) an unwatched game pays for the digest **once** and
     * then never again until somebody looks.
     */
    public const val REBUILD_INTERVAL_TICKS: Int = 2

    /** Recent events rendered. The tail is what an agent asked for; the rest is in the ring. */
    public const val EVENT_LIMIT: Int = 20

    /** Characters of one event message. Longer messages are truncated, never dropped. */
    public const val EVENT_CHARS: Int = 64

    /** Command answers rendered. A caller polls for its own, which is always among the newest. */
    public const val RESULT_LIMIT: Int = 4

    /** UI labels rendered. */
    public const val LABEL_LIMIT: Int = 12

    /** Scalars a game may publish into the `game` block. */
    public const val GAME_SCALAR_LIMIT: Int = 24

    /** The name the digest build reports itself under in `AgentTimings`. */
    public const val TIMING_NAME: String = "agent.digest"
}
