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
 * `RenderMode.Headless` means "no GL context at all").
 *
 * Each entry is a namespace a headless module has no business naming. `UDEA-MG-002` and
 * `UDEA-MG-009` ban the same things at the dependency level, and are checked first; this table is
 * what catches a type that reaches a headless module some other way, such as inside a jar those
 * rules allow.
 *
 * Until issue #213 the LibGDX part of this table was a set of carve-outs - `graphics/`, `Gdx`,
 * `utils/viewport/` and `backends/` banned, `math/` and the `utils` collections legal - because
 * `com.badlogicgames.gdx:gdx` was an allowed jar that carried both. LibGDX has left the tree and
 * `UDEA-MG-009` bans every artifact of it from every project, so there is no allowed half left to
 * carve out, and the whole namespace is one entry.
 */
internal val GL_BANNED_OWNERS: List<BannedOwner> = listOf(
    BannedOwner(
        "org/lwjgl/",
        "LWJGL is the native GL/GLFW binding; nothing outside udea-render may name it",
    ),
    BannedOwner(
        "com/badlogic/",
        "LibGDX left the tree in issue #213; its graphics, backends and natives are GL, and " +
            "UDEA-MG-009 bans every artifact of it",
    ),
    BannedOwner(
        "box2dLight/",
        "box2dlights renders shadows through GL and pulls a native backend with it",
    ),
)
