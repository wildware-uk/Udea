package dev.wildware.udea.replay.equality

import dev.wildware.udea.core.Tick
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.system.exitProcess

/**
 * The join step: read every leg's `.udeaeq` and say whether they are the same simulation.
 *
 * ## Why this has a `main` and lives in `src/main`
 *
 * It is the half of the gate that has no game in it. A digest is produced by a process that can
 * build the game's world; comparing two of them needs nothing but the two files, which is what
 * lets one CI job download artifacts from several matrix legs and rule on them without a JVM that
 * can boot the game at all. Any Udea game's `replay-equality` job runs this class unchanged.
 *
 * ```
 * java -cp <udea-replay and udea-core> dev.wildware.udea.replay.equality.ReplayEqualsMain \
 *   --workspace /home/runner/work/Udea/Udea \
 *   --summary build/reports/udea/replay-equality/summary.md \
 *   digests
 * ```
 *
 * Every path is resolved through [ReplayEqualityPaths] against `--workspace`, for the reason
 * issue #169 records: the directory the join compares has to be the directory
 * `actions/download-artifact` wrote into, and a relative path inherited from whatever launched
 * the JVM is not that directory.
 *
 * Exit code 0 when every leg agrees, 1 when they do not, 2 when the inputs cannot be compared at
 * all. Three codes rather than two, because "Windows and Linux disagree about a float" and "you
 * handed me one file" are not the same news and CI should not render them the same way.
 */
public object ReplayEqualsMain {

    /** Every leg agreed. */
    public const val EXIT_EQUAL: Int = 0

    /** At least one leg diverged. The report names the tick, the entity, the component and field. */
    public const val EXIT_DIVERGED: Int = 1

    /** The inputs were unusable: too few, missing, or describing different fixtures. */
    public const val EXIT_UNUSABLE: Int = 2

    /** The join's command line, with every path already resolved against the workspace. */
    public class Options(
        /** Where the rendered verdict goes, or `null`. Absolute when present. */
        public val summary: Path?,
        /** The files and directories to read digest streams from. Absolute, unexpanded. */
        public val streams: List<Path>,
    )

    /**
     * Reads the join's command line and resolves its paths, without touching the disk.
     *
     * Separate from [main] for the same reason `DriftDigestMain.parse` is: it lets a test hand
     * this the `-Pudea.replay.streams` value `ci.yml` passes and check the answer against the
     * directory `actions/download-artifact` was told to write into.
     */
    public fun parse(args: Array<String>): Options {
        var summaryPath: String? = null
        var workspace: Path = ReplayEqualityPaths.defaultWorkspace()
        val streams = ArrayList<String>()
        var at = 0
        while (at < args.size) {
            when (val arg = args[at]) {
                "--summary" -> {
                    require(at + 1 < args.size) { "--summary needs a path after it" }
                    summaryPath = args[at + 1]
                    at++
                }

                ReplayEqualityPaths.WORKSPACE_OPTION -> {
                    require(at + 1 < args.size) {
                        "${ReplayEqualityPaths.WORKSPACE_OPTION} needs a directory after it"
                    }
                    workspace = Path.of(args[at + 1]).toAbsolutePath().normalize()
                    at++
                }

                else -> {
                    require(!arg.startsWith("--")) { "unknown option '$arg'" }
                    streams += arg
                }
            }
            at++
        }
        return Options(
            summary = summaryPath?.let { ReplayEqualityPaths.resolve(workspace, it) },
            streams = streams.map { ReplayEqualityPaths.resolve(workspace, it) },
        )
    }

    @JvmStatic
    public fun main(args: Array<String>) {
        val options = parse(args)
        val summaryPath = options.summary
        val inputs = ArrayList<Path>()
        for (stream in options.streams) inputs += expand(stream)
        inputs.sort()

        val report = StringBuilder()
        val code = run(inputs, report)
        val rendered = report.toString()
        println(rendered)
        if (summaryPath != null) {
            Files.createDirectories(summaryPath.toAbsolutePath().parent)
            Files.writeString(summaryPath, rendered)
        }
        exitProcess(code)
    }

    /**
     * Compares every leg against the first and appends the whole verdict to [report].
     *
     * The first leg is the reference only in the sense of *naming*: the comparison is symmetric,
     * and a divergence between legs 2 and 3 that both share with leg 1 is reported twice rather
     * than missed. Comparing every pair would report the same float `n(n-1)/2` times for no extra
     * information.
     */
    public fun run(inputs: List<Path>, report: StringBuilder): Int {
        // Named before the count, because "you handed me one file" and "the directory you named
        // is not there" read identically once the second has been collapsed into a count of one.
        // Issue #169 is what that reads like from the other end of a CI log.
        val missing = inputs.filter { !Files.isRegularFile(it) }
        if (missing.isNotEmpty()) {
            report.append("replay-equality was pointed at ").append(missing.size)
                .append(" path(s) that are not readable digest streams:")
                .append(missing.joinToString("") { "\n  $it" })
                .append("\nEach is either absent or not a regular file. A leg that did not ")
                .append("produce a stream is a different failure from two legs that disagree.")
            return EXIT_UNUSABLE
        }
        if (inputs.size < 2) {
            report.append("replay-equality needs at least two digest streams; got ")
                .append(inputs.size).append(inputs.joinToString("") { "\n  $it" })
                .append("\nA single leg proves nothing: the whole point is that two machines ran ")
                .append("the same recording.")
            return EXIT_UNUSABLE
        }

        val digests = try {
            inputs.map { ReplayDigestIo.read(it) }
        } catch (e: ReplayDigestFormatException) {
            report.append("replay-equality could not read a digest stream: ").append(e.message)
            return EXIT_UNUSABLE
        }

        val reference = digests.first()
        report.append("replay-equality over ").append(digests.size).append(" leg(s) of '")
            .append(reference.header.fixture).append("', ").append(reference.tickCount)
            .append(" tick(s) from ").append(reference.firstTick)
        for (digest in digests) {
            report.append("\n  ").append(digest.header.label).append("  [")
                .append(digest.header.os).append("; ").append(digest.header.jvm).append(']')
        }

        var worst = EXIT_EQUAL
        var firstDivergence: Tick? = null
        for (index in 1 until digests.size) {
            val result = try {
                ReplayEquality.replayEquals(reference, digests[index])
            } catch (e: IncomparableDigestsException) {
                report.append("\n\n").append(e.message)
                worst = EXIT_UNUSABLE
                continue
            }
            report.append("\n\n").append(result.describe())
            val tick = result.tick
            // The earliest across every pair, not the first pair's. Two legs can diverge from the
            // reference at different ticks, and the one worth landing on is the earlier - every
            // later one may be a consequence of it.
            if (tick != null && (firstDivergence == null || tick < firstDivergence)) {
                firstDivergence = tick
            }
            if (!result.isEqual && worst == EXIT_EQUAL) worst = EXIT_DIVERGED
        }
        // Issue #165: the summary tells a reader how to reproduce this on their own machine, on a
        // green run as well as a red one. Rendered by a class with tests rather than assembled in
        // a workflow step - see `ReplayBisectGuide`.
        report.append(ReplayBisectGuide.render(reference.header.fixture, firstDivergence))
        return worst
    }

    /** [path] if it is a file, or every `.udeaeq` directly inside it if it is a directory. */
    private fun expand(path: Path): List<Path> =
        if (Files.isDirectory(path)) {
            Files.list(path).use { stream ->
                stream.filter { it.fileName.toString().endsWith(ReplayDigestFormat.EXTENSION) }
                    .collect(Collectors.toList())
            }
        } else {
            listOf(path)
        }

}
