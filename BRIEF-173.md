# BRIEF-173 — the FIR-checker gate has never run

**SHA: 6fe116f**

*(the whole change, and this brief. A brief cannot name the commit that contains it, so there is
exactly one commit on top of `6fe116f` — the branch head — and it edits nothing but this line.
`git diff 6fe116f HEAD` is that one-line diff.)*

Branch `issue-173-checker-probe-classpath`, off `origin/example` at `7942823`.
Commits: `90e44e3`, `e2e708d`, `536df6f`, `252f0df`, `6fe116f`, then the SHA line.

---

## 1. The evidence command

```
bash scripts/run-checkers-fire.sh
```

`bash`, not `sh`: the step it runs is a `shell: bash` step and uses `pipefail`, which dash has not
got. Nothing else is needed. It sets `JAVA_HOME` to the sdkman 21.0.11 itself — this box exports
`JAVA_HOME=~/.sdkman/candidates/java/current`, which is Temurin 25, and Gradle 8.13's entire answer
to a JDK 25 launcher is the one-line message `25.0.2`, which arrives as an empty diagnostic list
and reads exactly like the checkers not firing. It also does the job's own `chmod +x ./gradlew`
and puts the mode back on exit, so it leaves no `M gradlew` behind.

**It is not a transcription of the gate.** It parses `.github/workflows/ci.yml`, finds the job
`checkers-fire` and the step named *"A broken component fails a real build, and compiles clean
without the plugin"*, and executes that step's `run:` script verbatim. A second copy of a gate is a
second implementation of it, and two implementations disagree eventually — which is the same
species of defect as this ticket. A renamed or reordered step is a loud failure in the extractor
rather than an empty script that exits 0.

### It passes on this branch

```
$ bash scripts/run-checkers-fire.sh
running the 'A broken component fails a real build, and compiles clean without the plugin' step of job 'checkers-fire' from /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a46c9497c28044fbe/.github/workflows/ci.yml
JAVA_HOME=/home/shaun/.sdkman/candidates/java/21.0.11-tem

expecting UDEA0001 at 9:14, UDEA0003 at 10:42
the compilation reported, and every line of it is a UDEA rule:
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a46c9497c28044fbe/udea-assets/src/main/kotlin/dev/wildware/udea/assets/CheckerProbe.kt:9:14 UDEA0001: @Net annotates the val dev.wildware.udea.assets.CheckerProbe.health. A val can never change, so it can never replicate, and Replicator.apply could not restore it. Make it a var or drop the annotation.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a46c9497c28044fbe/udea-assets/src/main/kotlin/dev/wildware/udea/assets/CheckerProbe.kt:10:42 UDEA0003: @Q annotates dev.wildware.udea.assets.CheckerProbe.slots, which is kotlin.Int, not Float. Quantization is only defined for floats.
-Pudea.compilerPlugin.enabled=false, the same file, :udea-assets:compileKotlin:
BUILD SUCCESSFUL in 1s
### The FIR checkers fail a real build

| Rule | Symbol | Reported at |
| --- | --- | --- |
| UDEA0001 | CheckerProbe.health | 9:14 |
| UDEA0003 | CheckerProbe.slots | 10:42 |

Module: `:udea-assets`, main source set. Every diagnostic the compilation
produced carried a UDEA rule id, so nothing here is a compile that failed for
some other reason. The same file compiles clean under
`-Pudea.compilerPlugin.enabled=false`.

--- $GITHUB_STEP_SUMMARY ---
### The FIR checkers fail a real build

| Rule | Symbol | Reported at |
| --- | --- | --- |
| UDEA0001 | CheckerProbe.health | 9:14 |
| UDEA0003 | CheckerProbe.slots | 10:42 |

Module: `:udea-assets`, main source set. Every diagnostic the compilation
produced carried a UDEA rule id, so nothing here is a compile that failed for
some other reason. The same file compiles clean under
`-Pudea.compilerPlugin.enabled=false`.
--- end ---
step exit=0
```

Whole file, no elisions: `/tmp/claude-1000/-srv-ssd1-workspace-Udea/a3ee2737-1b26-4f77-96b3-6805f45c796f/scratchpad/173/green-run.log`.

### Proof it goes red when the fix is reverted

The literal edit — put the probe back where it was:

```
diff --git a/.github/workflows/ci.yml b/.github/workflows/ci.yml
index ec91d59..de72e7f 100644
--- a/.github/workflows/ci.yml
+++ b/.github/workflows/ci.yml
@@ -552,8 +552,8 @@ jobs:
           set -euo pipefail
           # The module, once. `probe` and the Gradle task below are both derived from it, so
           # the file this step writes and the compilation it inspects cannot come apart.
-          module=udea-assets
-          probe=udea-assets/src/main/kotlin/dev/wildware/udea/assets/CheckerProbe.kt
+          module=udea-gradle
+          probe=udea-gradle/src/main/kotlin/dev/wildware/udea/gradle/CheckerProbe.kt
           logs=$(mktemp -d)
           # Deleted whatever happens, so a failing run cannot leave a source that will not
           # compile behind for a later step or for an artifact upload. The compiler output goes
@@ -562,7 +562,7 @@ jobs:
           trap 'rm -f "$probe"; rm -rf "$logs"' EXIT
 
           cat > "$probe" <<'KOTLIN'
-          package dev.wildware.udea.assets
+          package dev.wildware.udea.gradle
 
           import dev.wildware.udea.annotations.Net
           import dev.wildware.udea.annotations.Q
```

Then, run against the shipped step:

```
$ bash scripts/run-checkers-fire.sh
                                                                    ← lines 1-3 elided (header)
expecting UDEA0001 at 9:14, UDEA0003 at 10:42
::error::the probe failed to compile, but not one diagnostic carries a UDEA rule id, so no FIR checker ran. This says nothing about the checkers either way - it is the probe failing for the wrong reason. Check that :udea-gradle resolves dev.wildware.udea.annotations from this compilation, and that nothing else in the build compiles udea-gradle/src/main/kotlin/dev/wildware/udea/gradle/CheckerProbe.kt first.
e: file:///WT/udea-gradle/src/main/kotlin/dev/wildware/udea/gradle/CheckerProbe.kt:3:26 Unresolved reference: annotations
e: file:///WT/udea-gradle/src/main/kotlin/dev/wildware/udea/gradle/CheckerProbe.kt:4:26 Unresolved reference: annotations
e: file:///WT/udea-gradle/src/main/kotlin/dev/wildware/udea/gradle/CheckerProbe.kt:5:26 Unresolved reference: annotations
e: file:///WT/udea-gradle/src/main/kotlin/dev/wildware/udea/gradle/CheckerProbe.kt:7:2 Unresolved reference: Replicated
e: file:///WT/udea-gradle/src/main/kotlin/dev/wildware/udea/gradle/CheckerProbe.kt:9:6 Unresolved reference: Net
e: file:///WT/udea-gradle/src/main/kotlin/dev/wildware/udea/gradle/CheckerProbe.kt:10:6 Unresolved reference: Q
--- tail of the build log ---
Calculating task graph as no cached configuration is available for tasks: :udea-gradle:compileKotlin
> Task :build-logic:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :build-logic:generateExternalPluginSpecBuilders UP-TO-DATE
> Task :build-logic:extractPrecompiledScriptPluginPlugins UP-TO-DATE
> Task :build-logic:compilePluginsBlocks UP-TO-DATE
> Task :build-logic:generatePrecompiledScriptPluginAccessors UP-TO-DATE
> Task :build-logic:generateScriptPluginAdapters UP-TO-DATE
> Task :build-logic:pluginDescriptors UP-TO-DATE
> Task :build-logic:processResources UP-TO-DATE

> Task :build-logic:compileKotlin FAILED
                                                    ← lines 24-47 elided (the same six e: lines,
                                                      then Gradle's FAILURE block)
--- $GITHUB_STEP_SUMMARY ---
--- end ---
step exit=1
```

That is lines 4–23 and 48–50 of the 50-line
`scratchpad/173/red-control1.log`, each range a consecutive in-order run; the two elisions are
marked above. `WT` replaces
`/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a46c9497c28044fbe`, and is the only substitution.

Two things to see. The six `Unresolved reference` lines are the six in the issue body, so this
reproduces the reported failure exactly. And the tail names **`:build-logic:compileKotlin`** as the
task that failed — which is the finding, and which the old step could not have told anybody.

The second red — a broken checker caught at the position assertion — is §6, AC3.

---

## 2. Summary

### What the issue said, and what is actually true

The job is red and has never reached a checker. That is exactly right. Reproduced before anything
was changed, by writing the step's own fixture by hand into `udea-gradle` on `origin/example`
content and running `sh gradlew :udea-gradle:compileKotlin`: the six `Unresolved reference`
errors in the issue body, and no UDEA diagnostic — `scratchpad/173/baseline-enabled.log`, which
has exactly 6 lines matching `^e: `.

**The stated cause does not hold.** The issue says `implementation` does not put
`udea-annotations` on `udea-gradle`'s compile classpath, and calls the `ci.yml` comment saying
otherwise false. `implementation` does not propagate to *your consumers*; it does put your
dependency's `api` on *your own* compile classpath. Executed — and it answers the same on
`origin/example`, because this change touches no build script:

```
$ sh gradlew :udea-gradle:dependencies --configuration compileClasspath
compileClasspath - Compile classpath for 'main'.
+--- org.jetbrains.kotlin:kotlin-stdlib:2.2.10
|    \--- org.jetbrains:annotations:13.0
+--- project :udea-assets-compiler
|    +--- project :udea-diagnostics
|    |    \--- org.jetbrains.kotlin:kotlin-stdlib:2.2.10 (*)
|    +--- project :udea-assets
|    |    +--- project :udea-annotations
|    |    |    \--- org.jetbrains.kotlin:kotlin-stdlib:2.2.10 (*)
|    |    \--- org.jetbrains.kotlin:kotlin-stdlib:2.2.10 (*)
|    \--- org.jetbrains.kotlin:kotlin-stdlib:2.2.10 (*)
\--- project :udea-diagnostics (*)
```

`project :udea-annotations` is on it. The comment the issue quotes as false was true.

**The real cause.** `build-logic/build.gradle.kts` does

```kotlin
val udeaGradleSources: File = rootDir.resolve("../udea-gradle/src/main/kotlin")

sourceSets.main {
    kotlin.srcDir(udeaGradleSources)
}
```

so that `:moba` can apply `UdeaAgentPlugin` — a plugin Gradle cannot apply from a sibling
subproject of the build it configures. `udea-gradle`'s main sources are therefore compiled
**twice**, and the second compiler is `:build-logic:compileKotlin`: an included build carrying
`kotlin-gradle-plugin` and `asm`, with neither `udea-annotations` nor the K2 plugin on it, running
before any task of the main build. The probe never reached `:udea-gradle:compileKotlin` at all.
That build script's own KDoc had already written the constraint down — *"anything these sources
reference must be resolvable from `build-logic`'s classpath too"* — nobody had connected it to the
probe.

The arithmetic check on the explanation: the issue's cause predicts that giving `udea-gradle` the
annotations fixes the job. It cannot, because the failing compilation is not `udea-gradle`'s.

### What I did, and what I rejected

**Decided: move the probe to `udea-assets`.** Three properties must hold at once, and the issue had
enumerated two:

1. `UdeaCompilerPluginWiring.appliesTo` accepts the module, so the plugin is applied — this rules
   out `:udea-annotations`, `:udea-diagnostics` and `:udea-compiler-plugin`, on which a probe would
   compile *clean* and be reported as an unwired plugin;
2. `udea-annotations` is on its compile classpath — `udea-assets` declares
   `api(project(":udea-annotations"))` itself, the shortest route there is;
3. its sources are compiled once.

No production dependency is added anywhere, which is the lead's stated preference.

**Rejected: adding `udea-annotations` to `udea-gradle`.** It is the "dependency nobody needs"
smell, *and* it does not work — `build-logic`'s classpath is the one that was short.

**Rejected: having the step add and remove that dependency around the probe.** Same reason. It
would have looked like a fix and left the job red, which is the failure mode this ticket is an
instance of.

**Rejected: `udea-gradle/src/test/kotlin`.** This does work — `build-logic` srcDirs only
`src/main/kotlin`, and `UdeaCompilerPluginSupport.isApplicable` covers every compilation of an
applicable module. Rejected because it leaves the probe one directory away from the trap that
produced the bug, and because a main source set is the stronger reading of "a real module's real
source set".

If the owner disagrees with the module, it is one line in the step (`module=`, with `probe=` under
it) plus the fixture's `package` line. `CompilerPluginSwitchTest` fails on any replacement that is
doubly compiled or that the plugin is not applied to, so a wrong swap is a red test rather than a
red job nobody reads.

### The third outcome

The step had two outcomes: "compiled clean, so the plugin is not applied" and "the position grep
missed". A probe that never compiled landed in the second, and the log said *"expected UDEA0001 at
9:14; the compiler reported: …"* — which points a reader at the checkers, the one place the fault
was not. The step now collects the `e:` lines once and:

- **none of them carries a `UDEA` rule id** → its own named error, *"the probe failed to compile,
  but not one diagnostic carries a UDEA rule id, so no FIR checker ran… it is the probe failing for
  the wrong reason"*, followed by the tail of the build log — because a build that dies before the
  compiler starts produces no `e:` lines at all, and an empty list would look identical to a
  compiler that reported nothing;
- **some line is not a `UDEA` rule** → a second named error, because a position read off a
  compilation that was broken anyway means nothing;
- only then are positions asserted, and that message now begins *"Every diagnostic here is a UDEA
  rule, so the checkers did run"*, which tells the reader which half to look at.

It also echoes, on the **success** path, the diagnostics it matched and the second run's
`BUILD SUCCESSFUL`, and prints the summary table to stdout as well as to `$GITHUB_STEP_SUMMARY`.
Both gradle invocations write into a `mktemp -d` the EXIT trap removes, so a green run used to
leave a log saying only what it expected — and a job summary is not in the run log and cannot be
fetched with `gh api`, so a gate whose only passing evidence is its summary is a gate nobody can
quote afterwards.

### The fences, and every one of them watched failing

`CompilerPluginSwitchTest` — already the tripwire on this wiring — gains two tests. Both derive
their side of the comparison instead of repeating it: the doubly-compiled trees come out of
`build-logic/build.gradle.kts`'s own `kotlin.srcDir(...)` calls, and the applicability answer comes
from `UdeaCompilerPluginWiring.appliesTo`. The `module=`/`probe=` read is scoped to the
`checkers-fire:` job block, because a `module=` line anywhere else in this workflow would
otherwise answer a different question with the same confidence.

Each mutation below was applied to a clean `252f0df` worktree (the step and the test are
byte-identical at `6fe116f`, which only edits the runner script's header comment), run, and reverted; the diffs are
`git diff` output from that worktree, not descriptions. The command in every row is
`sh gradlew -p build-logic test --tests 'dev.wildware.udea.build.CompilerPluginSwitchTest'`, and
the run is 8 tests in each case. Failure messages come from the `<failure message=…>` attributes of
`build-logic/build/test-results/test/TEST-dev.wildware.udea.build.CompilerPluginSwitchTest.xml`;
the only edit to them is `/…/` in place of the absolute worktree path.

**M1 — break the `srcDir` parser, so the doubly-compiled set comes back empty.**

```
@@ -190,7 +190,7 @@ class CompilerPluginSwitchTest {
         val resolved = Regex("""val\s+(\w+)\s*:\s*File\s*=\s*rootDir\.resolve\("\.\./([^"]+)"\)""")
             .findAll(text)
             .associate { it.groupValues[1] to it.groupValues[2] }
-        val dirs = Regex("""kotlin\.srcDir\(([^)]+)\)""")
+        val dirs = Regex("""kotlin\.srcDirNOPE\(([^)]+)\)""")
             .findAll(text)
             .map { it.groupValues[1].trim() }
             .mapNotNull { resolved[it] }
```

`8 tests completed, 1 failed` —
`the checkers-fire probe is not written into a tree build-logic compiles a second time`, with

> `/…/build-logic/build.gradle.kts still calls kotlin.srcDir(...), but this test could not work
> out which tree, so it is about to pass without checking anything`

This is the control on the parser, and it is the point: without it, a scanner that quietly stopped
matching would make the fence green on everything — the same defect as the ticket, one level up.

**M2 — break the *slicer*, not the assertion.** Removing `.drop(1)` makes the job block end at its
own header, so the slice is empty and the fence would have nothing to disagree with:

```
@@ -143,7 +143,6 @@ class CompilerPluginSwitchTest {
         val rest = text.substring(start + 1)
         val next = Regex("""^ {2}[A-Za-z0-9_-]+:$""", RegexOption.MULTILINE)
             .findAll(rest)
-            .drop(1)
             .firstOrNull()
         val block = next?.let { rest.substring(0, it.range.first) } ?: rest
         assertTrue(
```

`8 tests completed, 2 failed` — both new tests, with

> `the checkers-fire job no longer contains the step these tests are about; the slice found:`

**M3 — move the probe to a module the plugin is excluded from.**

```
@@ -552,8 +552,8 @@ jobs:
           set -euo pipefail
           # The module, once. `probe` and the Gradle task below are both derived from it, so
           # the file this step writes and the compilation it inspects cannot come apart.
-          module=udea-assets
-          probe=udea-assets/src/main/kotlin/dev/wildware/udea/assets/CheckerProbe.kt
+          module=udea-annotations
+          probe=udea-annotations/src/main/kotlin/dev/wildware/udea/annotations/CheckerProbe.kt
           logs=$(mktemp -d)
```

`8 tests completed, 1 failed` —
`the checkers-fire probe is in a module the K2 plugin is actually applied to`, with

> `the checkers-fire probe is in :udea-annotations, which the K2 plugin is not applied to (on the
> plugin's runtime classpath, so applying the plugin here would make
> :udea-annotations:compileKotlin depend on a jar built from it.). The probe would compile clean
> and the job would fail claiming the plugin is unwired.`

**M4 — move the probe back where the bug was.** The same diff as §1's revert. `8 tests completed,
1 failed` — `the checkers-fire probe is not written into a tree build-logic compiles a second
time`, naming `udea-gradle/src/main/kotlin` and `build-logic/build.gradle.kts`.

**C1 — the control that a fence which fails on prose is as wrong as one that passes on it.** A
comment that merely *mentions* the forbidden declaration must not turn it red:

```
@@ -550,6 +550,8 @@ jobs:
         shell: bash
         run: |
           set -euo pipefail
+          # A comment that merely mentions module=udea-gradle and
+          # probe=udea-gradle/src/main/kotlin/dev/wildware/udea/gradle/CheckerProbe.kt.
           # The module, once. `probe` and the Gradle task below are both derived from it, so
           # the file this step writes and the compilation it inspects cannot come apart.
           module=udea-assets
```

`BUILD SUCCESSFUL` — green, as it must be.

### Frozen contracts

None touched. Nothing under `docs/contracts/` is in the diff:

```
$ git diff --stat origin/example HEAD -- docs/contracts/
(no output)
```

### Not done, and deliberately

The job's own comment says **"This job must be a required status check on `master`."** That is a
repository branch-protection setting, not a workflow edit, and no agent here can set it. It is now
a job that has passed, so the setting is possible for the first time; somebody with repository
admin has to make it.

---

## 3. The build

### `sh gradlew build`, no exclusions, on `252f0df` — the last commit that changes a build input

Run from a `clean`, on a quiet box:

```
$ JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew clean build --console=plain
                                                        ← lines 1-482 elided
> Task :udea-compiler-plugin:test
> Task :udea-compiler-plugin:check
> Task :udea-compiler-plugin:build

BUILD SUCCESSFUL in 17s
234 actionable tasks: 139 executed, 80 from cache, 15 up-to-date
Configuration cache entry stored.
BUILD_EXIT=0
```

*(lines 483–490, the tail of `scratchpad/173/full-build-clean.log`, consecutive and in order.
`BUILD_EXIT` is the shell's `$?` appended by the wrapper, not Gradle output.)*

And plain `sh gradlew build` immediately after, which is the command the contract names:

```
BUILD SUCCESSFUL in 4s
204 actionable tasks: 3 executed, 1 from cache, 200 up-to-date
```

### `sh gradlew :udea-gradle:tasks`

Required after any build-script edit. This change edits none — the diff is a workflow, a test, two
documents and a new script — but the task list was checked anyway:

```
BUILD SUCCESSFUL in 669ms
1 actionable task: 1 executed
```

### The three gates outside `check`

```
$ sh gradlew udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd
BUILD SUCCESSFUL in 7s
42 actionable tasks: 42 up-to-date
```

### The load artefact, stated because it happened

The **first** `sh gradlew build` on this branch was run while `melon-merge` had a scenario suite on
the box at load 38, and it failed — on wall-clock budget tasks the developer contract names as
load-sensitive, and on nothing else:

| Task | Under load 38 | Alone | Budget |
|---|---|---|---|
| `:udea-assets-compiler:udeaDaemonBudget` (validate) | median **738**ms `[82, 738, 544, 746]` | median **119**ms `[8, 119, 104, 128]` | 300ms |
| `:udea-assets-compiler:udeaDaemonBudget` (reload) | FAILED | median **173**ms `[260, 173, 148, 144]` | edit-to-observe |
| `:udea-agent-host:udeaPhase2Exit` | **1250**ms | **503**ms | 1000ms |
| `:udea-core:udeaBenchCharacterMover` | FAILED | median **2.757**ms | 4.0ms |

Logs: `scratchpad/173/full-build.log` (loaded, `BUILD_EXIT=1`) and
`scratchpad/173/full-build-solo.log` (alone, `BUILD_EXIT=0`). That is the box, not the branch, and
the `clean build` above is the one that matters.

### GL

This ticket does not touch `udea-render`, the render half of `udea-agent-host`, or anything that
opens a context — the diff is a workflow file, a `build-logic` test and two documents — so the
xvfb `-Pudea.render.requireGl=true` run is not evidence about anything here and was not run. Saying
a green `build` proves something about GL is the error being avoided; so is running a GL suite and
implying it bears on a change that cannot reach GL.

### `sh gradlew -p build-logic check` — two pre-existing failures, environmental

CI runs this as its own job (`ci.yml:352`, and `-p build-logic test` again at `ci.yml:1138`), and
it is where my two new tests live. On this box it fails, in `KotlinPinCheckTest` — and it fails
identically with my whole change stashed, which is `origin/example` content on the same box
minutes apart:

```
$ git stash push -u
$ sh gradlew -p build-logic test --tests 'dev.wildware.udea.build.KotlinPinCheckTest' \
    | grep -E "FAILED|tests completed|BUILD"
KotlinPinCheckTest > an unclassified resolvable configuration fails the gate(File) FAILED
KotlinPinCheckTest > a module whose classpaths are all classified passes(File) FAILED
2 tests completed, 2 failed
> Task :test FAILED
BUILD FAILED in 8s
```

*(that is grep output, not a contiguous run — the four kinds of line are interleaved with task
progress in `scratchpad/173/control-kotlinpin-example.log`.)*

The same two fail with my change applied, and the cause is in the report and is the box:

> `Could not determine the dependencies of task ':udea-core:udeaVerifyKotlinPin'.`
> `> Could not resolve all dependencies for configuration ':udea-core:compileClasspath'.`
> `   > Failed to calculate the value of task ':udea-core:compileJava' property 'javaCompiler'.`
> `      > Cannot find a Java installation on your machine (Linux 6.8.0-138-generic amd64)
> matching: {languageVersion=17, vendor=any vendor, implementation=vendor-specific}. Toolchain
> download repositories have not been configured.`

*(from the `<failure message=…>` of
`build-logic/build/test-results/test/TEST-dev.wildware.udea.build.KotlinPinCheckTest.xml`, with the
XML's `&#10;` newline entities decoded and nothing else changed.)*

These are TestKit tests that spawn a nested Gradle build; this box has no JDK 17 and the nested
build has no toolchain provisioning configured. The CI job uses `actions/setup-java@v4` with
`java-version: "17"`.

`CompilerPluginSwitchTest` itself: `tests="8" skipped="0" failures="0" errors="0"`, from
`build-logic/build/test-results/test/TEST-dev.wildware.udea.build.CompilerPluginSwitchTest.xml`.

---

## 4. The Actions runs

Both on the final commits, both running the identical step.

| | Branch @ SHA | `the FIR checkers fail a real build` |
|---|---|---|
| **green** | `issue-173-checker-probe-classpath` @ `252f0df` | **success** — https://github.com/wildware-uk/Udea/actions/runs/33428555116/job/99608220379 |
| **red** | `issue-173-broken-checker-proof` @ `4ef2e60` | **failure**, at the position assertion — https://github.com/wildware-uk/Udea/actions/runs/33428562099/job/99608721340 |

The output of each is quoted in §6, spliced from
`gh api repos/wildware-uk/Udea/actions/jobs/<id>/logs`.

An earlier, identical pair on `536df6f` / `e8bc212` is also on record
(runs `33428287273` and `33427966530`); the newest pair supersedes it and adds nothing but the
success-path echoes.

**The rest of both workflows is red, and not for reasons this branch owns.** Every `moba`-building
job fails on 25 × `UDEA0032` until #170 merges, and `bridge-conformance` is #171's. `checkers-fire`
builds no `moba` and is independent of both, which is why it can be green here while the workflow
is not.

---

## 5. Images

There are none, and that is the honest answer rather than a gap. Nothing in this ticket draws a
pixel: the diff is a CI step, a `build-logic` test and two documents. What carries it is the two
Actions runs, the executed transcripts above, and the job summary table — which is text by
construction. A screenshot of any of them would be a picture of text already quoted here.

---

## 6. The issue, criterion by criterion

### ☑ A real Actions run shows `the FIR checkers fail a real build` **green**, with the job summary table naming `UDEA0001` at `CheckerProbe.health` and `UDEA0003` at `CheckerProbe.slots` at the computed line and column. Link the run.

https://github.com/wildware-uk/Udea/actions/runs/33428555116/job/99608220379 — conclusion
`success`. The table is now written to the run log as well as to the job summary, so it can be
fetched rather than only clicked:

```
$ gh api repos/wildware-uk/Udea/actions/jobs/99608220379/logs
2026-08-31T19:05:31.1952102Z expecting UDEA0001 at 9:14, UDEA0003 at 10:42
2026-08-31T19:05:53.7574701Z the compilation reported, and every line of it is a UDEA rule:
2026-08-31T19:05:53.7584693Z e: file:///home/runner/work/Udea/Udea/udea-assets/src/main/kotlin/dev/wildware/udea/assets/CheckerProbe.kt:9:14 UDEA0001: @Net annotates the val dev.wildware.udea.assets.CheckerProbe.health. A val can never change, so it can never replicate, and Replicator.apply could not restore it. Make it a var or drop the annotation.
2026-08-31T19:05:53.7587977Z e: file:///home/runner/work/Udea/Udea/udea-assets/src/main/kotlin/dev/wildware/udea/assets/CheckerProbe.kt:10:42 UDEA0003: @Q annotates dev.wildware.udea.assets.CheckerProbe.slots, which is kotlin.Int, not Float. Quantization is only defined for floats.
2026-08-31T19:05:56.1875285Z -Pudea.compilerPlugin.enabled=false, the same file, :udea-assets:compileKotlin:
2026-08-31T19:05:56.1890788Z BUILD SUCCESSFUL in 2s
2026-08-31T19:05:56.1901212Z ### The FIR checkers fail a real build
2026-08-31T19:05:56.1901818Z 
2026-08-31T19:05:56.1902278Z | Rule | Symbol | Reported at |
2026-08-31T19:05:56.1903090Z | --- | --- | --- |
2026-08-31T19:05:56.1903837Z | UDEA0001 | CheckerProbe.health | 9:14 |
2026-08-31T19:05:56.1904659Z | UDEA0003 | CheckerProbe.slots | 10:42 |
2026-08-31T19:05:56.1905245Z 
2026-08-31T19:05:56.1905834Z Module: `:udea-assets`, main source set. Every diagnostic the compilation
2026-08-31T19:05:56.1907351Z produced carried a UDEA rule id, so nothing here is a compile that failed for
2026-08-31T19:05:56.1908835Z some other reason. The same file compiles clean under
2026-08-31T19:05:56.1909894Z `-Pudea.compilerPlugin.enabled=false`.
```

*(lines 363–380 of `scratchpad/173/actions-green-252f0df.log`, one consecutive in-order run, no
elisions.)*

`9:14` and `10:42` are computed by the step from the file it just wrote — `index($0, "health")` on
line 9 is 14, `index($0, "slots")` on line 10 is 42 — and the compiler independently reported
`CheckerProbe.kt:9:14 UDEA0001` and `CheckerProbe.kt:10:42 UDEA0003`. That the two agree is the
assertion.

### ☑ The run also shows the `-Pudea.compilerPlugin.enabled=false` half compiling the same source clean, which is the second thing the step asserts and which has also never executed.

Two ways, and the second was added for this criterion. Structurally, the summary table is written
*after* `if ! ./gradlew ":${module}:compileKotlin" -Pudea.compilerPlugin.enabled=false …; then …
exit 1; fi`, so a run that printed the table ran that compile and it succeeded. And literally, the
step now echoes that run's own verdict — these two lines are in the Actions log quoted above:

```
2026-08-31T19:05:56.1875285Z -Pudea.compilerPlugin.enabled=false, the same file, :udea-assets:compileKotlin:
2026-08-31T19:05:56.1890788Z BUILD SUCCESSFUL in 2s
```

The second line is `grep -E '^BUILD '` over that invocation's own log, not a sentence the step
writes about it. The 25-second gap between it and the `expecting …` line at `19:05:31.19` is the
two Gradle runs; the diagnostics land at `19:05:53.75`, 22s in, and the disabled compile 2s after
that.

### ☑ Deliberately breaking one of the two checkers makes the job red at the position assertion, not at the compile. Show it — a gate that has never been seen to fail for the right reason is not yet a gate.

Branch `issue-173-broken-checker-proof`, one commit on top of the fix, marked `PROOF ONLY, DO NOT
MERGE`. The mutation reports both diagnostics at the property's **return type** instead of at the
property name — so both rules still fire, both still carry their rule id, the compile still fails,
and every check before the position assertions still passes. Only the position disagrees, which is
exactly the case `docs/compiler-plugin.md` says presence alone cannot see:

```
$ git diff issue-173-checker-probe-classpath issue-173-broken-checker-proof -- udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaReplicatedPropertyChecker.kt
diff --git a/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaReplicatedPropertyChecker.kt b/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaReplicatedPropertyChecker.kt
index 912549f..9435747 100644
--- a/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaReplicatedPropertyChecker.kt
+++ b/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaReplicatedPropertyChecker.kt
@@ -39,7 +39,7 @@ internal object UdeaReplicatedPropertyChecker : FirPropertyChecker(MppCheckerKin
         if (UdeaFieldTypes.isUnresolved(type)) return
 
         val name = declaration.symbol.callableId.asSingleFqName().asString()
-        val source = declaration.source
+        val source = declaration.returnTypeRef.source
 
         // Only a *directly stored* val is the defect: a composite `@Net val position: Vector2`
         // is legal, because `Replicator.apply` restores it by writing `position.x`/`position.y`
```

Actions, https://github.com/wildware-uk/Udea/actions/runs/33428562099/job/99608721340, conclusion
`failure`:

```
$ gh api repos/wildware-uk/Udea/actions/jobs/99608721340/logs
2026-08-31T19:07:12.3117960Z expecting UDEA0001 at 9:14, UDEA0003 at 10:42
2026-08-31T19:07:33.2382164Z ##[error]UDEA0001 did not land at CheckerProbe.kt:9:14. Every diagnostic here is a UDEA rule, so the checkers did run; they reported:
2026-08-31T19:07:33.2389245Z e: file:///home/runner/work/Udea/Udea/udea-assets/src/main/kotlin/dev/wildware/udea/assets/CheckerProbe.kt:9:22 UDEA0001: @Net annotates the val dev.wildware.udea.assets.CheckerProbe.health. A val can never change, so it can never replicate, and Replicator.apply could not restore it. Make it a var or drop the annotation.
2026-08-31T19:07:33.2391426Z e: file:///home/runner/work/Udea/Udea/udea-assets/src/main/kotlin/dev/wildware/udea/assets/CheckerProbe.kt:10:49 UDEA0003: @Q annotates dev.wildware.udea.assets.CheckerProbe.slots, which is kotlin.Int, not Float. Quantization is only defined for floats.
2026-08-31T19:07:33.2409909Z ##[error]UDEA0003 did not land at CheckerProbe.kt:10:42. Every diagnostic here is a UDEA rule, so the checkers did run; they reported:
2026-08-31T19:07:33.2419326Z e: file:///home/runner/work/Udea/Udea/udea-assets/src/main/kotlin/dev/wildware/udea/assets/CheckerProbe.kt:9:22 UDEA0001: @Net annotates the val dev.wildware.udea.assets.CheckerProbe.health. A val can never change, so it can never replicate, and Replicator.apply could not restore it. Make it a var or drop the annotation.
2026-08-31T19:07:33.2421405Z e: file:///home/runner/work/Udea/Udea/udea-assets/src/main/kotlin/dev/wildware/udea/assets/CheckerProbe.kt:10:49 UDEA0003: @Q annotates dev.wildware.udea.assets.CheckerProbe.slots, which is kotlin.Int, not Float. Quantization is only defined for floats.
2026-08-31T19:07:33.2451199Z ##[error]Process completed with exit code 1.
```

*(lines 363–370 of `scratchpad/173/actions-red-4ef2e60.log`, one consecutive in-order run, no
elisions.)*

Red at the **position** assertion: 9:22 instead of 9:14, 10:49 instead of 10:42. It is not red at
the compile, and it is not the "wrong reason" branch — the message says so.

The branch was pushed and then left in place, unrebased into the fix, so the run is inspectable. It
must not be merged.

### ☑ The `ci.yml` comment no longer claims a compile-classpath route that `implementation` does not provide.

It no longer makes the claim, and it does not replace it with the issue's version either, because
that version is wrong in the other direction. It now says why the module is right in three numbered
properties, records that the old sentence *was* true and that the module was still wrong, and names
both rejected alternatives. It is `.github/workflows/ci.yml` lines 487–524, replacing the four
lines the issue quotes.
`docs/compiler-plugin.md`'s description of the job was updated in the same change, since it named
`udea-gradle` too.

### The class, not just the instance

The finding is "a document names the module the probe lives in". The census, run on `252f0df` (unchanged at `6fe116f`)
(`BRIEF-173.md`'s own hits filtered out, since it is this document):

```
$ grep -rn -e CheckerProbe -e checkers-fire --include='*.md' --include='*.yml' --include='*.kt' --include='*.kts' . | grep -v '/build/'
./docs/compiler-plugin.md:163:- **`checkers-fire`** writes a `@Net val` and a `@Q`-annotated `Int` into `udea-assets`'s real
./.github/workflows/ci.yml:422:    # works. The `checkers-fire` job below is the other half: it proves that the source which
./.github/workflows/ci.yml:474:  checkers-fire:
./.github/workflows/ci.yml:556:          probe=udea-assets/src/main/kotlin/dev/wildware/udea/assets/CheckerProbe.kt
./.github/workflows/ci.yml:572:          internal class CheckerProbe(
./.github/workflows/ci.yml:620:            if ! grep -q "CheckerProbe.kt:${position} ${rule}:" "$logs/errors.log"; then
./.github/workflows/ci.yml:621:              echo "::error::${rule} did not land at CheckerProbe.kt:${position}. Every diagnostic here is a UDEA rule, so the checkers did run; they reported:"
./.github/workflows/ci.yml:654:            echo "| UDEA0001 | CheckerProbe.health | ${health_line}:${health_col} |"
./.github/workflows/ci.yml:655:            echo "| UDEA0003 | CheckerProbe.slots | ${slots_line}:${slots_col} |"
```

Every hit is inside the two files this change edits, and none of them still says `udea-gradle`.
There is nothing else in the repository — no other document, script or test — that names the probe
or the job, so the sweep is complete rather than sampled.

---

## 7. Regenerated files

None, and no id moved. `udea-codegen/net-protocol.lock` and
`udea-codegen/src/test/resources/expected-generated-hashes.txt` are untouched: the probe adds a
`@Replicated` class only for the duration of the step, in `udea-assets`, which applies no KSP
(`grep -n ksp udea-assets/build.gradle.kts` matches nothing), and the step's `trap … EXIT` deletes
it whether the step passes or fails.

```
$ git diff --stat origin/example HEAD -- udea-codegen/
(no output)
```

The whole diff, at `6fe116f`:

```
$ git diff --stat origin/example HEAD
 .github/workflows/ci.yml                           | 110 +++-
 BRIEF-173.md                                       | 693 +++++++++++++++++++++
 .../udea/build/CompilerPluginSwitchTest.kt         | 132 ++++
 docs/compiler-plugin.md                            |  27 +-
 scripts/run-checkers-fire.sh                       | 106 ++++
 5 files changed, 1045 insertions(+), 23 deletions(-)
```

Four of those five are the change; the fifth is this document.

`gradlew` is **not** in it. It is `M` in `git status` on this box because the wrapper is checked in
without the executable bit and both CI and `scripts/run-checkers-fire.sh` `chmod +x` it; the mode
flip is deliberately never staged.
