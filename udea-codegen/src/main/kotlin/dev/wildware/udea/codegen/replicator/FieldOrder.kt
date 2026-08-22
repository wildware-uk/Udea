package dev.wildware.udea.codegen.replicator

/**
 * **The single source of bit indices.**
 *
 * A field's bit index is its position in the component's **lowered field names sorted
 * ascending**, using the ordinary lexicographic ordering of the name's UTF-16 code units.
 * `@Net` and `@Sim` properties share one index space: `fieldNames[i]`, `FieldMask` bit `i`
 * and `FieldStore` field `i` are all the same `i`, so a `@Sim` field cannot be "skipped"
 * when assigning network bits without breaking that alignment.
 *
 * The name sorted on is the *lowered* one, so a composite property contributes one name per
 * component (`position.x`, `position.y`). That needs no special case here and none in the
 * ordering: `.` is code unit 46, below every character a Kotlin identifier may contain, so
 * a property's components always sort adjacent and immediately before any longer property
 * name that shares its prefix.
 *
 * Nothing else in the engine may assign a bit index. `netMask` and `allMask` are built by
 * *selecting* from this order, never by re-ordering it, which is why moving a property in the
 * source file cannot change the wire format, and renaming one always does.
 *
 * The rule is a pure function of the names alone so it can be tested without a compiler.
 */
internal object FieldOrder {

    /**
     * Returns [fields] in bit-index order: the order in which indices `0, 1, 2, …` are handed
     * out.
     *
     * @param name the property name of an element; the only thing the ordering depends on.
     */
    fun <T> assign(fields: List<T>, name: (T) -> String): List<T> =
        fields.sortedWith(compareBy(String::compareTo, name))

    /**
     * The `FIELD_…` constant name generated for a field, e.g. `lastGroundedTick` becomes
     * `FIELD_LAST_GROUNDED_TICK` and the lowered `position.x` becomes `FIELD_POSITION_X`.
     *
     * Two different field names can screaming-snake to the same constant (`fooBar` and
     * `foo_bar`, or `position.x` and `positionX`), so [constantNames] — not this function —
     * is what generated code uses.
     */
    fun constantName(fieldName: String): String {
        val out = StringBuilder("FIELD_")
        var previousWasLower = false
        for (ch in fieldName) {
            when {
                ch == '_' || ch == '`' || ch == '.' -> {
                    if (out.length > "FIELD_".length && out.last() != '_') out.append('_')
                    previousWasLower = false
                }

                ch.isUpperCase() -> {
                    if (previousWasLower) out.append('_')
                    out.append(ch)
                    previousWasLower = false
                }

                else -> {
                    out.append(ch.uppercaseChar())
                    previousWasLower = ch.isLowerCase() || ch.isDigit()
                }
            }
        }
        return out.toString()
    }

    /**
     * Constant names for [fieldNames], in the same order, made unique.
     *
     * A collision is resolved by appending the field's index, because a generated file with two
     * `const val FIELD_FOO_BAR` does not compile and a silent rename would be worse.
     *
     * Uniquification is **closed**: a disambiguated name is itself checked against everything
     * already handed out, and bumped again if it collides. Without that the routine is not a
     * uniquifier at all — `fooBar`, `foo_bar` and `foo_bar_0` produce `FIELD_FOO_BAR_0` twice
     * (the first from disambiguating the pair, the second because `foo_bar_0` screams to it and
     * looked unique on its own), and the generated object does not compile.
     */
    fun constantNames(fieldNames: List<String>): List<String> {
        val raw = fieldNames.map(::constantName)
        val occurrences = raw.groupingBy { it }.eachCount()
        val taken = HashSet<String>(raw.size)
        return raw.mapIndexed { index, candidate ->
            var name = if (occurrences.getValue(candidate) == 1) candidate else "${candidate}_$index"
            var suffix = index
            while (!taken.add(name)) {
                suffix++
                name = "${candidate}_$suffix"
            }
            name
        }
    }
}
