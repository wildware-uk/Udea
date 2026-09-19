package dev.wildware.udea.build

/**
 * The structural invariants of the module graph (spec 4, spec 6), as data.
 *
 * Each of these is cheap to enforce while it passes trivially, and expensive to retrofit
 * once a module violates it — `UDEA-MG-005` in particular was a ratchet placed before `moba`
 * had any content, because the old `common` module showed exactly what happens without it:
 * five `kotlin-scripting-*` artifacts and `org.reflections:reflections` on a shipped classpath.
 *
 * Every id is documented in `docs/module-graph.md`; [ALL] is asserted against that document
 * by `ModuleGraphRulesTest`, so a rule cannot be added without explaining itself.
 */
public object ModuleGraphRules {

    /**
     * Classpaths scanned. A rule narrows this set through [DependencyRule.configurations]
     * rather than by being invisible on a classpath nobody looked at.
     *
     * The Kotlin plugin's own tool classpaths (`ksp`, `kotlinCompilerPluginClasspath`) are
     * deliberately excluded: what they carry is the compiler's business, not the module's
     * API, and nothing on them reaches shipped code.
     */
    public val CONFIGURATIONS: Set<String> = setOf(
        "compileClasspath",
        "runtimeClasspath",
        "testCompileClasspath",
        "testRuntimeClasspath",
        "testFixturesCompileClasspath",
        "testFixturesRuntimeClasspath",
    )

    /**
     * The modules that are allowed to see GL, and the whole of the exception list.
     *
     * - **`:udea-render`** is spec 4's "the only module that touches GL" — it owns the
     *   backend, the targets, the pipeline and the capture. `RenderModuleGraphTest` asserts
     *   no other engine module takes the render convention it is on.
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
     * It is on the plain JVM convention: it takes `:udea-render` as an `implementation`
     * dependency, so its GL surface is exactly what that module chooses to expose.
     *
     * - **`:udea-editor`** joined in issue #194, for the same shape of reason. The editor window
     *   is a ComposeGL screen whose viewport shows the world Kool draws, so its runtime classpath
     *   carries Kool through `:udea-render` and cannot not. What it may *compile against* is
     *   narrower: [EDITOR_NAMES_NO_RENDERER] (`UDEA-MG-011`) bans Kool and every ComposeGL frontend
     *   from its compile classpath, so it binds both only through `:udea-render`, and
     *   [NO_EDITOR_ON_A_SHIPPED_CLASSPATH] (`UDEA-MG-010`) keeps it off every shipped one.
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
    public val GL_ALLOWED_PROJECTS: Set<String> = setOf(":udea-render", ":udea-agent-host", ":udea-editor")

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
        // a device SPI with no gdx or Kool type in it; the device that makes a noise is
        // `udea-render`'s `KoolAudioDevice` (issue #221), and `udea-render` is not a designated
        // headless module. So this stays on the headless side and `AudioDevice.Silent` is what a
        // `RenderMode.Headless` process gets.
        ":udea-audio",
        ":udea-codegen",
        ":udea-compiler-plugin",
        ":udea-core",
        ":udea-diagnostics",
        ":udea-fleks",
        ":udea-gas",
        ":udea-gradle",
        ":udea-net",
        // Box2D is native, but it is a solver and not a renderer: the simulation steps it on a
        // dedicated server and in CI, where there is no display.
        ":udea-physics2d",
        ":udea-replay",
    )

    /**
     * The build-time modules that run the FBX converter (issue #244), and the only ones that may
     * resolve it: `udea-assets-compiler`, which converts an `.fbx` to a `.glb` with Assimp, and
     * `udea-gradle`, which carries the compiler on its classpath and forks it.
     *
     * Both are in [HEADLESS_PROJECTS] and stay there. Assimp is a model importer and not a
     * renderer, but it arrives through LWJGL's binding, and `UDEA-MG-002` bans every `org.lwjgl`
     * artifact; so [NO_GL_OUTSIDE_RENDER] excuses exactly [MODEL_CONVERTER_ARTIFACTS] on exactly
     * these two, and a GL binding on either still fails. [NO_MODEL_CONVERTER_AT_RUN_TIME] is the
     * other half: Assimp on any other classpath fails. `udeaVerifyHeadless` reads the same set,
     * through [MODEL_CONVERTER_PROPERTY], to excuse the converter's class references.
     */
    public val MODEL_CONVERTER_PROJECTS: Set<String> = setOf(":udea-assets-compiler", ":udea-gradle")

    /** LWJGL's core, which loads native libraries, and its Assimp binding. Nothing else of LWJGL. */
    private val MODEL_CONVERTER_ARTIFACTS: List<CoordinatePattern> = listOf(
        CoordinatePattern("org.lwjgl:lwjgl"),
        CoordinatePattern("org.lwjgl:lwjgl-assimp"),
    )

    /**
     * The class-file namespace the converter's code names, excused by `udeaVerifyHeadless` in the
     * [MODEL_CONVERTER_PROJECTS] that compile it. `org/lwjgl/opengl/` and every other GL package
     * stay banned there.
     */
    public const val MODEL_CONVERTER_NAMESPACE: String = "org/lwjgl/assimp/"

    /**
     * The system property `udea-render`'s build script uses to hand [MODEL_CONVERTER_PROJECTS]
     * and [MODEL_CONVERTER_NAMESPACE] to the bytecode scan, for the reason
     * [HEADLESS_MODULES_PROPERTY] exists.
     */
    public const val MODEL_CONVERTER_PROPERTY: String = "udea.headless.modelConverter"

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
     * The headless-kernel rule: no renderer, no GL binding and no native loader outside the
     * modules in [GL_ALLOWED_PROJECTS]. LibGDX is not named here; [NO_LIBGDX] bans it from every project.
     */
    /** Kool, every ComposeGL frontend and LWJGL: what [NO_GL_OUTSIDE_RENDER] and [EDITOR_NAMES_NO_RENDERER] ban. */
    private val RENDERER_ARTIFACTS: List<CoordinatePattern> = listOf(
        CoordinatePattern("de.fabmax.kool:*"),
        CoordinatePattern("dev.wildware.composegl:composegl-kool*"),
        CoordinatePattern("dev.wildware.composegl:composegl-lwjgl3*"),
        CoordinatePattern("dev.wildware.composegl:composegl-webgl*"),
        CoordinatePattern("dev.wildware.composegl:composegl-android*"),
        CoordinatePattern("org.lwjgl:*"),
    )

    public val NO_GL_OUTSIDE_RENDER: DependencyRule = DependencyRule(
        id = RuleId("UDEA-MG-002"),
        summary = "only udea-render may see Kool, a ComposeGL backend, a GL backend or a native platform artifact",
        rationale = "udea-core is a headless kernel: the simulation must run in a test JVM, in a " +
            "dedicated server and inside an agent harness with no display. Once a renderer is " +
            "on the compile classpath, a static initialiser or a context reference gets written and " +
            "the headless path is gone. Kool and the ComposeGL backends are the renderer after the " +
            "port (spec section 3: no Kool and no ComposeGL backend outside udea-render); " +
            "composegl-ui, the toolkit with no backend in it, stays legal. The exempt modules are " +
            "ModuleGraphRules.GL_ALLOWED_PROJECTS, whose KDoc says why each one is there and why " +
            "udea-core's guarantee is untouched by them.",
        specSection = "4, 3.5; kool port 3",
        projects = HEADLESS_PROJECTS,
        configurations = setOf("compileClasspath", "runtimeClasspath"),
        banned = RENDERER_ARTIFACTS,
        allowedIn = MODEL_CONVERTER_PROJECTS.associateWith { MODEL_CONVERTER_ARTIFACTS },
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
        rationale = "The old common module pulled in five kotlin-scripting-* artifacts and org.reflections:" +
            "reflections, which is both a startup cost and the mechanism behind the " +
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
     * LibGDX is gone from the tree (issue #213), and this is what keeps it gone.
     *
     * It was removed in three steps: `udea-render` moved to Kool (issue #211), `moba` followed it
     * (issue #212), and #213 deleted the old tree and every `com.badlogicgames` coordinate from
     * the version catalog. Each step had a rule scoped to the project it had just cleaned -
     * `UDEA-MG-008` for `udea-render`, then this id for `moba` - and a ban scoped to the modules
     * that last had LibGDX lets it back in through any other one. So it governs every project,
     * the two exempt from [NO_GL_OUTSIDE_RENDER] included: being allowed GL is not being allowed
     * a second renderer. `UDEA-MG-008` is retired into this rule rather than reused.
     *
     * The patterns are the LibGDX groups, `com.badlogicgames.gdx` and the extensions published
     * beside it such as `com.badlogicgames.box2dlights`, and ComposeGL's LibGDX backend, which
     * drags gdx in transitively without a build script naming it.
     */
    public val NO_LIBGDX: DependencyRule = DependencyRule(
        id = RuleId("UDEA-MG-009"),
        summary = "no project resolves a LibGDX artifact",
        rationale = "LibGDX was deleted from the tree (issue #213): udea-render and moba draw with " +
            "Kool (issues #211, #212), and the old tree that depended on it is gone. A LibGDX " +
            "artifact back on any classpath is a second renderer - the parallel-renderer " +
            "arrangement spec D9 rejected - or the Box2D world spec D4 retires, and on a headless " +
            "module it is a GL-carrying jar. The rule covers every target classpath of every project.",
        specSection = "kool port 3, 4, D4, D9, D12",
        configurations = setOf("compileClasspath", "runtimeClasspath"),
        banned = listOf(
            CoordinatePattern("com.badlogicgames.*:*"),
            CoordinatePattern("dev.wildware.composegl:composegl-gdx*"),
        ),
    )

    /**
     * The editor is a debug tool, and a debug tool reaches a player's machine only by mistake.
     *
     * Issue #194: `udea-editor` is the editor window, and the only thing that may depend on it is a
     * game's `editor` source set (`:moba:desktop`'s), whose classpaths are `editor*Classpath` and so
     * not among [CONFIGURATIONS]. So the rule can be as blunt as "no scanned classpath of any
     * project resolves it": on `:moba:desktop` that is the release runtime classpath, and on an
     * engine module it is an arrow pointing up the module table. A class that reaches a release
     * classpath with no dependency edge - a gizmo, an editor source set's output - is
     * [EditorReleaseRules]' `UDEA-MG-012`, which reads classes where this reads the graph.
     */
    public val NO_EDITOR_ON_A_SHIPPED_CLASSPATH: DependencyRule = DependencyRule(
        id = RuleId("UDEA-MG-010"),
        summary = "no compile or runtime classpath resolves udea-editor; only a game's editor source set may",
        rationale = "The editor window writes any field of any entity through the editor.* tools, " +
            "which ignore agentWritable because authoring a level needs every field. It exists for " +
            "a person at a desk and never for a player. A game reaches it only through its editor " +
            "source set - :moba:desktop's runEditor - whose classpaths are not scanned here, so " +
            "udea-editor on a scanned classpath is either the shipped game carrying the editor or " +
            "an engine module depending upward on it.",
        specSection = "4; issue #194",
        configurations = setOf("compileClasspath", "runtimeClasspath"),
        banned = listOf(CoordinatePattern(":udea-editor")),
    )

    /**
     * The editor is GL-allowed at run time and not at compile time.
     *
     * It needs Kool on its runtime classpath to show the world at all, which is why it is in
     * [GL_ALLOWED_PROJECTS]. But spec section 3 keeps Kool and every ComposeGL frontend inside
     * `udea-render`, and issue #194 says the editor binds both only through it. This is that
     * sentence as a gate: the patterns are [NO_GL_OUTSIDE_RENDER]'s, on `compileClasspath` alone.
     */
    public val EDITOR_NAMES_NO_RENDERER: DependencyRule = DependencyRule(
        id = RuleId("UDEA-MG-011"),
        summary = "udea-editor compiles against no Kool, no ComposeGL frontend and no GL binding",
        rationale = "The editor draws its panels with composegl-ui, the toolkit with no backend in " +
            "it, and shows the world through udea-render, which owns Kool and the ComposeGL Kool " +
            "frontend (kool port spec section 3). Its runtime classpath carries Kool through " +
            "udea-render; its compile classpath carrying it would let an editor panel name a Kool " +
            "type, which is the second renderer binding the spec rules out.",
        specSection = "kool port 3; issue #194",
        projects = setOf(":udea-editor"),
        configurations = setOf("compileClasspath"),
        banned = RENDERER_ARTIFACTS,
    )

    /**
     * An `.fbx` becomes a `.glb` while the game builds, and never while it runs (issue #244).
     *
     * Kool reads glTF and nothing else, so the asset compiler converts every `.fbx` model with
     * Assimp and the game is given the `.glb`. The converter is native code the size of a small
     * game, and a runtime module that could name it could start importing models at run time -
     * the path the design rejects, because the build is where a broken model fails with a
     * diagnostic. So an Assimp binding, LWJGL's or any other, is banned from every runtime
     * module's and every game project's classpath, and allowed in [MODEL_CONVERTER_PROJECTS] alone.
     *
     * A game's `agent` and `editor` source sets are not among [CONFIGURATIONS], and `:moba:desktop`'s
     * `agent` source set does carry the converter: it runs the asset daemon, which is the asset
     * compiler, in process, as it already carries the Kotlin compiler. `UDEA-REL-002` keeps that
     * source set out of every release, and this rule's `runtimeClasspath` is the one that ships.
     */
    public val NO_MODEL_CONVERTER_AT_RUN_TIME: DependencyRule = DependencyRule(
        id = RuleId("UDEA-MG-013"),
        summary = "no runtime module and no game classpath resolves the FBX converter; only the asset build may",
        rationale = "An .fbx model is converted to glTF by the asset compiler, with Assimp, and the game " +
            "is given the .glb (issue #244). Nothing that runs the game may carry the converter: a " +
            "runtime import is the path the design rejects, because a model that does not convert " +
            "must fail the build with a diagnostic, not a player's session. Every Assimp binding is " +
            "banned from every engine runtime module and every game project, on the compile and " +
            "runtime classpaths; the asset compiler and udea-gradle, which carries it, are the exceptions.",
        specSection = "issue #244",
        projects = HEADLESS_PROJECTS + GL_ALLOWED_PROJECTS + MOBA_PROJECTS - MODEL_CONVERTER_PROJECTS,
        configurations = setOf("compileClasspath", "runtimeClasspath"),
        banned = listOf(CoordinatePattern("*:*assimp*")),
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
        NO_LIBGDX,
        NO_EDITOR_ON_A_SHIPPED_CLASSPATH,
        EDITOR_NAMES_NO_RENDERER,
        NO_MODEL_CONVERTER_AT_RUN_TIME,
    )

    /**
     * True when [projectPath] is an engine module or part of the game, and so subject to [ALL].
     *
     * `:moba:` as a prefix and not `:moba` alone, because issue #212 split the game into nested
     * projects (spec D12): `:moba:game`, `:moba:desktop` and `:moba:android`. Matching the parent
     * path only would leave every one of them ungoverned - silently, because an ungoverned project
     * is not reported as skipped. The compiler-plugin wiring asks the same question through here.
     */
    public fun governs(projectPath: String): Boolean =
        projectPath.startsWith(":udea-") || projectPath == ":moba" || projectPath.startsWith(":moba:")

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
