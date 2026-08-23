package dev.wildware.udea.agent.assets

import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.Json
import dev.wildware.udea.annotations.AgentTool
import dev.wildware.udea.annotations.Arg
import dev.wildware.udea.assets.compiler.DeclaredAsset
import dev.wildware.udea.assets.compiler.Ref
import dev.wildware.udea.assets.compiler.daemon.AssetDaemon
import dev.wildware.udea.assets.compiler.daemon.ReloadOutcome
import dev.wildware.udea.assets.compiler.daemon.ValidationReport
import dev.wildware.udea.core.Tick
import dev.wildware.udea.diagnostics.DidYouMean
import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import dev.wildware.udea.diagnostics.UdeaRules
import java.io.File
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * `assets.*`: read, search, validate and edit the asset graph through the warm daemon.
 *
 * ## The tool surface is the editor
 *
 * Spec 1 deletes the level editor and the IDE plugin, and this is what replaces them.
 * `assets.validate` - not `./gradlew build` - is the agent's compile loop, which is why it is
 * gated at 300ms warm (`DaemonLatencyBudgetTest`) rather than merely measured. Everything here is
 * a thin handler over [AssetDaemon]; there is no asset logic in this file and there must not be,
 * because the daemon is the same code a Gradle path runs and a second opinion here would be the
 * IDE-versus-CI divergence spec 3.6 exists to prevent.
 *
 * ## Why this toolset is not in `EngineToolModules`
 *
 * It needs `udea-assets-compiler`, which carries the Kotlin scripting host, and `UDEA-MG-005`
 * forbids that on the shipped game's runtime classpath. So `udea-agent` takes the dependency
 * `compileOnly`, this class is unloadable in a process without a daemon, and only the dev host
 * that has one registers [AssetToolModule]. Listing it in `EngineToolModules.ALL` would make
 * touching that object throw `NoClassDefFoundError` in every shipped game.
 *
 * ## Writes are validated first
 *
 * [write] and [patch] both validate before they commit and restore the previous bytes on any
 * error. An agent's broken edit must leave the file it was editing byte-identical *and* leave the
 * running game on its last-good graph; the second is [AssetDaemon]'s job and the first is this
 * class's.
 */
public class AssetsToolset(
    private val daemon: AssetDaemon,
    /** The live game, when there is one. `null` for a daemon serving no running process. */
    private val hotReload: AssetHotReload? = null,
) {

    @AgentTool(
        name = "assets.list",
        description = "Every asset id in the graph, with its kind and the script that declares " +
            "it. Start here when you do not yet know what exists; use assets.search once you do.",
    )
    public fun list(
        // Nullable rather than `= ""`: the generator publishes a nullable parameter as optional
        // with no default, and `@Arg(default = "")` is indistinguishable from declaring none.
        @Arg(description = "Only ids starting with this prefix, e.g. \"character/\".", required = false)
        prefix: String?,
        @Arg(description = "Only assets of this kind, as assets.list spells it.", required = false)
        kind: String?,
        @Arg(description = "Rows to return, at most 500.", required = false, default = "200")
        limit: Int = 200,
    ): AgentResult = AgentResult.ok {
        val matches = daemon.ids
            .mapNotNull { daemon.declaration(it) }
            .filter { it.id.startsWith(prefix.orEmpty()) && (kind.isNullOrEmpty() || it.kind == kind) }
        put("total", matches.size)
        put("generation", daemon.generation)
        arr("assets") { matches.take(limit.coerceIn(1, MAX_ROWS)).forEach { element { summary(it) } } }
    }

    @AgentTool(
        name = "assets.get",
        description = "One asset in full: its kind, every field value, the ids it references " +
            "and the script it is declared in. The tool to call before patching anything.",
    )
    public fun get(
        @Arg(description = "Asset id, exactly as assets.list spells it.")
        id: String,
    ): AgentResult {
        val declared = daemon.declaration(id) ?: return miss(id)
        return AgentResult.ok {
            summary(declared)
            obj("fields") { declared.fields.forEach { (name, value) -> key(name); fieldValue(value) } }
            arr("references") { declared.referencedIds.forEach { value(it) } }
            // False means the kind has no AssetData type yet, so editing it cannot hot-reload.
            // Stated rather than implied: an agent that patches such an asset and sees no change
            // in the game deserves to know why before it starts debugging the game.
            put("packed", daemon.value(id) != null)
        }
    }

    @AgentTool(
        name = "assets.search",
        description = "Assets whose id or field values contain a substring. Use it to find " +
            "everything that mentions a sprite path or a component type before you rename one.",
    )
    public fun search(
        @Arg(description = "Case-insensitive substring to look for in ids and field values.")
        query: String,
        @Arg(description = "Rows to return, at most 500.", required = false, default = "50")
        limit: Int = 50,
    ): AgentResult {
        if (query.isBlank()) {
            return AgentResult.failed(AgentErrorKind.BAD_ARGUMENT, "assets.search needs a non-blank query")
        }
        val needle = query.lowercase()
        val matches = daemon.ids.mapNotNull { daemon.declaration(it) }.filter { asset ->
            needle in asset.id.lowercase() || asset.fields.values.any { needle in it.toString().lowercase() }
        }
        return AgentResult.ok {
            put("total", matches.size)
            arr("assets") { matches.take(limit.coerceIn(1, MAX_ROWS)).forEach { element { summary(it) } } }
        }
    }

    @AgentTool(
        name = "assets.graph",
        description = "What one asset reaches, out to a depth: the reference tree rooted at an " +
            "id. Use it to see what a level actually pulls in before you change something it uses.",
    )
    public fun graph(
        @Arg(description = "Asset id to start from.")
        rootId: String,
        @Arg(description = "How many reference hops to follow, 1 to 6.", required = false, default = "2")
        depth: Int = 2,
    ): AgentResult {
        if (daemon.declaration(rootId) == null) return miss(rootId)
        val hops = depth.coerceIn(1, MAX_DEPTH)
        val seen = LinkedHashSet<String>()
        return AgentResult.ok {
            put("rootId", rootId)
            put("depth", hops)
            arr("edges") { walk(rootId, hops, seen) }
            put("visited", seen.size)
        }
    }

    @AgentTool(
        name = "assets.resolve_reference",
        description = "Does this id exist, and is it the kind you expect? Answers with the " +
            "closest existing ids when it does not, so a typo costs one call rather than a turn " +
            "spent listing the tree.",
    )
    public fun resolveReference(
        @Arg(description = "The id a reference(\"...\") names.")
        id: String,
        @Arg(
            description = "The kind you expect it to be, as assets.list spells it. Omit to skip " +
                "the kind check.",
            required = false,
        )
        expectedKind: String?,
    ): AgentResult {
        val declared = daemon.declaration(id)
        return AgentResult.ok {
            put("id", id)
            put("exists", declared != null)
            put("kind", declared?.kind)
            put("matchesExpected", if (expectedKind.isNullOrEmpty()) true else declared?.kind == expectedKind)
            // The rule a diagnostic for this miss would carry, so an agent reading a build log and
            // an agent reading this result see one name for one defect (spec 5).
            put("ruleId", UdeaRules.UNRESOLVED_REFERENCE.id)
            arr("suggestions") { if (declared == null) suggestions(id).forEach { value(it) } }
        }
    }

    @AgentTool(
        name = "assets.validate",
        description = "Compile and check the asset graph and return the diagnostics, changing " +
            "nothing. This is the compile loop: run it after every edit instead of a Gradle build.",
    )
    public fun validate(
        @Arg(
            description = "Repo-relative script paths to check, comma separated. Omit to check " +
                "everything the daemon holds.",
            required = false,
        )
        files: String?,
    ): AgentResult {
        val report = daemon.validate(pathsOf(files.orEmpty()))
        return AgentResult.ok { validation(report) }
    }

    @AgentTool(
        name = "assets.write",
        description = "Replace a script's whole text, validate it, and hot-reload the running " +
            "game. A file that fails to validate is restored byte for byte and the game keeps " +
            "the assets it already had.",
    )
    public fun write(
        @Arg(description = "Repo-relative path of the .udea.kts to write.")
        path: String,
        @Arg(description = "The complete new contents of the file.")
        content: String,
        @Arg(
            description = "Push the change into the running game when it validates.",
            required = false,
            default = "true",
        )
        apply: Boolean = true,
    ): AgentResult = edit(path, apply) { content }

    @AgentTool(
        name = "assets.patch",
        description = "Replace one exact substring in a script, validate, and hot-reload. Use " +
            "this to tune a number without reformatting the file or resending it whole.",
    )
    public fun patch(
        @Arg(description = "Repo-relative path of the .udea.kts to patch.")
        path: String,
        @Arg(description = "The exact text to replace. It must occur exactly once in the file.")
        find: String,
        @Arg(description = "What to put in its place.")
        replace: String,
        @Arg(
            description = "Push the change into the running game when it validates.",
            required = false,
            default = "true",
        )
        apply: Boolean = true,
    ): AgentResult {
        if (find.isEmpty()) {
            return AgentResult.failed(AgentErrorKind.BAD_ARGUMENT, "assets.patch needs a non-empty `find`")
        }
        return edit(path, apply) { original ->
            val occurrences = original.split(find).size - 1
            // Exactly once, checked rather than assumed: a `find` matching twice would make which
            // edit happened depend on string order, and one matching zero times would report
            // "written" for a file nothing changed in.
            require(occurrences == 1) {
                "`find` occurs $occurrences times in $path and a patch must be unambiguous; " +
                    "include more surrounding text"
            }
            original.replace(find, replace)
        }
    }

    @AgentTool(
        name = "assets.changed_since",
        description = "Which asset ids have hot-reloaded since a tick. Call it after a rewind " +
            "that reports assetGraphChangedSince to see exactly what is different now.",
    )
    public fun changedSince(
        @Arg(description = "The tick to compare against, as time.rewind reports it.")
        tick: Long,
    ): AgentResult {
        val reload = hotReload ?: return AgentResult.failed(
            AgentErrorKind.TOOL_THREW,
            "this daemon is not attached to a running game, so no asset has hot-reloaded",
        )
        return AgentResult.ok {
            put("sinceTick", tick)
            put("appliedReloads", reload.applied)
            put("lastAppliedTick", reload.lastAppliedTick?.value ?: -1L)
            arr("changedIds") { reload.changedSince(Tick(tick)).forEach { value(it.value) } }
        }
    }

    // --- the shared edit path -------------------------------------------------------------------

    /**
     * Rewrite, validate, reload, and put the old bytes back on any failure.
     *
     * One implementation for [write] and [patch] because the *rollback* is the part that has to be
     * right, and two copies of a rollback is one copy that eventually is not. The original text is
     * read before anything is written and restored on every failing branch - including the one
     * where the file compiles and the *graph* is invalid, which a naive "check the string first"
     * version would leave on disk.
     */
    private fun edit(path: String, apply: Boolean, transform: (String) -> String): AgentResult {
        val file = fileFor(path) ?: return AgentResult.failed(
            AgentErrorKind.BAD_ARGUMENT,
            "no script the daemon is watching matches '$path'; assets.list names the file of " +
                "every asset",
        )
        val original = file.readText()
        val updated = try {
            transform(original)
        } catch (failure: IllegalArgumentException) {
            return AgentResult.failed(
                AgentErrorKind.BAD_ARGUMENT,
                failure.message ?: "the patch could not be applied",
            )
        }
        if (updated == original) {
            return AgentResult.ok {
                put("path", path)
                put("changed", false)
                put("note", "the file already had exactly this content")
            }
        }

        file.writeText(updated)
        val report = daemon.validate(listOf(file))
        if (!report.ok) {
            file.writeText(original)
            return AgentResult.ok {
                put("path", path)
                put("changed", false)
                put("rolledBack", true)
                validation(report)
            }
        }
        if (!apply) {
            return AgentResult.ok {
                put("path", path)
                put("changed", true)
                put("applied", false)
                validation(report)
            }
        }
        return applyReload(path, file)
    }

    private fun applyReload(path: String, file: Path): AgentResult = when (
        val outcome = daemon.reload(listOf(file))
    ) {
        is ReloadOutcome.Applied -> {
            hotReload?.push(outcome.delta)
            daemon.commit()
            AgentResult.ok {
                put("path", path)
                put("changed", true)
                put("applied", true)
                put("generation", daemon.generation)
                put("reloadMs", outcome.durationMs)
                // False means the daemon has no game attached, so the ids below changed in the
                // graph and nothing on screen moved.
                put("pushedToGame", hotReload != null)
                arr("changedIds") { outcome.changedIds.forEach { value(it.value) } }
            }
        }

        is ReloadOutcome.NoChange -> AgentResult.ok {
            put("path", path)
            put("changed", true)
            put("applied", false)
            put("note", "the file changed but no asset value did")
        }

        is ReloadOutcome.RequiresRestart -> AgentResult.ok {
            put("path", path)
            put("changed", true)
            put("applied", false)
            put("code", outcome.code)
            arr("shapeChanges") {
                outcome.changes.forEach {
                    element {
                        put("id", it.id.value)
                        put("code", it.code)
                        put("detail", it.detail)
                    }
                }
            }
        }

        // The file validated and the reload still refused: an unpackable kind. The edit is the
        // author's and is not wrong, so it stays on disk; what is reported is that the running
        // game did not take it.
        is ReloadOutcome.Rejected -> AgentResult.ok {
            put("path", path)
            put("changed", true)
            put("applied", false)
            arr("diagnostics") { outcome.diagnostics.forEach { element { diagnostic(it) } } }
        }
    }

    // --- rendering --------------------------------------------------------------------------

    private fun Json.validation(report: ValidationReport) {
        put("ok", report.ok)
        put("durationMs", report.durationMs)
        put("recompiled", report.recompiled)
        put("errors", report.diagnostics.count { it.severity == Severity.Error })
        arr("diagnostics") { report.diagnostics.forEach { element { diagnostic(it) } } }
    }

    /**
     * One [UdeaDiagnostic], field for field.
     *
     * Deliberately not reshaped: spec 5 requires an agent and a build log to say identical things
     * about one defect, and a tool result that renamed `ruleId` or dropped `causedBy` would be a
     * second vocabulary for the same event.
     */
    private fun Json.diagnostic(diagnostic: UdeaDiagnostic) {
        put("severity", diagnostic.severity.wireName)
        put("ruleId", diagnostic.ruleId)
        put("message", diagnostic.message)
        put("assetId", diagnostic.assetId)
        put("causedBy", diagnostic.causedBy)
        val span = diagnostic.span
        if (span == null) {
            put("span", null as String?)
        } else {
            obj("span") {
                put("path", span.path)
                put("startLine", span.startLine)
                put("startColumn", span.startColumn)
                put("endLine", span.endLine)
                put("endColumn", span.endColumn)
            }
        }
        diagnostic.fix?.let { fix -> obj("fix") { put("description", fix.description) } }
    }

    private fun Json.summary(asset: DeclaredAsset) {
        put("id", asset.id)
        put("kind", asset.kind)
        put("kindFqn", asset.kindFqn)
        put("file", asset.origin?.path)
    }

    /**
     * A field value as JSON.
     *
     * A [Ref] renders as its bare id rather than as a structure, because that is what an author
     * types and therefore what a `assets.patch` has to find in the file.
     */
    private fun Json.fieldValue(value: Any?) {
        when (value) {
            null -> value(null as String?)
            is Boolean -> value(value)
            is Int -> value(value)
            is Long -> value(value)
            is Float -> value(value)
            is Ref -> value(value.id)
            is List<*> -> {
                beginArray()
                value.forEach { fieldValue(it) }
                endArray()
            }

            is Map<*, *> -> {
                beginObject()
                value.forEach { (name, entry) -> key(name.toString()); fieldValue(entry) }
                endObject()
            }

            else -> value(value.toString())
        }
    }

    private fun Json.walk(id: String, remaining: Int, seen: MutableSet<String>) {
        if (remaining == 0 || !seen.add(id)) return
        val declared = daemon.declaration(id) ?: return
        for (target in declared.referencedIds) {
            element {
                put("from", id)
                put("to", target)
                put("resolved", daemon.declaration(target) != null)
            }
        }
        declared.referencedIds.forEach { walk(it, remaining - 1, seen) }
    }

    private fun miss(id: String): AgentResult {
        val closest = suggestions(id)
        return AgentResult.failed(
            NO_SUCH_ASSET,
            "no asset called '$id'" +
                if (closest.isEmpty()) "" else "; did you mean ${closest.joinToString(", ") { "'$it'" }}?",
        )
    }

    /**
     * The closest existing ids to [candidate].
     *
     * [DidYouMean] and not a second edit distance here: the same function backs the build-time
     * diagnostic and `AssetRegistry`'s runtime miss, and three implementations of one threshold is
     * three answers to one question.
     */
    private fun suggestions(candidate: String): List<String> {
        val closest = DidYouMean.suggest(candidate, daemon.ids)
        val sameName = daemon.ids.filter { it.endsWith("/${candidate.substringAfterLast('/')}") }
        return (listOfNotNull(closest) + sameName).distinct().take(SUGGESTIONS)
    }

    private fun fileFor(path: String): Path? {
        val native = path.replace('/', File.separatorChar)
        return daemon.scripts().firstOrNull { it.toString().endsWith(native) || it.toString().endsWith(path) }
    }

    private fun pathsOf(files: String): List<Path> =
        files.split(',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { fileFor(it) }

    public companion object {

        /**
         * No asset of that id exists.
         *
         * Declared here rather than in `AgentErrorKind`, which says plainly that the kinds are
         * open by design and each module declares its own; an enum there would mean every toolset
         * in the tree edits one file to add a failure mode.
         */
        public val NO_SUCH_ASSET: AgentErrorKind = AgentErrorKind("no_such_asset")

        /** The most rows any listing returns. Spec 5 caps a tool result; this is that cap. */
        private const val MAX_ROWS = 500

        /** Reference hops. Past six a graph walk is a document, not an answer. */
        private const val MAX_DEPTH = 6

        /** Suggestions on a miss. One is usually right; three is a menu, ten is noise. */
        private const val SUGGESTIONS = 3
    }
}
