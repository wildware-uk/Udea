package dev.wildware.udea.assets.compiler.edit

import com.squareup.kotlinpoet.CodeBlock

/**
 * The kinds of plain literal the editor can save into an asset script (issue #195): numbers,
 * strings, booleans and `reference("...")`.
 *
 * A field's type is the type of the literal already written there, and a new value has to parse as
 * that type. The file is the only thing that knows what the DSL parameter wants, and it said so the
 * moment someone wrote `1.58F` rather than `1.58`.
 */
public enum class LiteralType {
    Int,
    Long,
    Float,
    Double,
    String,
    Boolean,
    Reference,
}

/**
 * One plain literal value, and the Kotlin that writes it.
 *
 * The Kotlin is a KotlinPoet [CodeBlock], never an assembled string: `%S` is what escapes a quote
 * or a `$` in a string the editor was typed, and `%L` is what renders a number the way Kotlin reads
 * it back.
 */
public sealed class LiteralValue {

    /** Which kind of literal this is. */
    public abstract val type: LiteralType

    /** The expression that writes this value in a script: `1.58F`, `"a"`, `reference("x/y")`. */
    internal abstract fun expression(): CodeBlock

    /**
     * What replaces the value's own text in a file that already has it.
     *
     * The same as [expression] except for a reference, where it is only the id's string literal:
     * `reference(` and its closing bracket are the file's, spelt however the file spelt them.
     */
    internal open fun replacement(): CodeBlock = expression()

    /** The value as the editor shows it and takes it back: `1.58`, `a`, `x/y`. */
    public abstract val display: kotlin.String

    internal data class IntValue(val value: kotlin.Int) : LiteralValue() {
        override val type: LiteralType get() = LiteralType.Int
        override fun expression(): CodeBlock = CodeBlock.of("%L", value)
        override val display: kotlin.String get() = value.toString()
    }

    internal data class LongValue(val value: kotlin.Long) : LiteralValue() {
        override val type: LiteralType get() = LiteralType.Long
        override fun expression(): CodeBlock = CodeBlock.of("%LL", value)
        override val display: kotlin.String get() = value.toString()
    }

    internal data class FloatValue(val value: kotlin.Float) : LiteralValue() {
        override val type: LiteralType get() = LiteralType.Float
        override fun expression(): CodeBlock = CodeBlock.of("%LF", display)

        /**
         * `120` rather than `120.0`: the `F` suffix already makes a whole number a float literal,
         * and `health = 120F` is how the asset scripts write one, so a save of 120 into `100F`
         * changes the digits and nothing else.
         */
        override val display: kotlin.String get() = value.toString().removeSuffix(".0")
    }

    internal data class DoubleValue(val value: kotlin.Double) : LiteralValue() {
        override val type: LiteralType get() = LiteralType.Double
        override fun expression(): CodeBlock = CodeBlock.of("%L", value)
        override val display: kotlin.String get() = value.toString()
    }

    internal data class StringValue(val value: kotlin.String) : LiteralValue() {
        override val type: LiteralType get() = LiteralType.String
        override fun expression(): CodeBlock = CodeBlock.of("%S", value)
        override val display: kotlin.String get() = value
    }

    internal data class BooleanValue(val value: kotlin.Boolean) : LiteralValue() {
        override val type: LiteralType get() = LiteralType.Boolean
        override fun expression(): CodeBlock = CodeBlock.of("%L", value)
        override val display: kotlin.String get() = value.toString()
    }

    /** `reference("...")`: the id of another asset. */
    internal data class ReferenceValue(val id: kotlin.String) : LiteralValue() {
        override val type: LiteralType get() = LiteralType.Reference
        override fun expression(): CodeBlock = CodeBlock.of("reference(%S)", id)
        override fun replacement(): CodeBlock = CodeBlock.of("%S", id)
        override val display: kotlin.String get() = id
    }

    internal companion object {

        /**
         * [input], as the editor's text field holds it, read as a value of [type]; `null` when it is
         * not one.
         *
         * A string is taken exactly as typed - leading spaces and all, since they are part of the
         * value. Everything else is trimmed, and a number may carry the suffix its type is written
         * with (`2.5F`, `9L`), because that is what a person copying a value out of a script types.
         * A float or double that is not finite is refused: Kotlin has no literal for it.
         */
        fun parse(type: LiteralType, input: kotlin.String): LiteralValue? {
            val text = input.trim()
            return when (type) {
                LiteralType.Int -> text.toIntOrNull()?.let(::IntValue)
                LiteralType.Long -> text.removeSuffix("L").toLongOrNull()?.let(::LongValue)
                LiteralType.Float -> text.removeSuffix("F").removeSuffix("f").toFloatOrNull()
                    ?.takeIf { it.isFinite() }?.let(::FloatValue)
                LiteralType.Double -> text.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::DoubleValue)
                LiteralType.Boolean -> text.toBooleanStrictOrNull()?.let(::BooleanValue)
                LiteralType.String -> StringValue(input)
                LiteralType.Reference -> text.takeIf { it.isNotEmpty() }?.let(::ReferenceValue)
            }
        }
    }
}
