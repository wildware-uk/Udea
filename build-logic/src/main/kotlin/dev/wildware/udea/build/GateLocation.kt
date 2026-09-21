package dev.wildware.udea.build

/**
 * [path] as a gate prints a filesystem location: forward slashes, whatever platform found it.
 *
 * A gate's message is read by a person, quoted in a report, and asserted by a test, and the
 * separator is the one part of it that changes with the machine that ran the build. Left alone it
 * makes every such assertion a claim about its author's operating system: `VerifyEditorAbsentTest`
 * asked that `UDEA-MG-012`'s failure "say where the class was" by looking for `moba/editor` in it,
 * which is true on Linux and false on Windows, where the same correct gate says `moba\editor`.
 * That one substring turned every CI job that runs `build-logic`'s own tests on `windows-latest`
 * red, while the gate under test was working perfectly.
 *
 * This is not a new convention; it is the one the rest of this package already keeps. For
 * instance, `ContractFreeze.digestsOf` forward-slashes the keys it freezes and
 * `DeterminismScan.span` forward-slashes the source location it reports a finding at, and the
 * frozen diagnostics contract asks the same of every `SourceSpan`. `UDEA-MG-012` and
 * `UDEA-REL-001` are the release gates that had not been told.
 *
 * It takes a `String` rather than a `File` deliberately. `File.invariantSeparatorsPath` rewrites
 * only the separator of the platform it is running on, so on Linux it returns a Windows path
 * unchanged - which would make this function untestable on the machine this repository is
 * developed on, and untestable is how the defect above survived. A `String` can carry either
 * spelling on either platform, so [GateLocationTest] feeds it both.
 *
 * The cost is that a directory whose name genuinely contains a backslash - legal on Linux, and
 * findable nowhere in this repository or on any classpath it builds - would be printed with a
 * slash instead. That is a cosmetic error in a failure message, against a real one on every
 * Windows build, and `ContractFreeze` already took the same trade.
 */
internal fun gateLocation(path: String): String = path.replace('\\', '/')
