package dev.wildware.udea.build

/**
 * **The project-wide component type id space**, as a reviewed file rather than as whatever
 * each module happened to see.
 *
 * A KSP run only ever sees one Gradle module. A processor that numbers the `@Replicated`
 * symbols in front of it therefore hands out `0, 1, 2, …` *per module*, so `udea-gas`'s first
 * component and `moba`'s first component are both `ComponentTypeId(0)`. Two peers then decode
 * each other's packets as the wrong component type, silently, while the connect-time
 * `protoHash` reports agreement — because each module's lock is internally consistent.
 *
 * Spec 5 answers that with one sorted-FQN assignment for the whole build, and the build hands
 * that list to every module as `udea.projectComponents` (`CodegenOptions.PROJECT_COMPONENTS`).
 * The list is a checked-in file, [FILE_NAME] in the repository root, for the same reason
 * `net-protocol.lock` is: an id is a wire-visible promise, so inserting a name renumbers its
 * successors and that has to show up in a diff somebody reads. Deriving it instead by scanning
 * sources or artifacts at configuration time would be discovery by another name — the exact
 * mechanism the retired generator used, and the reason its output depended on build order.
 *
 * The file is deliberately *not* self-maintaining. Adding a `@Replicated` component without
 * adding its name here is a build failure at the symbol, raised by the processor, and that is
 * the moment the id space is supposed to be reviewed.
 */
public object UdeaNetComponents {

    /** The registry's name, in the repository root. */
    public const val FILE_NAME: String = "net-components.lock"

    /** The KSP option the list is handed over as; mirrors `CodegenOptions.PROJECT_COMPONENTS`. */
    public const val KSP_OPTION: String = "udea.projectComponents"

    /** How the option separates names; a fully-qualified name can never contain one. */
    public const val SEPARATOR: Char = ','

    /** A fully-qualified name: dotted segments, each starting with a letter or underscore. */
    private val NAME_FORMAT: Regex = Regex("""[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+""")

    /** The outcome of reading [FILE_NAME]. */
    public sealed interface Parse {
        public data class Success(val components: List<String>) : Parse
        public data class Failure(val problem: String) : Parse
    }

    /**
     * Reads the registry, or explains why it cannot be used as an id space.
     *
     * Every rejection here is a rejection of an id space that would be *ambiguous*, not merely
     * untidy. Out-of-order names would make the assignment depend on how the file was edited;
     * a repeated name would give one component two ids; an empty file would silently mean "no
     * project id space", which is the fallback this whole mechanism exists to remove.
     */
    public fun parse(text: String): Parse {
        val names = text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter(String::isNotEmpty)
            .toList()

        if (names.isEmpty()) {
            return Parse.Failure(
                "$FILE_NAME names no components. An empty id space is not 'no components yet' - " +
                    "it is the per-module numbering this file exists to replace, and it would be " +
                    "applied silently. Delete the file to make its absence a build failure, or " +
                    "list the components.",
            )
        }
        val malformed = names.filterNot(NAME_FORMAT::matches)
        if (malformed.isNotEmpty()) {
            return Parse.Failure(
                "$FILE_NAME contains ${malformed.size} entry/entries that are not fully-qualified " +
                    "component names: ${malformed.joinToString()}.",
            )
        }
        val duplicates = names.groupBy { it }.filterValues { it.size > 1 }.keys.sorted()
        if (duplicates.isNotEmpty()) {
            return Parse.Failure(
                "$FILE_NAME lists ${duplicates.joinToString()} more than once. One component " +
                    "cannot hold two component type ids.",
            )
        }
        if (names != names.sorted()) {
            val first = names.zipWithNext().first { (a, b) -> a > b }
            return Parse.Failure(
                "$FILE_NAME must be sorted, because ids are assigned by position: " +
                    "'${first.second}' follows '${first.first}'. Sorted order is what makes the " +
                    "id of a component a function of the set of components and nothing else.",
            )
        }
        return Parse.Success(names)
    }

    /** The list as the KSP option value. */
    public fun optionValue(components: List<String>): String =
        components.joinToString(SEPARATOR.toString())

    // --- udeaWriteNetComponents (issue #274) --------------------------------------------------

    /** The outcome of folding a build's discovered components into the reviewed [FILE_NAME]. */
    public sealed interface Merge {

        /**
         * The file to write, and the names it gains.
         *
         * @property text the whole file, ready to write.
         * @property added the discovered names the reviewed file did not already carry, sorted.
         *   These are the ids that moved: every existing name at or after the first of them is
         *   renumbered, which is why the task tells the reader rather than writing quietly.
         */
        public data class Rewrite(val text: String, val added: List<String>) : Merge

        public data class Failure(val problem: String) : Merge
    }

    /**
     * The file [WRITE_TASK] should write, given the reviewed one and what the build compiled.
     *
     * Additive on purpose. A name may legitimately be in the file and in no module's output -
     * the header of the engine's own lock says so ("Names may be listed before the component
     * exists"), and a module that failed to compile emits nothing at all - so removing a name
     * because this build did not see it would renumber the wire from a *failure*. Deleting a
     * name stays a hand edit, which is the direction where a mistake is loud.
     *
     * Comments survive. A trailing note on a name travels with it to wherever sorting puts it,
     * and the leading block is copied verbatim; a full-line comment *between* names has no
     * position that survives a sort, so it is refused rather than silently moved or dropped.
     *
     * @param existing the reviewed file's text, or `null` when there is none yet.
     * @param discovered every `@Replicated` component the build's modules reported, in any order.
     */
    public fun merge(existing: String?, discovered: List<String>): Merge {
        val malformed = discovered.filterNot(NAME_FORMAT::matches).sorted()
        if (malformed.isNotEmpty()) {
            return Merge.Failure(
                "the build reported ${malformed.size} component name(s) that are not " +
                    "fully-qualified: ${malformed.joinToString()}. That is a defect in the " +
                    "processor's manifest rather than in $FILE_NAME.",
            )
        }
        val header: List<String>
        val reviewed: Map<String, String>
        if (existing == null) {
            header = NEW_FILE_HEADER.lines()
            reviewed = emptyMap()
        } else {
            val lines = existing.replace("\r\n", "\n").lines()
            val firstName = lines.indexOfFirst { it.substringBefore('#').isNotBlank() }
            if (firstName < 0) {
                // Every line is a comment or blank: the file exists but names nothing, which
                // `parse` refuses as an id space. Rewriting it keeps whatever the author wrote
                // at the top and gives it its first names.
                header = lines.dropLastWhile(String::isBlank)
                reviewed = emptyMap()
            } else {
                val stray = lines.withIndex()
                    .filter { (index, line) -> index > firstName && line.isNotBlank() && line.trimStart().startsWith('#') }
                if (stray.isNotEmpty()) {
                    return Merge.Failure(
                        "$FILE_NAME has a comment on line ${stray.first().index + 1}, after the " +
                            "first component name. Sorting decides where every name goes, so a " +
                            "comment between two of them has no position that survives: move it " +
                            "into the block at the top of the file, or add the name by hand.",
                    )
                }
                header = lines.take(firstName).dropLastWhile(String::isBlank)
                val named = lines.drop(firstName).filter { it.substringBefore('#').isNotBlank() }
                val problem = reviewedProblem(named)
                if (problem != null) return Merge.Failure(problem)
                reviewed = named.associateBy { it.substringBefore('#').trim() }
            }
        }
        val added = discovered.toSortedSet().filterNot(reviewed::containsKey)
        val lines = (reviewed + added.associateWith { it })
            .toSortedMap()
            .values
            .toList()
        if (lines.isEmpty()) {
            // The same refusal `parse` makes, made one step earlier: writing a file that names
            // nothing would be writing the per-module numbering this file exists to replace, and
            // the next build would then fail on a file this task had just produced.
            return Merge.Failure(
                "no module of this build compiles a @Replicated component, so writing " +
                    "$FILE_NAME would write an empty id space - which is not 'no components " +
                    "yet' but the per-module numbering this file exists to replace. Add a " +
                    "component first.",
            )
        }
        return Merge.Rewrite(
            text = (header + lines).joinToString(separator = "\n", postfix = "\n"),
            added = added,
        )
    }

    /** Why the reviewed file's own name lines cannot be folded into, or `null`. */
    private fun reviewedProblem(namedLines: List<String>): String? {
        val names = namedLines.map { it.substringBefore('#').trim() }
        val malformed = names.filterNot(NAME_FORMAT::matches)
        if (malformed.isNotEmpty()) {
            return "$FILE_NAME contains ${malformed.size} entry/entries that are not " +
                "fully-qualified component names: ${malformed.joinToString()}."
        }
        val duplicates = names.groupBy { it }.filterValues { it.size > 1 }.keys.sorted()
        if (duplicates.isNotEmpty()) {
            return "$FILE_NAME lists ${duplicates.joinToString()} more than once. One component " +
                "cannot hold two component type ids, and a rewrite cannot choose which line to keep."
        }
        return null
    }

    /** The task that writes [FILE_NAME] from what the build compiled. */
    public const val WRITE_TASK: String = "udeaWriteNetComponents"

    /**
     * Where a KSP run leaves the list of `@Replicated` components it saw.
     *
     * Module-qualified for the reason `ProtocolLock.resourcePath` is: two modules contributing
     * one classpath resource path means whichever jar comes first wins, silently.
     */
    public fun manifestResourcePath(moduleName: String): String = "udea/$moduleName-$MANIFEST_NAME"

    /** The manifest's file name, which [WRITE_TASK] globs for under each project's build dir. */
    public const val MANIFEST_NAME: String = "net-components.txt"

    /** The header a brand-new [FILE_NAME] is written with. */
    private val NEW_FILE_HEADER: String =
        """
        # udea $FILE_NAME - THE COMPONENT TYPE ID SPACE.
        #
        # Every @Replicated component in this build, sorted. A component's position in this list
        # is its ComponentTypeId, and that id travels in every packet and every recorded replay -
        # so inserting a name renumbers its successors and breaks both.
        #
        # Written by `gradlew $WRITE_TASK`, which only ever adds. Review the diff: it is the wire
        # contract. Removing a name is a hand edit, deliberately.
        """.trimIndent()
}
