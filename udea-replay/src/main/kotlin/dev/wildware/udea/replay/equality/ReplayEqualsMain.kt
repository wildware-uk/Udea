package dev.wildware.udea.replay.equality

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
 *   --summary build/reports/udea/replay-equality/summary.md \
 *   build/replay-equality/leg-a.udeaeq build/replay-equality/leg-b.udeaeq
 * ```
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

    @JvmStatic
    public fun main(args: Array<String>) {
        var summaryPath: Path? = null
        val inputs = ArrayList<Path>()
        var at = 0
        while (at < args.size) {
            when (val arg = args[at]) {
                "--summary" -> {
                    require(at + 1 < args.size) { "--summary needs a path after it" }
                    summaryPath = Path.of(args[at + 1])
                    at++
                }

                else -> {
                    require(!arg.startsWith("--")) { "unknown option '$arg'" }
                    inputs += expand(Path.of(arg))
                }
            }
            at++
        }
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
        for (index in 1 until digests.size) {
            val result = try {
                ReplayEquality.replayEquals(reference, digests[index])
            } catch (e: IncomparableDigestsException) {
                report.append("\n\n").append(e.message)
                worst = EXIT_UNUSABLE
                continue
            }
            report.append("\n\n").append(result.describe())
            if (!result.isEqual && worst == EXIT_EQUAL) worst = EXIT_DIVERGED
        }
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
