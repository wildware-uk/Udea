package dev.wildware.udea.build.determinism

import java.io.File

/** One violation, with everywhere a reader needs to go to fix it. */
public data class Finding(
    public val ruleId: String,
    /** Repo-relative `path:line:column`, or `<class>` when no source file could be located. */
    public val span: String,
    public val className: String,
    public val method: String,
    public val target: String,
    public val message: String,
    public val didYouMean: String,
) {
    /** The one-line rendering used in the task's failure output. */
    public fun render(): String = "$span: error: [$ruleId] $message\n    did you mean: $didYouMean"
}

/** What one run of the scanner produced. */
public data class ScanResult(
    /** Violations, ranked, capped at [DeterminismRules.MAX_FINDINGS]. */
    public val findings: List<Finding>,
    /** How many violations there were before the cap. */
    public val totalFindings: Int,
    /** Allowlist parse/drift/staleness problems. Any of these fails the task too. */
    public val problems: List<AllowlistProblem>,
    /** Class files actually read, per declared scope. A zero here is a broken gate. */
    public val scannedClasses: Map<String, Int>,
    /** Allowlist entries that suppressed at least one finding. */
    public val usedEntries: Set<String>,
) {
    /** True when the task must fail. */
    public val failed: Boolean get() = findings.isNotEmpty() || problems.isNotEmpty()
}

/**
 * The determinism scan: rules from [DeterminismRules], bytecode from [ClassScanner], exceptions
 * from `determinism-allowlist.txt`.
 *
 * ## Read the last line of its output
 *
 * Green here means "no declared simulation class makes a direct reference to a known-bad
 * member". Spec section 7 predicts, correctly, that this will be misread as "the simulation is
 * deterministic", so the task prints a line saying it is not, every run, pass or fail. The gate
 * is `WorldHasher` snapshot equivalence plus the cross-OS `replay-equality` CI job.
 */
public object DeterminismScan {

    /**
     * Where a declared [SimScope]'s compiled classes and sources live.
     *
     * Passed in rather than derived here so the tests can drive the scan over fixture
     * directories, and so the Gradle task can hand over the exact `classes` output it already
     * declared as a task input.
     */
    public data class ScopeInput(
        public val scope: SimScope,
        /** Roots holding the scope's `.class` files, e.g. `udea-core/build/classes/kotlin/main`. */
        public val classRoots: List<File>,
        /** Source roots, for turning a class into a repo-relative span. */
        public val sourceRoots: List<File>,
    )

    /**
     * Runs the scan.
     *
     * @param repoRoot used to make spans repo-relative.
     * @param resolvedVersions catalog alias to resolved version, for the audit's version pin.
     * @param requireClasses fails when a declared scope contributed no classes at all. A scan of
     *   nothing passes forever, which is the failure mode `HeadlessScan` and `DependencyRules`
     *   both call out; only the unit tests turn it off.
     */
    public fun run(
        inputs: List<ScopeInput>,
        allowlist: Allowlist,
        repoRoot: File,
        resolvedVersions: Map<String, String> = emptyMap(),
        requireClasses: Boolean = true,
    ): ScanResult {
        val problems = ArrayList<AllowlistProblem>(allowlist.problems)
        problems += allowlist.versionProblems(resolvedVersions)

        val raw = ArrayList<Finding>()
        val used = LinkedHashSet<String>()
        val scanned = LinkedHashMap<String, Int>()

        for (input in inputs) {
            val classFiles = ClassScanner.classFilesUnder(input.classRoots)
            scanned[input.scope.project] = classFiles.size
            if (classFiles.isEmpty() && requireClasses) {
                error(
                    "${input.scope.project} (${input.scope.sourceSet}) contributed no compiled " +
                        "classes to udeaVerifyDeterminism. The gate is broken, not the module: " +
                        "a scan of nothing passes forever. Roots looked at: " +
                        input.classRoots.joinToString(", ") { it.path },
                )
            }
            for (classFile in classFiles) {
                // Scanned once per class, not once per reference: `DET004` needs to know what
                // the whole class does (see `DeterminismRules.HASH_ORDER`), and re-walking the
                // bytecode per reference to find out would make the gate quadratic.
                val refs = ClassScanner.scan(classFile)
                val facts = DeterminismRules.classFacts(refs)
                for (ref in refs) {
                    if (!input.scope.covers(ref.className)) continue
                    if (isSynthetic(ref)) continue
                    val rule = DeterminismRules.ALL.firstOrNull { rule ->
                        (rule.appliesTo?.invoke(ref.className) ?: true) &&
                            (rule.requiresClassFact?.invoke(facts) ?: true) &&
                            rule.matches(ref)
                    } ?: continue
                    val entry = allowlist.entries.firstOrNull {
                        it.covers(rule.id, ref.owner, ref.member)
                    }
                    if (entry != null) {
                        used += "${entry.ruleId} ${entry.target}"
                        continue
                    }
                    raw += finding(rule, ref, input, repoRoot)
                }
            }
        }

        problems += allowlist.entries
            .filter { "${it.ruleId} ${it.target}" !in used }
            .map { entry ->
                AllowlistProblem(
                    Allowlist.UNUSED_ENTRY,
                    "line ${entry.line}: '${entry.ruleId} ${entry.target}' matched nothing on " +
                        "this run. Either the code it excused is gone - delete the line - or " +
                        "the scope it lived in stopped being declared simulation, which is a " +
                        "bigger problem than the entry.",
                )
            }

        val ranked = rank(raw)
        return ScanResult(
            findings = ranked.take(DeterminismRules.MAX_FINDINGS),
            totalFindings = ranked.size,
            problems = problems,
            scannedClasses = scanned,
            usedEntries = used,
        )
    }

    /**
     * Ranked root-cause-first: by rule id, then by the class with the most violations, then by
     * line. A reader fixing the top of the list is fixing the file that produced most of it,
     * and a fix at the top routinely deletes several rows below it.
     */
    public fun rank(findings: List<Finding>): List<Finding> {
        val distinct = findings.distinct()
        val perClass = distinct.groupingBy { it.className }.eachCount()
        return distinct.sortedWith(
            compareBy(
                { it.ruleId },
                { -(perClass[it.className] ?: 0) },
                { it.className },
                { it.span },
            ),
        )
    }

    /**
     * Kotlin's own generated members, which reference things the source never wrote.
     *
     * `hashCode`/`equals` on a data class and the `$values` array of an enum are the two that
     * matter here. Neither is a determinism decision anybody took.
     */
    private fun isSynthetic(ref: MemberRef): Boolean =
        ref.method == "\$values" || ref.className.endsWith("\$WhenMappings")

    private fun finding(
        rule: DeterminismRule,
        ref: MemberRef,
        input: ScopeInput,
        repoRoot: File,
    ): Finding {
        val span = span(ref, input, repoRoot)
        val where = "${ref.className}.${ref.method}"
        return Finding(
            ruleId = rule.id,
            span = span,
            className = ref.className,
            method = ref.method,
            target = ref.target,
            message = "$where is declared simulation (${input.scope.project}) and " +
                "${rule.title}: it references ${ref.target}.",
            didYouMean = rule.didYouMean,
        )
    }

    /**
     * Repo-relative `path:line:column`.
     *
     * Falls back to `<${'$'}className>` when the source file cannot be located - a generated
     * class, or one compiled without debug info. Inventing a path would send a reader, or an
     * agent applying a fix, to a file that does not exist.
     */
    private fun span(ref: MemberRef, input: ScopeInput, repoRoot: File): String {
        val sourceFile = ref.sourceFile ?: return "<${ref.className}>"
        val packagePath = ref.className.substringBeforeLast('.', "").replace('.', '/')
        val relative = if (packagePath.isEmpty()) sourceFile else "$packagePath/$sourceFile"
        val file = input.sourceRoots.map { it.resolve(relative) }.firstOrNull { it.isFile }
            ?: return "<${ref.className}>"
        val path = file.relativeToOrNull(repoRoot)?.invariantSeparatorsPath
            ?: file.invariantSeparatorsPath
        // Column 1 rather than a guess: the line is exact, the column is not recorded in a
        // class file, and a fabricated column is a fabricated fact.
        return "$path:${ref.line}:1"
    }

    /** The line every run ends with, green or red. Spec section 7 asks for exactly this. */
    public const val NOT_THE_GATE: String =
        "udeaVerifyDeterminism is a cheap first filter, not the determinism gate. It sees " +
            "direct references only: nondeterminism laundered through Fleks internals, through " +
            "an interface call whose receiver happens to be a HashMap, or through float " +
            "differences between two JVMs is invisible to it. The gate is WorldHasher " +
            "snapshot equivalence and the cross-OS replay-equality CI job. See " +
            "determinism-audit.md for the full list of what this cannot see."

    /** The full report written to `build/reports/udea/determinism.txt` and to the console. */
    public fun report(result: ScanResult): String = buildString {
        appendLine("udeaVerifyDeterminism")
        result.scannedClasses.forEach { (project, count) ->
            appendLine("  scanned $project: $count class files")
        }
        appendLine("  allowlist entries used: ${result.usedEntries.size}")
        appendLine("  findings: ${result.totalFindings}")
        if (result.totalFindings > result.findings.size) {
            appendLine(
                "  (showing the first ${result.findings.size} of ${result.totalFindings}; " +
                    "fix these and re-run)",
            )
        }
        result.findings.forEach { appendLine(it.render()) }
        result.problems.forEach { appendLine("determinism-allowlist.txt: [${it.ruleId}] ${it.message}") }
        appendLine()
        appendLine(NOT_THE_GATE)
    }
}
