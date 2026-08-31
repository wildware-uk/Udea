package dev.wildware.udea.build

import dev.wildware.udea.gradle.UdeaAssetsPlugin
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Which character sheets `:moba` needs, and where the repository already keeps them.
 *
 * ## Why a build stages art at all
 *
 * `moba/assets/character` names sheets under `sprites/`, and those pixels are third-party licensed
 * art from the Tiny RPG Character Asset Pack. This repository is public and has no right to
 * sublicense them, so `.gitignore` excludes the whole destination tree and a clone carries none of
 * it. Until this task existed, `:moba:udeaValidateAssets` therefore refused the manifest on every
 * clean clone and on every CI runner with one `UDEA0032` per sheet, and the only way out was a
 * shell step named in one document nobody had to read.
 *
 * Every file named here is **already committed**, under [SOURCE_TREE]. Copying out of it adds no
 * exposure; committing a second set of the same frames under a second path would have doubled one
 * that `docs/art-assets.md` documents and recommends removing.
 *
 * ## Why the plan is data and not a search
 *
 * Nothing here looks at the filesystem to decide what to copy. A configuration-time probe of a
 * directory is a decision the configuration cache would make once and then reuse against a tree
 * that had changed underneath it, and a build that stages a different set of sheets depending on
 * what it found is a build whose output depends on the machine. So the mapping is a literal, and
 * `CharacterArtStagingTest` holds it against the sprites the real `.udea.kts` name: a seventh
 * character fails that test by name rather than failing CI three pushes later.
 *
 * ## The coupling this carries, stated
 *
 * [SOURCE_TREE] is inside `example`, which is old tree scheduled for deletion. Deleting the
 * *module* from `settings.gradle.kts` is safe - this reads files, and declares no Gradle
 * dependency on the project, so `UDEA-LEGACY-001` is not engaged. Deleting the *files* is what
 * breaks it, and it breaks loudly: [UdeaStageCharacterArtTask] names every sheet it could not
 * find. `docs/art-assets.md` carries the decision and what to do instead.
 */
public object CharacterArtStaging {

    /** The task a game registers to put its character art in place before the asset pipeline runs. */
    public const val TASK: String = "udeaStageCharacterArt"

    /** Repo-relative tree the sheets are copied out of. Committed; see the class KDoc. */
    public const val SOURCE_TREE: String = "example/src/main/resources/assets/sprites"

    /** Repo-relative tree the sheets are copied into: the part of `:moba`'s asset root git ignores. */
    public const val DESTINATION_TREE: String = "moba/assets/sprites"

    /**
     * The sheets each character's `.udea.kts` names, by the file name it names them at.
     *
     * The names are the committed ones, capitalisation included, because two of the six characters
     * disagree about it and a build that "fixed" either would stage a file the manifest cannot
     * find on a case-sensitive filesystem while passing on a Mac.
     */
    private val SHEETS: Map<String, List<String>> = mapOf(
        "orc" to listOf(
            "Orc-Attack01.png",
            "Orc-Death.png",
            "Orc-Hurt.png",
            "Orc-Idle.png",
            "Orc-Walk.png",
        ),
        "orc_elite" to listOf(
            "orc_elite_attack01.png",
            "orc_elite_attack02.png",
            "orc_elite_death.png",
            "orc_elite_hurt.png",
            "orc_elite_idle.png",
            "orc_elite_walk.png",
        ),
        "priest" to listOf(
            "Priest-Attack.png",
            "Priest-Death.png",
            "Priest-Heal.png",
            "Priest-Hurt.png",
            "Priest-Idle.png",
            "Priest-Walk.png",
        ),
        "skeleton" to listOf(
            "Skeleton-Attack01.png",
            "Skeleton-Death.png",
            "Skeleton-Hurt.png",
            "Skeleton-Idle.png",
            "Skeleton-Walk.png",
        ),
        "soldier" to listOf(
            "Soldier-Attack01.png",
            "Soldier-Attack03.png",
            "Soldier-Death.png",
            "Soldier-Hurt.png",
            "Soldier-Idle.png",
            "Soldier-Walk.png",
        ),
        "wizard" to listOf(
            "Wizard-Attack01.png",
            "Wizard-Death.png",
            "Wizard-Hurt.png",
            "Wizard-Idle.png",
            "Wizard-Walk.png",
        ),
    )

    /**
     * The characters the committed tree nests one directory deeper than the rest, and by what.
     *
     * Data rather than a fallback search, for the reason the class KDoc gives: a search that tries
     * two places and takes what it finds resolves differently on two machines.
     */
    private val NESTED: Map<String, String> = mapOf("wizard" to "Wizard")

    /**
     * Destination-relative path to source-relative path, both under the two trees above.
     *
     * `orc/Orc-Idle.png` to `orc/Orc-Idle.png`; `wizard/Wizard-Idle.png` to
     * `wizard/Wizard/Wizard-Idle.png`.
     */
    public val PLAN: Map<String, String> = SHEETS.entries
        .flatMap { (character, sheets) ->
            val nested = NESTED[character]
            sheets.map { sheet ->
                val source = if (nested == null) "$character/$sheet" else "$character/$nested/$sheet"
                "$character/$sheet" to source
            }
        }
        .toMap()
}

/**
 * Copies the sheets of [CharacterArtStaging.PLAN] into the game's asset root.
 *
 * Idempotent: it overwrites what it copies and deletes nothing, so a developer who has staged the
 * art by hand, or who is holding a newer sheet in place, loses nothing but that one file.
 *
 * ## Where the outputs are declared, and why not the directory
 *
 * [stagedSheets] names the individual copies rather than declaring the destination directory as an
 * `@OutputDirectory`. Two committed files live in that directory - the champion placeholder and the
 * free demo pack's arrow - and an output *directory* invites Gradle's stale-output cleanup to
 * remove whatever this task did not write. Deleting a file that is in git, out of a build, is not
 * a failure anybody would think to look for.
 */
public abstract class UdeaStageCharacterArtTask : DefaultTask() {

    /** Destination-relative to source-relative. [CharacterArtStaging.PLAN] in a real build. */
    @get:Input
    public abstract val plan: MapProperty<String, String>

    /**
     * The committed tree the sheets are copied out of.
     *
     * `RELATIVE` so the cache key is the shape and contents of that tree rather than where this
     * checkout happens to sit, which is the property `UdeaAssetTask` makes the same choice for.
     */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val sourceTree: DirectoryProperty

    /** Where the copies land. `@Internal` for [sourceTree]'s reason; [stagedSheets] is the output. */
    @get:Internal
    public abstract val destinationTree: DirectoryProperty

    /** Every file this task writes, so Gradle can tell an up-to-date staging from a missing one. */
    @get:OutputFiles
    public abstract val stagedSheets: ConfigurableFileCollection

    /** Stages, or fails naming every sheet it could not find. */
    @TaskAction
    public fun stage() {
        val source = sourceTree.get().asFile
        val destination = destinationTree.get().asFile
        val missing = mutableListOf<String>()
        var copied = 0

        for ((target, origin) in plan.get().toSortedMap()) {
            val from = File(source, origin)
            if (!from.isFile) {
                missing += origin
                continue
            }
            val to = File(destination, target)
            to.parentFile?.mkdirs()
            Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            copied++
        }

        if (missing.isNotEmpty()) {
            throw GradleException(
                "$name cannot stage the character art: ${missing.size} sheet(s) named by this " +
                    "game's assets are not under ${source.absolutePath}:\n" +
                    missing.sorted().joinToString("\n") { "  $it" } +
                    "\nThat tree holds this repository's only copy of them. If it has moved, or " +
                    "the art has been re-sourced, CharacterArtStaging has to name the new " +
                    "location - see docs/art-assets.md.",
            )
        }
        logger.lifecycle("[$name] staged $copied sheet(s) into ${destination.absolutePath}")
    }
}

/**
 * Registers [CharacterArtStaging.TASK] and puts it ahead of everything that reads the asset tree.
 *
 * Three tasks, and all three are needed rather than only the one that reports the failure.
 * `udeaValidateAssets` is where a missing sheet is *diagnosed*, but `udeaScanAssets` and
 * `udeaPackBundle` both take the whole asset tree as an input, so a build that ordered only the
 * validator would have Gradle rejecting the graph for an undeclared dependency on files another
 * task produces.
 *
 * `tasks.named` rather than `pluginManager.withPlugin`: a game that calls this without the assets
 * plugin applied has nothing to stage art for, and an immediate `UnknownTaskException` naming the
 * task says so, where a silent no-op would restore the exact defect this closes.
 */
public fun Project.registerCharacterArtStaging(): TaskProvider<UdeaStageCharacterArtTask> {
    val repositoryRoot: Directory = rootProject.layout.projectDirectory
    val destination: Directory = repositoryRoot.dir(CharacterArtStaging.DESTINATION_TREE)

    val stage = tasks.register(CharacterArtStaging.TASK, UdeaStageCharacterArtTask::class.java) {
        group = UdeaAssetsPlugin.GROUP
        description = "Copies this game's licensed character art out of the tree that already " +
            "holds it, so a clone builds with no manual step."
        plan.set(CharacterArtStaging.PLAN)
        sourceTree.set(repositoryRoot.dir(CharacterArtStaging.SOURCE_TREE))
        destinationTree.set(destination)
        stagedSheets.setFrom(CharacterArtStaging.PLAN.keys.map { destination.file(it) })
    }

    for (consumer in listOf(
        UdeaAssetsPlugin.SCAN_TASK,
        UdeaAssetsPlugin.VALIDATE_TASK,
        UdeaAssetsPlugin.PACK_TASK,
    )) {
        tasks.named(consumer) { dependsOn(stage) }
    }
    return stage
}
