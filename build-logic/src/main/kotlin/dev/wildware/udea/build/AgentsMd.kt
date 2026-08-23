package dev.wildware.udea.build

/**
 * Keeps `AGENTS.md` honest about the tree it describes.
 *
 * The premise of the project is that agents do most of the work, and an agent starting cold has
 * `AGENTS.md` and nothing else. The rules cheapest to break are the invisible ones — the module
 * arrows, the tick model, the reconciled decisions that killed `by net(...)` and the separate
 * snapshot codec — so a stale `AGENTS.md` is a correctness bug rather than a docs nit.
 *
 * Two things are checkable without a human reading the file, and both are checked:
 *
 * 1. Its module table lists **exactly** the projects in `settings.gradle.kts`. A module added
 *    without a row is a module the next agent does not know exists; a row for a module that has
 *    been deleted is worse, because it reads as current.
 * 2. It names every cross-cutting contract from spec section 5. Those are the agreements a late
 *    change breaks several modules at once, so an agent that has not been told about one is an
 *    agent about to break it.
 */
public object AgentsMd {

    /** `AGENTS.md` lists a module `settings.gradle.kts` does not, or vice versa. */
    public val MODULE_TABLE_DRIFT: RuleId = RuleId("UDEA-DOC-001")

    /** `AGENTS.md` does not name one of the spec section 5 contracts. */
    public val MISSING_CONTRACT: RuleId = RuleId("UDEA-DOC-002")

    /**
     * The cross-cutting contracts of spec section 5, by the name that section gives them.
     *
     * Issue #138 says "all eight"; the section's table has nine rows. The list follows the
     * spec rather than the count, because the count is the thing that is wrong.
     */
    public val CONTRACTS: List<String> = listOf(
        "Serialization",
        "Dirty determination",
        "Id assignment",
        "Between-tick mutation",
        "Entity identity",
        "Time",
        "Authority vocabulary",
        "Diagnostics",
        "Randomness",
    )

    /** `include("...")` in a settings script, capturing the project path inside the quotes. */
    private val INCLUDE = Regex("""^\s*include\(\s*"([^"]+)"\s*\)""", RegexOption.MULTILINE)

    /**
     * A module-table row: a leading pipe, then a name in backticks.
     *
     * Only the first cell is read. The purpose column is prose and belongs to whoever writes
     * it; the name is the part that has to agree with the build.
     */
    private val TABLE_ROW = Regex("""^\|\s*`([^`]+)`\s*\|""", RegexOption.MULTILINE)

    /** Every project included by [settingsScript], in declaration order. */
    public fun declaredModules(settingsScript: String): List<String> =
        INCLUDE.findAll(settingsScript).map { it.groupValues[1] }.toList()

    /** The heading the module table lives under. */
    public const val MODULE_SECTION: String = "## Modules"

    /**
     * Every module named in [agentsMd]'s module table.
     *
     * Scoped to the [MODULE_SECTION] section rather than the whole file on purpose: `AGENTS.md`
     * has other tables whose first cell is also a backticked identifier — the render modes, the
     * bridge endpoints — and reading those as modules would make the gate fail on a correct
     * document, which is the fastest way to get a gate deleted.
     *
     * @throws IllegalArgumentException if the section is absent. A module table this cannot
     *   find is a module table that is not being checked.
     */
    public fun documentedModules(agentsMd: String): List<String> {
        val start = agentsMd.indexOf(MODULE_SECTION)
        require(start >= 0) { "AGENTS.md has no '$MODULE_SECTION' section, so its module table cannot be checked" }
        val rest = agentsMd.substring(start + MODULE_SECTION.length)
        val end = rest.indexOf("\n## ").takeIf { it >= 0 } ?: rest.length
        return TABLE_ROW.findAll(rest.substring(0, end)).map { it.groupValues[1] }.toList()
    }

    /**
     * Every way [agentsMd] disagrees with [settingsScript], plus any unnamed contract.
     *
     * @param agentsMdPath repo-relative path, for the finding's location.
     */
    public fun findings(
        agentsMd: String,
        settingsScript: String,
        agentsMdPath: String = "AGENTS.md",
    ): List<MigrationFinding> {
        val declared = declaredModules(settingsScript).toSet()
        val documented = documentedModules(agentsMd).toSet()

        val undocumented = (declared - documented).sorted().map {
            MigrationFinding(
                rule = MODULE_TABLE_DRIFT,
                path = agentsMdPath,
                line = 1,
                message = "settings.gradle.kts includes '$it', which has no row in the module " +
                    "table. An agent starting cold would not know the module exists.",
            )
        }
        val phantom = (documented - declared).sorted().map {
            MigrationFinding(
                rule = MODULE_TABLE_DRIFT,
                path = agentsMdPath,
                line = 1,
                message = "the module table lists '$it', which settings.gradle.kts does not " +
                    "include. A row for a module that is gone reads as current.",
            )
        }
        val missingContracts = CONTRACTS.filterNot { agentsMd.contains(it) }.map {
            MigrationFinding(
                rule = MISSING_CONTRACT,
                path = agentsMdPath,
                line = 1,
                message = "does not name the spec section 5 contract '$it'. A contract an agent " +
                    "has not been told about is a contract it is about to break.",
            )
        }
        return undocumented + phantom + missingContracts
    }
}
