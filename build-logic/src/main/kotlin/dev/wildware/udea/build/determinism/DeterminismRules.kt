package dev.wildware.udea.build.determinism

/**
 * A source set (narrowed to package prefixes) that the build **declares** to be simulation.
 *
 * Issue #150 is explicit that membership is never inferred from a module name, and the reason
 * is `udea-assets-compiler`: it legitimately calls `System.currentTimeMillis` from a build-time
 * script host, and a scanner that guessed "anything called `udea-*` is simulation" would either
 * fail on it forever or teach everyone to reach for the allowlist. So membership is a decision
 * with a [why], written here, reviewed in a diff.
 *
 * [packagePrefixes] narrows further because two of the declared modules mix simulation with
 * things that are legitimately not: `udea-net` holds sockets and a server loop beside its
 * predicted movement, and `moba` holds a HUD and audio cues beside its game rules. Declaring
 * the whole of either as simulation would produce findings that are all false and one gate
 * everybody switches off.
 */
public data class SimScope(
    /** Gradle path, e.g. `:udea-core`. */
    public val project: String,
    /** Source set name. Only `main` is ever simulation; tests may do what they like. */
    public val sourceSet: String,
    /** Dotted package prefixes inside that source set. Empty means the whole source set. */
    public val packagePrefixes: List<String>,
    /** Why this is simulation. Read in review; not decorative. */
    public val why: String,
) {
    /** Whether [className] (dotted FQN) falls inside this scope. */
    public fun covers(className: String): Boolean =
        packagePrefixes.isEmpty() || packagePrefixes.any { className.startsWith("$it.") }
}

/**
 * What the whole class a reference lives in also does.
 *
 * One rule needs it. `DET004` cannot decide from a single reference whether a hash-ordered
 * collection is *iterated*, because Kotlin never emits the concrete owner at an iteration site:
 * `for ((k, v) in someHashMap)` compiles to `checkcast java/util/Map` followed by
 * `INVOKEINTERFACE java/util/Map.entrySet`, whatever the static type was. The concrete type
 * appears only at the `NEW`. So the two halves of the evidence sit in different methods of the
 * same class - typically `<init>` and `onTick` - and have to be joined at class level.
 */
public data class ClassFacts(
    /** Whether this class iterates a map or set anywhere, by interface or by concrete type. */
    public val iteratesMapOrSet: Boolean,
)

/** How a rule decides whether a [MemberRef] is a violation. */
public data class DeterminismRule(
    /** Stable rule id, e.g. `DET001`. Appears in the allowlist and in every diagnostic. */
    public val id: String,
    /** One line, present tense, naming the defect. */
    public val title: String,
    /** The sanctioned replacement, rendered as the diagnostic's did-you-mean. */
    public val didYouMean: String,
    /** True when this reference violates the rule. */
    public val matches: (MemberRef) -> Boolean,
    /**
     * Restricts the rule to a narrower set of classes than the declared simulation scopes.
     * `null` means "every simulation class". `DET005` is the only rule that narrows.
     */
    public val appliesTo: ((String) -> Boolean)? = null,
    /**
     * An extra condition on the whole class, or `null` for "the reference is enough".
     * `DET005` narrows by class *name*; this narrows by what the class *does*.
     */
    public val requiresClassFact: ((ClassFacts) -> Boolean)? = null,
)

/**
 * The determinism rule table, and the declared simulation surface it runs over.
 *
 * ## This is a cheap first filter, not the determinism gate
 *
 * Spec section 7 says so in as many words, and `determinism-audit.md` spells out what this
 * cannot see. The gate is the `WorldHasher` snapshot-equivalence test and the cross-OS replay
 * equality job. A green `udeaVerifyDeterminism` means "no *direct* call to a known-bad member
 * from declared simulation code" and nothing more. It says nothing about nondeterminism
 * laundered through Fleks internals, through an interface call whose runtime receiver happens
 * to be a `HashMap`, or through float differences between two JVMs.
 */
public object DeterminismRules {

    /**
     * The simulation surface, declared.
     *
     * `:udea-gas` is here because ability activation, cooldowns and effect application are
     * authoritative state. `:udea-render`, `:udea-audio` and `:udea-agent-host` are deliberately
     * absent: spec 3.3 puts presentation outside `world.update` by construction, and spec 5
     * gives it a separately typed `PresentationRandom`, so wall-clock reads and unseeded
     * randomness are *correct* there.
     */
    public val SIMULATION_SCOPES: List<SimScope> = listOf(
        SimScope(
            project = ":udea-core",
            sourceSet = "main",
            packagePrefixes = emptyList(),
            why = "The simulation kernel. Every class here runs inside world.update or decides " +
                "what does: SimSystem, the tick loop, physics, movement, snapshots.",
        ),
        SimScope(
            project = ":udea-gas",
            sourceSet = "main",
            packagePrefixes = emptyList(),
            why = "Abilities, cooldowns and effects are authoritative state that must rewind " +
                "and must agree between server and client.",
        ),
        SimScope(
            project = ":udea-net",
            sourceSet = "main",
            packagePrefixes = listOf(
                "dev.wildware.udea.net.prediction",
                "dev.wildware.udea.net.input",
            ),
            why = "Prediction re-runs the same movement the server ran, so it is simulation by " +
                "definition, and input command application writes authoritative state. The " +
                "transport, the sockets and the server loop are NOT simulation and are " +
                "excluded by these prefixes.",
        ),
        SimScope(
            project = ":moba",
            sourceSet = "main",
            packagePrefixes = listOf(
                "dev.wildware.moba.ability",
                "dev.wildware.moba.ai",
                "dev.wildware.moba.match",
            ),
            why = "The example game's own rules: abilities and combat, unit AI, and the match " +
                "lifecycle. Its HUD, audio cues, scene, animation and renderers live outside " +
                "these prefixes and are presentation, where seconds and PresentationRandom are " +
                "allowed (spec 3.3, spec 5).",
        ),
    )

    /**
     * Classes `DET005` treats as **predicted**: re-run locally against a server that ran the
     * same code, so a solver whose result depends on iteration order or accumulated internal
     * state cannot be in the loop.
     *
     * Declared as a package list because the tree has **no `@Predicted` annotation** to key on.
     * That is a real gap and is recorded as one in `determinism-audit.md`: the day such an
     * annotation exists, this should read it from the bytecode instead and the package list
     * should go.
     */
    public val PREDICTED_PACKAGES: List<String> = listOf("dev.wildware.udea.net.prediction")

    private fun isPredicted(className: String): Boolean =
        PREDICTED_PACKAGES.any { className.startsWith("$it.") }

    /** `DET001` - wall clock. */
    public val WALL_CLOCK: DeterminismRule = DeterminismRule(
        id = "DET001",
        title = "reads the wall clock",
        didYouMean = "SimClock.tick - a tick is the simulation's only clock; seconds are a " +
            "presentation unit (spec 5)",
        matches = { ref ->
            ref.owner == "java.lang.System" && ref.member in setOf("currentTimeMillis", "nanoTime")
        },
    )

    /** `DET002` - unseeded randomness. */
    public val UNSEEDED_RANDOM: DeterminismRule = DeterminismRule(
        id = "DET002",
        title = "draws from an unseeded random source",
        didYouMean = "RngService.stream(Combat) - a named, seeded stream, so adding a consumer " +
            "does not perturb an existing sequence",
        matches = { ref ->
            (ref.owner == "java.lang.Math" && ref.member == "random") ||
                // `Random.Default`, and every no-arg `List.random()` / `Random.nextInt(n)`,
                // which the Kotlin compiler routes through this same object.
                ref.owner == "kotlin.random.Random\$Default" ||
                (ref.owner == "kotlin.random.Random" && ref.member == "Default") ||
                // `new java.util.Random()` with no seed. The seeded constructor is NOT a
                // violation - it is what a seeded RNG stream is built out of - so the
                // descriptor is part of the match rather than the owner alone.
                (
                    ref.owner == "java.util.Random" && ref.member == "<init>" &&
                        ref.descriptor == "()V"
                    ) ||
                ref.owner == "java.util.concurrent.ThreadLocalRandom" ||
                (ref.owner == "com.badlogic.gdx.math.MathUtils" && ref.member.startsWith("random"))
        },
    )

    /** `DET003` - calendar time. */
    public val CALENDAR_TIME: DeterminismRule = DeterminismRule(
        id = "DET003",
        title = "reads calendar time",
        didYouMean = "SimClock.tick, or record the value at build time if it is metadata",
        matches = { ref ->
            (ref.owner.startsWith("java.time.") && ref.member == "now") ||
                (
                    ref.owner == "java.time.Clock" &&
                        ref.member in setOf("systemUTC", "systemDefaultZone")
                    ) ||
                (ref.owner == "java.util.Date" && ref.member == "<init>") ||
                (ref.owner == "java.util.Calendar" && ref.member == "getInstance")
        },
    )

    /**
     * `DET004` - a hash-ordered collection that a simulation class **iterates**.
     *
     * ## The two things this rule had to survive to be worth shipping
     *
     * **It cannot fire on construction alone.** The first version did, and over the real tree it
     * produced *seven findings, all false*: `SimRegistry.resolve`, `SystemOrder.findCycle`,
     * `AbilityTable.of`, `AttributeTableBuilder.build`, `GameplayEffectTable.of` and
     * `GameplayTagTable.of` each build a `HashMap` or `HashSet` as a **lookup index** whose
     * ordering comes from a sorted array beside it, and never iterate it. The engineering
     * standard bans these "where the order affects output", and in none of those does it. A gate
     * that is wrong seven times out of seven on the first codebase it meets is a gate people
     * switch off.
     *
     * **It cannot fire on the iteration site either.** That was the next version, and it was
     * *inert*: a `HashMap` field iterated with `for ((k, v) in map)` produced no finding at all,
     * because Kotlin emits `checkcast java/util/Map` + `INVOKEINTERFACE java/util/Map.entrySet`
     * whatever the static type is. Verified by `javap` on the planted probe. A rule that fires
     * only on `javac` output is a rule this project never runs.
     *
     * **So it joins the two.** The concrete type is named at the `NEW`; the iteration is visible
     * as a map-or-set walk somewhere in the same class ([ClassFacts.iteratesMapOrSet]); the
     * finding is reported at the construction site, which is where the fix goes.
     *
     * ## What it still cannot see, stated rather than hidden
     *
     * A `HashMap` constructed in one class and iterated in another. A class that iterates a map
     * it was handed. Both are in `determinism-audit.md`'s blind-spot table, where the answer is
     * the replay-equality job rather than a cleverer bytecode rule.
     */
    public val HASH_ORDER: DeterminismRule = DeterminismRule(
        id = "DET004",
        title = "builds a hash-ordered collection and iterates a map or set, and hash iteration " +
            "order is not a contract",
        didYouMean = "LinkedHashMap / LinkedHashSet (or linkedMapOf / linkedSetOf), whose " +
            "iteration order is insertion order and therefore reproducible",
        matches = { ref ->
            (ref.kind == RefKind.TYPE && ref.owner in HASH_ORDERED_TYPES) ||
                (ref.owner in HASH_ORDERED_TYPES && ref.member in HASH_ITERATION_MEMBERS) ||
                (
                    ref.owner.startsWith("kotlin.collections.") &&
                        ref.member in setOf("hashMapOf", "hashSetOf")
                    )
        },
        requiresClassFact = { it.iteratesMapOrSet },
    )

    /** `DET005` - the Box2D solver inside predicted code. */
    public val BOX2D_IN_PREDICTED: DeterminismRule = DeterminismRule(
        id = "DET005",
        title = "reaches the Box2D solver from a predicted system",
        didYouMean = "PhysicsWorld - the server owns the solver; prediction re-runs " +
            "CharacterMover, which is a closed-form step",
        matches = { ref -> ref.owner.startsWith("com.badlogic.gdx.physics.box2d") },
        appliesTo = ::isPredicted,
    )

    /** `DET006` - the device, read from simulation. */
    public val DEVICE_IN_SIM: DeterminismRule = DeterminismRule(
        id = "DET006",
        title = "reads the device (frame delta, input or files) from simulation code",
        didYouMean = "SimClock.tick for time, the replicated InputCommand for input, and the " +
            "compiled asset registry for files",
        matches = { ref ->
            (
                ref.owner == "com.badlogic.gdx.Gdx" &&
                    ref.member in setOf("graphics", "input", "files", "app", "gl", "gl20", "gl30")
                ) ||
                (
                    ref.owner == "com.badlogic.gdx.Graphics" &&
                        ref.member in setOf("getDeltaTime", "getRawDeltaTime", "getFrameId")
                    ) ||
                ref.owner == "com.badlogic.gdx.Input"
        },
    )

    /** Every rule, in id order. The allowlist parser rejects an id that is not in here. */
    public val ALL: List<DeterminismRule> = listOf(
        WALL_CLOCK, UNSEEDED_RANDOM, CALENDAR_TIME, HASH_ORDER, BOX2D_IN_PREDICTED, DEVICE_IN_SIM,
    )

    /** Ids, for the allowlist parser's "unknown rule id" message. */
    public val IDS: List<String> = ALL.map { it.id }

    /** Rule by id, or null. */
    public fun byId(id: String): DeterminismRule? = ALL.firstOrNull { it.id == id }

    /**
     * Findings reported per run. Past 25 the output stops being a list somebody reads and
     * becomes a wall somebody scrolls past - the same cap the rest of the diagnostics surface
     * uses (spec 5).
     */
    public const val MAX_FINDINGS: Int = 25

    private val HASH_ORDERED_TYPES = setOf(
        "java.util.HashMap",
        "java.util.HashSet",
        "java.util.Hashtable",
        "java.util.WeakHashMap",
        "java.util.IdentityHashMap",
        "com.badlogic.gdx.utils.ObjectMap",
        "com.badlogic.gdx.utils.ObjectSet",
        "com.badlogic.gdx.utils.IntMap",
        "com.badlogic.gdx.utils.IntSet",
        "com.badlogic.gdx.utils.LongMap",
    )

    private val HASH_ITERATION_MEMBERS =
        setOf("keySet", "entrySet", "values", "iterator", "keys", "entries", "forEach")

    /**
     * References that mean "this class walks a map or a set".
     *
     * Interface owners on purpose: that is the only form Kotlin emits (see [HASH_ORDER]).
     * Deliberately NOT `java.util.Collection.iterator` or `java.lang.Iterable.iterator` - a
     * list walk says nothing about hash order, and including it would put every class that
     * iterates anything back into the false-positive set the seven findings came from.
     */
    private val MAP_OR_SET_WALKS: Set<Pair<String, String>> = setOf(
        "java.util.Map" to "entrySet",
        "java.util.Map" to "keySet",
        "java.util.Map" to "values",
        "java.util.Map" to "forEach",
        "java.util.Set" to "iterator",
        "java.util.Set" to "forEach",
    )

    /** Whether [refs] - every reference one class makes - contains a map or set walk. */
    public fun classFacts(refs: List<MemberRef>): ClassFacts = ClassFacts(
        iteratesMapOrSet = refs.any { (it.owner to it.member) in MAP_OR_SET_WALKS },
    )
}
