package dev.wildware.udea.gradle.ci

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Issue #218: the `clean-build-budget` job must time a commit against **the commit it came from**
 * on the branch it merges into, not against a branch the project has stopped integrating on.
 *
 * It used to take `git merge-base HEAD origin/example`. `example` was retired for `kmp`, so every
 * port commit was compared with pre-port `master` and the ratio measured the whole port. The rule
 * now lives in `.github/scripts/clean-build-base.sh`, and this test executes that script against
 * real repositories with a real `origin`, in the history shapes the job meets:
 *
 *  - a push to the integration branch itself, where the base is the first parent;
 *  - a branch cut from it, where the base is the merge base even after the integration branch
 *    has moved on, and a stale retired branch pointing further back is ignored;
 *  - after the port merges to `master`, where the nearest candidate wins over a stale one listed
 *    first;
 *  - after the integration branch is deleted, where a missing candidate is skipped rather than
 *    failing the job;
 *  - a pull request, where `GITHUB_BASE_REF` names the target and wins over the nearest candidate;
 *  - a history no candidate shares, which fails loudly instead of timing against nothing.
 *
 * Skipped on Windows only: the job that runs the script is `runs-on: ubuntu-latest`, and the
 * `bash` a Windows runner resolves first is not guaranteed to be Git's.
 */
class CleanBuildBaseScriptTest {

    @TempDir
    lateinit var dir: File

    private lateinit var origin: File
    private lateinit var seed: File

    @BeforeEach
    fun `an origin with master and a port branch cut from it`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "the job runs on ubuntu-latest")
        origin = File(dir, "origin.git").apply { mkdirs() }
        git(origin, "init", "--bare", "-b", "master")
        seed = File(dir, "seed")
        git(dir, "clone", origin.path, seed.path)
        commit(seed, "m1")
        git(seed, "push", "origin", "HEAD:refs/heads/master")
        git(seed, "push", "origin", "HEAD:refs/heads/example")
        git(seed, "checkout", "-b", "kmp")
        commit(seed, "k1")
        commit(seed, "k2")
        git(seed, "push", "origin", "kmp")
    }

    @Test
    fun `a push to the integration branch is timed against its first parent`() {
        val head = clone("kmp")
        assertEquals(rev(seed, "kmp^1"), base(head))
    }

    @Test
    fun `a branch is timed against its merge base with the integration branch, not its tip or a retired branch`() {
        val cutAt = rev(seed, "kmp")
        val head = clone("kmp")
        git(head, "checkout", "-b", "issue-1")
        commit(head, "b1")
        commit(head, "b2")
        commit(seed, "k3")
        git(seed, "push", "origin", "kmp")

        val base = base(head)

        assertEquals(cutAt, base)
        assertNotEquals(rev(seed, "master"), base, "the base walked back to the retired line")
    }

    @Test
    fun `the nearest candidate wins, so a branch off master is not timed against a stale kmp listed first`() {
        git(seed, "checkout", "master")
        git(seed, "merge", "--ff-only", "kmp")
        commit(seed, "m2")
        git(seed, "push", "origin", "master")
        val cutAt = rev(seed, "master")
        val head = clone("master")
        git(head, "checkout", "-b", "issue-4")
        commit(head, "b1")

        assertEquals(cutAt, base(head))
    }

    @Test
    fun `a candidate that no longer exists on origin is skipped`() {
        git(seed, "checkout", "master")
        git(seed, "merge", "--ff-only", "kmp")
        git(seed, "push", "origin", "master")
        git(seed, "push", "origin", "--delete", "kmp")
        commit(seed, "m2")
        git(seed, "push", "origin", "master")
        val cutAt = rev(seed, "master")
        val head = clone("master")
        git(head, "checkout", "-b", "issue-2")
        commit(head, "b1")

        assertEquals(cutAt, base(head))
    }

    @Test
    fun `a pull request is timed against the branch it targets, which wins over the nearest candidate`() {
        val head = clone("kmp")
        git(head, "checkout", "-b", "issue-3")
        commit(head, "b1")

        assertEquals(rev(seed, "master"), base(head, baseRef = "master"))
    }

    @Test
    fun `a history that shares nothing with any candidate fails instead of choosing a base`() {
        val head = clone("kmp")
        git(head, "checkout", "--orphan", "unrelated")
        commit(head, "u1")

        val run = script(head, baseRef = "")
        assertNotEquals(0, run.exitCode, "the script chose a base for an unrelated history:\n${run.output}")
        assertTrue("no candidate" in run.output, "the failure does not say why:\n${run.output}")
    }

    private fun clone(branch: String): File {
        val at = File(dir, "head-${dir.listFiles()!!.size}")
        git(dir, "clone", "--branch", branch, origin.path, at.path)
        return at
    }

    private fun base(head: File, baseRef: String = ""): String {
        val run = script(head, baseRef)
        assertEquals(0, run.exitCode, "the script failed:\n${run.output}")
        return run.stdout.trim()
    }

    private fun script(head: File, baseRef: String): Run =
        exec(head, listOf("bash", SCRIPT.path, "kmp", "master"), mapOf("GITHUB_BASE_REF" to baseRef))

    private fun commit(repo: File, message: String) {
        git(repo, "commit", "--allow-empty", "-m", message)
    }

    private fun rev(repo: File, ref: String): String = git(repo, "rev-parse", ref).stdout.trim()

    private fun git(repo: File, vararg args: String): Run =
        exec(repo, listOf("git", *args), emptyMap()).also {
            check(it.exitCode == 0) { "git ${args.joinToString(" ")} failed in $repo:\n${it.output}" }
        }

    private fun exec(workDir: File, command: List<String>, env: Map<String, String>): Run {
        val stderr = File.createTempFile("stderr", ".txt", dir)
        val process = ProcessBuilder(command)
            .directory(workDir)
            .redirectError(stderr)
            .apply { environment().putAll(GIT_ENV + env) }
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        check(process.waitFor(2, TimeUnit.MINUTES)) { "${command.joinToString(" ")} did not finish" }
        return Run(process.exitValue(), stdout, stderr.readText())
    }

    private data class Run(val exitCode: Int, val stdout: String, val stderr: String) {
        val output: String get() = "stdout:\n$stdout\nstderr:\n$stderr"
    }

    private companion object {
        val SCRIPT = File(LatencyBudgetAggregate.repoRoot, ".github/scripts/clean-build-base.sh")

        /** A repository nobody's own git configuration can reach into: no hooks, no signing. */
        val GIT_ENV = mapOf(
            "GIT_CONFIG_GLOBAL" to "/dev/null",
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_AUTHOR_NAME" to "udea-test",
            "GIT_AUTHOR_EMAIL" to "udea-test@example.invalid",
            "GIT_COMMITTER_NAME" to "udea-test",
            "GIT_COMMITTER_EMAIL" to "udea-test@example.invalid",
        )
    }
}
