package dev.wildware.udea.compiler.fir

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.types.ConeErrorType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.name.StandardClassIds

/**
 * The narrow slice of `udea-codegen`'s field model this plugin is willing to reason about.
 *
 * ### Why it is narrow on purpose
 *
 * `udea-codegen` owns field lowering, and that set is growing: a `@Net` property may be a
 * primitive, an enum, `NetId`, `Tick`, or a value type whose `var` properties are lowered to
 * one field each (`position` becomes `position.x` and `position.y`). A FIR checker that
 * reproduced that table would drift out of step with the generator on the next type it
 * learns, and every drift is a **false positive** - a red error on code that builds fine.
 * Issue #37's `assertCompilesClean` cases exist precisely to pin that down.
 *
 * So the plugin decides only what it can decide from the type alone and hands everything
 * else to KSP, which raises the *same rule ids* a task boundary later. A missing in-editor
 * diagnostic costs a developer one build; a false one costs them their trust in the checkers.
 */
internal object UdeaFieldTypes {

    /** The types `udea-codegen` stores as one field with no lowering step. */
    private val DIRECTLY_STORED = setOf(
        StandardClassIds.Boolean,
        StandardClassIds.Int,
        StandardClassIds.Long,
        StandardClassIds.Float,
    )

    /**
     * True when this type occupies exactly one field and is written back by assignment.
     *
     * This is the precondition for the `val` rules: a composite is restored by mutating its
     * components in place, so `@Net val position: Vector2` is legal, and only a *directly
     * stored* `val` is the defect `UdeaRules.NET_ON_VAL` describes.
     */
    fun isDirectlyStored(type: ConeKotlinType, session: FirSession): Boolean {
        if (type.isMarkedNullable) return false
        if (type.classId in DIRECTLY_STORED) return true
        // An enum is stored and sent as its ordinal, which is why it needs no field codec.
        return type.toRegularClassSymbol(session)?.classKind == ClassKind.ENUM_CLASS
    }

    /** True for `kotlin.Float`, the only type `@Q` is defined for. */
    fun isFloat(type: ConeKotlinType): Boolean =
        !type.isMarkedNullable && type.classId == StandardClassIds.Float

    /**
     * True when the compiler could not resolve the type at all.
     *
     * An unresolved type already has a diagnostic of its own on it; adding a Udea rule id on
     * top would report a replication defect for what is really a missing import.
     */
    fun isUnresolved(type: ConeKotlinType): Boolean = type is ConeErrorType

    /**
     * The type as a developer wrote it - `kotlin.Float`, `kotlin.String?`.
     *
     * The same spelling `udea-codegen`'s messages use, so the two producers of one rule id
     * differ only where they genuinely say different things.
     */
    fun describe(type: ConeKotlinType): String {
        val base = type.classId?.asFqNameString() ?: type.toString()
        return if (type.isMarkedNullable) "$base?" else base
    }
}
