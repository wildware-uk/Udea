package dev.wildware.udea.compiler.kdoc

/**
 * One source declaration's documentation, reduced to the parts a generated member can carry.
 *
 * Spec 3.2 assigns KDoc propagation to K2 because "KSP cannot read or re-emit KDoc": a KSP
 * processor sees a resolved symbol, and a doc comment is not part of one. The compiler
 * plugin sees the source, harvests this, and `udea-codegen` reads it back out of the index
 * when it calls KotlinPoet's `addKdoc`.
 *
 * Anything not modelled here is **dropped**, deliberately. An unrecognised tag re-emitted
 * into a generated file is a KDoc the generated file cannot resolve - a `@sample` pointing
 * at a function that is not on the generated file's classpath is a broken build for a
 * comment - so [KDocParser] keeps only the four tags whose text is self-contained.
 */
internal data class KDocBlock(
    /** The prose before the first tag, with `*` margins stripped and lines joined by `\n`. */
    val summary: String,
    /** `@param` tags, in source order. Order is part of the index's determinism. */
    val params: List<KDocParam>,
    /** `@return`, `@see` and `@throws`, in source order. */
    val tags: List<KDocTag>,
) {
    /** True when the declaration had a doc comment that carried nothing worth re-emitting. */
    val isEmpty: Boolean
        get() = summary.isEmpty() && params.isEmpty() && tags.isEmpty()
}

/**
 * One `@param` tag.
 *
 * @param name the parameter or property name the tag documents. This is the second half of
 *   the identity issue #42 requires: an entry is keyed by declaration FQN *plus* parameter
 *   name, because a generated builder turns one source parameter into one DSL member.
 * @param text the tag's prose, verbatim apart from margin stripping and link qualification.
 */
internal data class KDocParam(val name: String, val text: String)

/**
 * A `@return`, `@see` or `@throws` tag.
 *
 * @param tag the tag name without its `@`, one of [KDocParser.PASS_THROUGH_TAGS].
 * @param text the tag's prose, verbatim apart from margin stripping and link qualification.
 */
internal data class KDocTag(val tag: String, val text: String)
