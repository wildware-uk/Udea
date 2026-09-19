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
 * The distinction is load-bearing rather than pedantic: a single-class entry for `a/b/Gdx` must
 * catch `a/b/Gdx` and `a/b/Gdx$Companion` and not `a/b/GdxRuntimeException`, which a naive
 * `startsWith` would ban too. `HeadlessScanTest` holds both halves.
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
 * `RenderMode.Headless` means "no GL context at all"), read by `HeadlessScan` over the headless
 * modules.
 *
 * Each entry is a namespace a headless module has no business naming. `UDEA-MG-002` bans the same
 * things at the dependency level, and is checked first; this table is what catches a type that
 * reaches a headless module some other way, such as inside a jar that rule allows.
 *
 * LibGDX is not in it, for the reason `UDEA-MG-002` does not name LibGDX either: it is banned from
 * every module, not only the headless ones, and [LIBGDX_BANNED_OWNERS] is that table. One
 * reference, one diagnostic.
 */
internal val GL_BANNED_OWNERS: List<BannedOwner> = listOf(
    BannedOwner(
        "org/lwjgl/",
        "LWJGL is the native GL/GLFW binding; nothing outside udea-render may name it",
    ),
)

/**
 * The LibGDX banned-owner table, read by `LibGdxScan` over **every** module, `udea-render`, the
 * editor and the game included (issue #189).
 *
 * It is the bytecode half of `UDEA-MG-009`, which bans every LibGDX artifact by coordinate. A
 * coordinate check cannot see a LibGDX class that arrived without one: source vendored into the
 * tree under LibGDX's own package, a jar added with `files(...)`, or a shaded jar republished under
 * another group. Whatever the route, the class file names the owner, and this reads the owner.
 *
 * Order matters: the first entry that matches gives the reason, so scene2d comes before the
 * namespace that contains it and a scene2d reference is told what replaced it.
 */
internal val LIBGDX_BANNED_OWNERS: List<BannedOwner> = listOf(
    BannedOwner(
        "com/badlogic/gdx/scenes/scene2d/",
        "scene2d is LibGDX's GL-backed widget toolkit, and the UI layer that replaced it is " +
            "ComposeGL: udea-render's UiLayer for menus and panels, CapturedUi for a HUD " +
            "(issues #187, #188, #189)",
    ),
    BannedOwner(
        "com/badlogic/",
        "LibGDX left the tree in issue #213; rendering is Kool inside udea-render, and " +
            "UDEA-MG-009 bans every artifact of it",
    ),
    BannedOwner(
        "box2dLight/",
        "box2dlights is a LibGDX extension that renders shadows through GL; UDEA-MG-009 bans " +
            "its artifact with the rest of LibGDX",
    ),
)
