# udea-compiler-plugin

The K2/FIR compiler plugin: `@Net`/`@Sim`/`@Q` checkers, KDoc harvesting, and the kill switch
that keeps neither of them load-bearing.

The design, the merge rule for a new checker, the degrade procedure and the declaration-
synthesis verdict all live in **[`docs/compiler-plugin.md`](../docs/compiler-plugin.md)**. This
file is the operational bit.

## Layout

| Package | What is in it |
|---|---|
| `dev.wildware.udea.compiler` | `UdeaCompilerPluginRegistrar`, `UdeaCommandLineProcessor`, the option model |
| `…compiler.fir` | the FIR checkers, their diagnostic factories, and the KDoc harvest extension |
| `…compiler.kdoc` | the pure half of the harvester: scanner, parser, link qualifier, index encoder |
| `…compiler.testing` (test) | the compile-testing harness — `UdeaCheckerTest`, `UdeaCompileTesting`, inline `// expect:` markers |

## Commands

```bash
./gradlew :udea-compiler-plugin:test                        # the compile-testing suite
./gradlew :udea-compiler-plugin:udeaVerifyPluginOptional    # spec 7's load-bearing guard
./gradlew :udea-compiler-plugin:check                       # both, plus udeaVerifyKotlinPin
```

`check` is the gate a Kotlin upgrade has to pass (spec §3.2).

## Suite runtime

Measured 2026-08-22 on the development machine (Windows 11, JDK 17, warm Gradle daemon):

| Measure | Command | Value |
|---|---|---|
| Cold suite, forced rerun | `./gradlew :udea-compiler-plugin:test --rerun-tasks` | **26.5 s** wall |
| Test execution only | (sum of JUnit class times from the same run) | **11.9 s** over 92 tests |

Most of the wall time is the `jar` and `compileTestKotlin` tasks the suite depends on. Most of
the *test* time is real compilation: 14 of the 92 tests run an in-process `K2JVMCompiler`, at
roughly 0.2 s each once the compiler classes are warm; the pure unit tests over the KDoc
parser, scanner, link qualifier and index encoder are collectively under 0.05 s.

That budget is why the KDoc harvester's decisions are pure functions tested directly rather
than through a compilation, and it is the number to watch when adding a checker: a new rule
should cost two or three compilations, not twenty.

## Adding a checker

1. Add the rule to `UdeaRules` in `udea-diagnostics` **first**, if it does not already exist.
   Never mint an id here — an id minted locally is not shared with `udea-codegen` or the asset
   validator, which is the entire point of spec §5's shared id space.
2. Add a factory to `UdeaDiagnostics` and an entry in its `factories` map; `UdeaRuleParityTest`
   fails if you forget.
3. Write the checker, reporting through `UdeaDiagnostics.report(rule, source, detail)` so the
   message carries the id in the same shape KSP prints it.
4. Test it with all three of a positive case, a negative case and a position assertion. See
   `docs/compiler-plugin.md` for why all three, and `UdeaReplicatedPropertyCheckerTest` for the
   shape.
