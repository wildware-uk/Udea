# Budgets

The single budgets document for this repository. Later epics **write their numbers into this
file** rather than creating a second one, and add their gate as a job or a matrix leg in
`.github/workflows/ci.yml` rather than a second workflow.

A budget here is a CI gate, not an aspiration. A number nobody fails a build over is a note,
and notes drift.

## Clean build — enforced

| | |
|---|---|
| **Budget** | 90 000 ms (spec §6, Phase 0 exit: "clean build <90s") |
| **Gate** | `clean-build-budget` job in `.github/workflows/ci.yml` |
| **Command** | `./gradlew clean udeaAssemble --no-build-cache` |
| **Runner** | `ubuntu-latest`, Temurin JDK 17, daemon warmed with `./gradlew help` first |
| **Threshold source** | `UDEA_CLEAN_BUILD_BUDGET_MS`, defaulting to `90000` |
| **Measured** | 26 248 ms – 31 705 ms |
| **Measured on** | developer workstation, Windows 11, 32 logical cores, Corretto 17.0.8, Gradle 8.13 |

### What is measured, and what is not

`udeaAssemble` assembles every `:udea-*` project and `:moba`, and nothing else —
`./gradlew udeaAssemble --dry-run` lists no old-module task. Budgeting plain `assemble` would
measure `idea-plugin` resolving a whole IntelliJ distribution and `compose-ui` behind it; that
number is real but it is not a number the rewrite can move, and a budget nobody can act on is
a budget nobody looks at.

The configuration cache stays **on**. It is on in `gradle.properties`, so it is the
configuration this project actually runs; turning it off for the measurement would produce a
faithful number about a build nobody performs. A fresh CI runner has no cache entry, so the
gated run is a genuine cold build, whereas the workstation figures above are with an entry
already stored — expect the first CI number to be the higher one, and treat the two as
different measurements rather than a regression.

`--no-build-cache` is there for the opposite reason: the build cache would let the gate pass
by not compiling anything, which is exactly the thing the gate is supposed to notice.

### Known drag

None outstanding. `ksp.incremental=false` used to sit in `gradle.properties` and make
`udea-codegen`'s KSP pass do full work on every build; it is now `true`, and the section
below is the gate that keeps it honest.

## KSP incremental processing — enforced

| | |
|---|---|
| **Owner** | codegen epic (issue #35). Owns `ksp.incremental`, the isolating/aggregating split and this threshold — not the 90s gate above. |
| **Gate** | `ksp-incremental-budget` job in `.github/workflows/ci.yml` |
| **Hard gate** | a second identical `./gradlew :udea-codegen:testClasses` must execute **no** `:udea-codegen:` task |
| **Threshold** | 20% over the committed baseline, via `UDEA_KSP_COLD_BUDGET_MS` and `UDEA_KSP_EDIT_BUDGET_MS` |
| **Measured on** | developer workstation, Windows 11, 32 logical cores, Corretto 17.0.8, Gradle 8.13, other builds running concurrently |

| Scenario | Command | Measured |
|---|---|---|
| Cold codegen pass | `:udea-codegen:clean` then `:udea-codegen:testClasses --no-build-cache` | 34 285 ms |
| No-op | `:udea-codegen:testClasses` again | 5 124 ms, every task `UP-TO-DATE` |
| One-component edit | touch a fixture component, then `:udea-codegen:testClasses` | 10 767 ms |
| One `src/main` edit to runnable | touch `FieldIo.kt`, then `:udea-codegen:testClasses` | 8 317 ms |

The committed CI thresholds are **provisional**: they were measured on a workstation, and the
job runs on `ubuntu-latest`, so the first green run's numbers — which the job writes to the
step summary every time, pass or fail — should replace them. They are deliberately loose
rather than tight-and-wrong; the gate that carries the real weight is the hard one.

### Why the hard gate is the one that matters

A timing threshold cannot see the regression this is guarding against. An output whose *name*
varies between runs — `UdeaSerializerRegistry_${System.currentTimeMillis()}`, which is what the
generator being retired emitted — is never recognised as up to date, so every build is a full
rebuild. The first build is exactly as fast either way; only the second one tells you, and
only by re-executing. So the gate asserts task states, not milliseconds.

The other half is the isolating/aggregating split. `udea-codegen` writes one **isolating**
file per `@Replicated` component (invalidated only by an edit to that component's own source)
and exactly one **aggregating** group per module — the `…NetProtocol` constant, the
`ServiceLoader` index and `net-protocol.lock`, which genuinely do depend on every component,
because adding one renumbers the ids of all its successors. One aggregating output per module
is fine; one per component would make every keystroke a full module reprocess.
`IncrementalProcessingTest` audits both halves, because nothing else in the repository would
notice either being got wrong again.

## Budgets owned by later epics

These are placeholders. The epic that lands the capability lands the gate, records the
measured number here, and adds the job to `.github/workflows/ci.yml`.

| Budget | Target | Spec | Gate | Measured |
|---|---|---|---|---|
| Asset edit-to-observe | < 3 s | §6 (Phase 3 exit) | *(assets epic)* | *(not yet measured)* |
| Agent edit-to-observe | < 12 s | §6 (Phase 5 exit) | *(agent epic)* | *(not yet measured)* |
| Snapshot capture, 1 000 entities | < 1 ms | §6 (Phase 7 exit) | *(determinism epic)* | *(not yet measured)* |
| Snapshot digest, 500 entities | < 0.3 ms | §6 (Phase 7 exit) | *(determinism epic)* | *(not yet measured)* |

## How to re-measure the clean build

```bash
./gradlew help                                   # warm the daemon; the budget is about compiling, not about JVM startup
./gradlew clean udeaAssemble --no-build-cache    # time this
```

On CI the same two commands run in the `clean-build-budget` job, which writes the measured
milliseconds to the job summary on every run — pass or fail — so the trend is readable without
opening a log. To prove the gate can still fail, dispatch the workflow manually with
`clean_build_budget_ms` set to `1`.
