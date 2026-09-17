package dev.wildware.udea.build.determinism

import dev.wildware.udea.build.ContractFreeze
import java.io.File

/**
 * The version `determinism-audit.md`'s Fleks rows were read against, now that Fleks is vendored
 * source in `udea-fleks` rather than a Maven artifact (issue #215).
 *
 * The `@version fleks` pin exists because the audit is a set of claims about particular Fleks
 * source: "`Bag` is an array", "`Family` iteration is insertion-ordered". From Maven, the source
 * could only change with the release number, so the pin compared release numbers. Vendored, the
 * source can change with no release at all - an edit in `udea-fleks/src/commonMain` - so the version
 * the pin is compared against is the release **and** a digest of that directory. Either moving
 * fails `udeaVerifyDeterminism` under `ALLOW005` until somebody re-reads the audit and moves the pin.
 */
public object VendoredFleks {

    /** The catalog alias the pin is keyed by, and whose `[versions]` entry names the release. */
    public const val ALIAS: String = "fleks"

    /** The vendored source the audit describes, repo-relative. Tests are not part of the claim. */
    public const val SOURCE_DIRECTORY: String = "udea-fleks/src/commonMain"

    /** `2.14+sha256:<hex>`: the release [SOURCE_DIRECTORY] was copied from, and what it holds now. */
    public fun auditedVersion(release: String, sourceDirectory: File): String =
        "$release+sha256:${digest(sourceDirectory)}"

    /**
     * SHA-256 over every file under [sourceDirectory]: its path relative to the directory and the
     * digest of its text, one line each, in path order.
     *
     * The path is in the digest so a rename moves it. Each file's text goes through
     * [ContractFreeze.digest], which normalises line endings, so a Windows checkout does not fail a
     * perfect tree.
     *
     * @throws IllegalStateException when the directory holds no file. A digest of nothing would
     *   pin nothing, and pass for ever.
     */
    public fun digest(sourceDirectory: File): String {
        val files = sourceDirectory.walkTopDown().filter { it.isFile }.toList()
        check(files.isNotEmpty()) {
            "No vendored Fleks source under ${sourceDirectory.path}; the determinism pin has nothing to describe."
        }
        val listing = files
            .map { it.relativeTo(sourceDirectory).invariantSeparatorsPath to ContractFreeze.digest(it.readText()) }
            .sortedBy { it.first }
            .joinToString(separator = "") { (path, hash) -> "$path  $hash\n" }
        return ContractFreeze.digest(listing)
    }
}
