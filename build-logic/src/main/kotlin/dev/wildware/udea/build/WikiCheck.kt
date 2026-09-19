package dev.wildware.udea.build

import java.io.File

/**
 * The repository as a wiki page sees it: does a repo-relative path name something that exists.
 *
 * A path may elide a run of directories with a `...` segment - `udea-core/src/.../Tick.kt` - which
 * matches when some file under the part before the elision ends with the part after it. The walk
 * behind an elision skips [SKIPPED_DIRECTORIES], so a generated copy of a file never stands in for
 * the source one a page means.
 */
public class RepoFiles(private val root: File) {

    /** True when [path] (forward slashes, repo-relative, optional trailing `/`) exists. */
    public fun exists(path: String): Boolean {
        val segments = path.trimEnd('/').split('/').filter { it.isNotEmpty() }
        val wantsDirectory = path.endsWith("/")
        return matches(root, segments, wantsDirectory)
    }

    private fun matches(dir: File, segments: List<String>, wantsDirectory: Boolean): Boolean {
        if (segments.isEmpty()) return !wantsDirectory || dir.isDirectory
        val head = segments.first()
        val rest = segments.drop(1)
        if (head == ELISION) {
            return descendantDirectories(dir).any { matches(it, rest, wantsDirectory) }
        }
        val child = File(dir, head)
        return child.exists() && matches(child, rest, wantsDirectory)
    }

    /** [dir] itself and every directory below it, [SKIPPED_DIRECTORIES] excluded. */
    private fun descendantDirectories(dir: File): Sequence<File> =
        dir.walkTopDown()
            .onEnter { it == dir || it.name !in SKIPPED_DIRECTORIES }
            .filter { it.isDirectory }

    private companion object {
        const val ELISION = "..."
        val SKIPPED_DIRECTORIES = setOf("build", ".gradle", ".git", ".kotlin", "node_modules")
    }
}

/**
 * Keeps `docs/wiki/` pointing only at things that exist.
 *
 * The wiki is the document a newcomer - person or agent - reads first, and nearly every sentence
 * in it names something: another page, a file, a Gradle task. Those are the parts that rot
 * silently when code moves, so they are the parts this checks:
 *
 * - [BROKEN_LINK]: a link to another wiki page, as `[[Page Name]]`, `[[text|Page Name]]` or
 *   `[text](Page-Name)`, names a page that is not in `docs/wiki/`; or a relative link with a
 *   slash in it does not resolve from `docs/wiki/`.
 * - [MISSING_PATH]: a backticked repository path does not exist. What counts as a path is
 *   decided by [repoPathOrNull], which reads its KDoc for the cases it deliberately passes over.
 * - [UNKNOWN_PROJECT]: a Gradle task path such as `:moba:desktop:runEditor` names a project
 *   that `settings.gradle.kts` does not create. The task name itself is not checked: tasks are
 *   registered by plugins at configuration time, and a document gate cannot see them.
 * - [STALE_PENDING]: `docs/wiki/.pending` lists a page that now exists.
 *
 * `.pending` exists because the wiki is written by more than one branch at once, and `Home.md`
 * links every page. A page named there may be linked before it lands; once it has landed, the
 * entry is a finding, so the last branch to merge is made to delete it rather than trusted to.
 *
 * Links and paths are read outside fenced code blocks only, because a code block shows text
 * rather than linking it. Task paths are read everywhere, fences included, because a fenced
 * `sh gradlew :moba:desktop:run` is exactly the line a reader will paste.
 */
public object WikiCheck {

    /** Where the wiki lives, repo-relative. */
    public const val WIKI_DIRECTORY: String = "docs/wiki"

    /** The list of pages that may be linked before they exist, inside [WIKI_DIRECTORY]. */
    public const val PENDING_FILE: String = ".pending"

    /** A wiki link names a page that does not exist. */
    public val BROKEN_LINK: RuleId = RuleId("UDEA-DOC-004")

    /** A backticked repository path does not exist. */
    public val MISSING_PATH: RuleId = RuleId("UDEA-DOC-005")

    /** A Gradle task path names a project settings.gradle.kts does not create. */
    public val UNKNOWN_PROJECT: RuleId = RuleId("UDEA-DOC-006")

    /** `.pending` lists a page that now exists. */
    public val STALE_PENDING: RuleId = RuleId("UDEA-DOC-007")

    private val FENCE = Regex("""^\s*(```|~~~)""")
    private val INLINE_CODE = Regex("""`([^`\n]+)`""")
    private val WIKI_LINK = Regex("""\[\[([^\]\n]+)]]""")
    private val MARKDOWN_LINK = Regex("""\[[^\]\n]*]\(([^)\s]+)(?:\s+"[^"]*")?\)""")
    private val URL_SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""")

    /**
     * A task path: a colon, then colon-separated names. Not preceded by a word character, a
     * colon, a dot or a slash, which is what keeps `127.0.0.1:8010`, `Foo::class` and
     * `moba:game` out of it.
     */
    private val TASK_PATH = Regex("""(?<![\w:./-])((?::[A-Za-z0-9_][A-Za-z0-9_-]*)+)""")

    /** The shape of a slashed path: names of letters, digits, `.`, `_`, `-` and `@`. */
    private val PATH_SHAPE = Regex("""^[A-Za-z0-9_.@-]+(/[A-Za-z0-9_.@-]+)+/?$""")

    /** A file name with a stem and an extension: `Tick.kt`, not `.udearep`. */
    private val FILE_NAME = Regex("""^[^.]+\.[A-Za-z0-9]+$""")

    /** Page names from a `.pending` file: one per line, `#` comments and blank lines ignored. */
    public fun pendingPages(text: String): Set<String> =
        text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()

    /** Every project path [settingsScript] creates: its includes, their parents, and the root. */
    public fun projects(settingsScript: String): Set<String> =
        buildSet {
            add(":")
            AgentsMd.declaredModules(settingsScript).forEach { include ->
                val segments = include.trimStart(':').split(':')
                for (n in 1..segments.size) add(":" + segments.take(n).joinToString(":"))
            }
        }

    /**
     * Every problem in [pages].
     *
     * @param pages file name (`Home.md`) to its text, for every page in the wiki.
     * @param pending the page file names `.pending` lists.
     * @param repo the repository the paths are resolved against.
     * @param projects every Gradle project path, as [projects] returns them.
     */
    public fun findings(
        pages: Map<String, String>,
        pending: Set<String>,
        repo: RepoFiles,
        projects: Set<String>,
    ): List<GateFinding> {
        val linkable = pages.keys + pending
        val pageFindings = pages.toSortedMap().flatMap { (name, text) ->
            pageFindings("$WIKI_DIRECTORY/$name", text, linkable, repo, projects)
        }
        val stale = pending.filter { it in pages.keys }.sorted().map {
            GateFinding(
                rule = STALE_PENDING,
                path = "$WIKI_DIRECTORY/$PENDING_FILE",
                line = 1,
                message = "lists '$it', which now exists. Remove the entry, and delete the file " +
                    "when it is empty: a pending list that never empties hides the next broken link.",
            )
        }
        return pageFindings + stale
    }

    private fun pageFindings(
        path: String,
        text: String,
        linkable: Set<String>,
        repo: RepoFiles,
        projects: Set<String>,
    ): List<GateFinding> {
        val findings = mutableListOf<GateFinding>()
        var inFence = false
        text.lines().forEachIndexed { index, line ->
            val lineNumber = index + 1
            fun finding(rule: RuleId, message: String) {
                findings += GateFinding(rule, path, lineNumber, message)
            }

            TASK_PATH.findAll(line).forEach { match ->
                val segments = match.groupValues[1].trimStart(':').split(':')
                if (segments.size < 2) return@forEach
                val project = ":" + segments.dropLast(1).joinToString(":")
                if (project !in projects) {
                    finding(
                        UNKNOWN_PROJECT,
                        "the task path '${match.groupValues[1]}' names the project '$project', " +
                            "which settings.gradle.kts does not create.",
                    )
                }
            }

            if (FENCE.containsMatchIn(line)) {
                inFence = !inFence
                return@forEachIndexed
            }
            if (inFence) return@forEachIndexed

            INLINE_CODE.findAll(line).forEach { match ->
                val repoPath = repoPathOrNull(match.groupValues[1].trim(), repo) ?: return@forEach
                if (!repo.exists(repoPath)) {
                    finding(MISSING_PATH, "names the repository path '$repoPath', which does not exist.")
                }
            }

            val prose = INLINE_CODE.replace(line, "")
            WIKI_LINK.findAll(prose).forEach { match ->
                val target = match.groupValues[1].substringAfter('|').trim()
                val page = target.substringBefore('#').trim().replace(' ', '-') + ".md"
                if (page !in linkable) {
                    finding(BROKEN_LINK, "links to the page '${page.removeSuffix(".md")}', which is not in $WIKI_DIRECTORY/.")
                }
            }
            MARKDOWN_LINK.findAll(prose).forEach { match ->
                val target = match.groupValues[1]
                if (URL_SCHEME.containsMatchIn(target) || target.startsWith("#") || target.startsWith("/")) {
                    return@forEach
                }
                val bare = target.substringBefore('#')
                if ('/' in bare) {
                    val resolved = File("$WIKI_DIRECTORY/$bare").normalize().invariantSeparatorsPath
                    if (resolved.startsWith("..") || !repo.exists(resolved)) {
                        finding(BROKEN_LINK, "links to '$bare', which does not resolve from $WIKI_DIRECTORY/.")
                    }
                } else {
                    val page = bare.removeSuffix(".md") + ".md"
                    if (page !in linkable) {
                        finding(BROKEN_LINK, "links to the page '${page.removeSuffix(".md")}', which is not in $WIKI_DIRECTORY/.")
                    }
                }
            }
        }
        return findings
    }

    /**
     * [code] as a repository path to check, or null when it is not one.
     *
     * It is one when it has the shape of a slashed path and either its first name is something
     * at the repository root, or its last name is a file name with an extension - the second
     * clause is what still catches a typo in the first directory. Passed over, deliberately:
     *
     * - anything with characters a path here never has: `*` globs, `<placeholders>`, URLs, spaces;
     * - `/health`-style absolute paths, which in this wiki are HTTP endpoints;
     * - anything under a `build/` directory, which is output a clean checkout does not have;
     * - slashed prose such as `jvm/android` or `@Net/@Sim`, which matches neither clause.
     */
    internal fun repoPathOrNull(code: String, repo: RepoFiles): String? {
        if (!PATH_SHAPE.matches(code)) return null
        val names = code.trimEnd('/').split('/')
        if ("build" in names) return null
        val rooted = names.first() != "..." && repo.exists(names.first())
        val fileNamed = FILE_NAME.matches(names.last())
        return code.takeIf { rooted || fileNamed }
    }

    /** The failure message for [findings] from the gate [taskName], or null when there are none. */
    internal fun report(taskName: String, findings: List<GateFinding>): String? =
        gateFailureReport(
            taskName,
            findings,
            "Every link, backticked path and task path in $WIKI_DIRECTORY/ must name something " +
                "that exists. Fix the page, or - for a page another branch is still writing - list " +
                "its file name in $WIKI_DIRECTORY/$PENDING_FILE until it lands.",
        )
}
