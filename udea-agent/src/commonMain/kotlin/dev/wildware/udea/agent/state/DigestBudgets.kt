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

    /**
     * Hard ceiling on the rendered document, and now enforced rather than only asserted.
     *
     * The per-section caps do not jointly guarantee it and cannot: an event message, a UI
     * label and every `@AgentState` scalar name are game-authored text, so a count cap bounds
     * how many there are and says nothing about how many bytes they are. [EVENT_CHARS] and
     * [LABEL_CHARS] bound the first two, and the `game` block - which is rendered last, so it
     * is the one section that can see what the rest of the document has already spent -
     * refuses a scalar that would not leave room to close the document and say it truncated.
     *
     * What is still not bounded here is the host's own vocabulary: archetype names and the
     * screen name are declared once at wiring time rather than per item, so they are a
     * reviewable constant of an integration and not something a running game can grow.
     */
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

    /**
     * Characters of one UI label. Longer labels are truncated, never dropped.
     *
     * A label is game-authored text, so [LABEL_LIMIT] bounds how many there are and nothing
     * about how large they are - which is half of why [MAX_BYTES] was not something the caps
     * jointly guaranteed.
     */
    public const val LABEL_CHARS: Int = 40

    /** Scalars a game may publish into the `game` block. */
    public const val GAME_SCALAR_LIMIT: Int = 24

    // --- the byte ceiling, made enforceable ---------------------------------------------------
    //
    // The count caps above bound how many items each section holds and say nothing about how
    // many bytes they are: an event message, a UI label, a tool's result document and every
    // `@AgentState` scalar name are all authored outside this module. Saturating them produced a
    // 2318-character document against a 2048 ceiling that nothing checked.
    //
    // So each variable-length section is given a *ceiling* as well as a count, in render order,
    // and each one leaves a floor for the sections that come after it. A section stops when the
    // next item would not fit and says so - `labelsTruncated`, `commandResultsTruncated`,
    // `eventsTruncated`, `gameTruncated` - because a silent truncation is the failure mode that
    // costs an agent the most time.
    //
    // What is still not bounded is the fixed prelude: `counts` carries the host's archetype
    // names and `ui.screen` its screen names, both declared once at wiring time rather than
    // grown by a running game. They are a reviewable constant of an integration.

    /** Closing braces plus the four truncation flags, kept free at the end of every document. */
    public const val TAIL_BYTES: Int = 64

    /** Bytes the `game` block is guaranteed, whatever the sections before it spent. */
    public const val GAME_MIN_BYTES: Int = 192

    /** Bytes the `events` array is guaranteed. */
    public const val EVENT_MIN_BYTES: Int = 512

    /** Bytes the `commandResults` array is guaranteed. */
    public const val RESULT_MIN_BYTES: Int = 256

    /** What the document may reach while writing `ui.elements`. */
    public const val LABEL_CEILING: Int =
        MAX_BYTES - TAIL_BYTES - GAME_MIN_BYTES - EVENT_MIN_BYTES - RESULT_MIN_BYTES

    /** What the document may reach while writing `commandResults`. */
    public const val RESULT_CEILING: Int = MAX_BYTES - TAIL_BYTES - GAME_MIN_BYTES - EVENT_MIN_BYTES

    /** What the document may reach while writing `events`. */
    public const val EVENT_CEILING: Int = MAX_BYTES - TAIL_BYTES - GAME_MIN_BYTES

    /** What the document may reach while writing the `game` block. */
    public const val GAME_CEILING: Int = MAX_BYTES - TAIL_BYTES

    /** The name the digest build reports itself under in `AgentTimings`. */
    public const val TIMING_NAME: String = "agent.digest"
}
