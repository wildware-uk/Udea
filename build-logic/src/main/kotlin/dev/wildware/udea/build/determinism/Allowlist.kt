package dev.wildware.udea.build.determinism

/**
 * One allowed exception: `RULE_ID  owner.Class#member  # reasoning`.
 *
 * [member] may be `*`, which allows every member of that owner under that rule. Nothing else
 * is wildcarded: an allowlist that could say `com.badlogic.gdx.*` would be a switch, not an
 * exception list.
 */
public data class AllowEntry(
    public val ruleId: String,
    public val owner: String,
    public val member: String,
    public val reasoning: String,
    /** 1-based line in `determinism-allowlist.txt`, so a parse failure can point at it. */
    public val line: Int,
) {
    /** Whether this entry covers [finding]. */
    public fun covers(ruleId: String, refOwner: String, refMember: String): Boolean =
        this.ruleId == ruleId && owner == refOwner && (member == "*" || member == refMember)

    /** How the entry is written back into a report. */
    public val target: String get() = "$owner#$member"
}

/** A version the audit was performed against: `@version fleks 2.14`. */
public data class VersionPin(public val name: String, public val version: String, public val line: Int)

/** A refusal to accept the allowlist as written. Distinct [ruleId] per failure kind. */
public data class AllowlistProblem(public val ruleId: String, public val message: String)

/**
 * The parsed `determinism-allowlist.txt`.
 *
 * Strictness is the whole point (spec 6's exit criterion: "a reviewed artefact, not a dumping
 * ground"). An unknown rule id, a missing reasoning, a malformed target and an entry that
 * matches nothing all **fail the task**, each under its own id, so the failure says which kind
 * of rot set in rather than "the allowlist is bad".
 */
public data class Allowlist(
    public val entries: List<AllowEntry>,
    public val versionPins: List<VersionPin>,
    /** Problems found while parsing. Non-empty means the task fails. */
    public val problems: List<AllowlistProblem>,
) {
    public companion object {

        /** Unknown rule id in the first column. */
        public const val UNKNOWN_RULE: String = "ALLOW001"

        /** No `#` reasoning after the target. */
        public const val NO_REASONING: String = "ALLOW002"

        /** The target is not `owner.Class#member`. */
        public const val MALFORMED_TARGET: String = "ALLOW003"

        /** The entry matched no finding on this run, so it is stale. */
        public const val UNUSED_ENTRY: String = "ALLOW004"

        /** A pinned dependency version no longer matches the resolved one. */
        public const val VERSION_DRIFT: String = "ALLOW005"

        /** An unknown `@directive`. */
        public const val UNKNOWN_DIRECTIVE: String = "ALLOW006"

        /** Every id this parser can fail under, for the docs test. */
        public val IDS: List<String> = listOf(
            UNKNOWN_RULE, NO_REASONING, MALFORMED_TARGET, UNUSED_ENTRY, VERSION_DRIFT,
            UNKNOWN_DIRECTIVE,
        )

        /**
         * Parses [text].
         *
         * Blank lines and whole-line `#` comments are skipped. Everything else must be either
         * an `@version` directive or a well-formed entry.
         */
        public fun parse(text: String): Allowlist {
            val entries = ArrayList<AllowEntry>()
            val pins = ArrayList<VersionPin>()
            val problems = ArrayList<AllowlistProblem>()

            text.lineSequence().forEachIndexed { index, raw ->
                val lineNumber = index + 1
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

                if (line.startsWith("@")) {
                    parseDirective(line, lineNumber, pins, problems)
                    return@forEachIndexed
                }

                // Split the reasoning off first: the target itself contains a `#`.
                val hash = line.indexOf(" #")
                if (hash < 0) {
                    problems += AllowlistProblem(
                        NO_REASONING,
                        "line $lineNumber: '$line' has no '  # reasoning'. Every exception " +
                            "carries the reason it is allowed; an entry nobody justified is " +
                            "the dumping ground this format exists to prevent.",
                    )
                    return@forEachIndexed
                }
                val head = line.substring(0, hash).trim()
                val reasoning = line.substring(hash + 2).trim()
                if (reasoning.isEmpty()) {
                    problems += AllowlistProblem(
                        NO_REASONING,
                        "line $lineNumber: '$line' has an empty reasoning after '#'.",
                    )
                    return@forEachIndexed
                }

                val columns = head.split(Regex("\\s+"))
                if (columns.size != 2) {
                    problems += AllowlistProblem(
                        MALFORMED_TARGET,
                        "line $lineNumber: expected 'RULE_ID  owner.Class#member  # reasoning', " +
                            "got '$head'.",
                    )
                    return@forEachIndexed
                }
                val (ruleId, target) = columns
                if (DeterminismRules.byId(ruleId) == null) {
                    problems += AllowlistProblem(
                        UNKNOWN_RULE,
                        "line $lineNumber: '$ruleId' is not a determinism rule id. Known ids: " +
                            "${DeterminismRules.IDS.joinToString(", ")}.",
                    )
                    return@forEachIndexed
                }
                val split = target.lastIndexOf('#')
                if (split <= 0 || split == target.length - 1) {
                    problems += AllowlistProblem(
                        MALFORMED_TARGET,
                        "line $lineNumber: '$target' is not 'owner.Class#member'. Use '#*' to " +
                            "allow every member of one owner; nothing wider is expressible.",
                    )
                    return@forEachIndexed
                }
                entries += AllowEntry(
                    ruleId = ruleId,
                    owner = target.substring(0, split),
                    member = target.substring(split + 1),
                    reasoning = reasoning,
                    line = lineNumber,
                )
            }
            return Allowlist(entries, pins, problems)
        }

        private fun parseDirective(
            line: String,
            lineNumber: Int,
            pins: MutableList<VersionPin>,
            problems: MutableList<AllowlistProblem>,
        ) {
            val columns = line.split(Regex("\\s+"))
            if (columns.firstOrNull() != "@version" || columns.size != 3) {
                problems += AllowlistProblem(
                    UNKNOWN_DIRECTIVE,
                    "line $lineNumber: '$line' is not a directive this file understands. The " +
                        "only one is '@version <catalog-alias> <version>'.",
                )
                return
            }
            pins += VersionPin(columns[1], columns[2], lineNumber)
        }
    }

    /**
     * Version drift, as problems.
     *
     * Issue #151's reason for existing in one method: the manual Fleks/LibGDX audit was
     * performed against *particular versions*, and its verdicts ("`Family` iteration is
     * insertion-ordered", "`MathUtils.sin` reads a fixed table") are statements about that
     * source. An upgrade silently invalidates every one of them, so an upgrade has to fail this
     * task until somebody re-reads the audit and re-stamps the pin.
     */
    public fun versionProblems(resolved: Map<String, String>): List<AllowlistProblem> {
        val missing = resolved.keys.filter { alias -> versionPins.none { it.name == alias } }
            .map { alias ->
                AllowlistProblem(
                    VERSION_DRIFT,
                    "determinism-allowlist.txt has no '@version $alias ${resolved[alias]}' pin. " +
                        "The audit in determinism-audit.md is a set of claims about a " +
                        "particular version of $alias; an unpinned one is an unreviewed one.",
                )
            }
        val drifted = versionPins.mapNotNull { pin ->
            val actual = resolved[pin.name] ?: return@mapNotNull AllowlistProblem(
                VERSION_DRIFT,
                "line ${pin.line}: '@version ${pin.name}' pins a library the build does not " +
                    "resolve. Remove the pin or fix the alias.",
            )
            if (actual == pin.version) {
                null
            } else {
                AllowlistProblem(
                    VERSION_DRIFT,
                    "line ${pin.line}: the audit was performed against ${pin.name} " +
                        "${pin.version}, but the build resolves $actual. Re-read the affected " +
                        "rows of determinism-audit.md against the new source, then move the " +
                        "pin. Iteration order, lookup tables and pool reuse are all things a " +
                        "point release is entitled to change.",
                )
            }
        }
        return missing + drifted
    }
}
