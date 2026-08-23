package dev.wildware.udea.build

import java.io.Serializable
import java.security.MessageDigest

/**
 * What is to become of one old-tree file.
 *
 * Three words, deliberately. The vocabulary is small so that a 140-row table stays diffable:
 * a reviewer scanning a pull request should be able to see a disposition change as a one-word
 * edit, not hunt for it inside a sentence.
 */
public enum class Disposition {
    /** The file is copied forward largely as it stands. Spec section 4 requires the copy be reviewed. */
    PORT,

    /** The concept survives, the code does not. The replacement is written against the new design. */
    REWRITE,

    /** Nothing replaces it. It goes when its module goes. */
    DELETE,

    ;

    /** The lower-case spelling used in the ledger table. */
    public val wireName: String get() = name.lowercase()

    public companion object {
        /** Parses [text], or null when it is not one of the three words. */
        public fun parseOrNull(text: String): Disposition? =
            values().firstOrNull { it.wireName == text }
    }
}

/**
 * One row of `docs/migration/ledger.md`: the disposition of a single old-tree Kotlin file.
 *
 * ### Why the review columns are on the *source* row
 *
 * Spec section 4 permits copying out of `common` only "file by file, with the copy reviewed".
 * The review is a fact about a (source, copy) pair, and the source is what the ledger already
 * enumerates, so the pair is recorded on the source's row: [path] is the file copied *from*,
 * [copiedTo] the file copied *to*. Issue #146 words the first of those as a `copiedFrom`
 * column; on a table already keyed by the source path that column would only ever repeat the
 * key, so the direction is inverted and the key is reused.
 *
 * @param path repo-relative path of the old-tree file, forward slashes, the table's key.
 * @param disposition what happens to it.
 * @param destination the module from the spec section 4 table that takes over its job, or `-`
 *   for [Disposition.DELETE].
 * @param replacedIn the phase in which the replacement lands.
 * @param deletedIn the phase in which this file is deleted, which is usually later: `common`
 *   compiles as one unit and `example` consumes it, so a file cannot leave before its
 *   consumers do.
 * @param copiedTo repo-relative path of the reviewed copy in the new tree. Null unless
 *   [disposition] is [Disposition.PORT].
 * @param sourceHash [MigrationLedger.contentHash] of this file at the moment the copy was
 *   reviewed. What makes a *stale* copy detectable.
 * @param reviewedBy who reviewed the copy.
 * @param reviewedIn the commit or pull request the review happened in.
 * @param notes free text.
 */
public data class LedgerRow(
    public val path: String,
    public val disposition: Disposition,
    public val destination: String,
    public val replacedIn: String,
    public val deletedIn: String,
    public val copiedTo: String? = null,
    public val sourceHash: String? = null,
    public val reviewedBy: String? = null,
    public val reviewedIn: String? = null,
    public val notes: String? = null,
) : Serializable {

    /**
     * True when every field a reviewed copy needs is filled in.
     *
     * All four, not any: a row naming a reviewer but no commit records an assertion nobody can
     * check, which is the state this gate exists to refuse.
     */
    public val hasCompleteReview: Boolean
        get() = disposition == Disposition.PORT &&
            !copiedTo.isNullOrBlank() &&
            !sourceHash.isNullOrBlank() &&
            !reviewedBy.isNullOrBlank() &&
            !reviewedIn.isNullOrBlank()

    /** The row as one tab-separated line, with `-` for every absent optional field. */
    public fun toTsv(): String = listOf(
        path,
        disposition.wireName,
        destination,
        replacedIn,
        deletedIn,
        copiedTo.orDash(),
        sourceHash.orDash(),
        reviewedBy.orDash(),
        reviewedIn.orDash(),
        notes.orDash(),
    ).joinToString("\t")

    private fun String?.orDash(): String = if (isNullOrBlank()) "-" else this
}

/** One Kotlin source file, read into memory so the rules can be executed without a filesystem. */
public data class SourceFile(public val path: String, public val text: String) : Serializable

/**
 * One rule broken, in the shape `UdeaDiagnostic.toString()` renders.
 *
 * Issue #146 asks for a real `UdeaDiagnostic` with a `UdeaRules` id. It cannot be one here:
 * `udea-diagnostics` is a project of the *main* build, and `build-logic` is the included build
 * that configures that build, so a dependency would be a cycle — `build-logic` has to compile
 * before `:udea-diagnostics` exists as anything at all. The rule id space is therefore
 * `build-logic`'s own [RuleId], as it already is for `UDEA-MG-*` and `UDEA-REL-*`, and only
 * the rendered shape is shared so that a person reading CI output sees one format.
 */
public data class MigrationFinding(
    public val rule: RuleId,
    /** Repo-relative, forward slashes, never absolute — the `SourceSpan` contract. */
    public val path: String,
    public val line: Int,
    public val message: String,
) : Serializable {
    override fun toString(): String = "$path:$line:1: error: [$rule] $message"
}

/**
 * The rules behind `udeaLegacyReport` and `udeaVerifyMigration`.
 *
 * Both gates exist because spec section 7 rates "two module trees coexist for six phases" as
 * the highest-impact insidious risk: if `common` leaks into the new tree the globals come back
 * and headless breaks somewhere far from the cause. The ledger records *intent* — issue #136 —
 * and these rules make *compliance* checkable — issue #146.
 *
 * Everything here is pure: a `String` in, a finding out. The Gradle tasks are the I/O around
 * it. That is what lets the thresholds below be bracketed from both sides by unit tests
 * instead of only observed through a build.
 */
public object MigrationLedger {

    /** A legacy Kotlin file exists with no row in the ledger. */
    public val UNLEDGERED_FILE: RuleId = RuleId("UDEA-MIG-001")

    /** A ledger row names a file that is no longer in the tree. */
    public val STALE_ROW: RuleId = RuleId("UDEA-MIG-002")

    /** A new-tree file is a near-duplicate of a `common/` file with no reviewed ledger row. */
    public val UNREVIEWED_COPY: RuleId = RuleId("UDEA-MIG-003")

    /** A reviewed copy's source has changed since the review, so the review is out of date. */
    public val STALE_COPY: RuleId = RuleId("UDEA-MIG-004")

    /**
     * Where the ledger's machine-readable rows live inside the Markdown.
     *
     * A fenced block rather than a Markdown table: the prose explaining the retirement order is
     * what a person opens the file for, and 140 pipe-delimited rows in the middle of it would
     * bury that. Tab-separated inside a fence keeps both audiences on one file, which is the
     * point — a ledger split across two files drifts.
     */
    public const val FENCE_OPEN: String = "```ledger"

    /** The header line the fenced block must start with. */
    public const val HEADER: String =
        "path\tdisposition\tdestination\treplacedIn\tdeletedIn\tcopiedTo\tsourceHash\treviewedBy\treviewedIn\tnotes"

    /**
     * Fraction of shared significant lines above which two files are called near-duplicates.
     *
     * The one judgement call in this file, so it is a named constant and the tests bracket it
     * from both sides: `MigrationLedgerTest` asserts a reformatted copy lands above it and a
     * genuine rewrite lands below it. Tuning it is then a visible change to this line with two
     * failing tests attached, rather than a drift nobody notices.
     *
     * 0.6 rather than something stricter because a copy-forward is expected to be *edited* —
     * renamed types, a dropped global, a changed package — and a threshold that only caught
     * byte-identical files would be satisfied by any copy somebody bothered to reformat.
     */
    public const val SIMILARITY_THRESHOLD: Double = 0.6

    /**
     * Below this many significant lines, similarity is not consulted at all.
     *
     * Two four-line files that both declare a data class and close it are 100% similar and
     * mean nothing. Exact-hash equality still catches those, because an identical short file
     * is still an identical file.
     */
    public const val MIN_SIGNIFICANT_LINES: Int = 8

    /**
     * Shortest line that counts toward similarity.
     *
     * `}`, `)` and `{` appear in every Kotlin file ever written; counting them would put a
     * floor under every comparison and drag unrelated files toward the threshold.
     */
    private const val MIN_LINE_LENGTH: Int = 4

    /**
     * Parses the fenced ledger block out of [markdown].
     *
     * @throws IllegalArgumentException if the block is missing, the header is wrong, or a row
     *   is malformed. A ledger the gate cannot read is a gate that is not running, so this is
     *   loud rather than an empty list.
     */
    public fun parse(markdown: String): List<LedgerRow> {
        val lines = markdown.replace("\r\n", "\n").split("\n")
        val open = lines.indexOfFirst { it.trim() == FENCE_OPEN }
        require(open >= 0) { "the ledger has no `$FENCE_OPEN` block; nothing can be verified against it" }
        val close = lines.drop(open + 1).indexOfFirst { it.trim() == "```" }
        require(close >= 0) { "the `$FENCE_OPEN` block at line ${open + 1} is never closed" }

        val body = lines.subList(open + 1, open + 1 + close).filter { it.isNotBlank() }
        require(body.isNotEmpty()) { "the `$FENCE_OPEN` block is empty" }
        require(body.first() == HEADER) {
            "the ledger header is\n  ${body.first()}\nbut this build expects\n  $HEADER"
        }

        return body.drop(1).mapIndexed { index, line -> parseRow(line, open + index + 3) }
    }

    private fun parseRow(line: String, lineNumber: Int): LedgerRow {
        val cells = line.split("\t")
        require(cells.size == HEADER.split("\t").size) {
            "ledger line $lineNumber has ${cells.size} columns, expected ${HEADER.split("\t").size}: $line"
        }
        val disposition = Disposition.parseOrNull(cells[1])
        requireNotNull(disposition) {
            "ledger line $lineNumber has disposition '${cells[1]}'; expected one of " +
                Disposition.values().joinToString { it.wireName }
        }
        return LedgerRow(
            path = cells[0],
            disposition = disposition,
            destination = cells[2],
            replacedIn = cells[3],
            deletedIn = cells[4],
            copiedTo = cells[5].orNull(),
            sourceHash = cells[6].orNull(),
            reviewedBy = cells[7].orNull(),
            reviewedIn = cells[8].orNull(),
            notes = cells[9].orNull(),
        )
    }

    private fun String.orNull(): String? = takeUnless { it == "-" || it.isBlank() }

    /**
     * Renders [rows] back into the fenced block, sorted by path.
     *
     * Sorted because the generator and a hand edit must produce the same bytes; an unsorted
     * ledger would show a diff every time it was regenerated.
     */
    public fun render(rows: List<LedgerRow>): String =
        (listOf(HEADER) + rows.sortedBy { it.path }.map { it.toTsv() }).joinToString("\n")

    /**
     * Every legacy file with no row, and every row naming a file that is gone.
     *
     * @param rows the parsed ledger.
     * @param presentPaths repo-relative paths of the legacy Kotlin files actually in the tree.
     */
    public fun coverageFindings(rows: List<LedgerRow>, presentPaths: Set<String>): List<MigrationFinding> {
        val ledgered = rows.map { it.path }.toSet()
        val missing = (presentPaths - ledgered).sorted().map {
            MigrationFinding(
                rule = UNLEDGERED_FILE,
                path = it,
                line = 1,
                message = "no row in docs/migration/ledger.md. Every old-tree file needs a named " +
                    "disposition before the new tree grows past it (spec section 7, D1).",
            )
        }
        val stale = (ledgered - presentPaths).sorted().map {
            MigrationFinding(
                rule = STALE_ROW,
                path = "docs/migration/ledger.md",
                line = 1,
                message = "row names '$it', which is not in the tree. Delete the row, or restore " +
                    "the file if it went by accident.",
            )
        }
        val duplicates = rows.groupBy { it.path }.filterValues { it.size > 1 }.keys.sorted().map {
            MigrationFinding(
                rule = STALE_ROW,
                path = "docs/migration/ledger.md",
                line = 1,
                message = "'$it' has more than one row, so its disposition is ambiguous.",
            )
        }
        return missing + stale + duplicates
    }

    /** Per-module counts, for the progress line `udeaLegacyReport` prints. */
    public fun moduleCounts(rows: List<LedgerRow>, presentPaths: Set<String>): Map<String, ModuleCount> =
        rows.groupBy { it.path.substringBefore('/') }
            .toSortedMap()
            .mapValues { (_, moduleRows) ->
                ModuleCount(
                    remaining = moduleRows.count { it.path in presentPaths },
                    deleted = moduleRows.count { it.path !in presentPaths },
                )
            }

    /** How much of one old module is left. */
    public data class ModuleCount(public val remaining: Int, public val deleted: Int) : Serializable {
        public val total: Int get() = remaining + deleted
    }

    /**
     * Strips everything a copy-forward changes for free, leaving the lines that carry meaning.
     *
     * Package declaration, imports, whole-line comments, blank lines and indentation all change
     * when a file moves module, and every one of them would let an otherwise identical file
     * slip past a naive comparison. Only *whole-line* comments go: a `//` inside a line could
     * be inside a string literal, and mangling a URL into a false difference would work in the
     * wrong direction.
     */
    public fun normalise(source: String): List<String> =
        source.replace("\r\n", "\n").split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("package ") || it.startsWith("import ") }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /** SHA-256 of the [normalise]d content, hex, lower case. Stable across line endings. */
    public fun contentHash(source: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(normalise(source).joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** The lines similarity is computed over: normalised, de-duplicated, trivia dropped. */
    private fun significantLines(source: String): Set<String> =
        normalise(source).filter { it.length >= MIN_LINE_LENGTH }.toSet()

    /**
     * Jaccard similarity of two files' significant lines, in `0.0..1.0`.
     *
     * Set-based rather than sequence-based on purpose: reordering declarations is one of the
     * first things somebody does to a copied file, and an edit-distance measure would score
     * that as a rewrite.
     */
    public fun similarity(a: String, b: String): Double {
        val left = significantLines(a)
        val right = significantLines(b)
        if (left.size < MIN_SIGNIFICANT_LINES || right.size < MIN_SIGNIFICANT_LINES) return 0.0
        val union = (left + right).size
        return if (union == 0) 0.0 else left.intersect(right).size.toDouble() / union
    }

    /**
     * Every new-tree file that looks copied out of the old tree without a current review.
     *
     * @param legacy the old-tree sources, keyed by repo-relative path.
     * @param rewrite the `udea-*` and `moba` sources.
     * @param rows the parsed ledger.
     */
    public fun copyFindings(
        legacy: List<SourceFile>,
        rewrite: List<SourceFile>,
        rows: List<LedgerRow>,
    ): List<MigrationFinding> {
        val byPath = rows.associateBy { it.path }
        return rewrite.sortedBy { it.path }.mapNotNull { copy ->
            val source = bestMatch(copy, legacy) ?: return@mapNotNull null
            findingFor(copy, source, byPath[source.path])
        }
    }

    /**
     * The old-tree file [copy] most resembles, or null if none passes the bar.
     *
     * Exact normalised-hash equality wins outright over the best similarity score, so that a
     * verbatim copy is always attributed to the file it actually came from even when a sibling
     * scores marginally higher.
     */
    private fun bestMatch(copy: SourceFile, legacy: List<SourceFile>): SourceFile? {
        val copyHash = contentHash(copy.text)
        legacy.firstOrNull { contentHash(it.text) == copyHash }?.let { return it }
        return legacy
            .map { it to similarity(copy.text, it.text) }
            .filter { it.second >= SIMILARITY_THRESHOLD }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun findingFor(copy: SourceFile, source: SourceFile, row: LedgerRow?): MigrationFinding? {
        val currentHash = contentHash(source.text)
        if (row == null || !row.hasCompleteReview || row.copiedTo != copy.path) {
            return MigrationFinding(
                rule = UNREVIEWED_COPY,
                path = copy.path,
                line = 1,
                message = "is a near-duplicate of ${source.path} with no reviewed ledger row. " +
                    "Spec section 4 allows copying out of the old tree only file by file, with " +
                    "the copy reviewed. Add a row to docs/migration/ledger.md for " +
                    "${source.path} with disposition '${Disposition.PORT.wireName}', " +
                    "copiedTo=${copy.path}, sourceHash=$currentHash, and a reviewedBy and " +
                    "reviewedIn naming who reviewed it and where. " +
                    missingFields(row, copy.path),
            )
        }
        if (row.sourceHash != currentHash) {
            return MigrationFinding(
                rule = STALE_COPY,
                path = copy.path,
                line = 1,
                message = "was reviewed against ${source.path} at sourceHash ${row.sourceHash}, " +
                    "but that file now hashes to $currentHash. The copy was reviewed against a " +
                    "version that no longer exists: re-review it, port the change across, and " +
                    "update sourceHash.",
            )
        }
        return null
    }

    /** Names the fields actually absent, so the message says what to type rather than what to read. */
    private fun missingFields(row: LedgerRow?, copyPath: String): String {
        if (row == null) return "There is no row for that source file at all."
        val absent = buildList {
            if (row.disposition != Disposition.PORT) add("disposition is '${row.disposition.wireName}'")
            when {
                row.copiedTo.isNullOrBlank() -> add("copiedTo is empty")
                row.copiedTo != copyPath -> add("copiedTo names ${row.copiedTo}, not $copyPath")
            }
            if (row.sourceHash.isNullOrBlank()) add("sourceHash is empty")
            if (row.reviewedBy.isNullOrBlank()) add("reviewedBy is empty")
            if (row.reviewedIn.isNullOrBlank()) add("reviewedIn is empty")
        }
        return absent.joinToString(prefix = "The existing row is incomplete: ", postfix = ".")
    }

    /**
     * The failure message for [findings], or null when there are none.
     *
     * @param taskName the gate that found them, so the message says what to re-run.
     */
    public fun report(taskName: String, findings: List<MigrationFinding>): String? =
        findings.takeIf { it.isNotEmpty() }?.let {
            buildString {
                appendLine("$taskName found ${it.size} migration problem(s):")
                appendLine()
                it.forEach { finding -> appendLine("  $finding") }
                appendLine()
                append("docs/migration/ledger.md explains the columns and how to fill them in.")
            }
        }
}
