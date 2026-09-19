package dev.wildware.udea.annotations

/**
 * Marks a function of the `.udea.kts` asset DSL whose lambda parameters each run once.
 *
 * Asset scripts may not contain loops (issue #192, rule `UDEA0015`): the editor saves an exact
 * value back to the line it came from, and a value made inside a loop has no single line. The
 * **`udea-compiler-plugin` K2 FIR checker** that enforces this in an asset script refuses a
 * lambda handed to any callee that might run it more than once. A callee is trusted with a
 * lambda for one of two reasons: it declares a Kotlin contract
 * `callsInPlace(block, EXACTLY_ONCE | AT_MOST_ONCE)`, as `let` and `apply` do, or it carries
 * this marker.
 *
 * The marker is a promise the function's author makes, which is why it is a separate annotation
 * rather than "anything in the asset compiler's package": putting it on a function that runs a
 * lambda twice would let a loop through, so each use should be read as that claim.
 *
 * Retention is [AnnotationRetention.BINARY]: the FIR checker reads it from the compiled DSL,
 * and nothing reads it at runtime.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class AssetDsl
