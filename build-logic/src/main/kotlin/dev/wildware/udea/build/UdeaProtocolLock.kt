package dev.wildware.udea.build

import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

/**
 * The drift check for `net-protocol.lock`, as a rule plus the two tasks that run it.
 *
 * `udea-codegen` writes a module's wire contract into KSP's generated-resources directory.
 * On its own that is a build artefact nobody diffs: it is regenerated from scratch every
 * build, compared to nothing and never committed, so renaming a `@Net` field, reordering two
 * fields, adding a component or widening a `@Q` range changes the bytes on the wire with a
 * green build and no diff to review.
 *
 * The remedy is the same one a dependency lock file uses. The generated lock is compared
 * against a reviewed copy in the module root, on `check`, by [CHECK_TASK]; [WRITE_TASK]
 * rewrites the reviewed copy deliberately, so the update is an edit somebody made rather than
 * a file that quietly moved.
 *
 * This lives in `build-logic`, not in one module's test source set, because the rule has to
 * hold for *every* module that emits protocol identity. A check that exists only inside the
 * one module which happens to run the processor today is a check the second module silently
 * does without.
 */
public object UdeaProtocolLock {

    /** The reviewed copy's name, in the owning module's root. */
    public const val FILE_NAME: String = "net-protocol.lock"

    public const val CHECK_TASK: String = "udeaCheckProtocolLock"
    public const val WRITE_TASK: String = "udeaWriteProtocolLock"

    /**
     * The message to fail [CHECK_TASK] with, or `null` when the reviewed lock is the protocol
     * the build just generated.
     *
     * @param projectPath the owning module, for the message.
     * @param generated the lock text KSP emitted, or `null` if the processor emitted none —
     *   which is a broken gate rather than a clean module, and fails.
     * @param reviewed the checked-in lock text, or `null` when the file is absent.
     */
    public fun violation(projectPath: String, generated: String?, reviewed: String?): String? {
        if (generated == null) {
            return "$projectPath ran $CHECK_TASK but the processor emitted no $FILE_NAME. A drift " +
                "check with nothing to compare passes forever; fix the generated-resources path " +
                "or the module name rather than the gate."
        }
        val left = generated.normalise()
        if (reviewed == null) {
            return "$projectPath emits a wire protocol but has no checked-in $FILE_NAME. The wire " +
                "contract is only reviewable if it is in the tree - write it with " +
                "`gradlew $projectPath:$WRITE_TASK` and commit it."
        }
        val right = reviewed.normalise()
        if (left == right) return null
        return "$projectPath: the generated protocol and the checked-in $FILE_NAME disagree. " +
            "Every client already speaking this protocol and every recorded replay breaks with " +
            "this change. If it was intended, rewrite the lock with `gradlew " +
            "$projectPath:$WRITE_TASK` and review the diff.\n" + firstDifference(right, left)
    }

    /** The first differing line either way round, which is what a reviewer needs to see. */
    public fun firstDifference(reviewed: String, generated: String): String {
        val left = reviewed.normalise().lines()
        val right = generated.normalise().lines()
        val index = (0 until maxOf(left.size, right.size))
            .firstOrNull { left.getOrNull(it) != right.getOrNull(it) }
            ?: return "the files differ only in trailing content"
        return "first difference at line ${index + 1}:\n" +
            "  checked in: ${left.getOrNull(index) ?: "<end of file>"}\n" +
            "  generated:  ${right.getOrNull(index) ?: "<end of file>"}"
    }

    private fun String.normalise(): String = replace("\r\n", "\n")
}

/**
 * Registers [UdeaProtocolLock.CHECK_TASK] and [UdeaProtocolLock.WRITE_TASK] for a module whose
 * KSP run emits a `net-protocol.lock`, and wires the check into `check`.
 *
 * @param generatedLock where the processor writes it, under `build/generated/ksp/…`.
 * @param producingTask the KSP task that has to have run first. Named rather than inferred:
 *   a module can be processed on `ksp`, `kspTest` or both, and depending on the wrong one
 *   gives a check that reads a stale file.
 */
public fun Project.registerNetProtocolLock(
    generatedLock: Provider<RegularFile>,
    producingTask: String,
): TaskProvider<Task> {
    val reviewed: File = layout.projectDirectory.file(UdeaProtocolLock.FILE_NAME).asFile
    val projectPath = path

    val write = tasks.register(UdeaProtocolLock.WRITE_TASK) {
        group = "verification"
        description = "Rewrites the reviewed ${UdeaProtocolLock.FILE_NAME} from the protocol " +
            "this build generated. Review the diff: it is the wire contract."
        dependsOn(producingTask)
        val source = generatedLock
        outputs.upToDateWhen { false }
        doLast {
            val emitted = source.get().asFile
            if (!emitted.isFile) {
                throw GradleException(
                    "$projectPath: no generated ${UdeaProtocolLock.FILE_NAME} at ${emitted.absolutePath}",
                )
            }
            reviewed.writeText(emitted.readText().replace("\r\n", "\n"))
            logger.lifecycle("$projectPath: wrote ${reviewed.absolutePath}")
        }
    }

    val check = tasks.register(UdeaProtocolLock.CHECK_TASK) {
        group = "verification"
        description = "Fails when the generated wire protocol has drifted from the reviewed " +
            "${UdeaProtocolLock.FILE_NAME} in the module root."
        dependsOn(producingTask)
        val source = generatedLock
        inputs.file(source).withPropertyName("generatedLock")
        // Declared optional so an absent reviewed lock reaches `violation` as a named failure
        // instead of a Gradle "file does not exist" with no explanation of what it was for.
        inputs.file(provider { reviewed }).optional(true).withPropertyName("reviewedLock")
        outputs.upToDateWhen { false }
        doLast {
            val emitted = source.get().asFile
            UdeaProtocolLock.violation(
                projectPath = projectPath,
                generated = emitted.takeIf(File::isFile)?.readText(),
                reviewed = reviewed.takeIf(File::isFile)?.readText(),
            )?.let { throw GradleException(it) }
        }
    }
    tasks.named("check") { dependsOn(check) }
    // Writing and checking in the same invocation would race on the reviewed file, and the
    // check would then be reading what the write just produced - a gate that cannot fail.
    check.configure { mustRunAfter(write) }
    return check
}
