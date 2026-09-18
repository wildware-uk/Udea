package dev.wildware.udea.build

/**
 * The structural invariants of the module graph (spec 4, spec 6), as data.
 *
 * Each of these is cheap to enforce now, while it passes trivially, and expensive to
 * retrofit once a module violates it — `UDEA-MG-005` in particular is a ratchet placed
 * before `moba` has any content, because `common/build.gradle.kts` shows exactly what
 * happens without it: five `kotlin-scripting-*` artifacts and `org.reflections:reflections`
 * on a shipped classpath.
 *
 * The `common` ban is deliberately *not* here. It is [LegacyDependencyRules], run by its
 * own task, because the two answer different questions and a single failure that could mean
 * either is a worse message than two that cannot.
 *
 * Every id is documented in `docs/module-graph.md`; [ALL] is asserted against that document
 * by `ModuleGraphRulesTest`, so a rule cannot be added without explaining itself.
 */
public object ModuleGraphRules {

    /**
     * Classpaths scanned. Same set as [LegacyDependencyRules.CONFIGURATIONS] — a rule
     * narrows it through [DependencyRule.configurations] rather than by being invisible on
     * a classpath nobody looked at.
     */
    public val CONFIGURATIONS: Set<String> = LegacyDependencyRules.CONFIGURATIONS

    /**
     * The two modules that are allowed to see GL, and the whole of the exception list.
     *
     * - **`:udea-render`** is spec 4's "the only module that touches GL" — it owns the
     *   backend, the targets, the pipeline and the capture. It applies
     *   `udea.kotlin-library-gl`, which is the visible marker, and `RenderModuleGraphTest`
     *   asserts it is the only module that does.
     * - **`:udea-agent-host`** is here as of the Phase 1 follow-up, by a controller ruling,
     *   and the reason is that the previous arrangement was a contradiction rather than a
     *   trade-off. Spec 4 gives this module "the toolsets that need a render context or live
     *   input: render, input, ui". A module that **owns the render toolset** and may not
     *   name a render type cannot implement the toolset's own port: the `RenderControl`
     *   implementation and the GL `OverlaySystem` were both written, both proven against a
     *   real LWJGL3 context, and both marooned in **test** sources, because that is the only
     *   place in this module the old rule allowed them. The shipped result was that every
     *   `render.*` tool answered `no_render_context` on a real run and the overlay was drawn
     *   only by tests.
     *
     * It does **not** apply `udea.kotlin-library-gl`: it takes `:udea-render` as a plain
     * `implementation` dependency, so its GL surface is exactly what that module chooses to
     * expose plus the gdx types it names on that one line of its build script.
     *
     * ## What is not weakened by this
     *
     * The headless guarantee that matters is `:udea-core` — the simulation kernel must run in
     * a test JVM, in a dedicated server and inside an agent harness with no display — and
     * that is untouched: `:udea-core` cannot name `udea-render`, and `RenderModuleGraphTest`
     * still asserts it. `:udea-agent-host` is the **debug HTTP host**, which
     * `udeaVerifyRelease` and `UDEA-REL-002` ([ReleaseRules.CLASSPATH_RULE]) already keep off
     * every shipped runtime classpath — so a GL dependency here reaches no release artifact
     * by a rule that is independently enforced and independently tested.
     */
    public val GL_ALLOWED_PROJECTS: Set<String> = setOf(":udea-render", ":udea-agent-host")

    /**
     * The game, as every path it has had or is planned to have.
     *
     * `:moba:game` is the library, and `:moba:desktop` and `:moba:android` are the launchers that
     * ship it (issue #212); a rule about what the shipped game carries has to read all three,
     * because each launcher's classpath is the game's plus its own. `:moba` stays in although that
     * project no longer exists, so re-creating a flat `moba` cannot re-open a rule, and `:moba:web`
     * is there ahead of issue #226. `ModuleGraphRulesTest` fails a rule that governs only paths
     * `settings.gradle.kts` does not include, which is what a flat `:moba` alone would now be.
     */
    internal val MOBA_PROJECTS: Set<String> =
        setOf(":moba", ":moba:game", ":moba:desktop", ":moba:android", ":moba:web")

    /**
     * Every module that must stay free of GL: the whole `udea-*` tree except
     * [GL_ALLOWED_PROJECTS].
     *
     * This is the **single source of truth** for the headless rule at both enforcement
     * levels. [NO_GL_OUTSIDE_RENDER] (`UDEA-MG-002`) reads it for the dependency check, and
     * `udea-render`'s build script reads it for `udeaVerifyHeadless`, which passes it to
     * `HeadlessScan` through [HEADLESS_MODULES_PROPERTY]. `docs/module-graph.md` calls the
     * bytecode scan "the same rule one level down", and two hand-maintained lists cannot be
     * that: before this existed they disagreed in both directions, and `udea-agent-host`,
     * `udea-diagnostics`, `udea-gradle` and `udea-compiler-plugin` were in neither, so a GL
     * backend on any of them passed both gates.
     *
     * Membership is *not* a judgement call: it is every `udea-*` module in
     * `settings.gradle.kts` that is not in [GL_ALLOWED_PROJECTS].
     * `ModuleGraphRulesTest` derives exactly that set from `settings.gradle.kts` and fails if
     * this list has drifted from it, which is what makes a newly included module a build
     * failure rather than a silent gap — and what makes adding a module to
     * [GL_ALLOWED_PROJECTS] a deliberate, reviewable edit in two places rather than a
     * deletion from one list.
     */
    public val HEADLESS_PROJECTS: Set<String> = setOf(
        ":udea-agent",
        ":udea-annotations",
        ":udea-assets",
        ":udea-assets-compiler",
        // Audio is presentation, and presentation is where seconds and wall-clock randomness are
        // allowed (spec 5) - but none of that is GL. The module holds the cue-to-sound routing and
        // a device SPI with no gdx type in it; the device that actually opens a `Sound` lives in
        // the game, which is not a designated headless module. So this stays on the headless side
        // and `AudioDevice.Silent` is what a `RenderMode.Headless` process gets.
        ":udea-audio",
        ":udea-codegen",
        ":udea-compiler-plugin",
        ":udea-core",
        ":udea-diagnostics",
        ":udea-fleks",
        ":udea-gas",
        ":udea-gradle",
        ":udea-net",
        ":udea-replay",
    )

    /**
     * The system property `udea-render`'s build script uses to hand [HEADLESS_PROJECTS] to
     * the bytecode scan.
     *
     * A system property rather than a copied list: `udea-render`'s test sources cannot see
     * `build-logic`, and the only alternative to passing the set in is writing it out twice.
     * `HeadlessScan` fails loudly when the property is absent, so a broken hand-off is a red
     * gate rather than a scan of nothing.
     */
    public const val HEADLESS_MODULES_PROPERTY: String = "udea.headless.modules"

    /**
     * The task every module on a Udea Kotlin convention registers to compile the bytecode its
     * main code ships as, whichever plugin compiles it (issue #201).
     *
     * `udeaVerifyHeadless` scans that bytecode in each of [HEADLESS_PROJECTS], so it has to make
     * the bytecode exist first. A JVM module's is `classes`; a multiplatform module has no
     * `classes` task at all, and ships bytecode from two compilations, `jvm` and `android`. One
     * name for both is what lets the gate depend on every headless module without knowing which
     * plugin each is on.
     */
    public const val MAIN_BYTECODE_TASK: String = "udeaMainBytecode"

    /**
     * `udea-annotations` is on the compile classpath of the engine, the game, the KSP
     * processor and the K2 compiler plugin simultaneously, so anything it drags in is
     * dragged everywhere at once — including into the compiler's own classloader.
     */
    public val ANNOTATIONS_ARE_A_LEAF: DependencyRule = DependencyRule(
        id = RuleId("UDEA-MG-001"),
        summary = "udea-annotations resolves the Kotlin stdlib and nothing else",
        rationale = "The annotation vocabulary is consumed by the engine, the game, the KSP " +
            "processor and the K2 plugin at once. A dependency added here is added to all four, " +
            "and two of them are loaded inside the Kotlin compiler, where an extra jar is a " +
            "classloader conflict rather than an inconvenience.",
        specSection = "4",
        projects = setOf(":udea-annotations"),
        configurations = setOf("runtimeClasspath"),
        allowOnly = listOf(
            CoordinatePattern("org.jetbrains.kotlin:kotlin-stdlib"),
            CoordinatePattern("org.jetbrains:annotations"),
            // The same stdlib, as Wasm resolves it (issue #201): `kotlin-stdlib`'s Wasm variant
            // is published as this module and reached only through `kotlin-stdlib` itself.
            CoordinatePattern("org.jetbrains.kotlin:kotlin-stdlib-wasm-js"),
        ),
    )

    /**
     * The headless-kernel rule. Note what is *not* banned: `com.badlogicgames.gdx:gdx`
     * carries `Vector2` and the rest of gdx-math, which is headless. The ban is on GL and
     * on native loaders, not on maths.
     */
    public val NO_GL_OUTSIDE_RENDER: DependencyRule = DependencyRule(
        id = RuleId("UDEA-MG-002"),
        summary = "only udea-render may see Kool, a ComposeGL backend, a GL backend or a native platform artifact",
        rationale = "udea-core is a headless kernel: the simulation must run in a test JVM, in a " +
            "dedicated server and inside an agent harness with no display. Once a renderer is " +
            "on the compile classpath, a static initialiser or a context reference gets written and " +
            "the headless path is gone. Kool and the ComposeGL backends are the renderer after the " +
            "port (spec section 3: no Kool and no ComposeGL backend outside udea-render); " +
            "composegl-ui, the toolkit with no backend in it, stays legal. gdx-math " +
            "(com.badlogicgames.gdx:gdx) is still allowed - Vector2 is not GL. udea-render and " +
            "udea-agent-host are the two exempt modules; see ModuleGraphRules.GL_ALLOWED_PROJECTS " +
            "for why the debug HTTP host is one of them and why udea-core's guarantee is untouched by it.",
        specSection = "4, 3.5; kool port 3",
        projects = HEADLESS_PROJECTS,
        configurations = setOf("compileClasspath", "runtimeClasspath"),
        banned = listOf(
            CoordinatePattern("de.fabmax.kool:*"),
            CoordinatePattern("dev.wildware.composegl:composegl-kool*"),
            CoordinatePattern("dev.wildware.composegl:composegl-gdx*"),
            CoordinatePattern("dev.wildware.composegl:composegl-lwjgl3*"),
            CoordinatePattern("dev.wildware.composegl:composegl-webgl*"),
            CoordinatePattern("dev.wildware.composegl:composegl-android*"),
            CoordinatePattern("com.badlogicgames.gdx:gdx-backend-lwjgl3"),
            CoordinatePattern("org.lwjgl:*"),
            CoordinatePattern("com.badlogicgames.gdx:*-platform"),
        ),
    )

    /**
     * `udea-render` draws with Kool (spec section 4, issue #211), and LibGDX left it in the same
     * change. A gdx artifact back on its classpath - directly, or through `composegl-gdx` - is two
     * renderers in one module, which is the parallel-renderer arrangement spec D9 rejected.
     */
    public val RENDER_HAS_NO_LIBGDX: DependencyRule = DependencyRule(
        id = RuleId("UDEA-MG-008"),
        summary = "udea-render resolves no LibGDX artifact and no LibGDX ComposeGL backend",
        rationale = "udea-render draws with Kool (spec section 4, issue #211). LibGDX on its " +
            "classpath would be a second renderer in the one module that owns rendering, which is " +
            "the parallel-renderers migration spec D9 rejected, and composegl-gdx drags gdx in " +
            "transitively. The rule covers every target classpath the module has.",
        specSection = "kool port 4, D9",
        projects = setOf(":udea-render"),
        configurations = setOf("compileClasspath", "runtimeClasspath"),
        banned = listOf(
            CoordinatePattern("com.badlogicgames.gdx:*"),
            CoordinatePattern("dev.wildware.composegl:composegl-gdx*"),
        ),
    )

    /**
     * The asset compiler runs in two places — a Gradle task and the dev daemon — and both
     * must run identical code, or "it compiled in the IDE" and "it compiled in CI" stop
     * meaning the same thing.
     */
    public val ASSETS_COMPILER_HAS_NO_GRADLE: DependencyRule = DependencyRule(
        id = RuleId("UDEA-MG-003"),
        summary = "udea-assets-compiler holds zero Gradle types",
        rationale = "The five-pass asset compiler is one implementation behind both the Gradle " +
            "task and the dev daemon. A Gradle type in the compiler makes the daemon path either " +
            "impossible or a second implementation, and a second implementation is how CI and the " +
            "IDE come to disagree about whether an asset is valid.",
        specSection = "4",
        projects = setOf(":udea-assets-compiler"),
        banned = listOf(
            CoordinatePattern("org.gradle:*"),
            // gradleApi() and gradleTestKit() are file dependencies, not modules: they reach
            // the classpath as loose jars under one display name and are invisible to a
            // component-graph-only scan. This pattern is what catches the leak the old
            // `gradle-plugin` module shipped through `implementation(gradleApi())`.
            CoordinatePattern("file:Gradle *"),
        ),
    )

    /**
     * The old `gradle-plugin` module declared `implementation(gradleApi())` and `example`
     * depended on it, which shipped the Gradle API inside the game. `udea-gradle` is applied
     * as a plugin and depended on by nothing.
     */
    public val NOBODY_DEPENDS_ON_UDEA_GRADLE: DependencyRule = DependencyRule(
        id = RuleId("UDEA-MG-004"),
        summary = "no udea-* or moba runtime classpath may resolve udea-gradle",
        rationale = "A Gradle plugin is applied, never depended on. The old gradle-plugin module " +
            "was depended on by example, and its implementation(gradleApi()) put the whole Gradle " +
            "API on the shipped game's runtime classpath. Here gradleApi() is compileOnly and this " +
            "rule closes the other half of the same hole.",
        specSection = "4",
        configurations = setOf("runtimeClasspath", "testRuntimeClasspath"),
        banned = listOf(CoordinatePattern(":udea-gradle")),
    )

    /**
     * Placed before Phase 2 had a reason to reach for `kotlin-scripting-jvm-host`, as a ratchet.
     * It governs [MOBA_PROJECTS]: scoped to the flat `:moba` alone, it scanned nothing once issue
     * #212 split that project up.
     */
    public val NO_SCRIPTING_OR_REFLECTION_IN_THE_GAME: DependencyRule = DependencyRule(
        id = RuleId("UDEA-MG-005"),
        summary = "the shipped game carries no Kotlin scripting host and no classpath scanner",
        rationale = "common pulls in five kotlin-scripting-* artifacts and org.reflections:" +
            "reflections today, which is both a startup cost and the mechanism behind the " +
            "reflection-on-hot-paths smell the rewrite exists to kill. Asset scripts are compiled " +
            "at build time; discovery is a generated registry, not classpath scanning.",
        specSection = "6 (Phase 2 exit), 3.6",
        projects = MOBA_PROJECTS,
        configurations = setOf("runtimeClasspath"),
        banned = listOf(
            CoordinatePattern("org.jetbrains.kotlin:kotlin-scripting-*"),
            CoordinatePattern("org.jetbrains.kotlin:kotlin-reflect"),
            CoordinatePattern("org.reflections:reflections"),
        ),
    )

    /**
     * The runtime asset model is on the classpath of the engine, the game and the agent harness,
     * and it is the module a `.udeapak` reader will live in. Everything it drags in is dragged
     * into the shipped game.
     */
    public val ASSETS_MODEL_IS_A_LEAF: DependencyRule = DependencyRule(
        id = RuleId("UDEA-MG-006"),
        summary = "udea-assets resolves only udea-annotations, udea-diagnostics, kotlinx-io and the stdlib",
        rationale = "The runtime asset model replaces `common/assets/*` and the `Assets` global, " +
            "and the old one could not be read without LibGDX, a Jackson databind stack and a " +
            "Kotlin scripting host, because asset values held live Sound and Texture handles and " +
            "were produced by evaluating scripts at runtime. Spec 3.6 compiles assets at build " +
            "time instead, so the runtime model is plain data: a dependency here means an asset " +
            "value has started holding something that is not. kotlinx-io is the one exception, " +
            "and it holds no asset value: it is how the .udeapak reader names and reads a file on " +
            "every target once the module is multiplatform (spec 6, issue #205).",
        specSection = "4, 3.6, 6",
        projects = setOf(":udea-assets"),
        configurations = setOf("compileClasspath", "runtimeClasspath"),
        allowOnly = listOf(
            CoordinatePattern(":udea-annotations"),
            CoordinatePattern(":udea-assets"),
            CoordinatePattern(":udea-diagnostics"),
            CoordinatePattern("org.jetbrains.kotlin:kotlin-stdlib"),
            // The stdlib as the wasmJs classpath resolves it once kotlinx-io asks for it by name.
            CoordinatePattern("org.jetbrains.kotlin:kotlin-stdlib-wasm-js"),
            CoordinatePattern("org.jetbrains:annotations"),
            // Issue #205. `kotlinx-io-core` and the `kotlinx-io-bytestring` it depends on, each
            // with its per-target artifacts (`kotlinx-io-core-jvm`, `-wasm-js`, `-iosarm64`), by
            // name: a `kotlinx-*` wildcard would let serialization or coroutines in unannounced.
            CoordinatePattern("org.jetbrains.kotlinx:kotlinx-io-core"),
            CoordinatePattern("org.jetbrains.kotlinx:kotlinx-io-core-*"),
            CoordinatePattern("org.jetbrains.kotlinx:kotlinx-io-bytestring"),
            CoordinatePattern("org.jetbrains.kotlinx:kotlinx-io-bytestring-*"),
        ),
    )

    /**
     * Fleks is third-party source vendored so it has iOS targets (issue #215). It sits under
     * `udea-core`, so an arrow from it to any Udea module is an arrow pointing upward, and a
     * dependency added to it is added under the whole engine.
     */
    public val VENDORED_FLEKS_IS_A_LEAF: DependencyRule = DependencyRule(
        id = RuleId("UDEA-MG-007"),
        summary = "udea-fleks resolves only kotlinx-serialization-core and the stdlib",
        rationale = "udea-fleks is Fleks 2.14's own source, vendored because Fleks publishes no " +
            "iOS artifact. It is the bottom of the engine: udea-core exposes it as api, so what it " +
            "resolves reaches every module and the shipped game. Upstream's source needs only " +
            "kotlinx-serialization-core, for its @Serializable Entity and Snapshot. A Udea module " +
            "here is an upward arrow, and a new library is an edit to third-party code's " +
            "dependencies that nobody vendoring it agreed to.",
        specSection = "4",
        projects = setOf(":udea-fleks"),
        configurations = setOf("compileClasspath", "runtimeClasspath"),
        allowOnly = listOf(
            CoordinatePattern(":udea-fleks"),
            CoordinatePattern("org.jetbrains.kotlin:kotlin-stdlib"),
            CoordinatePattern("org.jetbrains.kotlin:kotlin-stdlib-wasm-js"),
            CoordinatePattern("org.jetbrains:annotations"),
            // With its per-target artifacts (`-jvm`, `-wasm-js`, `-iosarm64`), by name: a
            // `kotlinx-serialization-*` wildcard would let JSON or CBOR in unannounced.
            CoordinatePattern("org.jetbrains.kotlinx:kotlinx-serialization-core"),
            CoordinatePattern("org.jetbrains.kotlinx:kotlinx-serialization-core-*"),
            // The platform `kotlinx-serialization-core` pulls in on the JVM and Android to align
            // versions. It carries constraints and no classes.
            CoordinatePattern("org.jetbrains.kotlinx:kotlinx-serialization-bom"),
        ),
    )

    /**
     * The game draws with Kool too, because it draws through `udea-render` (issue #212).
     *
     * `moba` was the last project in the rewrite tree with LibGDX on it: it named `libs.gdx` to
     * write a `RenderSystem` against `Batch`, and `gdx-box2d` plus its desktop natives for a
     * physics world spec D4 retires with LibGDX. Both are gone with the split, and this is what
     * stops either coming back through a nested project nobody thought to check.
     *
     * Every `:moba:*` project, not only the one that used to name it: `:moba:desktop` and
     * `:moba:android` each resolve `:moba:game`, so a gdx artifact reintroduced on any of them
     * reaches the shipped game the same way.
     */
    public val MOBA_HAS_NO_LIBGDX: DependencyRule = DependencyRule(
        id = RuleId("UDEA-MG-009"),
        summary = "no moba project resolves a LibGDX artifact",
        rationale = "moba draws through udea-render, which draws with Kool (issue #211). LibGDX " +
            "on a moba classpath is a second renderer in the shipped game - the parallel-renderer " +
            "arrangement spec D9 rejected - and it is how the Box2D world spec D4 retires would " +
            "come back. The rule covers every target classpath each nested project has.",
        specSection = "kool port 3, 4, D4, D9, D12",
        projects = MOBA_PROJECTS,
        configurations = setOf("compileClasspath", "runtimeClasspath"),
        banned = listOf(
            CoordinatePattern("com.badlogicgames.gdx:*"),
            CoordinatePattern("dev.wildware.composegl:composegl-gdx*"),
        ),
    )

    /** Every rule, in id order. */
    public val ALL: List<DependencyRule> = listOf(
        ANNOTATIONS_ARE_A_LEAF,
        NO_GL_OUTSIDE_RENDER,
        ASSETS_COMPILER_HAS_NO_GRADLE,
        NOBODY_DEPENDS_ON_UDEA_GRADLE,
        NO_SCRIPTING_OR_REFLECTION_IN_THE_GAME,
        ASSETS_MODEL_IS_A_LEAF,
        VENDORED_FLEKS_IS_A_LEAF,
        RENDER_HAS_NO_LIBGDX,
        MOBA_HAS_NO_LIBGDX,
    )

    /** True when [projectPath] is part of the rewrite tree and therefore subject to [ALL]. */
    public fun governs(projectPath: String): Boolean = LegacyDependencyRules.governs(projectPath)

    /** Every violation visible on [configuration] of [projectPath]. */
    public fun violations(
        projectPath: String,
        configuration: String,
        graph: ResolvedGraph,
    ): List<DependencyViolation> =
        DependencyRules.violations(projectPath, configuration, graph, ALL)

    /** The build-failure message, or `null` when [violations] is empty. */
    public fun report(violations: List<DependencyViolation>): String? =
        DependencyRules.report("udeaVerifyModuleGraph", violations)
}
