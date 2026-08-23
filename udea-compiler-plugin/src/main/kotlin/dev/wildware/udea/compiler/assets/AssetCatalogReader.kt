package dev.wildware.udea.compiler.assets

import dev.wildware.udea.diagnostics.assets.AssetCatalog
import dev.wildware.udea.diagnostics.assets.AssetCatalogDecode
import dev.wildware.udea.diagnostics.assets.AssetCatalogJson
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Something wrong with one `META-INF/udea/asset-index.json` on the classpath.
 *
 * A *missing* index is deliberately not one of these: absence is the silent case (issue #40),
 * because a module with no assets must compile without a word from this plugin, and so must
 * the whole tree when the plugin is switched off.
 */
internal sealed interface AssetCatalogProblem {

    /**
     * Which classpath entry it came from. A **file name**, never a path: spec section 5 forbids
     * an absolute path in a diagnostic, and a path relative to nothing is worse than a name.
     */
    val origin: String

    /** The index declares a format version this build cannot read. See `UdeaRules.ASSET_INDEX_FORMAT`. */
    data class UnknownVersion(
        override val origin: String,
        val found: Int,
        val expected: Int,
    ) : AssetCatalogProblem

    /** The index is present but is not the shape the format defines, or could not be read. */
    data class Malformed(
        override val origin: String,
        val reason: String,
    ) : AssetCatalogProblem
}

/** One classpath scan: what was found, and what was wrong with it. */
internal class AssetCatalogScan(
    val catalog: AssetCatalog,
    val problems: List<AssetCatalogProblem>,
) {
    /** The whole point of the empty case: nothing indexed and nothing to say. */
    val isSilent: Boolean get() = catalog.isEmpty && problems.isEmpty()

    companion object {
        val EMPTY: AssetCatalogScan = AssetCatalogScan(AssetCatalog.EMPTY, emptyList())
    }
}

/** Produces a scan. An interface so the caching test can count invocations for real. */
internal fun interface AssetCatalogScanner {
    fun scan(): AssetCatalogScan
}

/**
 * A scanner's result, computed at most once.
 *
 * Issue #40 requires the classpath be walked once per compilation, not once per
 * `reference("...")`. One instance of this is created per FIR session — see
 * `UdeaFirAdditionalCheckers` — so "per session" and "per compilation" are the same thing
 * here, and the `lazy` is what makes it true rather than a comment claiming it is.
 *
 * Synchronised rather than `NONE`: FIR runs checkers over files in parallel, so two threads
 * can reach the first `reference("...")` at once, and two concurrent classpath walks would
 * make the counting assertion flaky *and* the work doubled.
 */
internal class AssetCatalogSource(scanner: AssetCatalogScanner) {
    private val cached: AssetCatalogScan by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { scanner.scan() }

    fun scan(): AssetCatalogScan = cached
}

/**
 * Reads every `META-INF/udea/asset-index.json` on a compilation's classpath and merges them.
 *
 * ### Why the classpath and not a path option
 *
 * A FIR checker must not read a file by absolute path: it would break Gradle's up-to-date
 * checking (the file is not declared as an input of the compile task), break build-cache
 * relocatability (an absolute path in a task input property), and be unavailable to the IDE's
 * in-memory analysis, which has a classpath but not this build's argument list. Every module
 * already contributes its own resources to its consumers' compile classpaths, so the classpath
 * *is* the delivery mechanism, and merging across roots is how an upstream module's assets
 * become visible downstream.
 *
 * ### Failure policy
 *
 * A root that cannot be opened at all is one [AssetCatalogProblem.Malformed], not an
 * exception: a compile classpath routinely contains entries that no longer exist on disk, and
 * a compiler plugin that throws on one turns a stale classpath into "the Kotlin compiler
 * crashed".
 */
internal class ClasspathAssetCatalogScanner(
    private val roots: List<File>,
) : AssetCatalogScanner {

    override fun scan(): AssetCatalogScan {
        val catalogs = ArrayList<AssetCatalog>()
        val problems = ArrayList<AssetCatalogProblem>()
        for (root in roots) {
            val origin = root.name
            val text = readIndex(root, origin, problems) ?: continue
            when (val decoded = AssetCatalogJson.decode(text)) {
                is AssetCatalogDecode.Ok -> catalogs += decoded.catalog
                is AssetCatalogDecode.VersionMismatch ->
                    problems += AssetCatalogProblem.UnknownVersion(
                        origin = origin,
                        found = decoded.found,
                        expected = decoded.expected,
                    )
                is AssetCatalogDecode.Malformed ->
                    problems += AssetCatalogProblem.Malformed(origin, decoded.reason)
            }
        }
        if (catalogs.isEmpty() && problems.isEmpty()) return AssetCatalogScan.EMPTY
        return AssetCatalogScan(AssetCatalog.merge(catalogs), problems)
    }

    /** The index in [root], or `null` when this root simply does not carry one. */
    private fun readIndex(
        root: File,
        origin: String,
        problems: MutableList<AssetCatalogProblem>,
    ): String? = try {
        when {
            root.isDirectory -> File(root, AssetCatalog.RESOURCE_PATH).takeIf { it.isFile }
                ?.readText(Charsets.UTF_8)

            root.isFile -> ZipFile(root).use { zip ->
                zip.getEntry(AssetCatalog.RESOURCE_PATH)
                    ?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }
                    ?.toString(Charsets.UTF_8)
            }

            // A classpath entry that no longer exists. Common, and not this plugin's business.
            else -> null
        }
    } catch (failure: ZipException) {
        problems += AssetCatalogProblem.Malformed(origin, "not a readable archive: ${failure.message}")
        null
    } catch (failure: IOException) {
        problems += AssetCatalogProblem.Malformed(origin, "could not be read: ${failure.message}")
        null
    }
}
