package dev.wildware.udea.compiler.kdoc

/**
 * Rewrites `[Foo]` KDoc links to fully qualified names.
 *
 * A generated DSL builder lands in a different package from the type it was generated for, so
 * a link the author wrote as `[Ability]` resolves in the source file and dangles in the
 * generated one. Issue #42 requires qualifying it at harvest time, which is the only point
 * where the source file's imports are still in view.
 *
 * Only a bare simple name is rewritten. A link that is already qualified is left alone, a
 * markdown link (`[text](url)`) is left alone because its brackets are not a KDoc reference at
 * all, and a name the resolver does not know is left as the author wrote it rather than being
 * guessed at.
 */
internal object KDocLinks {

    /**
     * A simple-name reference: `[Foo]`, but not `[a.Foo]`, not `[Foo]( ... )`.
     *
     * The trailing `(?!\()` is what separates a KDoc reference from a markdown link.
     */
    private val SIMPLE_LINK = Regex("""\[([A-Za-z_][A-Za-z0-9_]*)](?!\()""")

    /**
     * @param text KDoc prose.
     * @param resolve the fully qualified name for a simple name, or `null` if it is unknown.
     */
    fun qualify(text: String, resolve: (String) -> String?): String =
        SIMPLE_LINK.replace(text) { match ->
            val simpleName = match.groupValues[1]
            val qualified = resolve(simpleName)
            if (qualified == null) match.value else "[$qualified]"
        }

    /** [qualify] applied to every string in [block]. */
    fun qualify(block: KDocBlock, resolve: (String) -> String?): KDocBlock = KDocBlock(
        summary = qualify(block.summary, resolve),
        params = block.params.map { KDocParam(it.name, qualify(it.text, resolve)) },
        tags = block.tags.map { KDocTag(it.tag, qualify(it.text, resolve)) },
    )
}
