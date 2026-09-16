package dev.wildware.udea.annotations

/**
 * Marks a Fleks component as one the engine generates a `Replicator<T>` for.
 *
 * Consumed by the **`udea-codegen` KSP2 processor**, which emits one `Replicator<T>`
 * per annotated class (network delta write, network full write, snapshot capture,
 * snapshot restore, agent field read/write - spec 3.1) plus the `NetModule`
 * ServiceLoader registry entry that makes the replicator discoverable across modules.
 * The **`udea-compiler-plugin` K2 FIR checkers** also key off this marker: a [Net] or
 * [Sim] property on a class that is not `@Replicated` is a diagnostic.
 *
 * Retention is [AnnotationRetention.BINARY]: nothing reads this at runtime. Component
 * metadata reaches the runtime as generated code, never reflection, so the marker only
 * has to survive as far as KSP and the compiler plugin - and staying out of the
 * runtime-visible annotation table keeps it invisible to R8 (spec 3.1).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Replicated
