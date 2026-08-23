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
}
