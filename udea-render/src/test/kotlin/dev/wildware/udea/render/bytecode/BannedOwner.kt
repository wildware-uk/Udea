package dev.wildware.udea.render.bytecode

/**
 * One entry in a bytecode rule's banned-owner table.
 *
 * A rule is a table plus a set of modules, never a hand-written traversal: adding "and
 * `box2dLight` too" must be one line, or it will not happen at the moment somebody notices
 * it is missing.
 *
 * [pattern] is an internal name (`/`-separated, as it appears in a class file):
 *
 * - ending in `/` it is a **package prefix** and matches everything beneath it;
 * - otherwise it is a **single class**, matching that class and its nested classes only.
 *
 * The distinction is load-bearing rather than pedantic. `com/badlogic/gdx/Gdx` is banned --
 * it is the static handle to the GL context and the application -- while
 * `com/badlogic/gdx/GdxRuntimeException` and `com/badlogic/gdx/math/Vector2` are perfectly
 * legal in a headless module. A naive `startsWith` would ban all three and the gate would be
 * turned off within a week.
 */
internal data class BannedOwner(
    val pattern: String,
    /** Why it is banned, in one clause. Goes into the failure message. */
    val why: String,
) {
    init {
        require(pattern.isNotBlank()) { "BannedOwner.pattern must not be blank" }
        require('.' !in pattern) { "BannedOwner.pattern must be an internal name: '$pattern'" }
        require(why.isNotBlank()) { "BannedOwner.why must not be blank" }
    }

    private val isPackage: Boolean = pattern.endsWith("/")

    /** True when [owner], an internal name, is covered by this entry. */
    fun matches(owner: String): Boolean =
        if (isPackage) owner.startsWith(pattern) else owner == pattern || owner.startsWith("$pattern$")
}

/**
 * The GL banned-owner table (spec 4, "no GL on the compile classpath"; spec 3.5,
 * `RenderMode.Headless` means "no GL context at all").
 *
 * `com/badlogic/gdx` as a whole is deliberately *not* here. `Vector2`, `Rectangle` and the
 * rest of `gdx-math` are headless value types the simulation legitimately uses; the ban is
 * on GL and on natives, not on maths. That distinction is the same one `UDEA-MG-002` draws
 * at the dependency level, and the two must agree or one of them is wrong.
 *
 * `com/badlogic/gdx/utils/` is likewise not banned as a package -- `Array`, `ObjectMap` and
 * `Pool` are headless collections -- but two things inside it are, by exact name and by
 * sub-package: `GdxNativesLoader`, which links the desktop natives, and `utils/viewport/`,
 * which reaches `Gdx.gl` through `HdpiUtils` on every `apply()`. The viewport case is the one
 * a configuration rule structurally cannot see: it ships inside `com.badlogicgames.gdx:gdx`,
 * the jar `UDEA-MG-002` deliberately allows, which is exactly the transitive gap this scan
 * exists to close. `HeadlessScanTest` names a `Viewport` as a positive control.
 */
internal val GL_BANNED_OWNERS: List<BannedOwner> = listOf(
    BannedOwner(
        "com/badlogic/gdx/graphics/",
        "textures, meshes, shaders, framebuffers and cameras are GL objects and need a context",
    ),
    BannedOwner(
        "com/badlogic/gdx/scenes/",
        "scene2d is GL-backed UI; a headless module that draws UI is not headless",
    ),
    BannedOwner(
        "com/badlogic/gdx/Gdx",
        "the static handles to the GL context, the application and the window",
    ),
    BannedOwner(
        "box2dLight/",
        "box2dlights renders shadows through GL and pulls a native backend with it",
    ),
    BannedOwner(
        "org/lwjgl/",
        "LWJGL is the native GL/GLFW binding; nothing outside udea-render may name it",
    ),
    BannedOwner(
        "com/badlogic/gdx/utils/viewport/",
        "a Viewport reaches Gdx.gl through HdpiUtils in apply() and update(), so a module " +
            "holding one dies on a machine with no display",
    ),
    BannedOwner(
        "com/badlogic/gdx/backends/",
        "a gdx backend *is* a window and a context; UDEA-MG-002 catches it as a dependency, " +
            "and this catches it as a type named through one that is allowed",
    ),
    BannedOwner(
        "com/badlogic/gdx/utils/GdxNativesLoader",
        "it extracts and links the gdx desktop natives, which a headless module must never " +
            "require to be present",
    ),
)
