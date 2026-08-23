package dev.wildware.udea.assets.compiler.daemon

import dev.wildware.udea.assets.compiler.TestPaths
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * A writable asset tree under `build/tmp/scratch`, plus a daemon over it.
 *
 * Writable is the point: every claim issue #91 makes is about what happens when a file *changes*,
 * so the corpus cannot be the read-only fixture tree `AssetCompilerTest` uses. It lives under
 * `build` rather than in a JUnit `@TempDir` for the reason `TestPaths.scratch` documents - the
 * Kotlin scripting host holds every compiled-script jar open for the life of the JVM, so on
 * Windows `@TempDir` cleanup fails after a test whose assertions all passed.
 *
 * The corpus is deliberately made of **packable** kinds. `character` and `gameConfig` have no
 * runtime value yet ([AssetPacker]), and a hot-reload test built on a kind that cannot be packed
 * would be testing the refusal path while claiming to test the tuning loop.
 */
internal class DaemonFixture(name: String) {

    /** The asset root. Every id below is relative to it. */
    val assetRoot: Path = TestPaths.scratch("daemon/$name/assets")

    private val cache: Path = TestPaths.scratch("daemon/$name/cache")

    /** The daemon under test, not yet started. */
    val daemon: AssetDaemon = AssetDaemon(
        repoRoot = TestPaths.repoRoot,
        assetRoot = assetRoot,
        scriptClasspath = TestPaths.compilerClasspath,
        cacheDirectory = cache,
    )

    /** Writes [text] to `<assetRoot>/<relative>` and returns the absolute path. */
    fun write(relative: String, text: String): Path {
        val file = assetRoot.resolve(relative)
        file.parent.createDirectories()
        file.writeText(text.trimIndent() + "\n")
        return file.toAbsolutePath().normalize()
    }

    /** Deletes `<assetRoot>/<relative>` and returns the path it had. */
    fun delete(relative: String): Path {
        val file = assetRoot.resolve(relative).toAbsolutePath().normalize()
        file.toFile().delete()
        return file
    }

    /**
     * The three-script corpus every test starts from: two sheets, an animation over one of them,
     * a sound cue, a blueprint and a level pointing at the blueprint.
     */
    fun writeBaseline(): DaemonFixture {
        write(
            "character/orc.udea.kts",
            """
            spriteSheet(name = "orc_idle", spritePath = "/sprites/orc/idle.png", rows = 1, columns = 6, scale = 0.02f)
            spriteSheet(name = "orc_walk", spritePath = "/sprites/orc/walk.png", rows = 1, columns = 8, scale = 0.02f)
            spriteAnimation(name = "orc_idle_anim", sheet = reference("character/orc_idle"))
            soundCue(name = "orc_hit", pitchVariance = 0.3f, volume = 1.0f, sounds = listOf("/sounds/orc/hit.ogg"))
            """,
        )
        write(
            "blueprint/player.udea.kts",
            """
            blueprint(name = "player", components = listOf("dev.wildware.moba.Health"))
            """,
        )
        write(
            "level/arena.udea.kts",
            """
            level(name = "arena", entities = listOf(reference("blueprint/player")))
            """,
        )
        return this
    }
}
