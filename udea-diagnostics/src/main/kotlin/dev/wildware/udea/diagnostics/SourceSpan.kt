package dev.wildware.udea.diagnostics

/**
 * A region of a source file, addressed by a **repo-relative** path.
 *
 * Spec section 5 requires that a span is never absolute. That is enforced here rather than
 * left to the caller, because the three producers of diagnostics all start from an absolute
 * path: the K2 checkers get one from the compiler, the asset validator gets one from a
 * Gradle worker's file walk, and the runtime gets one from a `.udeapak` manifest. If any one
 * of them leaked a machine path, `diagnostics.json` would stop being byte-comparable between
 * producers and would leak the build machine's directory layout into shipped artefacts.
 *
 * The constructor rejects an absolute path, any path containing a `..` segment, and any
 * non-canonical spelling — a `\`-separated path, a `.` segment, or a repeated separator —
 * so that a Windows producer and a Linux producer emit identical bytes. That last part is
 * not cosmetic: `moba/src/./Health.kt` and `moba/src/Health.kt` are unequal `data class`
 * values for one location, so a sink keyed on a span would dedupe neither against the
 * other and `diagnostics.json` would differ between two producers of the same diagnostic.
 * [of] and [relativize] do the normalising, and are the intended way in.
 *
 * Line and column numbers are deliberately **not** validated: producers legitimately use `0`
 * to mean "this line, column unknown".
 */
public data class SourceSpan(
    /** Repo-relative, `/`-separated path. Never absolute, never contains a `..` segment. */
    public val path: String,
    public val startLine: Int,
    public val startColumn: Int,
    public val endLine: Int,
    public val endColumn: Int,
) {
    init {
        require(path.isNotBlank()) { "SourceSpan.path must not be blank" }
        require(path == normalize(path)) {
            "SourceSpan.path must be '/'-separated and already normalized: '$path'"
        }
        require(!isAbsolute(path)) {
            "SourceSpan.path must be repo-relative, but '$path' is absolute. " +
                "Use SourceSpan.of(repoRoot, absolutePath, ...) to relativize it."
        }
        require(path.split('/').none { it == ".." }) {
            "SourceSpan.path must not contain a '..' segment: '$path'"
        }
    }

    /** `path:startLine:startColumn`, the form editors and terminals understand. */
    override fun toString(): String = "$path:$startLine:$startColumn"

    public companion object {
        /**
         * Builds a span from an absolute [absolutePath] that lives under [repoRoot].
         *
         * This is the sanctioned route from a compiler- or filesystem-supplied path to a
         * span; see [relativize] for the failure modes.
         */
        public fun of(
            repoRoot: String,
            absolutePath: String,
            startLine: Int,
            startColumn: Int,
            endLine: Int = startLine,
            endColumn: Int = startColumn,
        ): SourceSpan = SourceSpan(
            relativize(repoRoot, absolutePath),
            startLine,
            startColumn,
            endLine,
            endColumn,
        )

        /**
         * Strips [repoRoot] off the front of [path], returning a `/`-separated relative path.
         *
         * Comparison ignores case, because Windows paths reach us with an inconsistent drive
         * letter case. Throws [IllegalArgumentException] if [path] does not live under
         * [repoRoot] — a diagnostic pointing outside the repo is a bug in the producer, not
         * something to paper over with an absolute path.
         */
        public fun relativize(repoRoot: String, path: String): String {
            val root = normalize(repoRoot).trimEnd('/')
            val full = normalize(path)
            require(root.isNotEmpty()) { "repoRoot must not be blank" }
            if (!isAbsolute(full)) return full
            require(full.length > root.length && full.startsWith("$root/", ignoreCase = true)) {
                "'$path' is not under repo root '$repoRoot'"
            }
            return full.substring(root.length + 1)
        }

        /**
         * The canonical spelling of [path]: `\` becomes `/`, `.` segments are dropped and
         * repeated separators collapse. `..` is deliberately **not** resolved - the
         * constructor rejects it outright rather than letting a producer escape the repo.
         *
         * Splitting on `/` rather than string-replacing is what keeps a file *name* that
         * contains dots (`orc..idle.png`) untouched: only a whole `.` segment is a segment.
         * A leading `/` is preserved so a POSIX absolute path still trips [isAbsolute]
         * instead of being silently rewritten into a relative one.
         */
        private fun normalize(path: String): String {
            val slashed = path.replace('\\', '/')
            val segments = slashed.split('/')
            if (!slashed.contains("//") && segments.none { it == "." }) return slashed
            val leading = if (slashed.startsWith("/")) "/" else ""
            return leading + segments.filter { it.isNotEmpty() && it != "." }.joinToString("/")
        }

        /**
         * True for a POSIX root (`/x`), a Windows drive-qualified path including the
         * drive-relative `C:x` form, and a UNC path - `\\host\\share` normalizes to
         * `/host/share`, whose leading `/` is the branch that catches it.
         */
        private fun isAbsolute(path: String): Boolean =
            path.startsWith("/") || (path.length >= 2 && path[0].isDriveLetter() && path[1] == ':')

        private fun Char.isDriveLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
    }
}
