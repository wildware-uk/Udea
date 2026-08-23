package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.Json
import dev.wildware.udea.core.host.RenderMode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Advertises this instance to `~/.game-bridge/instances/<pid>.json`.
 *
 * ## Why an instance advertises itself at all
 *
 * Port scanning is the weak form of discovery: bounded by a range somebody guessed at, silent
 * about a game's identity until it answers, and prone to false negatives during startup - which
 * is exactly the moment an agent is most likely to look. A file naming this process fixes all
 * three, and carries the one thing the wire cannot say: `cwd`, so five worktrees running five
 * builds of the same game are tellable apart from outside the process.
 *
 * ## This is advertising, not infrastructure
 *
 * The rule that matters most, from the contract (`game-bridge-mcp` README §2), is that registry
 * failure **must never break the game**. An unwritable directory, a read-only home, a sandbox
 * with no `$HOME` at all: one line to stderr and the method returns. The game still starts, and
 * every endpoint still answers. There is no retry, no watchdog and no self-healing sweep, and a
 * crash deliberately leaves a stale entry behind - the contract makes staleness the *reader's*
 * problem, to be settled with a `GET /health`, precisely so that the writer can stay this small.
 *
 * ## Written after the bind, never before
 *
 * [AgentHost] calls [advertise] only once `HttpServer.start()` has returned. An entry naming a
 * port that was never claimed is worse than no entry: a reader that trusts it reports a running
 * game where none exists, and the contract's own reader rules exist because that happens anyway
 * when a process is killed.
 *
 * ## Never touches another process's entry
 *
 * The file is named for this pid and only this pid is written or deleted. Pruning stale entries
 * is the reader's job by contract, and a writer that swept the directory would race a game still
 * binding its port - indistinguishable, for a second or two, from a crashed one.
 */
public class AgentRegistry(
    /** System-property lookup. Injected so a test can drive precedence without mutating the JVM. */
    private val properties: (String) -> String? = System::getProperty,
    /** Environment lookup. Injected for the same reason; the environment cannot be set in-process. */
    private val environment: (String) -> String? = System::getenv,
    /** This process's id. */
    private val pid: Long = ProcessHandle.current().pid(),
) {

    /** The entry this registry wrote, or `null` when it has written none. */
    public var entry: Path? = null
        private set

    /**
     * Writes the entry for a host that has already bound [port].
     *
     * @return the file written, or `null` when it could not be - which is not an error condition
     *   for the caller and must not be treated as one.
     */
    public fun advertise(
        port: Int,
        identity: GameIdentity,
        renderMode: RenderMode,
        session: SessionIdentity,
        workingDirectory: Path = Path.of("").toAbsolutePath(),
    ): Path? {
        val directory = resolveDirectory(properties, environment) ?: run {
            warn("no home directory to advertise into; discovery falls back to a port scan")
            return null
        }
        val file = directory.resolve("$pid.json")
        val document = render(port, identity, renderMode, session, workingDirectory)
        return try {
            Files.createDirectories(directory)
            Files.writeString(file, document)
            entry = file
            file
        } catch (e: IOException) {
            warn("could not advertise to $file", e)
            null
        } catch (e: SecurityException) {
            warn("not permitted to advertise to $file", e)
            null
        }
    }

    /**
     * Deletes this process's entry. A no-op when nothing was written, and idempotent.
     *
     * Called from [AgentHost.stop] and from a shutdown hook, which is why it must be safe twice:
     * a clean `stop()` followed by JVM exit runs it exactly twice on every well-behaved run.
     */
    public fun withdraw() {
        val file = entry ?: return
        entry = null
        try {
            Files.deleteIfExists(file)
        } catch (e: IOException) {
            warn("could not withdraw $file", e)
        } catch (e: SecurityException) {
            warn("not permitted to withdraw $file", e)
        }
    }

    /** The payload, as the contract spells it. */
    private fun render(
        port: Int,
        identity: GameIdentity,
        renderMode: RenderMode,
        session: SessionIdentity,
        workingDirectory: Path,
    ): String = Json.render {
        put("name", identity.name)
        put("version", identity.version)
        put("protocol", identity.protocol)
        put("port", port)
        put("pid", pid)
        put("host", AgentHost.LOOPBACK)
        put("started", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString())
        put("cwd", workingDirectory.toAbsolutePath().normalize().toString())
        // Not in the contract's example, and additive by design: a reader that does not know the
        // field ignores it, and one that does can tell a Headless CI instance from a Windowed
        // developer session without calling /health first. /health remains authoritative.
        put("renderMode", renderMode.name)
        // Also additive, and for a reason `renderMode` does not have: three ports of one match
        // are otherwise indistinguishable from three unrelated games, so a bridge grouping
        // `list_instances` rows has nothing to group by. Written here as well as served by
        // `/health` so the grouping survives an instance that is still starting up and has not
        // answered a request yet - which is exactly when an agent that just launched a session
        // looks. `/health` stays authoritative when the two disagree, as the contract's reader
        // rules require, and they cannot disagree: both read one `SessionIdentity`.
        put("role", session.role.id)
        put("sessionId", session.sessionId.value)
    }

    private fun warn(message: String) {
        System.err.println("[udea-agent-host] $message")
    }

    private fun warn(message: String, failure: Throwable) {
        warn("$message: ${failure.javaClass.simpleName}: ${failure.message}")
    }

    override fun toString(): String = "AgentRegistry(pid=$pid, entry=$entry)"

    public companion object {

        /** Overrides everything: the entries directory, straight from a JVM argument. */
        public const val DIRECTORY_PROPERTY: String = "udea.agent.instances"

        /** The entries directory. */
        public const val INSTANCES_ENV: String = "GAME_BRIDGE_INSTANCES"

        /** The parent of the entries directory. */
        public const val HOME_ENV: String = "GAME_BRIDGE_HOME"

        /** The directory under `$HOME` when nothing else says otherwise. */
        public const val DEFAULT_HOME_DIRECTORY: String = ".game-bridge"

        /** The entries directory under the home. */
        public const val INSTANCES_DIRECTORY: String = "instances"

        /**
         * Where entries go, in precedence order.
         *
         * `udea.agent.instances` > `GAME_BRIDGE_INSTANCES` > `GAME_BRIDGE_HOME/instances` >
         * `~/.game-bridge/instances`.
         *
         * The system property wins because it is the only one of the four a *test* or a launcher
         * can set for one process without changing the environment of everything else the
         * developer has running - and pointing one instance's registry at a temporary directory
         * while leaving the rest alone is the whole reason it exists.
         *
         * `null` means there is nowhere to write, which happens under a sandbox with no `$HOME`.
         * That is a legitimate outcome, not a failure: the game runs, discovery falls back to a
         * port scan, and nothing throws.
         */
        public fun resolveDirectory(
            properties: (String) -> String? = System::getProperty,
            environment: (String) -> String? = System::getenv,
        ): Path? {
            properties(DIRECTORY_PROPERTY)?.blankToNull()?.let { return Path.of(it) }
            environment(INSTANCES_ENV)?.blankToNull()?.let { return Path.of(it) }
            environment(HOME_ENV)?.blankToNull()?.let { return Path.of(it).resolve(INSTANCES_DIRECTORY) }
            val home = properties("user.home")?.blankToNull() ?: return null
            return Path.of(home).resolve(DEFAULT_HOME_DIRECTORY).resolve(INSTANCES_DIRECTORY)
        }

        private fun String.blankToNull(): String? = takeIf { it.isNotBlank() }
    }
}
