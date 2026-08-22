package dev.wildware.udea.annotations

/**
 * Marks a property as **snapshotted but never replicated** - the second of the two masks
 * of spec 3.1. `@Sim` lands in the generated `FieldStore` under `ALL_MASK` only, so
 * snapshot capture and restore see it and `writeDelta` cannot: jungle respawn timers and
 * bot blackboards must rewind but must never reach a client.
 *
 * Consumed by the **`udea-codegen` KSP2 processor**, which emits the field's capture and
 * restore pair inside `Replicator<T>` and deliberately assigns it no network bit index.
 * The **`udea-compiler-plugin` K2 FIR checkers** reject `@Sim` on a `val` and reject a
 * property carrying both `@Sim` and [Net].
 *
 * Retention is [AnnotationRetention.BINARY] for the same reason as [Net]: the mask is
 * resolved into generated code at build time and nothing reads the annotation at runtime.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Sim
