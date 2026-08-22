package dev.wildware.udea.diagnostics

/**
 * How badly a [UdeaDiagnostic] should be taken.
 *
 * Declaration order is significant: it is the ranking order used by [DiagnosticSink], and
 * the enum name lowercased is the wire form written by [DiagnosticsJson]. Neither the
 * order nor the names may change without breaking the `diagnostics.json` contract.
 */
public enum class Severity {
    /** The build must fail. */
    Error,

    /** The build succeeds, but something is wrong enough to say out loud. */
    Warning,

    /** Purely informational; never fails a build. */
    Info,
    ;

    /** The stable lowercase wire form used in `diagnostics.json`. */
    public val wireName: String = name.lowercase()
}
