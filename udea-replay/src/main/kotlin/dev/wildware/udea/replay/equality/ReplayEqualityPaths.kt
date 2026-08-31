package dev.wildware.udea.replay.equality

import java.nio.file.Files
import java.nio.file.Path

/**
 * Where a `replay-equality` entry point writes, and what it does when it wrote nothing.
 *
 * ## Why the base is an argument rather than something a process inherits
 *
 * Issue #169. Every leg of the job since #152 wrote its digest into `udea-replay/digests/`
 * while `actions/upload-artifact` globbed `*.udeaeq` under `$GITHUB_WORKSPACE/digests`. Both
 * sides spelled the same relative path and neither was wrong on its own terms: a `JavaExec`
 * inherits the *project* directory, and an Actions glob is rooted at the *workspace*. The upload
 * tripped `if-no-files-found: error`, `replay-equality-join` declares `needs: replay-equality`,
 * and so the join has never executed on any run of this repository. No two digest streams have
 * ever been compared.
 *
 * So exactly one function turns a caller's path into a real one, and it takes the base it
 * resolves against as an argument instead of inheriting it from whatever launched the process.
 * That is what makes the rule callable from a test with the workflow's own strings, which is
 * what `ReplayEqualityProofTest` does. Setting `workingDir` on the two `JavaExec` tasks would
 * fix the same bug and is checkable by nothing: a test cannot ask a JVM to pretend its working
 * directory is somewhere else.
 *
 * `public` rather than `internal` because `DriftDigestMain` lives in this module's `testFixtures`
 * variant, which is a separate compilation that cannot see `internal` declarations here.
 */
public object ReplayEqualityPaths {

    /** The option both entry points take to name [resolve]'s base. */
    public const val WORKSPACE_OPTION: String = "--workspace"

    /**
     * [path] resolved against [workspace], or [path] itself when it is already absolute.
     *
     * [workspace] is the repository checkout root. In CI that is `$GITHUB_WORKSPACE`, which is
     * the directory `actions/upload-artifact` and `actions/download-artifact` root their own
     * paths at, so a relative path means one thing across the whole job rather than one thing
     * per process.
     */
    public fun resolve(workspace: Path, path: String): Path {
        val requested = Path.of(path)
        val resolved = if (requested.isAbsolute) requested else workspace.resolve(requested)
        return resolved.toAbsolutePath().normalize()
    }

    /**
     * The base to use when a caller passed no [WORKSPACE_OPTION]: this process's directory.
     *
     * A hand-run from the repository root therefore behaves exactly as CI does. It is a default
     * and not a fallback: the tasks in `udea-replay/build.gradle.kts` always pass the option, so
     * a CI leg never reaches this.
     */
    public fun defaultWorkspace(): Path = Path.of("").toAbsolutePath().normalize()

    /**
     * The post-condition of a digest run: a stream really is on disk, with bytes in it.
     *
     * Loud here rather than two steps later. When the digest went somewhere nothing looked, the
     * process that produced it exited 0 and the *upload* reported the absence — naming a glob
     * rather than the path this process actually wrote, which is the one fact that would have
     * explained it. A producer that cannot say where its own output went makes its own failure
     * unreadable, so this one refuses to exit successfully without saying so.
     *
     * @param requested the raw `--out` the caller passed, quoted back so the message names both
     *   what was asked for and what it turned into.
     * @return the size of the stream in bytes.
     */
    public fun requireStreamWritten(requested: String, workspace: Path, output: Path): Long {
        val absolute = output.toAbsolutePath().normalize()
        check(Files.isRegularFile(absolute)) {
            "the digest run wrote no stream: nothing is at '$absolute'. --out was '$requested', " +
                "resolved against the workspace '$workspace'. Nothing downstream can upload or " +
                "compare a file that is not there."
        }
        val size = Files.size(absolute)
        check(size > 0L) {
            "the digest run left an empty stream at '$absolute'. --out was '$requested', " +
                "resolved against the workspace '$workspace'. A zero-byte digest is not a leg " +
                "of the gate; it is a leg that failed quietly."
        }
        return size
    }
}
