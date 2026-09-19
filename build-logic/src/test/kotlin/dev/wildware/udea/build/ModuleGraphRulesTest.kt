package dev.wildware.udea.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The rule set itself, exercised without a Gradle build.
 *
 * The TestKit suites prove the rules are wired to a real resolution; these prove each rule
 * decides what its id says it decides, which is a lot cheaper to check exhaustively here.
 */
class ModuleGraphRulesTest {

    private fun graph(root: String, vararg to: String) =
        ResolvedGraph(root, to.map { DependencyEdge(root, it) })

    private fun violate(project: String, configuration: String, graph: ResolvedGraph) =
        ModuleGraphRules.violations(project, configuration, graph)

    @Test
    fun `UDEA-MG-001 fails anything but the stdlib on the annotations runtime classpath`() {
        val violations = violate(
            ":udea-annotations",
            "runtimeClasspath",
            graph(":udea-annotations", "org.jetbrains.kotlin:kotlin-stdlib", "com.squareup:kotlinpoet"),
        )
        assertEquals(listOf("com.squareup:kotlinpoet"), violations.map { it.coordinate })
        assertEquals(RuleId("UDEA-MG-001"), violations.single().ruleId)
    }

    @Test
    fun `UDEA-MG-001 passes the stdlib and its annotations artifact`() {
        assertTrue(
            violate(
                ":udea-annotations",
                "runtimeClasspath",
                graph(":udea-annotations", "org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains:annotations"),
            ).isEmpty(),
        )
    }

    @Test
    fun `UDEA-MG-002 fails a GL backend and the natives it drags in`() {
        val violations = violate(
            ":udea-core",
            "compileClasspath",
            graph(":udea-core", "de.fabmax.kool:kool-core-desktop", "org.lwjgl:lwjgl-opengl"),
        )
        assertEquals(
            listOf("de.fabmax.kool:kool-core-desktop", "org.lwjgl:lwjgl-opengl"),
            violations.map { it.coordinate },
        )
        assertTrue(violations.all { it.ruleId == RuleId("UDEA-MG-002") })
    }

    @Test
    fun `UDEA-MG-009 fails every LibGDX artifact on every project, gdx-math included`() {
        // Issue #213 deleted LibGDX from the tree. `com.badlogicgames.gdx:gdx` used to be legal on
        // a headless module for `Vector2`; nothing needs it now, and a ban scoped to the modules
        // that last had it would let it back in through any other one. The modules exempt from
        // UDEA-MG-002 are here on purpose: being allowed GL is not being allowed LibGDX.
        val libgdx = listOf(
            "com.badlogicgames.box2dlights:box2dlights",
            "com.badlogicgames.gdx:gdx",
            "com.badlogicgames.gdx:gdx-backend-lwjgl3",
            "com.badlogicgames.gdx:gdx-box2d",
            "com.badlogicgames.gdx:gdx-box2d-platform",
            "dev.wildware.composegl:composegl-gdx",
        )
        listOf(":udea-core", ":udea-gas", ":udea-assets-compiler", ":udea-render", ":udea-agent-host", ":moba:game")
            .forEach { project ->
                val violations = violate(project, "runtimeClasspath", graph(project, *libgdx.toTypedArray()))
                assertEquals(libgdx, violations.map { it.coordinate }, "$project is not guarded")
                assertTrue(violations.all { it.ruleId == RuleId("UDEA-MG-009") }, "$project: $violations")
            }
    }

    @Test
    fun `UDEA-MG-002 fails Kool and a ComposeGL backend outside udea-render`() {
        // Spec section 3: "No GL outside udea-render" became "No Kool and no ComposeGL backend
        // outside udea-render" (issue #211). `composegl-ui` is the toolkit, not a backend, and
        // stays legal: a module can compose a tree without being able to draw one.
        val violations = violate(
            ":udea-gas",
            "runtimeClasspath",
            graph(
                ":udea-gas",
                "de.fabmax.kool:kool-core",
                "de.fabmax.kool:kool-core-desktop",
                "dev.wildware.composegl:composegl-kool",
                "dev.wildware.composegl:composegl-ui",
            ),
        )
        assertEquals(
            listOf("de.fabmax.kool:kool-core", "de.fabmax.kool:kool-core-desktop", "dev.wildware.composegl:composegl-kool"),
            violations.map { it.coordinate },
        )
        assertTrue(violations.all { it.ruleId == RuleId("UDEA-MG-002") })
    }

    @Test
    fun `UDEA-MG-009 fails LibGDX on every nested moba project`() {
        // Issue #212: `moba` drew with `Batch` and solved collisions with `gdx-box2d`, and both
        // left with LibGDX. `:moba:desktop` and `:moba:android` each resolve `:moba:game`, so the
        // ban has to name all of them or the artifact comes back one project along.
        listOf(":moba", ":moba:game", ":moba:desktop", ":moba:android", ":moba:web").forEach { project ->
            val violations = violate(
                project,
                "runtimeClasspath",
                graph(project, "com.badlogicgames.gdx:gdx", "com.badlogicgames.gdx:gdx-box2d"),
            )
            assertEquals(
                listOf("com.badlogicgames.gdx:gdx", "com.badlogicgames.gdx:gdx-box2d"),
                violations.map { it.coordinate },
                "$project is not guarded",
            )
            assertTrue(violations.all { it.ruleId == RuleId("UDEA-MG-009") }, "$project")
        }
    }

    @Test
    fun `UDEA-MG-009 leaves Kool alone - the game draws through udea-render, which draws with Kool`() {
        // The ban is on the renderer that left, not on drawing. `:moba:desktop` resolves Kool
        // transitively through `udea-render` and that is the arrangement, not a violation.
        assertTrue(
            violate(
                ":moba:desktop",
                "runtimeClasspath",
                graph(":moba:desktop", "de.fabmax.kool:kool-core", ":udea-render"),
            ).isEmpty(),
        )
    }

    @Test
    fun `UDEA-MG-010 fails the editor on the shipped desktop game's classpaths`() {
        // Issue #194's second criterion. `:moba:desktop`'s `runtimeClasspath` is what its release
        // jar runs on; the editor reaches that process only through the `editor` source set, whose
        // `editorRuntimeClasspath` is not one of the scanned configurations.
        listOf("runtimeClasspath", "compileClasspath").forEach { configuration ->
            val violations = violate(
                ":moba:desktop",
                configuration,
                graph(":moba:desktop", ":udea-render", ":udea-editor"),
            )
            assertEquals(listOf(":udea-editor"), violations.map { it.coordinate }, configuration)
            assertEquals(RuleId("UDEA-MG-010"), violations.single().ruleId, configuration)
        }
    }

    @Test
    fun `UDEA-MG-010 fails the editor on every engine module, whose arrows point below it`() {
        // Nothing depends on the editor but a game's editor source set: an engine module that
        // resolved it would be an arrow pointing up the table, and would carry it into the game.
        listOf(":udea-render", ":udea-agent", ":udea-agent-host", ":moba:game").forEach { project ->
            val violations = violate(project, "runtimeClasspath", graph(project, ":udea-editor"))
            assertEquals(RuleId("UDEA-MG-010"), violations.single().ruleId, project)
        }
    }

    @Test
    fun `UDEA-MG-010 leaves the editor's own classpath and a test classpath alone`() {
        // The root never violates a rule about itself, and a test source set is not a release.
        assertTrue(violate(":udea-editor", "runtimeClasspath", graph(":udea-editor", ":udea-render")).isEmpty())
        assertTrue(
            violate(":moba:desktop", "testRuntimeClasspath", graph(":moba:desktop", ":udea-editor")).isEmpty(),
        )
    }

    @Test
    fun `UDEA-MG-011 fails Kool and a ComposeGL frontend on the editor's compile classpath`() {
        // The editor composes with `composegl-ui` and binds the world draw and the Kool frontend
        // only through `udea-render` (issue #194). Its *runtime* classpath carries Kool through
        // `udea-render`, which is why it is GL-allowed; what it may *name* is this rule's business.
        val violations = violate(
            ":udea-editor",
            "compileClasspath",
            graph(
                ":udea-editor",
                ":udea-render",
                "dev.wildware.composegl:composegl-ui",
                "de.fabmax.kool:kool-core-desktop",
                "dev.wildware.composegl:composegl-kool",
                "org.lwjgl:lwjgl-opengl",
            ),
        )
        assertEquals(
            listOf("de.fabmax.kool:kool-core-desktop", "dev.wildware.composegl:composegl-kool", "org.lwjgl:lwjgl-opengl"),
            violations.map { it.coordinate },
        )
        assertTrue(violations.all { it.ruleId == RuleId("UDEA-MG-011") }, "$violations")
    }

    @Test
    fun `UDEA-MG-011 does not govern the editor's runtime classpath, where Kool arrives through udea-render`() {
        assertTrue(
            violate(
                ":udea-editor",
                "runtimeClasspath",
                graph(":udea-editor", ":udea-render", "de.fabmax.kool:kool-core-desktop", "org.lwjgl:lwjgl-opengl"),
            ).isEmpty(),
        )
    }

    @Test
    fun `UDEA-MG-002 leaves udea-render alone - it is the module allowed to see GL`() {
        assertTrue(
            violate(
                ":udea-render",
                "compileClasspath",
                graph(":udea-render", "de.fabmax.kool:kool-core-desktop", "org.lwjgl:lwjgl-glfw"),
            ).isEmpty(),
        )
    }

    @Test
    fun `UDEA-MG-003 fails gradleApi, which reaches the classpath as a file dependency`() {
        val violations = violate(
            ":udea-assets-compiler",
            "compileClasspath",
            graph(":udea-assets-compiler", "file:Gradle API"),
        )
        assertEquals(RuleId("UDEA-MG-003"), violations.single().ruleId)
    }

    @Test
    fun `UDEA-MG-003 fails a published Gradle module too`() {
        val violations = violate(
            ":udea-assets-compiler",
            "testRuntimeClasspath",
            graph(":udea-assets-compiler", "org.gradle:gradle-core-api"),
        )
        assertEquals(listOf("org.gradle:gradle-core-api"), violations.map { it.coordinate })
    }

    @Test
    fun `UDEA-MG-003 does not govern udea-gradle, which is allowed gradleApi as compileOnly`() {
        assertTrue(violate(":udea-gradle", "compileClasspath", graph(":udea-gradle", "file:Gradle API")).isEmpty())
    }

    @Test
    fun `UDEA-MG-004 fails udea-gradle on a runtime classpath`() {
        val violations = violate(":moba", "runtimeClasspath", graph(":moba", ":udea-gradle"))
        assertEquals(RuleId("UDEA-MG-004"), violations.single().ruleId)
    }

    @Test
    fun `UDEA-MG-004 does not fire on a compile classpath, where a plugin author may need it`() {
        assertTrue(violate(":udea-core", "compileClasspath", graph(":udea-core", ":udea-gradle")).isEmpty())
    }

    @Test
    fun `UDEA-MG-005 fails a scripting host, kotlin-reflect and a classpath scanner in the game`() {
        val violations = violate(
            ":moba",
            "runtimeClasspath",
            graph(
                ":moba",
                "org.jetbrains.kotlin:kotlin-scripting-jvm-host",
                "org.jetbrains.kotlin:kotlin-reflect",
                "org.reflections:reflections",
            ),
        )
        assertEquals(
            listOf(
                "org.jetbrains.kotlin:kotlin-reflect",
                "org.jetbrains.kotlin:kotlin-scripting-jvm-host",
                "org.reflections:reflections",
            ),
            violations.map { it.coordinate },
        )
        assertTrue(violations.all { it.ruleId == RuleId("UDEA-MG-005") })
    }

    @Test
    fun `UDEA-MG-005 governs only the game, not the build-time modules that need scripting`() {
        assertTrue(
            violate(
                ":udea-assets-compiler",
                "runtimeClasspath",
                graph(":udea-assets-compiler", "org.jetbrains.kotlin:kotlin-scripting-jvm-host"),
            ).isEmpty(),
        )
    }

    @Test
    fun `UDEA-MG-006 fails anything the asset model is not allowed to hold`() {
        val violations = violate(
            ":udea-assets",
            "runtimeClasspath",
            graph(
                ":udea-assets",
                ":udea-annotations",
                ":udea-diagnostics",
                "org.jetbrains.kotlin:kotlin-stdlib",
                // The three the old asset tree needed, and could not be read without.
                "com.badlogicgames.gdx:gdx",
                "com.fasterxml.jackson.core:jackson-databind",
                "org.jetbrains.kotlin:kotlin-scripting-jvm-host",
            ),
        )
        // LibGDX is also UDEA-MG-009's, which bans it everywhere; this rule's own answer is the
        // allow list, and gdx is outside it whatever any other rule says.
        assertEquals(
            listOf(
                "com.badlogicgames.gdx:gdx",
                "com.fasterxml.jackson.core:jackson-databind",
                "org.jetbrains.kotlin:kotlin-scripting-jvm-host",
            ),
            violations.filter { it.ruleId == RuleId("UDEA-MG-006") }.map { it.coordinate },
        )
    }

    @Test
    fun `UDEA-MG-006 fails udea-core on the asset model, which would be a dependency cycle`() {
        val violations = violate(
            ":udea-assets",
            "compileClasspath",
            graph(":udea-assets", ":udea-core", ":common"),
        )
        assertEquals(listOf(":common", ":udea-core"), violations.map { it.coordinate }.sorted())
    }

    @Test
    fun `UDEA-MG-006 passes what the asset model is allowed, kotlinx-io included`() {
        assertEquals(
            emptyList(),
            violate(
                ":udea-assets",
                "compileClasspath",
                graph(
                    ":udea-assets",
                    ":udea-annotations",
                    ":udea-diagnostics",
                    "org.jetbrains.kotlin:kotlin-stdlib",
                    "org.jetbrains:annotations",
                    // Issue #205: the `.udeapak` reader names a file by a kotlinx-io `Path` on
                    // every target, and `kotlinx-io-core` brings `kotlinx-io-bytestring` with it.
                    "org.jetbrains.kotlinx:kotlinx-io-core",
                    "org.jetbrains.kotlinx:kotlinx-io-bytestring",
                    // What a target's classpath actually resolves those two to.
                    "org.jetbrains.kotlinx:kotlinx-io-core-jvm",
                    "org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm",
                    "org.jetbrains.kotlinx:kotlinx-io-core-wasm-js",
                    "org.jetbrains.kotlinx:kotlinx-io-core-iosarm64",
                ),
            ).map { it.coordinate },
        )
    }

    @Test
    fun `UDEA-MG-006 allows kotlinx-io and not the rest of kotlinx`() {
        val violations = violate(
            ":udea-assets",
            "runtimeClasspath",
            graph(
                ":udea-assets",
                "org.jetbrains.kotlinx:kotlinx-io-core",
                "org.jetbrains.kotlinx:kotlinx-serialization-core",
                "org.jetbrains.kotlinx:kotlinx-coroutines-core",
                // kotlinx-io's other artifacts are not the reader's, and a prefix match on
                // `kotlinx-io-` would have let them in.
                "org.jetbrains.kotlinx:kotlinx-io-okio",
            ),
        )
        assertEquals(
            listOf(
                "org.jetbrains.kotlinx:kotlinx-coroutines-core",
                "org.jetbrains.kotlinx:kotlinx-io-okio",
                "org.jetbrains.kotlinx:kotlinx-serialization-core",
            ),
            violations.map { it.coordinate }.sorted(),
        )
    }

    @Test
    fun `UDEA-MG-006 governs only the asset model, not its compiler`() {
        assertTrue(
            violate(
                ":udea-assets-compiler",
                "compileClasspath",
                graph(":udea-assets-compiler", "com.fasterxml.jackson.core:jackson-databind"),
            ).isEmpty(),
        )
    }

    @Test
    fun `UDEA-MG-007 fails any Udea module and any library but serialization on vendored Fleks`() {
        val violations = violate(
            ":udea-fleks",
            "runtimeClasspath",
            graph(
                ":udea-fleks",
                "org.jetbrains.kotlin:kotlin-stdlib",
                "org.jetbrains.kotlinx:kotlinx-serialization-core",
                // An arrow from the vendored library back into the engine it sits under.
                ":udea-core",
                ":udea-annotations",
                // Upstream declares this on its main classpath; its main source does not use it.
                "org.jetbrains.kotlinx:kotlinx-serialization-json",
            ),
        )
        assertEquals(
            listOf(":udea-annotations", ":udea-core", "org.jetbrains.kotlinx:kotlinx-serialization-json"),
            violations.map { it.coordinate }.sorted(),
        )
        assertTrue(violations.all { it.ruleId == RuleId("UDEA-MG-007") })
    }

    @Test
    fun `UDEA-MG-007 passes the stdlib and serialization core on every target`() {
        assertEquals(
            emptyList(),
            violate(
                ":udea-fleks",
                "compileClasspath",
                graph(
                    ":udea-fleks",
                    "org.jetbrains.kotlin:kotlin-stdlib",
                    "org.jetbrains.kotlin:kotlin-stdlib-wasm-js",
                    "org.jetbrains:annotations",
                    "org.jetbrains.kotlinx:kotlinx-serialization-core",
                    "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm",
                    "org.jetbrains.kotlinx:kotlinx-serialization-bom",
                    "org.jetbrains.kotlinx:kotlinx-serialization-core-wasm-js",
                    "org.jetbrains.kotlinx:kotlinx-serialization-core-iosarm64",
                ),
            ).map { it.coordinate },
        )
    }

    @Test
    fun `UDEA-MG-007 governs only the vendored module`() {
        assertTrue(
            violate(
                ":udea-core",
                "runtimeClasspath",
                graph(":udea-core", ":udea-fleks", "org.jetbrains.kotlinx:kotlinx-serialization-cbor"),
            ).isEmpty(),
        )
    }

    @Test
    fun `a transitive violation is reported with the path that produced it`() {
        val transitive = ResolvedGraph(
            root = ":udea-core",
            edges = listOf(
                DependencyEdge(":udea-core", "com.example:physics"),
                DependencyEdge("com.example:physics", "org.lwjgl:lwjgl"),
            ),
        )
        val violation = violate(":udea-core", "compileClasspath", transitive).single()
        assertEquals(
            listOf(":udea-core", "com.example:physics", "org.lwjgl:lwjgl"),
            violation.resolutionPath,
        )
        assertTrue("com.example:physics" in violation.describe(), violation.describe())
    }

    @Test
    fun `the headless set is every udea module in settings_gradle_kts except the GL-allowed ones`() {
        // The gap this closes: `HEADLESS_PROJECTS` used to be a hand-written subset, and a
        // module added to `settings.gradle.kts` joined neither the dependency rule nor the
        // bytecode scan. Deriving the expectation from the settings file makes including a
        // new `udea-*` module a red test rather than a silent hole in UDEA-MG-002.
        //
        // Subtracting `GL_ALLOWED_PROJECTS` rather than a literal `":udea-render"` is what
        // keeps that property after the controller ruling that put `udea-agent-host` on the
        // GL side: an exemption has to be *stated* in the rules object to be subtracted here,
        // so quietly deleting a module from `HEADLESS_PROJECTS` still fails this test.
        val settings = File("../settings.gradle.kts").canonicalFile
        assertTrue(settings.isFile, "settings.gradle.kts not found at ${settings.absolutePath}")
        val included = Regex("""^include\("(udea-[a-z0-9-]+)"\)""", RegexOption.MULTILINE)
            .findAll(settings.readText())
            .map { ":" + it.groupValues[1] }
            .toSortedSet()
        assertTrue(
            included.size > 5,
            "the settings scan found only $included - the regex has stopped matching, so this " +
                "test would pass against nothing",
        )
        assertEquals(
            (included - ModuleGraphRules.GL_ALLOWED_PROJECTS).toList(),
            ModuleGraphRules.HEADLESS_PROJECTS.sorted(),
            "every udea-* module outside ModuleGraphRules.GL_ALLOWED_PROJECTS must be in " +
                "ModuleGraphRules.HEADLESS_PROJECTS",
        )
    }

    @Test
    fun `the GL-allowed set is exactly udea-render, the debug agent host and the editor`() {
        // Stated as its own assertion so that widening the exemption is a deliberate edit to
        // a test, not a side effect of editing a list. Every module here is a module
        // `udeaVerifyHeadless` no longer scans, so the set is the whole of what the headless
        // guarantee costs. The editor joined in issue #194: it runs on Kool through udea-render,
        // UDEA-MG-011 keeps Kool off what it compiles against, and UDEA-MG-010 keeps it off every
        // shipped classpath.
        assertEquals(
            listOf(":udea-agent-host", ":udea-editor", ":udea-render"),
            ModuleGraphRules.GL_ALLOWED_PROJECTS.sorted(),
        )
    }

    @Test
    fun `udea-core is headless whatever else is exempted`() {
        // The guarantee that actually matters (spec 4, spec 3.5): the simulation kernel runs
        // in a test JVM, a dedicated server and an agent harness with no display. It is
        // asserted separately from the derived set above because that one would stay green if
        // `:udea-core` were added to GL_ALLOWED_PROJECTS.
        assertTrue(":udea-core" in ModuleGraphRules.HEADLESS_PROJECTS)
        assertTrue(":udea-core" !in ModuleGraphRules.GL_ALLOWED_PROJECTS)
        val violations = violate(
            ":udea-core",
            "compileClasspath",
            graph(":udea-core", "org.lwjgl:lwjgl-opengl"),
        )
        assertEquals(RuleId("UDEA-MG-002"), violations.single().ruleId)
    }

    @Test
    fun `the debug agent host may see GL, because it owns the render toolset`() {
        // Spec 4 gives udea-agent-host "the toolsets that need a render context or live
        // input: render, input, ui". Owning the render toolset and being unable to name a
        // render type is a contradiction, and it is the one that stranded both
        // OffscreenRenderControl and the GL overlay in test sources. UDEA-REL-002 is what
        // keeps the module off a shipped classpath - see the assertion below, which is the
        // other half of the ruling.
        assertTrue(
            violate(
                ":udea-agent-host",
                "compileClasspath",
                graph(":udea-agent-host", "org.lwjgl:lwjgl-opengl"),
            ).isEmpty(),
        )
        // ...and it is still refused a release build, which is what makes the exemption safe.
        assertTrue(
            ReleaseRules.CLASSPATH_RULE.banned.any { it.matches(":udea-agent-host") },
            "UDEA-REL-002 must still ban :udea-agent-host; it is the rule the GL exemption " +
                "leans on for the guarantee it gives up",
        )
    }

    @Test
    fun `UDEA-MG-002 governs exactly the headless set`() {
        // The dependency rule and the bytecode scan are "the same rule, one level down"
        // (docs/module-graph.md). They are only that while both read HEADLESS_PROJECTS.
        assertEquals(
            ModuleGraphRules.HEADLESS_PROJECTS,
            ModuleGraphRules.NO_GL_OUTSIDE_RENDER.projects,
        )
    }

    @Test
    fun `UDEA-MG-002 covers the modules that were previously in neither gate`() {
        // Each of these was outside both the dependency rule and the bytecode scan, so
        // a GL backend on any of them stayed green twice over.
        // `:udea-agent-host` was in this list too and is now deliberately exempt - see
        // `the debug agent host may see GL, because it owns the render toolset`.
        listOf(":udea-diagnostics", ":udea-gradle", ":udea-compiler-plugin").forEach {
            val violations = violate(it, "compileClasspath", graph(it, "org.lwjgl:lwjgl-opengl"))
            assertEquals(RuleId("UDEA-MG-002"), violations.single().ruleId, "$it is not guarded")
        }
    }

    @Test
    fun `UDEA-MG-002 lets the asset build resolve the FBX converter and nothing else of LWJGL`() {
        // Issue #244: the asset compiler converts `.fbx` to `.glb` with Assimp, through LWJGL's
        // binding, and `udea-gradle` carries the compiler. Those two artifacts, on those two
        // modules, are the whole exemption: a GL binding on the same classpath still fails.
        ModuleGraphRules.MODEL_CONVERTER_PROJECTS.forEach { project ->
            val violations = violate(
                project,
                "runtimeClasspath",
                graph(project, "org.lwjgl:lwjgl", "org.lwjgl:lwjgl-assimp", "org.lwjgl:lwjgl-opengl", "de.fabmax.kool:kool-core"),
            )
            assertEquals(
                listOf("de.fabmax.kool:kool-core", "org.lwjgl:lwjgl-opengl"),
                violations.filter { it.ruleId == RuleId("UDEA-MG-002") }.map { it.coordinate },
                project,
            )
        }
        assertEquals(listOf(":udea-assets-compiler", ":udea-gradle"), ModuleGraphRules.MODEL_CONVERTER_PROJECTS.sorted())
    }

    @Test
    fun `UDEA-MG-002 still refuses the FBX converter on every other headless module`() {
        (ModuleGraphRules.HEADLESS_PROJECTS - ModuleGraphRules.MODEL_CONVERTER_PROJECTS).forEach { project ->
            val violations = violate(project, "compileClasspath", graph(project, "org.lwjgl:lwjgl", "org.lwjgl:lwjgl-assimp"))
                .filter { it.ruleId == RuleId("UDEA-MG-002") }
            assertEquals(listOf("org.lwjgl:lwjgl", "org.lwjgl:lwjgl-assimp"), violations.map { it.coordinate }, project)
        }
    }

    @Test
    fun `UDEA-MG-013 fails an Assimp binding on every runtime module and every game classpath`() {
        // The GL-allowed modules and the game are where UDEA-MG-002 says nothing about LWJGL, so
        // they are the projects this rule exists for; the headless ones are covered twice.
        val governed = ModuleGraphRules.HEADLESS_PROJECTS + ModuleGraphRules.GL_ALLOWED_PROJECTS +
            listOf(":moba:game", ":moba:desktop", ":moba:android") - ModuleGraphRules.MODEL_CONVERTER_PROJECTS
        governed.forEach { project ->
            listOf("compileClasspath", "runtimeClasspath").forEach { configuration ->
                val violations = violate(project, configuration, graph(project, "org.lwjgl:lwjgl-assimp", "com.example:jassimp"))
                    .filter { it.ruleId == RuleId("UDEA-MG-013") }
                assertEquals(
                    listOf("com.example:jassimp", "org.lwjgl:lwjgl-assimp"),
                    violations.map { it.coordinate },
                    "$project $configuration",
                )
            }
        }
    }

    @Test
    fun `UDEA-MG-013 leaves the asset build itself alone, and Kool's own LWJGL`() {
        ModuleGraphRules.MODEL_CONVERTER_PROJECTS.forEach { project ->
            assertTrue(
                violate(project, "runtimeClasspath", graph(project, "org.lwjgl:lwjgl-assimp"))
                    .none { it.ruleId == RuleId("UDEA-MG-013") },
                project,
            )
        }
        // The renderer and the game resolve LWJGL through Kool; that is not the converter.
        assertTrue(
            violate(":moba:desktop", "runtimeClasspath", graph(":moba:desktop", "org.lwjgl:lwjgl", "org.lwjgl:lwjgl-opengl"))
                .isEmpty(),
        )
    }

    @Test
    fun `every rule governs at least one project settings_gradle_kts includes`() {
        // A rule scoped only to projects that do not exist scans nothing and passes for ever,
        // and nothing about it looks wrong: `udeaVerifyModuleGraph` is green because there is no
        // classpath to fail. Issue #212 split `:moba` into nested projects, and UDEA-MG-005 - the
        // ban on a scripting host and a classpath scanner in the shipped game - was still scoped
        // to the flat `:moba` that no longer exists. A rule may still name a path that is gone
        // (MG-009 keeps `:moba` so re-creating it cannot re-open the hole); it may not name
        // *only* such paths.
        val settings = File("../settings.gradle.kts").canonicalFile
        assertTrue(settings.isFile, "settings.gradle.kts not found at ${settings.absolutePath}")
        val included = Regex("""^include\("([a-z0-9:-]+)"\)""", RegexOption.MULTILINE)
            .findAll(settings.readText())
            .map { ":" + it.groupValues[1] }
            .toSet()
        assertTrue(
            ":moba:game" in included && ":udea-core" in included,
            "the settings scan found only $included - the regex has stopped matching, so this " +
                "test would pass against nothing",
        )
        val scanningNothing = ModuleGraphRules.ALL
            .filter { it.projects.isNotEmpty() && it.projects.none { project -> project in included } }
            .map { "${it.id.value} governs only ${it.projects.sorted()}" }
        assertEquals(emptyList(), scanningNothing, "rules that govern no project in settings.gradle.kts")
    }

    @Test
    fun `every rule id is unique`() {
        val ids = ModuleGraphRules.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate rule id in ModuleGraphRules.ALL: $ids")
    }

    @Test
    fun `every rule id and its rationale is documented in docs_module-graph_md`() {
        // A rule id that appears in a build failure and nowhere else is an error message
        // nobody can act on. This is what stops a rule being added without an explanation.
        val docs = File("../docs/module-graph.md").canonicalFile
        assertTrue(docs.isFile, "docs/module-graph.md not found at ${docs.absolutePath}")
        val text = docs.readText()
        ModuleGraphRules.ALL.forEach { rule ->
            assertTrue(rule.id.value in text, "${rule.id} is not documented in docs/module-graph.md")
        }
        assertTrue(ReleaseRules.ARTIFACT_RULE_ID.value in text, "${ReleaseRules.ARTIFACT_RULE_ID} is not documented")
        assertTrue(EditorReleaseRules.RULE_ID.value in text, "${EditorReleaseRules.RULE_ID} is not documented")
        assertTrue(ReleaseRules.CLASSPATH_RULE.id.value in text, "${ReleaseRules.CLASSPATH_RULE.id} is not documented")
        ReleaseRules.DEFAULT_BANNED_PREFIXES.forEach {
            assertTrue(it in text, "banned release prefix '$it' is not documented in docs/module-graph.md")
        }
    }

    @Test
    fun `the report names the module, the rule id and the offending coordinate`() {
        val report = assertNotNull(
            ModuleGraphRules.report(
                violate(":udea-core", "compileClasspath", graph(":udea-core", "org.lwjgl:lwjgl")),
            ),
        )
        assertTrue(":udea-core" in report, report)
        assertTrue("UDEA-MG-002" in report, report)
        assertTrue("org.lwjgl:lwjgl" in report, report)
    }
}
