package dev.wildware.moba.level

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * Compares two object graphs field by field, reading the JVM fields themselves.
 *
 * ## Why the level proof needs something this blunt
 *
 * Every other comparison in [LevelSaveLoadProofTest] goes through a description of a component:
 * `WorldHasher` reads the fields a `Replicator` lowers, and a second save reads the fields the
 * serializer writes. A field the serializer does not write is invisible to both - it is missing
 * from the first file, defaulted in the loaded world, and missing from the second file too, so the
 * two files agree byte for byte about a world that lost it. Measured: `@Transient` on
 * `GameUnit.movingTick` left all three of those comparisons green. Only a comparison that does
 * not ask the serializer what a component contains can see it.
 *
 * It is a test helper and runs once per proof, so reflection is not on any per-tick path.
 *
 * ## What counts as equal
 *
 * - primitives, boxed primitives, strings and enums by value;
 * - arrays and lists element by element, over their whole length;
 * - any other object by recursing into every instance field its class and superclasses declare,
 *   `transient` and private ones included, with an identity map so a shared or cyclic reference
 *   is walked once.
 *
 * A class named in `liveLength` keeps a count of the slots in use, and the arrays it holds are
 * compared up to that count only - the slots past it are what a swap-remove left behind, which no
 * reader looks at and a level does not store. The count itself is still compared like any field.
 *
 * Classes in `java.*` and `kotlin.*` are compared with `equals`, because their fields cannot be
 * opened from here and the ones a component holds (collections of values) define it.
 */
internal class FieldByField(private val liveLength: Map<String, String> = emptyMap()) {

    private val seen = IdentityHashMap<Any, Any>()

    /** Every difference between [left] and [right], each named by its path from [path]. */
    fun differences(path: String, left: Any?, right: Any?): List<String> {
        val out = ArrayList<String>()
        compare(path, left, right, out)
        return out
    }

    private fun compare(path: String, left: Any?, right: Any?, out: MutableList<String>) {
        if (left == null || right == null) {
            if (left !== right) out += "$path: $left != $right"
            return
        }
        val type = left.javaClass
        if (type != right.javaClass) {
            out += "$path: ${type.name} != ${right.javaClass.name}"
            return
        }
        when {
            type.isArray -> compareArrays(path, left, right, out)
            left is List<*> -> compareLists(path, left, right as List<*>, out)
            isValue(type) -> if (left != right) out += "$path: $left != $right"
            else -> {
                if (seen.put(left, right) != null) return
                val fields = instanceFields(type)
                val live = liveLength[type.simpleName]?.let { counter -> fields.single { it.name == counter }.getInt(left) }
                for (field in fields) {
                    if (live != null && field.type.isArray) {
                        comparePrefix("$path.${field.name}", field.get(left), field.get(right), live, out)
                    } else {
                        compare("$path.${field.name}", field.get(left), field.get(right), out)
                    }
                }
            }
        }
    }

    private fun compareArrays(path: String, left: Any, right: Any, out: MutableList<String>) {
        val length = java.lang.reflect.Array.getLength(left)
        if (length != java.lang.reflect.Array.getLength(right)) {
            out += "$path: length $length != ${java.lang.reflect.Array.getLength(right)}"
            return
        }
        for (index in 0 until length) {
            compare("$path[$index]", java.lang.reflect.Array.get(left, index), java.lang.reflect.Array.get(right, index), out)
        }
    }

    private fun comparePrefix(path: String, left: Any?, right: Any?, live: Int, out: MutableList<String>) {
        if (left == null || right == null) return compare(path, left, right, out)
        val shortest = minOf(java.lang.reflect.Array.getLength(left), java.lang.reflect.Array.getLength(right))
        if (shortest < live) {
            out += "$path: holds $shortest slots and $live are live"
            return
        }
        for (index in 0 until live) {
            compare("$path[$index]", java.lang.reflect.Array.get(left, index), java.lang.reflect.Array.get(right, index), out)
        }
    }

    private fun compareLists(path: String, left: List<*>, right: List<*>, out: MutableList<String>) {
        if (left.size != right.size) {
            out += "$path: size ${left.size} != ${right.size}"
            return
        }
        for (index in left.indices) compare("$path[$index]", left[index], right[index], out)
    }

    private fun isValue(type: Class<*>): Boolean =
        type.isPrimitive || type.isEnum || type.name.startsWith("java.") || type.name.startsWith("kotlin.")

    private fun instanceFields(type: Class<*>): List<Field> =
        generateSequence(type) { it.superclass }
            .takeWhile { !isValue(it) }
            .flatMap { it.declaredFields.asSequence() }
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .onEach { it.isAccessible = true }
            .toList()
}
