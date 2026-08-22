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
 * `\`-separated path, so that a Windows producer and a Linux producer emit identical bytes.
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

        /** Converts `\` to `/`. Does not collapse `.`, `..` or repeated separators. */
        private fun normalize(path: String): String = path.replace('\\', '/')

        /**
         * True for a POSIX root (`/x`), a UNC path (`//host/share`, normalized from `\`) and
         * a Windows drive-qualified path, including the drive-relative `C:x` form.
         */
        private fun isAbsolute(path: String): Boolean =
            path.startsWith("/") || (path.length >= 2 && path[0].isDriveLetter() && path[1] == ':')

        private fun Char.isDriveLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
    }
}
