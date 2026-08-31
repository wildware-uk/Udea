import dev.wildware.udea.build.ContractFreeze
import dev.wildware.udea.build.UdeaVerifyContractsTask
import dev.wildware.udea.build.UdeaWriteContractLockTask

/**
 * Registers `udeaVerifyContracts` and `udeaWriteContractLock`, and wires the first into `check`
 * (issue #174).
 *
 * Applied to the **root** project, for the same reason `udea.migration-check` and
 * `udea.determinism-check` are: the question is about the repository as a whole — "have the
 * frozen agreements moved" — and a per-module answer to it would be one answer per module to
 * a question that has one. It also keeps the switch that could turn the gate off out of the build
 * script of any module that implements what the contracts say.
 *
 * On `check` rather than only in `ci.yml` because `docs/contracts/` is the thing a *developer*
 * is about to edit, and a rule that only CI knows about is a rule found after the work is done.
 * `AGENTS.md`'s "Frozen contracts" section names the gate, so the rule and its enforcement are
 * stated in the same place.
 */

plugins {
    base
}

val frozenDirectory = layout.projectDirectory.dir(ContractFreeze.DIRECTORY)
val lock = layout.projectDirectory.file(ContractFreeze.LOCK_PATH)

/**
 * Every file under the frozen directory, as a tree rather than an `InputDirectory`.
 *
 * A `fileTree` of a directory that does not exist is empty rather than an error, which is what
 * lets the deletion of `docs/contracts/` be reported as every frozen contract having vanished,
 * instead of a Gradle validation failure about a missing input.
 */
val contractTree = fileTree(frozenDirectory) { include("**/*") }

// Registered by the constant rather than by a `by tasks.registering` property name, so the name
// the failure message tells people to type and the name a task actually answers to are one
// string. `udeaWriteProtocolLock` is registered the same way, for the same reason.
val udeaWriteContractLock = tasks.register<UdeaWriteContractLockTask>(ContractFreeze.WRITE_TASK) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Rewrites ${ContractFreeze.LOCK_PATH} from ${ContractFreeze.DIRECTORY}/. " +
        "Run only when a contract change is agreed. Review the diff: it is the contract."
    contractsDirectory.set(frozenDirectory)
    contractFiles.from(contractTree)
    repoRoot.set(layout.projectDirectory)
    lockFile.set(lock)

    // A re-baseline that silently did nothing would be the worst possible outcome of the one
    // command this gate tells people to run, so it always executes.
    outputs.upToDateWhen { false }
}

val udeaVerifyContracts = tasks.register<UdeaVerifyContractsTask>(ContractFreeze.VERIFY_TASK) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails if a frozen contract in ${ContractFreeze.DIRECTORY}/ has changed, " +
        "appeared or vanished since ${ContractFreeze.LOCK_PATH} froze it."
    contractsDirectory.set(frozenDirectory)
    contractFiles.from(contractTree)
    repoRoot.set(layout.projectDirectory)
    lockFile.from(lock)
    report.set(layout.buildDirectory.file("reports/udea/contract-freeze.txt"))

    // Both tasks read the same directory and one writes the file the other reads, so without an
    // order `gradlew udeaWriteContractLock check` is a race. With one, it is a re-baseline
    // followed by a check of what the re-baseline produced - which passes, and is the honest
    // answer to having asked for exactly that. The same bargain `udeaCheckProtocolLock` strikes.
    mustRunAfter(udeaWriteContractLock)
}

tasks.named("check") {
    dependsOn(udeaVerifyContracts)
}
