8d7b517

That is the commit carrying the change, and every transcript below was produced against it. This
file lands on top of it, so the branch tip is one commit further on — review either; the second
commit adds nothing but this document.

# BRIEF-180 — build-logic tests declare the five files they read, and a test that finds the sixth

Branch `issue-180-declare-build-logic-inputs`, off `origin/example` at `293649b`.
Worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a9ffff83bda14fb94`.

---

## 1. The evidence command

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew \
  :build-logic:test --tests "*OuterBuildInputsTest" --console=plain
```

### It goes red when the feature is reverted

The feature is the five `rootDir.resolve(...)` lines in `outerBuildInputs`. Deleting exactly those
five lines and running the command:

```
diff --git a/build-logic/build.gradle.kts b/build-logic/build.gradle.kts
index 61a744e..2550944 100644
--- a/build-logic/build.gradle.kts
+++ b/build-logic/build.gradle.kts
@@ -132,2 +131,0 @@ val outerBuildInputs: FileCollection = files(
-    rootDir.resolve("../determinism-allowlist.txt"),
-    rootDir.resolve("../determinism-audit.md"),
@@ -138 +135,0 @@ val outerBuildInputs: FileCollection = files(
-    rootDir.resolve("../gradle/libs.versions.toml"),
@@ -143,2 +139,0 @@ val outerBuildInputs: FileCollection = files(
-    rootDir.resolve("../docs/migration/trello-map.md"),
-    rootDir.resolve("../docs/superpowers/specs/2026-08-22-udea-ai-native-rewrite-design.md"),
```

```
gradle exit=1
> Task :build-logic:test FAILED
OuterBuildInputsTest > every repository file a build-logic test names is a declared input of this task() FAILED
4 tests completed, 1 failed
```

and the assertion message, spliced from the preserved
`build-logic/build/test-results/test/TEST-dev.wildware.udea.build.OuterBuildInputsTest.xml` of that
run (XML entities unescaped, no other edit):

```
org.opentest4j.AssertionFailedError: these repository paths are named by build-logic tests but
are not inputs of :build-logic:test, so an edit to one leaves the test that reads it UP-TO-DATE
and the gate it enforces silently absent. Add each to outerBuildInputs in
build-logic/build.gradle.kts, with a comment saying which test reads it - or, if it is a fixture
path that merely happens to name a real file, to NOT_READ_FROM_THE_REPOSITORY with the reason.
==> expected: <{}> but was: <{determinism-allowlist.txt=[AllowlistParserTest.kt, AuditTest.kt],
determinism-audit.md=[AllowlistParserTest.kt, AuditTest.kt, FloatPortabilityTest.kt],
docs/migration/trello-map.md=[TrelloMapTest.kt],
docs/superpowers/specs/2026-08-22-udea-ai-native-rewrite-design.md=[TrelloMapTest.kt],
gradle/libs.versions.toml=[GradleFixture.kt, UdeaVersionsTest.kt]}>
```

(The message is one line in the XML; it is wrapped here to fit the page and nothing else is
changed. The `expected: <{}> but was:` line is where the wrapping starts.)

That output is the issue's own table, re-derived by the test rather than copied from the issue:
the same five files, attributed to the same test sources.

This is also the transcript of the **RED step**. The test was written and watched fail in exactly
this state before the five lines were added.

---

## 2. What I did, what I decided, what I rejected

### The five lines

`build-logic/build.gradle.kts` now declares `determinism-allowlist.txt`, `determinism-audit.md`,
`gradle/libs.versions.toml`, `docs/migration/trello-map.md` and the design spec, each with a
comment saying which test reads it and why that test wants to run when it changes.

### Criterion 3: a mechanism, and I judged it proportionate

`build-logic/src/test/kotlin/dev/wildware/udea/build/OuterBuildInputsTest.kt`.

It scans every other `build-logic` test source for **plain string literals** and for
**SHOUTING_CASE identifiers resolved against the string constants `build-logic`'s own main sources
declare**, keeps whatever names a file or directory that really exists in the repository, and
requires each to be an input of the task it is running in.

The constants half is not decoration. `determinism-audit.md` and `determinism-allowlist.txt` are
read as `repoRoot.resolve(UdeaVerifyDeterminismTask.AUDIT_FILE)` and `ALLOWLIST_FILE`. A
literal-only scan would have missed two of the five — the two that carry the Phase 7 exit
criterion — while looking like it worked. Mutation M3 below is that half going blind.

The declared side is **not** scanned. `build-logic/build.gradle.kts` hands over the collection
Gradle actually resolved, as repository-relative paths, through a `CommandLineArgumentProvider`.
Re-deriving it by regex over the build script would put a second, differently-wrong parser between
the assertion and its subject, which is this defect's own shape one level up.

**What I rejected.**

1. *The five lines alone, with a note that a check is disproportionate.* Rejected because the
   recurrence is the issue's point: #174 fixed `AGENTS.md` and five more were already sitting
   there. Nothing about that changes by fixing five instead of one.
2. *Funnelling every outer read through one sanctioned `RepoRoot.file(...)` helper, so a sixth
   file cannot be read undeclared by construction.* Rejected as disproportionate **and
   unbuildable as stated**: a build script cannot use a class its own project compiles, so
   `outerBuildInputs` could not be derived from the same list the tests read through — which was
   the entire appeal of the design. What is left is 14 test classes rewritten for an enforcement
   that would still need a source scan.

**What the check cannot see, stated in its own KDoc.** A path assembled at runtime.
`"udea-codegen/" + UdeaProtocolLock.FILE_NAME` and the interpolated
`gradle-plugins/$PLUGIN_ID.properties` are both read by tests here and neither appears in the
scan; both happen to be declared already, by `net-protocol.lock` and by the `udea-gradle/src`
tree. So it narrows the hole rather than closing it. Closing it would mean forbidding
concatenation into a repository path at all — a fence that fails on two shapes that are currently
correct and declared. I judged that to cost more than the hole; the alternative is written down on
the issue so the owner can overrule it cheaply.

### The exemption list, and why it is safe

Three literals name real repository files without being read from the repository — a fixture path
written into a TestKit temp root, or a path quoted in an assertion message.
`NOT_READ_FROM_THE_REPOSITORY` carries each with its reason, and a fourth test deletes an
exemption the moment the scan stops producing it (mutation M5). `OuterBuildInputsTest.kt` is
excluded from its own scan, because every exemption key is a literal in that file and scanning
itself would make the staleness check vacuous.

### A second hole, found while doing it, and fixed

`build-logic/src/main/kotlin` and `src/test/kotlin` are now declared inputs of the test task.
They reach `test` as compiled classes, which is a **different object** from the source text:
compile avoidance means a comment-only edit produces byte-identical classes and leaves the task
UP-TO-DATE. `CompilerPluginSwitchTest` and the new scan both read the text. Mutation M6a/M6b
below is the negative and its matched control.

### A design claim I measured rather than asserted

The build script comment says the manifest goes through a `CommandLineArgumentProvider` so the
trees are walked at execution time, because walking them at configuration time would invalidate
the configuration cache for the whole build on every edit under `moba/src`. I built the eager
variant, ran it, and reverted it. Gradle's own first line, both ways, appending a comment to
`moba/src/main/kotlin/dev/wildware/moba/MobaVfx.kt`:

```
lazy  (shipped):    afterMobaEdit: exit=0 | Reusing configuration cache.
eager (discarded):  afterMobaEdit: exit=0 | Calculating task graph as configuration cache cannot be reused because an input to build file 'build-logic/build.gradle.kts' has changed.
```

The two locals inside `tasks.test` are not style either: a lambda that reaches back into the
script fails outright with `cannot serialize Gradle script object references as these are not
supported with the configuration cache`, which is how the first attempt failed.

### Two questions the issue left open, answered

- **Is `build-logic/build.gradle.kts` a sixth undeclared file?** It is read by
  `UdeaAgentPluginIdTest` and `CompilerPluginSwitchTest` and is not in `outerBuildInputs`, so it
  looks like one. **It is not.** Checked rather than assumed: on the unmodified tree I appended a
  comment to it and `:build-logic:test` executed rather than coming back UP-TO-DATE. Changing that
  script changes the task's own configuration, so Gradle re-runs it without being told.
- **Does the class extend past `build-logic`?** Swept, and it does — twice. The sweep was
  `grep -rn '"\.\./' --include='*.kt'` over every `udea-*` and `moba` test source tree, plus a
  second pass for `Paths.get`/`Path(` with `..`. Everything it returned falls into three groups.

  *Path-traversal **rejection** fixtures, which read nothing:* `SourceSpanTest`,
  `ResPathTest`, `FileValidatorTest` (`spritePath = "../../../secrets.png"`),
  `ArtifactStoreTest` (`ids outside cap_digits are rejected without touching the filesystem`),
  `ArtifactEndpointTest`, `ReplayToolTest`.

  *Real reads, already declared:* `udea-replay`'s and `moba`'s replay-equality tests reading
  `../.github/workflows/ci.yml`, declared at `udea-replay/build.gradle.kts:92` and
  `moba/build.gradle.kts:985`.

  *Real reads, **not** declared:*

  1. `udea-annotations/src/test/.../NoDuplicateFqnTest.kt:33` reads `../common/src` and
     `../gradle-plugin/src`; `udea-annotations/build.gradle.kts` declares no inputs at all (it is
     deliberately empty of build logic).
  2. `udea-codegen/src/test/.../ProtocolLockDriftTest.kt:66` reads the repository-root
     `net-components.lock`, and `udea-codegen/build.gradle.kts` has no `inputs.` line. Checked
     rather than assumed, by the same settle-edit-run protocol used for the five:

     ```
       settle1:   exit=0 | > Task :udea-codegen:test
       settle2:   exit=0 | > Task :udea-codegen:test UP-TO-DATE
       afterEdit: exit=0 | > Task :udea-codegen:test UP-TO-DATE
     ```

     Stated precisely, because the severity depends on it: an edit to `net-components.lock` that
     does **not** change what the module compiles — the appended comment line used here — leaves
     `:udea-codegen:test` UP-TO-DATE. An edit that inserts or removes a component changes the
     generated code and would re-run it. So this is a narrower hole than the allowlist one, not a
     claim that the id space is unguarded.

  **I did not fix either.** Both are different modules, outside this issue's stated scope, and
  widening a `build-logic` ticket into the id-space module's build script is not mine to do. Both
  are written up here and on the issue as follow-ups.

---

## 3. `sh gradlew build`

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --console=plain
```

```
BUILD SUCCESSFUL in 1m 8s
211 actionable tasks: 133 executed, 69 from cache, 9 up-to-date
Configuration cache entry stored.
```

`git status --short` afterwards showed only ` M gradlew` — the local `chmod +x` this box needs,
never staged. No art staging drift, no `UDEA0032`.

**The test count, measured rather than assumed.** My first attempt at a total was contaminated:
summing `*/build/test-results/**/*.xml` picks up `build-logic`'s leftovers from the filtered runs
below, and a filtered run also prunes the module it touches. So I deleted every non-`build-logic`
`build/test-results` directory, re-ran `build`, and counted only what that run produced:

```
test-results dirs remaining before the run:
build-logic/build/test-results

BUILD SUCCESSFUL in 943ms
202 actionable tasks: 2 executed, 23 from cache, 177 up-to-date
Configuration cache entry reused.
--- 'build-logic:test' lines in that log: 0 ---
tests 2543 skipped 34 failures 0 errors 0 across 16 modules
```

**2543 tests, 0 failures, 0 errors, 34 skipped, across 16 modules.** The results came back from
the build cache, which is the same task outputs; the point of emptying the directory first is that
nothing in the count can be a leftover.

**A green `build` says nothing about this change, and I am not offering it as if it did.**
`:build-logic:test` does not run in the root `build` — `build-logic` is an included build, and
`grep -c "build-logic:test"` over the full build log returns `0`. CI runs it separately
(`ci.yml:467` `./gradlew -p build-logic check`, `ci.yml:1269` `./gradlew -p build-logic test`),
which is exactly where the caching this ticket is about would bite. So I also ran the CI
invocation form:

```
$ sh gradlew -p build-logic test --tests "*OuterBuildInputsTest" --console=plain
> Task :test
BUILD SUCCESSFUL in 2s
```

The out-of-`check` verifiers:

```
$ sh gradlew udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd udeaVerifyContracts
BUILD SUCCESSFUL in 3s
43 actionable tasks: 43 up-to-date
```

**Pre-existing red, not mine.** The whole `:build-logic:test` suite is 284 tests with 2 failures
on this box: `KotlinPinCheckTest`'s two TestKit cases, both with

> Cannot find a Java installation on your machine (Linux 6.8.0-138-generic amd64) matching:
> {languageVersion=17, vendor=any vendor, implementation=vendor-specific}. Toolchain download
> repositories have not been configured.

There is no JDK 17 on this box and `GradleFixture` writes a `settings.gradle.kts` with no foojay
resolver.

Checked rather than taken on trust. With `build-logic/build.gradle.kts` restored to
`origin/example` (`git checkout origin/example -- build-logic`), the same two cases fail the same
way, and the tree was restored afterwards:

```
== on my branch (8d7b517 build-logic) ==
  mine: exit=1
KotlinPinCheckTest > an unclassified resolvable configuration fails the gate(File) FAILED
KotlinPinCheckTest > a module whose classpaths are all classified passes(File) FAILED
2 tests completed, 2 failed
== on origin/example's build-logic ==
  originExample: exit=1
KotlinPinCheckTest > an unclassified resolvable configuration fails the gate(File) FAILED
KotlinPinCheckTest > a module whose classpaths are all classified passes(File) FAILED
2 tests completed, 2 failed
restored: 0 changed under build-logic
```

It is also why every transcript below uses `--tests` filters: a task that always fails is never
up-to-date, so the whole suite can never demonstrate a cache hit on this machine.

**Latency budgets.** Not run. This change adds no measured work to any budgeted path — it adds
file inputs and one JVM argument to `:build-logic:test`, which is not a budgeted task and is not
on `check`.

**GL.** This ticket touches no GL: it is Gradle input declaration and a source-scanning test. I
did not run `udeaGlTest`/`udeaAgentGlTest` under xvfb and I claim nothing about them. `$DISPLAY`
is empty here, so their appearance in the `build` log is not evidence either way.

---

## 4. There is nothing to photograph

This change declares files as Gradle task inputs and adds a test that reads source text. It draws
nothing, moves nothing on screen, and has no runtime behaviour in the game. Every claim below is
an executed transcript instead. No screenshot was produced and none is referenced.

---

## 5. The acceptance criteria

### Criterion 1 — each of the five declared, and shown **executing** rather than `FROM-CACHE`

Protocol, per file: settle the task under one `--tests` filter until it reports `UP-TO-DATE` twice,
edit the file, run again with **the same filter**, and read the `> Task :build-logic:test` line.
The filter is held constant on purpose — changing it changes the task's inputs and forces
execution for the wrong reason, which is a mistake I made once and caught.

**Before the fix** (branch at `293649b`, working tree otherwise clean). Each of these is one run,
with the file edited and the task asked to run:

| File | edit | `> Task :build-logic:test` | build |
|---|---|---|---|
| `determinism-allowlist.txt` | `@version fleks 2.14` → `2.15` | `UP-TO-DATE` | `BUILD SUCCESSFUL in 646ms` |
| `determinism-audit.md` | appended a line | `UP-TO-DATE` | `BUILD SUCCESSFUL in 677ms` |
| `gradle/libs.versions.toml` | appended a comment | `UP-TO-DATE` | `BUILD SUCCESSFUL in 1s` |
| `docs/migration/trello-map.md` | appended a line | `UP-TO-DATE` | `BUILD SUCCESSFUL in 615ms` |
| `docs/superpowers/specs/2026-08-22-…md` | appended a line | `UP-TO-DATE` | `BUILD SUCCESSFUL in 618ms` |

The settle runs immediately before each of those reported `UP-TO-DATE` as well, so the state was
genuinely cached and the edit genuinely changed nothing Gradle could see.

**After the fix** (`8d7b517`). Same protocol, same filters, spliced from the run logs:

```
== after-allowlist: filter *determinism*, file determinism-allowlist.txt
settle 1:
  exit=0 > Task :build-logic:test
settle 2:
  exit=0 > Task :build-logic:test UP-TO-DATE
after editing determinism-allowlist.txt:
  exit=1 > Task :build-logic:test FAILED
```
```
== after-audit: filter *determinism*, file determinism-audit.md
settle 1:
  exit=0 > Task :build-logic:test FROM-CACHE
settle 2:
  exit=0 > Task :build-logic:test UP-TO-DATE
after editing determinism-audit.md:
  exit=0 > Task :build-logic:test
```
```
== after-versions: filter *UdeaVersionsTest, file gradle/libs.versions.toml
settle 1:
  exit=0 > Task :build-logic:test
settle 2:
  exit=0 > Task :build-logic:test UP-TO-DATE
after editing gradle/libs.versions.toml:
  exit=0 > Task :build-logic:test
```
```
== after-trello: filter *TrelloMapTest, file docs/migration/trello-map.md
settle 1:
  exit=0 > Task :build-logic:test
settle 2:
  exit=0 > Task :build-logic:test UP-TO-DATE
after editing docs/migration/trello-map.md:
  exit=0 > Task :build-logic:test
```
```
== after-spec: filter *TrelloMapTest, file docs/superpowers/specs/2026-08-22-udea-ai-native-rewrite-design.md
settle 1:
  exit=0 > Task :build-logic:test FROM-CACHE
settle 2:
  exit=0 > Task :build-logic:test UP-TO-DATE
after editing …-design.md:
  exit=0 > Task :build-logic:test
```

**The five `> Task :build-logic:test` lines criterion 1 asks for, on their own:**

```
after editing determinism-allowlist.txt:      > Task :build-logic:test FAILED
after editing determinism-audit.md:           > Task :build-logic:test
after editing gradle/libs.versions.toml:      > Task :build-logic:test
after editing docs/migration/trello-map.md:   > Task :build-logic:test
after editing …-rewrite-design.md:            > Task :build-logic:test
```

None is `FROM-CACHE`, `UP-TO-DATE` or `NO-SOURCE`. The bare `> Task :build-logic:test` line with no
suffix is Gradle saying the task executed; `FAILED` is it executing and failing, which is
criterion 2. Every edit was reverted immediately afterwards; `git status --short` at the end of
the sequence showed only ` M gradlew`.

### Criterion 2 — at least one shown going **red for its real subject**, on the allowlist

The allowlist edit made an existing `@version` pin stale — `fleks 2.14` → `2.15`, while
`determinism-audit.md` still records that the audit was performed against 2.14. That is precisely
the rot `ALLOW005` and `AuditTest` exist to catch. Spliced from the run's console log:

```
AuditTest > the audit is stamped with the versions the allowlist pins() FAILED
42 tests completed, 1 failed
> Task :build-logic:test FAILED
BUILD FAILED in 5s
```

Before the fix, the identical edit produced `> Task :build-logic:test UP-TO-DATE` and
`BUILD SUCCESSFUL in 646ms`. That is the whole ticket in two lines: the same repository state, the
same command, green before and red after, because the gate can now be reached.

### Criterion 3 — a decision recorded on whether anything prevents the sixth

Decided: a mechanism, `OuterBuildInputsTest`. Reasoning, alternatives and stated blind spot are in
section 2 above and posted on the issue
(https://github.com/wildware-uk/Udea/issues/180#issuecomment-5487205152).

**Proof it can fail.** Every mutation below is a literal `git diff` from the run that produced the
result beside it, taken with `git diff -U0 -- build-logic` against `8d7b517`, and reverted with
`git checkout -- build-logic` afterwards.

**M1 — one declaration removed.**
```
@@ -132 +131,0 @@ val outerBuildInputs: FileCollection = files(
-    rootDir.resolve("../determinism-allowlist.txt"),
```
```
> Task :build-logic:test FAILED
OuterBuildInputsTest > every repository file a build-logic test names is a declared input of this task() FAILED
4 tests completed, 1 failed
```

**M2 — the manifest never arrives.** The control on everything else: without it the test would see
an empty declared set, call every path undeclared, and could just as easily have been written to
pass on nothing.
```
@@ -214 +214 @@ tasks.test {
-            listOf("-Dudea.declaredTestInputs=$manifest")
+            listOf("-Dudea.declaredTestInputs.disabled=$manifest")
```
```
> Task :build-logic:test FAILED
OuterBuildInputsTest > the declared inputs reached the test JVM() FAILED
OuterBuildInputsTest > every repository file a build-logic test names is a declared input of this task() FAILED
4 tests completed, 2 failed
```

**M3 — the scan goes blind to resolved constants**, which is how two of the five files are read.
```
@@ -83 +83 @@ class OuterBuildInputsTest {
-                SHOUTING_CASE.findAll(text).flatMap { constants[it.value].orEmpty() }
+                emptySequence()
```
```
> Task :build-logic:test FAILED
OuterBuildInputsTest > the scan still finds the repository paths it is known to find() FAILED
4 tests completed, 1 failed
```

**M4 — the literal regex stops matching.** This is the shape that would otherwise make the whole
class pass on an empty set.
```
@@ -195 +195 @@ class OuterBuildInputsTest {
-        val LITERAL = Regex("\"([A-Za-z0-9_@./-]+)\"")
+        val LITERAL = Regex("\"(zzzz-no-such-literal)\"")
```
```
> Task :build-logic:test FAILED
OuterBuildInputsTest > the scan still finds the repository paths it is known to find() FAILED
OuterBuildInputsTest > no exemption has gone stale() FAILED
4 tests completed, 2 failed
```

**M5 — an exemption nothing matches.** An exemption list nobody prunes is how the next undeclared
file gets waved through.
```
@@ -215,0 +216 @@ class OuterBuildInputsTest {
+            "docs/art-assets.md" to "an exemption for a path no test names",
```
```
> Task :build-logic:test FAILED
OuterBuildInputsTest > no exemption has gone stale() FAILED
4 tests completed, 1 failed
```

**M6a — a repository path named only in a comment.** This is both a mutation and the proof of the
KDoc's claim that the scan reads comments, and of why `build-logic/src/test/kotlin` had to become
a declared input: the edit is comment-only, so the compiled classes are unchanged and only the
source-tree input can notice it.
```
@@ -168,0 +169,2 @@ class AgentsMdTest {
+
+// A commented-out read of "docs/art-assets.md" - not declared anywhere.
```
```
> Task :build-logic:test FAILED
OuterBuildInputsTest > every repository file a build-logic test names is a declared input of this task() FAILED
4 tests completed, 1 failed
```

**M6b — the matched control.** The same comment, in the same place, naming a file that *is*
declared. A fence that failed on any comment would be as wrong as one that passed on M6a.
```
@@ -168,0 +169,2 @@ class AgentsMdTest {
+
+// A commented-out read of "docs/module-graph.md" - already a declared input.
```
```
gradle exit=0
> Task :build-logic:test
```

After every mutation, `git status --short -- build-logic` reported `0` changed files.

---

## 6. Regenerated files

**None.** `udea-codegen/net-protocol.lock` and
`udea-codegen/src/test/resources/expected-generated-hashes.txt` are untouched; this change adds no
replicated component and moves no id. `git show --stat 8d7b517` is two files, both under
`build-logic/`.

---

## 7. My own pass over the diff, against the closed reject list

- **Section 1 smell in new code** — none. No `TODO()`, no stub, no swallowed exception; every path
  in `OuterBuildInputsTest` asserts or returns a value.
- **A `public` declaration nobody outside the module uses** — the test class is public because
  JUnit requires it; every member is `private`, and the companion is `private companion object`.
- **A test that cannot fail** — seven mutations above, each with its diff, and one matched control
  that stays green. Three of the four tests in the class exist specifically to make the fourth
  unable to pass vacuously.
- **Generated code by string concatenation** — none; nothing here generates code.
- **New `GameContext` field** — none.
- **Wall clock or unseeded randomness in simulation** — none; this touches no simulation code.
- **Copy-pasted logic differing only in a constant** — none. The one place duplication was
  tempting, the property name spelt on both sides of the build/test boundary, is unavoidable (a
  build script cannot use a class its own project compiles) and is covered by a control that fails
  loudly if the two drift.
- **AGENTS.md "Do not"** — no `by net(...)`, no snapshot codec, no setter instrumentation, no wall
  clock in `step()`, no `Math.random`, no new dependency on `common`, no per-tick reflection (the
  reflection-free source scan runs once, in a build-time test), no bare domain primitive, no GL
  outside `udea-render`, no presentation system as a Fleks system, no upward module arrow. The
  change is confined to `build-logic/`, which is below everything.
- **A `docs/contracts/` file changed** — none. `git show --stat 8d7b517` touches two files, both
  under `build-logic/`. `udeaVerifyContracts` passes.
- **`fieldNames`/FieldMask/FieldStore alignment** — untouched.
- **A duration expressed in seconds rather than `Tick`** — none; no duration here.
- **`AGENTS.md`'s module table stale** — no module moved; `udeaVerifyAgentsMd` passes.

And on the evidence:

- Every acceptance criterion has a transcript, above, quoted from a saved run log or a saved test
  report rather than retyped.
- The evidence command goes red with the feature reverted, shown with the diff that reverts it.
- **Execution was proved, not passing.** Every criterion-1 claim quotes the
  `> Task :build-logic:test` line itself and says which suffix it did *not* carry. The one place I
  nearly got this wrong is recorded rather than hidden: my first attempt at the "before" transcript
  changed the `--tests` filter between the settle and the edited run, which forced execution for
  the wrong reason and would have read as a passing gate. Every transcript above holds the filter
  constant.
- **Not exercised:** the behaviour of this scan on a Windows path separator (the manifest is
  normalised to `/` on both sides but only Linux was run); and `:build-logic:test` as a whole,
  which cannot reach a cache hit on this box for the pre-existing `KotlinPinCheckTest` reason
  above.
