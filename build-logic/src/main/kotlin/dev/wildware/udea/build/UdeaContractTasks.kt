package dev.wildware.udea.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Shared plumbing for the two halves of the freeze: where the contracts are, and where the
 * lock is.
 *
 * See [ContractFreeze] for what is compared, and why it is a set comparison rather than a
 * digest per known filename.
 */
public abstract class UdeaContractTask : DefaultTask() {

    /**
     * `docs/contracts/`.
     *
     * `@Internal`, with [contractFiles] carrying the up-to-date information, because the
     * directory may legitimately not exist: deleting it is one of the things this gate is for,
     * and an `@InputDirectory` pointing at a missing directory fails Gradle's own validation
     * before the task can say anything useful about it.
     */
    @get:Internal
    public abstract val contractsDirectory: DirectoryProperty

    /**
     * The files under [contractsDirectory], declared so Gradle sees the subject of the gate.
     *
     * A file collection rather than an input directory for the reason above, and because a
     * collection records the *absence* of a file as part of its fingerprint, which is what
     * makes a deletion re-run the task rather than leave it `UP-TO-DATE` across exactly the
     * edit it exists to notice.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val contractFiles: ConfigurableFileCollection

    /**
     * The repository root, only for shortening paths in messages, so declaring it as an input
     * would make every checkout location a different cache key.
     */
    @get:Internal
    public abstract val repoRoot: DirectoryProperty
}

/**
 * Fails when a file in `docs/contracts/` has changed, appeared or vanished since it was frozen.
 *
 * The gate `AGENTS.md` needed and did not have (issue #174). Wired into `check` rather than only
 * into CI, so a fresh clone catches an edit without anyone having to remember a task name — the
 * same reason `udeaVerifyAgentsMd` is on `check`.
 */
public abstract class UdeaVerifyContractsTask : UdeaContractTask() {

    /**
     * `docs/contracts.lock`.
     *
     * A collection holding one path rather than an `@InputFile`, so that an absent lock reaches
     * [ContractFreeze.findings] as a named failure — every frozen contract reported as
     * unfrozen — instead of a Gradle "file does not exist" that says nothing about what the
     * file was for. Deleting the lock is the same act as editing a contract with an extra
     * step, and it has to be as loud.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val lockFile: ConfigurableFileCollection

    /** What was compared, written on success too, so a green run still shows its working. */
    @get:OutputFile
    public abstract val report: RegularFileProperty

    /** Digests the directory, compares it against the lock, and fails naming every difference. */
    @TaskAction
    public fun verify() {
        val root = repoRoot.get().asFile
        val actual = ContractFreeze.digestsOf(contractsDirectory.get().asFile, root)
        val lock = lockFile.singleFile
        val locked = if (lock.isFile) ContractFreeze.parse(lock.readText()) else emptyMap()

        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                buildString {
                    appendLine("frozen directory: ${ContractFreeze.DIRECTORY}/")
                    appendLine("files in the tree: ${actual.size}")
                    appendLine("files frozen by ${ContractFreeze.LOCK_PATH}: ${locked.size}")
                    actual.keys.sorted().forEach { appendLine("  $it") }
                },
            )
        }

        ContractFreeze.report(ContractFreeze.findings(locked, actual))?.let { throw GradleException(it) }
    }
}

/**
 * Rewrites `docs/contracts.lock` from the contracts as they stand.
 *
 * The deliberate route out, and the only one. It is a task somebody types on purpose rather
 * than a `-P` flag, because a flag can be passed to a whole `gradlew build` and re-baseline the
 * freeze as a side effect of an ordinary build — which is the exact act the gate exists to
 * refuse. `udeaWriteProtocolLock` makes the same trade for the wire contract.
 */
public abstract class UdeaWriteContractLockTask : UdeaContractTask() {

    /** `docs/contracts.lock`, rewritten from the tree. */
    @get:OutputFile
    public abstract val lockFile: RegularFileProperty

    /** Digests every contract and writes the lock, saying where it went. */
    @TaskAction
    public fun write() {
        val digests = ContractFreeze.digestsOf(contractsDirectory.get().asFile, repoRoot.get().asFile)
        val target = lockFile.get().asFile
        target.parentFile.mkdirs()
        target.writeText(ContractFreeze.render(digests))
        logger.lifecycle(
            "wrote ${target.absolutePath} freezing ${digests.size} contract(s). " +
                "Review the diff: it is the contract.",
        )
    }
}
