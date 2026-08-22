package dev.wildware.udea.build

/**
 * What `udeaVerifyRelease` refuses to let out of the door.
 *
 * `udea-agent` is an MCP surface with `spawn_blueprint` and `set_component_field` on it, and
 * `udea-agent-host` serves it over loopback HTTP. Both are debug-only, and spec 4 words that
 * as "verified absent from release" rather than "excluded from release" for a reason: the
 * exclusion depends on a Gradle variant a developer can misconfigure, and a misconfigured
 * variant fails **silently**. A shipped game with a live remote-control API is the failure
 * this exists to make impossible.
 *
 * The check reads the packaged zip, not the configuration model. A green model check over a
 * leaky jar is exactly the failure mode being guarded against, and reading the artifact is
 * also what makes the gate survive shading and fat-jar packaging later, where the model no
 * longer describes what ships.
 */
public object ReleaseRules {

    /** Stable id for "an agent class is inside the packaged artifact". */
    public val ARTIFACT_RULE_ID: RuleId = RuleId("UDEA-REL-001")

    /**
     * Entry prefixes that may not appear in a release artifact.
     *
     * Configurable through `udeaVerifyRelease.bannedPrefixes`; these are the defaults and the
     * only ones Phase 0 needs. Old-tree packages are deliberately absent — those modules are
     * deleted in Phase 6, not gated.
     */
    public val DEFAULT_BANNED_PREFIXES: List<String> = listOf(
        "dev/wildware/udea/agent/",
        "dev/wildware/udea/agenthost/",
    )

    /**
     * The belt to the artifact scan's braces: the same two modules, banned from the release
     * runtime classpath.
     *
     * Checking both the model and the artifact is not redundant. The model check says *which
     * dependency* to remove, which the artifact scan cannot; the artifact scan catches the
     * case where the model is clean and the packaging is not, which the model check cannot.
     */
    public val CLASSPATH_RULE: DependencyRule = DependencyRule(
        id = RuleId("UDEA-REL-002"),
        summary = "a release build resolves neither udea-agent nor udea-agent-host",
        rationale = "The agent tool surface mutates the live simulation and the host serves it " +
            "over HTTP. Debug-only means absent from the shipped classpath, not merely disabled " +
            "at run time, because a runtime flag is one misconfiguration away from being on.",
        specSection = "4, 6 (Phase 1 exit)",
        configurations = setOf("runtimeClasspath"),
        banned = listOf(
            CoordinatePattern(":udea-agent"),
            CoordinatePattern(":udea-agent-host"),
        ),
    )

    /** One zip entry, named by the archive it came out of. */
    public data class ArchiveEntry(
        public val archivePath: String,
        public val entryName: String,
    )

    /** Every entry of [entries] whose name starts with one of [bannedPrefixes]. */
    public fun artifactViolations(
        entries: List<ArchiveEntry>,
        bannedPrefixes: List<String>,
    ): List<ArchiveEntry> =
        entries.filter { entry -> bannedPrefixes.any { entry.entryName.startsWith(it) } }
            .sortedWith(compareBy({ it.archivePath }, { it.entryName }))

    /**
     * The message to fail with when the task scanned nothing, or `null` when it had
     * something to look at.
     *
     * A release gate that finds no archive is not a passing release gate. This is the branch
     * that makes the difference between "no agent class shipped" and "nobody looked".
     */
    public fun brokenCheck(projectPath: String, scannedArchives: List<String>): String? {
        if (scannedArchives.isNotEmpty()) return null
        return "${ARTIFACT_RULE_ID.value} $projectPath: udeaVerifyRelease found no packaged " +
            "artifact to scan. A release gate with no input passes forever - make the release " +
            "assemble produce a jar, or fix the archive selection, but do not ship on this."
    }

    /** The build-failure message for [violations], or `null` when there are none. */
    public fun report(
        projectPath: String,
        violations: List<ArchiveEntry>,
        bannedPrefixes: List<String>,
    ): String? {
        if (violations.isEmpty()) return null
        return buildString {
            append(ARTIFACT_RULE_ID.value)
            append(' ')
            append(projectPath)
            append(": ")
            append(violations.size)
            append(if (violations.size == 1) " banned entry" else " banned entries")
            append(" in the packaged artifact. Banned prefixes: ")
            append(bannedPrefixes.joinToString())
            appendLine('.')
            violations.forEach {
                append("    ")
                append(it.archivePath)
                append(" -> ")
                appendLine(it.entryName)
            }
            append(
                "    The agent surface is debug-only and must be absent from a release build, " +
                    "not merely disabled in it.",
            )
        }
    }
}
