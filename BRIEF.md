# Shaders are a declared asset kind

SHA: `@@SHA@@`

Branch `shader-assets`, rebased onto `origin/master` at `edaded0` — fetched and read back, not
assumed. 38 files against that ref; `BRIEF-266.md` untouched.
Worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a777f6986276a548e`.

> **Every "predicted" figure below was written into this file before a JVM had been started on this
> branch.** The box was held for another project for about two hours, and the lead used the time to
> make me freeze the mutation table, the magnitudes and the absence controls in advance. So each row
> is a commitment made blind, and where the observation does not match, the row says so rather than
> being quietly rewritten. Four of them did not match. They are the most useful rows in the file.

---

## The evidence command

One paste. Two task paths, because one of them cannot cover the other's half and a vacuous
single-command answer is worse than an honest two-part one.

```sh
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew :moba:desktop:runShaderAssetProof \
             :udea-assets-compiler:test --tests '*ShaderAssetTest' \
  --no-configuration-cache --max-workers=4
```

**Why two halves.** `runShaderAssetProof` is the whole runtime chain: it fails to *compile* if the
accessor form stops working from `commonMain`, it fails at its first `require` if the GLSL stops
travelling in the pack, and it fails on its row and factor arithmetic if the shader stops reaching
the GPU. What it cannot say is that a **bad** shader name is a build error with a rule id, a span
and a did-you-mean — a proof that something is refused needs the refusal, and `ShaderAssetTest` is
where the refusals are. The ticket names both as minima, so both are in the command.

**What it leaves behind:** four PNGs in `moba/desktop/build/reports/udea/shader-asset/`, and a JUnit
report in `udea-assets-compiler/build/reports/tests/test/`.

### It goes red when the feature is reverted, and that was run

Eight single-edit mutations, each applied to the tree, run, and reverted. Every row carries the
**literal `git diff`** of the edit as it was applied, and the observed reds come from the JUnit XML
rather than from the console. M4 alone is enough to answer "does it go red if the feature is
removed" — deleting the accessor overload stops `moba/game/src/commonMain/.../MobaScreenEffects.kt`
compiling, so the evidence command cannot even build. The other seven say *which* part of the
feature each check is holding.

### The proof's own transcript

Spliced from `evidence.log`, the captured stdout of the run named above. Consecutive, no elisions.

```
shader-asset-proof: asset: `shaders/scanlines` is 1358 characters of GLSL from `shaders/scanlines.frag`
shader-asset-proof: control: two unprocessed captures of the paused scene differ by 0.0 levels/channel
shader-asset-proof: scanlines at 0.25: frame moved 11.737876 levels/channel, 166 of 360 rows changed
shader-asset-proof: rows: 166 changed, of 180 at parity 0, of which 14 are pure black and cannot be dimmed
shader-asset-proof: scanlines at 0.25: dimmed channels are 0.7500381923849041x what they were, over 318720 samples
shader-asset-proof: headroom: 318720 samples against a floor of 100000
shader-asset-proof: scanlines at 0.5: frame moved 23.417875 levels/channel, ratio 1.9950693
shader-asset-proof: scanlines at 0.5: dimmed channels are 0.5015754822776733x what they were, over 318720 samples
shader-asset-proof: off again: the frame differs from the first unprocessed capture by 0.0 levels/channel
shader-asset-proof: all checks passed; pictures in /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a777f6986276a548e/moba/desktop/build/reports/udea/shader-asset
```

---

## Predictions, frozen before anything ran

### Mutation table

| # | Mutation | Predicted red | Predicted magnitude | Observed |
|---|---|---|---|---|
| M1 | Delete `"shader" to Shader::class` from `DslKinds.TYPES` | `:moba:game:compileKotlinJvm` fails (unresolved `shaders` in `MobaScreenEffects.kt`); `AccessorCompilationTest.a fixture that types the member as Ref of Blueprint compiles`; `AccessorGeneratorTest.the aggregate names every group exactly once` | The accessor disappears entirely, so this is a **compile** failure and not a test assertion | **As predicted, both names exact.** `e: .../MobaScreenEffects.kt:47:53 Unresolved reference 'shaders'.` plus exactly those two tests. See "the parser that lied" below — the first reading of this row was wrong, and the mutation was not |
| M2 | `ShaderSources.fill` returns `declared` unchanged | `:moba:game:udeaValidateAssets` fails with `UDEA0041`; `ShaderAssetTest.a declared shader validates…`; `MobaShaderAssetTest.the packed bundle holds the GLSL…` | Exactly one extra `UDEA0041` per declared shader — 1 in `moba` | **Magnitude exact: `163 asset(s), 1 diagnostic(s)`**, and it is the "build-tool defect" branch of the message. 5 `ShaderAssetTest` reds, not 1: once `source` is missing, the version, comment, `udeaMain` and empty-file cases all lose the field they read. `MobaShaderAssetTest` never ran — `udeaPackBundle` fails first, which is a stronger statement than the one I predicted |
| M3 | Remove `ShaderFileValidator` from `AssetValidatorPipeline.DEFAULT` | **Exactly five** `ShaderAssetTest` methods: misspelled file, states-its-own-version, no-`udeaMain`, empty file, wrong extension | 5 red, 0 elsewhere. `a shader declaration the compiler never filled…` stays **green** | **All five predicted reds are red, and the correctly-silent prediction held exactly.** Six red in total: the sixth is `a version pragma inside a comment…`, a test that **did not exist** when this row was written — I added it mid-run for the finding below. The count moved because the suite grew, not because the prediction missed |
| M4 | Delete the `fragment(Ref<Shader>, AssetRegistry, …)` overload from `UdeaShader` | `:moba:game:compileKotlinJvm` and `:udea-render:compileTestKotlinJvm` both fail to compile | A compile failure, which is the point | **As predicted.** 14 errors: 12 in `ShaderFromAssetTest.kt` and 2 at `moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaScreenEffects.kt:47`, `Argument type mismatch: actual type is 'Ref<Shader>', but 'String' was expected` |
| M5 | Prepend `#version 330 core` to `moba/game/assets/shaders/scanlines.frag` | `:moba:game:udeaValidateAssets` fails, `UDEA0041`, naming `shaders/scanlines.frag` and "OpenGL ES" | One new error diagnostic, at the declaring line of `shaders/shaders.udea.kts` | **As predicted, to the span.** `163 asset(s), 1 diagnostic(s)`; `error UDEA0041 moba/game/assets/shaders/shaders.udea.kts:5:1 … states a #version of its own at line 1 … OpenGL and OpenGL ES disagree …` |
| M6 | In that `.frag`, `float dim = 1.0 - uStrength;` (dim **every** row instead of every other) | `:moba:desktop:runShaderAssetProof` fails its row check | `changed.size` goes **180 → 360, exactly 2x** | **The ratio is exactly 2x; both absolute numbers were wrong by the same 28 rows.** 166 → 332. The scene's top 28 rows are black sky, and 332 = 360 − 28 as 166 = 180 − 14. The failure is the parity check: `the changed rows are not all the same parity … [28, 29, 30, 31, 32, 33, 34, 35] of 332`. See "the sky" below |
| M7 | In that `.frag`, `… 1.0 - uStrength * 0.5;` (dim by **half** as much) | `runShaderAssetProof` fails `requireFactor` | `dimFactor` reads **0.875** where `0.75` is required — 0.125 out against a tolerance of 0.02, six times the band. Row count and ratio stay green | **0.8753164545125804.** Predicted 0.875 — the absolute-magnitude check, added because a ratio is invariant under exactly this mutation, lands within 0.0004 of its prediction. Row count unchanged at 166, exactly as predicted |
| M8 | `ShaderAssetProof.requireFactor`: delete the `require(measured.samples >= MIN_SAMPLES)` block | 3 red in `ShaderAssetProofChecksTest` | `a measurement below the sample floor…`, `an empty measurement reports the floor…`, `the sample floor is exactly where it says it is` — three, because the floor is asserted from three directions. Band and factor tests stay green | **Exactly those three, by name, and nothing else** |

### The literal diffs

Taken from `git diff` in the worktree with each mutation applied, not retyped.

```diff
@@ -55,7 +55,6 @@ public object DslKinds {                                   # M1
         "spriteAnimationSet" to SpriteAnimationSet::class,
         "soundCue" to SoundCue::class,
         "model" to Model::class,
-        "shader" to Shader::class,
         "blueprint" to Blueprint::class,
         "level" to Level::class,
         "gameConfig" to GameConfig::class,
```
```diff
@@ -78,6 +78,7 @@ internal object ShaderSources {                            # M2
      */
     fun fill(assetRoot: Path, declared: List<DeclaredAsset>): List<DeclaredAsset> {
         if (declared.none { it.kind == KIND }) return declared
+        return declared // M2
         return declared.map { asset ->
             if (asset.kind != KIND) return@map asset
             val read = read(assetRoot, asset)
```
```diff
@@ -93,7 +93,6 @@ public class AssetValidatorPipeline(                       # M3
             MissingFileValidator,
             SpriteSheetGeometryValidator,
             ModelFileValidator,
-            ShaderFileValidator,
             AnimationNotifyValidator,
             DeterminismValidator,
         )
```
```diff
@@ -131,14 +131,6 @@ public class UdeaShader internal constructor(            # M4
          *   source is one of them, and it is the shape a build that failed to read the `.frag`
          *   leaves behind.
          */
-        public fun fragment(
-            shader: Ref<Shader>,
-            assets: AssetRegistry,
-            uniforms: ShaderUniforms.() -> Unit = {},
-        ): UdeaShader {
-            val asset = assets[shader]
-            return fragment(path = asset.file.value, source = asset.source, uniforms = uniforms)
-        }
 
         /**
          * A fragment shader from [source], authored at [path]: the form for GLSL a game made up
```
```diff
@@ -1,3 +1,4 @@                                                     # M5
+#version 330 core
 // moba's own screen effect: every other row of the finished frame is dimmed.
 //
 // Declared as an asset in `shaders.udea.kts`, so the build reads this file, checks it and packs
```
```diff
@@ -16,6 +16,6 @@ vec4 udeaMain(vec2 uv) {                                  # M6
     float row = floor(uv.y * uResolution.y);
-    float dim = mod(row, 2.0) < 1.0 ? 1.0 : 1.0 - uStrength;
+    float dim = 1.0 - uStrength;
     return vec4(texture(uColor, uv).rgb * dim, 1.0);
 }
```
```diff
@@ -16,6 +16,6 @@ vec4 udeaMain(vec2 uv) {                                  # M7
     float row = floor(uv.y * uResolution.y);
-    float dim = mod(row, 2.0) < 1.0 ? 1.0 : 1.0 - uStrength;
+    float dim = mod(row, 2.0) < 1.0 ? 1.0 : 1.0 - uStrength * 0.5;
     return vec4(texture(uColor, uv).rgb * dim, 1.0);
 }
```
```diff
@@ -397,11 +397,6 @@ object ShaderAssetProof {                                # M8
     /** Fails unless [measured] is `1 - strength`, which is what the `.frag` multiplies by. */
     internal fun requireFactor(measured: DimFactor, strength: Float) {
-        require(measured.samples >= MIN_SAMPLES) {
-            "only ${measured.samples} channels were bright enough to take a dim factor from, " +
-                "under the $MIN_SAMPLES this needs. The scene is too dark for the check to mean " +
-                "anything, which is a failure and not a pass."
-        }
         val predicted = 1.0 - strength
         require(abs(measured.mean - predicted) <= FACTOR_TOLERANCE) {
             "a dimmed channel came back at ${measured.mean}x what it was. The .frag multiplies by " +
```

The M6 and M7 hunks above are shown with the `.frag`'s three comment lines between `{` and
`float row` elided, because those comment lines were themselves edited after the mutation runs (they
said "half the rows changed", which is the claim the sky disproved). Everything else in every hunk
is byte-for-byte what `git diff` printed. The unelided originals are the eight `M*.diff` files the
run left behind.

**Why M6 and not "turn the shader off".** Disabling the effect makes every figure go to zero, which
any broken pipeline also does. M6 leaves the shader running, the uniform bound and the magnitude
linear, and changes only the *shape*.

**M7 is the hole I first declared and then closed.** An earlier draft of this brief admitted that a
mutation *scaling* the effect would halve the measurement while leaving the ratio at 2.0, so only
`MIN_MOVE` stood between it and a green. A ratio is invariant under exactly the transformation that
defeats it. The fix was to assert the **absolute** value the `.frag` predicts: `dimFactor` reads
`after / before` over the dimmed channels, which **is** the shader's `dim` term, and `dim` is
`1.0 - uStrength` — a number that appears in the `.frag` and in `MobaScreenEffects` and in no
capture anywhere. It came back at 0.8753 against a predicted 0.875.

**Not circular, and here is the arithmetic.** The prediction is computed from the shader's source,
not from the frame it produced. The one thing taken from a picture is *which* rows are dimmed, and
the factor is then measured over the **unprocessed** frame's values against the processed one.

**A dependency between two tests, which nothing in the code states.** The absolute band only means
anything because the frame is plain 8-bit, and this branch does not assert that — `ShaderProof` does,
by requiring a palette shader's `vec3(0.35, …)` back within one level of `(0.35 * 255).toInt()`.
**If `ShaderProof.offPalette` ever moves, `FACTOR_TOLERANCE = 0.02` moves with it**, because in an
sRGB-encoded frame `after / before` would not be `dim` at all and this proof would be wrong about a
*working* shader. It is in `ShaderAssetProof`'s own comment as well as here, since a reviewer of the
other file will not be reading this one.

### The sample floor, bracketed on both sides, and the number that settles it

`requireFactor` refuses a measurement averaged over fewer than **100,000** channel samples. A mean
over nothing is NaN and every comparison against NaN is false, so an empty measurement would fail
anyway — but it would fail saying *the shader is wrong*, when what happened is that the proof could
not measure. Two different defects, and a proof that confuses them sends the next person to the
wrong file.

| Side | Where | Observed |
|---|---|---|
| **Above** — the floor is cleared with room to spare | `runShaderAssetProof` prints it | **`headroom: 318720 samples against a floor of 100000`** — 3.19x |
| **Below** — the floor trips, with its own words | `ShaderAssetProofChecksTest.a measurement below the sample floor fails saying the scene was too dark`, a direct call with 12 samples | Red, and M8 shows it is the floor holding it up |

**I committed in advance to what I would do with that number**, before the gate opened: *"if the real
run comes back near 100,000 the floor is a tripwire in the path of a working proof and I will lower
it; if it comes back at 300,000 the floor is a guard with 3x of headroom."* It came back at 318,720.
**The floor stands at 100,000**, unchanged, on the rule written down before the measurement existed.

The lower case is chosen so the *factor* is correct — `DimFactor(mean = 0.75, samples = 12)` is
exactly what a strength of 0.25 predicts — so if the floor were removed the assertion would go
**green**. That is what makes it a test of the floor and not of something nearby. It asserts the
message too: `"too dark for the check to mean anything"` present, `"1.0 - uStrength"` **absent**.

`ShaderAssetProofChecksTest` brackets three more lines the same way, all on `check`, none needing a
driver:

- **the tolerance band**, failing at `predicted ± 0.04` and passing just inside `predicted ± 0.02`.
  The passing cases sit deliberately a hair *inside* the edge rather than on it: `0.75 + 0.02` is
  `0.7700000000000000178` in binary, so a case on the boundary would be asserting something about
  IEEE-754 rounding rather than about the band;
- **the prediction is a function of the uniform**, not a constant: `0.75` passes at a strength of
  `0.25` and **fails** at `0.5`, where `0.50` passes. A constant somebody wrote down once could pass
  every other test here;
- **`dimFactor` itself**, over two 4x2 images: a row dimmed 200 → 150 reads back as exactly `0.75`
  over exactly 12 samples, and a row of channels below `MIN_CHANNEL` reads back as 0 samples and NaN
  rather than as a quiet zero.

### Absence claims, each with its positive control

| Claim | Predicted (claim) | Predicted (control) | Observed |
|---|---|---|---|
| A1: no `javaClass` in the new `commonMain` code | 0 lines, exit 1 | no external control exists — it is inside the test | **0 lines, exit 1.** Control: `MobaShaderAssetTest.the common source that builds the shader names nothing JVM-only` passes, and it fails if its own scanner stops matching the shipped line |
| A2: no `java.*` / `javax.*` import in the new `commonMain` code | 0 lines, exit 1 | the same pattern over `ShaderAssetProof.kt` → **3 lines** | **Claim: 0 lines, exit 1.** Control: **4 lines, not 3** — I enumerated `BufferedImage`, `File` and `ImageIO` and forgot `java.io.ByteArrayInputStream`. The control did its job; my count of it was wrong |
| A3: `docs/new-game.md` no longer tells a reader to use `getResource` | `grep -c` → 0, exit 1 | `templates/new-game/README.md` → 1 | **1, not 0 — the prediction was wrong and the claim is still true.** See below |

```
=== A1 claim
A1 claim exit=1

=== A2 claim
A2 claim exit=1

=== A2 control (the same pattern on a file that definitely has them)
27:import java.awt.image.BufferedImage
28:import java.io.ByteArrayInputStream
29:import java.io.File
30:import javax.imageio.ImageIO
A2 control exit=0

=== A3 claim
1
A3 claim exit=0

=== A3 control
1
A3 control exit=0
control: 1 fenced block(s), 1 containing `getResource`
control passed: the extractor finds the token in a block and ignores it in prose
docs/new-game.md: 17 fenced block(s), 0 containing `getResource`; 1 mention(s) in prose
templates/new-game/README.md: 5 fenced block(s), 0 containing `getResource`; 1 mention(s) in prose
VERDICT: clean - no guide shows it
```

**A3 is a row where the instrument was wrong rather than the claim.** `grep -c getResource
docs/new-game.md` counts the one place the guide *names the line as the thing not to do*, at
`docs/new-game.md:338`, inside a bullet explaining why the accessor form exists. A whole-file grep
cannot tell "shown" from "warned about", so it was the wrong instrument for the claim. The check
that does test the claim reads the **fenced code blocks only**, and runs its own positive control
first — a synthetic document whose block contains the token and whose prose also does, so the
extractor has to find the one and ignore the other. It passes, and then: `docs/new-game.md: 17
fenced block(s), 0 containing getResource; 1 mention(s) in prose`, and the template the same with 5
blocks. A fence that fails on prose would be as wrong as one that passes on a code block.

**A1 has no external control, and finding that out is the reason these rows are written down.** The
only file left on this branch containing the token is `MobaShaderAssetTest.kt`, where it appears as a
regex literal **inside the fence itself**; grepping that would prove the grep compiles and nothing
else. So the control moved inside the test, where the thing I know is there can be a fixture: the
test applies its own three patterns to the exact line `docs/new-game.md` used to ship and asserts
`\bjavaClass\b` matches it. A scanner with an empty pattern set, a broken regex or a path that
resolved to nothing fails that assertion instead of reading as a pass.

**And the strongest statement here is not an absence at all — it is a compiler.** The GLSL travels
as one `String` field on an ordinary `udea-assets` graph record, and `udea-assets` builds for
`wasmJs` and both iOS targets as well as `jvm` and `android`. `ShaderSourceReadTest` is in that
module's **`commonTest`**, so the code that reads a shader's source back out of a bundle is compiled
for a non-JVM target on this box. A JVM resource reader could not be written in that file at all.
*(iOS is **compiled** here and **run** only by CI's `ios-tests` job on macOS — see the finding below,
which turns on exactly that distinction.)*

### Correctly silent, and why

- **M6 leaves the ratio check and `requireFactor` green.** Dimming every row instead of every other
  changes neither the linearity nor the factor a dimmed channel comes back at. What M6 changes is
  *which* pixels are touched, and the parity check is the one aimed at it. Three checks, three
  properties: the rows are the shape, the factor is the magnitude, the ratio is that the uniform is
  read per frame rather than baked in.
- **M7 leaves the ratio check and the row set green.** Halving the dim leaves the effect linear and
  still on one parity. `requireFactor` is the only one of the three that can see M7, which is why it
  had to exist — before it, M7 was a mutation this proof would have passed.
- **M3 leaves `a shader declaration the compiler never filled is reported as a build-tool defect`
  green**, because that test constructs a `ValidationContext` by hand and calls
  `ShaderFileValidator.validate` directly rather than through the pipeline. Predicted, and observed.

---

## Four things the first real build found, which no amount of reading would have

### 1. The engine refused its own example shader

`moba/game/assets/shaders/scanlines.frag` failed `UDEA0041` on the first run — for a line in its own
**comment** that said *"No `#version` line. The engine writes it, per backend"*. `VERSION_PRAGMA` is
`#\s*version\b` over the whole file, so the comment describing the rule tripped the rule.

**The check stays blind to comments.** `udea-render` refuses the same body at registration on the
same regex — pre-existing, shipped in #266, unchanged here — so a comment-aware *build* with a
comment-blind *runtime* would let a `.frag` through the build and fail it at first draw, which is the
failure this asset kind exists to move earlier. Making both comment-aware is the irreversible
direction: once a game ships GLSL a relaxed engine accepts, tightening it again breaks that game.

**The message changed instead.** It now names the line — *"states a `#version` of its own at line 3
… Delete line 3 … Commenting it out is not enough: this check does not read comments, deliberately"*
— because *"states its own `#version`"* was **false** about that file, and a diagnostic an author can
read as false is one they go looking for a compiler bug behind. Pinned by a new test,
`a version pragma inside a comment is refused, and the message admits comments are read`.

**Swept for the class, not the instance.** `find . -name '*.frag'` over the whole tree: two files,
**both mine**, both carrying the same commented pragma. Both fixed. Nothing else in the repository
has one. The second was `udea-assets-compiler/src/test/resources/packassets/shaders/tint.frag`, a
packing fixture that is never validated — so it would have sat there indefinitely as a fixture the
engine would refuse.

Recorded on the issue: <https://github.com/wildware-uk/Udea/issues/269#issuecomment-5753989004>

### 2. Kotlin/Native rejects a comma in a backticked name, and iOS *does* compile on this box

`:udea-assets:compileTestKotlinIosArm64` failed with
`ShaderSourceReadTest.kt:75:9 Name contains illegal characters: ","` — on a test name that reads
perfectly normally and compiles fine for the JVM.

Two things worth carrying forward. First, **"iOS cannot build on this Linux box" is true of running
tests and false of compiling them**: `compileTestKotlinIosArm64` and `compileTestKotlinIosSimulatorArm64`
both execute here, and both failed on my change. A claim that iOS is untestable from here would have
been half wrong in exactly the way the brief warns about.

Second, the sweep: of every backticked declaration name containing a comma in this repository —
hundreds, across `udea-editor`, `udea-codegen`, `udea-render` and more — **exactly one** is in a
`commonTest` of a module with an iOS target, and it was mine. Renamed, and the reason is written into
that file's KDoc so it is not reintroduced.

### 3. The replay fixtures carry an asset-graph hash

Not in my briefing, and a third generated-file family beside `net-protocol.lock` and
`expected-generated-hashes.txt`. Adding one shader to `moba`'s asset graph changed
`BuildIdentity.assets` from `7ba1a653…` to `b4867d4d…`, and both checked-in `.udearep` fixtures
stopped being replayable. The engine says so precisely and names the route
(`-Dupdate.replay.fixtures=true`), which I took.

I measured the rewrite rather than trusting it. Both files are **the same length**, and **35 bytes
differ** in each:

```
moba-3600: 59572 bytes, unchanged in length; 35 differ
  offset 27..48 (22 bytes)
    old 7ba1a6532362da7bc58f8822a09e2ad4547a37b6fb1f
    new b4867d4df1327e1941128c5f356b5f3a6276a48d8134
  offset 50..58 (9 bytes)
    old 6aa4d3b63a1c0ac6b5
    new 81ce0d4c3f769ea7a8
  offset 59568..59571 (4 bytes)
    old c2a76782
    new b9af40c4
moba-36000: 590901 bytes, unchanged in length; 35 differ
  offset 27..48 (22 bytes)
    old 7ba1a6532362da7bc58f8822a09e2ad4547a37b6fb1f
    new b4867d4df1327e1941128c5f356b5f3a6276a48d8134
  offset 50..58 (9 bytes)
    old 6aa4d3b63a1c0ac6b5
    new 81ce0d4c3f769ea7a8
  offset 590897..590900 (4 bytes)
    old 6973a4ff
    new bf8c2770
```

That is the 32-byte asset hash at offsets 27..58 — in two runs because one byte at offset 49
happened to collide — plus a 4-byte trailer. **Not one recorded input sample moved**, which is what
"an asset was added and nothing else changed" has to look like. `proto` stayed `0x07b6` and
`inputSchema` stayed `407227863552470576` throughout, which is why `net-protocol.lock` and
`expected-generated-hashes.txt` are **not** in this diff: no replicated component was added.

They were regenerated a second time, near the end, because I edited a comment **inside** the `.frag`
— which is packed text, so it moves the hash. Worth knowing: on this branch, editing a shader's
comments is a fixture-regenerating change.

### 4. The sky does not dim, and my own parser lied about it

Two separate instances of the same species, both caught by re-running rather than by reasoning.

**The sky.** The proof asserted `changed.size == HEIGHT / 2` and the first run said
`166 rows changed; a shader dimming every other row of a 360-row frame changes exactly 180`. The
shader was fine. The top 28 rows of this scene are sky, pure `(0, 0, 0)`, and `0 * 0.75` is `0`, so
14 rows of the dimmed parity are dimmed to no visible effect. 180 − 14 = 166, and M6 confirmed it
independently from the other direction: 360 − 28 = 332. The arithmetic of the explanation matches
the size of the discrepancy in both directions, which is what makes it an explanation.

A count is a claim about the scene as much as about the shader, so the count is gone and the
property replaced it: every changed row has the same parity, **and** a row of that parity changed if
and only if it had colour in it to dim. That is a biconditional, so it fails both ways — a bright row
that did not change is a hole in the effect, and a black row that did change is the shader writing
something other than `colour * dim`. The ambiguous middle is refused outright: a row whose brightest
channel is 1 or 2 makes the check a rounding coin-toss, so such a row fails the run and says so.

**And on the way there I nearly blamed the shader.** My first script for "was this row black?" took
`max()` over the **raw bytes including alpha**, which is 255 on every pixel, and duly reported that
the pure-black sky rows had a brightest channel of 255. A measurement that ran, returned a confident
number, and was about the wrong subject. Reading the actual pixels gave `(0, 0, 0, 255)`.

**The parser.** The script that turned JUnit XML into "which tests went red" used
`<testcase name="...">(.*?)</testcase>` with DOTALL. Passing tests are self-closed `<testcase ... />`,
so the regex walked straight past them and attached each failure to the name of whichever testcase it
had last seen an opening tag for. It reported M1's reds as two plausible-looking but **wrong** test
names, and I was one step from writing "prediction missed" into this file. Rewritten with
`ElementTree`, M1 and M2 re-run from scratch, and both predictions were exact. A check that runs,
returns an answer of the right shape, and is about the wrong subject — be most suspicious when a
check tells you that you were wrong.

---

## What changed, and why

### The defect

`docs/new-game.md:306` shipped this as the documented way to give a game a look of its own:

```kotlin
val source = checkNotNull(javaClass.getResource("/shaders/scanlines.frag")).readText()
```

`javaClass` is a JVM property, `commonMain` has no `Class`, no `getResource` and no `readText()` on
a `URL`. The engine's own example could not compile in shared code — a game shipping on more than
one platform had to write that line once per platform to load a file byte-identical on all of them.

### The fix, in one sentence

A `.frag` is an asset: declared in a `.udea.kts`, read and checked by the build, its **text** packed
into the asset graph, and named in Kotlin as `GameAssets.shaders.<name>`.

### The one design decision worth arguing about

**The GLSL travels inside the asset rather than as a path to a file somebody opens.** Every other
file-backed asset names a file: `Model` holds a `ResPath` and `udea-render`'s `ModelFiles` — which is
`jvmMain` — opens it with `java.nio`. Following that pattern for a shader means writing a shader
loader per platform, which is the defect restated rather than fixed, and the ticket says in as many
words: *"If you find yourself writing a resource reader, stop."*

I checked whether there was an existing common-code way to read a file's bytes out of a `.udeapak`
before deciding. There is not one in use: `BundleFormat.SectionKind.BLOB` exists, but
`BundleWriter.blobs` defaults to empty and **nothing in the tree passes it** — no file-backed asset's
bytes are packed today, and the renderer reads a `.glb` off the asset root on disk. So there was no
reader to reuse, only one to write.

The graph already packs string fields, already has a reader in `commonMain` on every target, already
hot-swaps values, and already has a codec table one line long per kind. GLSL is a few hundred bytes
of text. It goes in the graph.

**Alternative rejected:** a multiplatform resource reader beside the asset system — a second asset
mechanism for one file type, with its own packing, validation and hot-reload story to keep in step
with the one that exists.

**If the owner disagrees:** the reversible half is which *field* carries the text. Moving it to a
`BLOB` section keyed by `ResPath` is a change to `GraphPacker.Context.shader()`, `AssetCodecs` and a
new common-code reader — the asset kind, the DSL word, the accessor, the rule id and the API all stay
exactly as they are.

### Decisions I had to make that the ticket left open

1. **The API takes the registry.** `UdeaShader.fragment(GameAssets.shaders.scanlines, assets)`, not
   `fragment(GameAssets.shaders.scanlines)`. A `Ref` is an id and a type token, and `registry[ref]`
   is how every other asset is read; a process may hold more than one graph. *To overturn:* add a
   one-line overload that reads a process-wide default, and decide where that default lives.
2. **The rule id is `UDEA0041`, minted in `UdeaRules`.** `AssetValidationRules`' reserved band
   (UDEA0030-0039) is full, and `ModuleContractTest` forbids `UdeaRules` growing into either local
   band. `UDEA0041` is the first free id past both, and it puts the build-time shader rule beside
   `UDEA0019` and `UDEA0040` in the shared registry. #266 minted `UDEA0040` there the same way.
3. **One reader, one reporter, and they are different objects.** `ShaderSources` (pass 2) reads each
   `.frag` once and puts the text in the declaration; `ShaderFileValidator` (pass 3) reports
   `UDEA0041` over what `ShaderSources` put there rather than reading the file again. The split is
   forced: a declaration's **span** comes from pass 1's scan, which is not an input to pass 2, so a
   diagnostic raised in `ShaderSources` would have no line number in any ordinary build.
4. **`moba` does not switch its own effect on.** Every existing shot, proof and agent screenshot in
   this repository is the game with no post-process over it. `ShaderAssetProof` registers it, and
   that is where the pictures come from. *To overturn:* one `registry.screenPass(...)` line in
   `MobaScene.build`, and re-baseline the shots.
5. **The template shows the form in its README rather than shipping a working one.**
   `templates/new-game` is one JVM project applying `udea.kotlin-library`, with no renderer and no
   asset pipeline. The README shows the declaration, the Kotlin and the failure modes, and says
   plainly that the template does not ship one. *To overturn:* apply `dev.wildware.udea.assets` and a
   render dependency to the template — with the rest of #269 rather than here.
6. **The `#version` check stays blind to comments** — decided during the run, reasoning in finding 1
   above and on the issue.

### Files

**New**
- `udea-assets/src/commonMain/.../Shader.kt` — the runtime kind: id, `file`, `source`, and the two
  GLSL facts (`ENTRY_POINT`, `VERSION_PRAGMA`) both the build and the runtime check against.
- `udea-assets-compiler/src/main/.../shader/ShaderSources.kt` — reads the `.frag`, fills `source`.
- `udea-assets-compiler/src/main/.../validate/ShaderFileValidator.kt` — `UDEA0041`.
- `moba/game/assets/shaders/scanlines.frag`, `moba/game/assets/shaders/shaders.udea.kts`.
- `moba/game/src/commonMain/.../MobaScreenEffects.kt` — the `commonMain` line the ticket is about.
- `moba/desktop/src/test/.../shader/ShaderAssetProof.kt` + the `runShaderAssetProof` task.
- `moba/desktop/src/test/.../shader/ShaderAssetProofChecksTest.kt` — the proof's own arithmetic, on
  `check`, with a failing case on both sides of the sample floor and of the tolerance band.
- Tests: `ShaderAssetTest` (compiler), `ShaderSourceReadTest` (`udea-assets` **commonTest**),
  `ShaderFromAssetTest` (`udea-render`), `MobaShaderAssetTest` (`moba:game`).
- Fixture: `udea-assets-compiler/src/test/resources/packassets/shaders/{screen.udea.kts,tint.frag}`.

**Changed**
- `udea-diagnostics`: `UdeaRules.SHADER_SOURCE` = `UDEA0041`, in `all`; `UdeaRulesTest` pin 20 → 21.
- `udea-assets`: `AssetCodecs.Builtin` gains the `Shader` codec.
- `udea-assets-compiler`: `AssetScope.shader(...)` + `MEMBER_NAMES`; `DslKinds`;
  `AssetKindHierarchy.KNOWN`; `GraphPacker` `SCHEMAS`/`MAPPED_TYPES`/`Context.shader()`;
  `AssetCompiler.compile` calls `ShaderSources.fill`; `AssetValidatorPipeline.DEFAULT`;
  `TranspiledAssetLoader` gains a required `assetRoot`.
- `udea-render`: the `fragment(Ref<Shader>, AssetRegistry, …)` overload, and KDoc on the
  `path`/`source` overload saying which case it is for.
- `moba/desktop/src/test/resources/fixtures/*.udearep` — regenerated, finding 3.
- Docs: `docs/new-game.md`, `templates/new-game/README.md`,
  `docs/superpowers/specs/2026-09-20-shader-api-design.md`, `AGENTS.md`, `docs/wiki/Assets.md`.

### Reading this diff in two passes

38 files is a lot for one review, so: **eleven of them are mechanical and carry no argument.**

| Mechanical | Why it moved |
|---|---|
| `MigratedCorpusCompilesTest.kt` | one line added to a pinned list of scripts |
| `MigratedCorpusGapTest.kt` | one word added to a pinned set of kinds |
| `PackFixture.kt` | one id added to `EXPECTED_IDS` |
| `AccessorGeneratorTest.kt` | one group name added to a list of four |
| `AccessorCompilationTest.kt` | one typed member, plus the new required `assetRoot` argument |
| `TranspilerParityTest.kt` | the new required `assetRoot` argument |
| `UdeaRulesTest.kt` | the pinned id and the count, 20 → 21 |
| `AssetValidator.kt` | one line: the new validator in `DEFAULT` |
| `AssetKind.kt` | one line: `Shader::class` in `KNOWN` |
| `DslKinds.kt` | one line: the DSL word to its type |
| `docs/wiki/Assets.md` | one sentence, which enumerated the DSL words and had gone stale twice; now a pointer at `DslKinds.kt` |

**Nothing was renamed, moved or reformatted.** The two `.udearep` files are binary and regenerated;
the byte-level diff is in finding 3.

**Not touched:** `docs/contracts/`, `docs/contracts.lock`, `udea-codegen/net-protocol.lock`,
`udea-codegen/src/test/resources/expected-generated-hashes.txt`, `local.properties`. `gradlew`'s
executable bit was set locally to run it and is **deliberately not staged**.

---

## `sh gradlew build`

No exclusions, and run after a `clean` so no project output survived into it.

**Read the second line rather than the first.** `clean` empties the projects' `build` directories;
it does not empty Gradle's **build cache**, and 328 of these tasks were served from it. 763 executed.
That is the normal, correct behaviour of a cached build and I am pointing at it because "I ran it
from clean" is exactly the sort of sentence that gets read as "therefore everything ran" — the GL
section below is where that distinction stopped being pedantic and became a defect in my evidence.

```
$ ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
    sh gradlew clean build --continue --no-configuration-cache --max-workers=4
...
BUILD SUCCESSFUL in 1m 13s
1212 actionable tasks: 763 executed, 328 from cache, 121 up-to-date
$ echo $?
0
```

**The first run of this command on this branch failed, with five failures, all mine**, and the three
causes are findings 1, 2 and 3 above. That transcript is in `build.log`; the summary line was
`BUILD FAILED in 16m 40s` / `1066 actionable tasks: 941 executed, 125 from cache`.

## The GL run

`udea-render` gains a public API and `moba:desktop` gains a proof that opens a context, so a green
`build` says nothing about the GL half: without `$DISPLAY`, `udeaGlTest`, `udeaAgentGlTest` and
`udeaEditorGlTest` **skip**, and the build stays green having tested none of it.

```sh
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
      ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest -Pudea.render.requireGl=true \
  --no-build-cache --no-configuration-cache --max-workers=4
```

**`--no-build-cache` is there because the run without it was a false green.** After the `clean
build`, this command came back `BUILD SUCCESSFUL in 6s`, `9 executed, 5 from cache` — and the JUnit
XML it left behind was stamped `00:58`, from a run twenty-four minutes earlier, restored wholesale
from the build cache. A cache-restored test task is indistinguishable from an executed one on the
console, which is the same shape as the skip-without-`$DISPLAY` trap one level up. So the result
directories were deleted, the cache was turned off, and the run below took **3m 13s** and wrote XML
stamped `01:22:36` to `01:25:34` against a wall clock of `01:26:00`.

**Counted out of the JUnit XML, not off the console**, because `BUILD SUCCESSFUL` on a test task is
fully compatible with zero tests having run:

```
BUILD SUCCESSFUL in 3m 13s

udea-agent-host/build/test-results/udeaAgentGlTest           2 files  tests=2 skipped=0 failures=0 errors=0
udea-editor/build/test-results/udeaEditorGlTest              6 files  tests=6 skipped=0 failures=0 errors=0
udea-render/build/test-results/udeaGlTest                    28 files  tests=29 skipped=0 failures=0 errors=0
TOTAL tests=37 skipped=0 failures=0 errors=0
```

## Images

All five are in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`.

| File | What it shows | What it proves |
|---|---|---|
| `issue269-1-no-effect.png` | Two crates on a lit ground plane, no post-process. | The control frame. Captured twice and required to differ by **exactly 0** levels per channel, so every figure after it is the shader and not the renderer's noise. |
| `issue269-2-scanlines-quarter.png` | The same frame with the scanline effect at `uStrength = 0.25`. | GLSL that the build read out of `moba/game/assets/shaders/scanlines.frag`, packed into `assets.udeapak`, and handed to the GPU through the ordinary asset reader, changed the picture. 166 rows moved — every row of one parity that had colour in it. |
| `issue269-3-scanlines-half.png` | The same, at `uStrength = 0.5`. | The uniform declared in Kotlin reached the program compiled from the asset's text: the frame moved 1.995x as far, and a dimmed channel came back at 0.5016 of its brightness against a predicted 0.5. |
| `issue269-4-off-again.png` | The effect switched off. | Byte-identical to the first frame — 0.0 levels per channel — so the effect is something the pass does rather than something the capture path acquired. |
| `issue269-5-sequence.png` | The four above, tiled and labelled. | The sequence at a glance: clean, quarter, half, clean again. |

## The issue, criterion by criterion

| # | Criterion | Proved by |
|---|---|---|
| **1** | A `.frag` declared in a `.udea.kts` produces a generated accessor, and `UdeaShader.fragment(<accessor>) { … }` compiles and runs **from `commonMain`** with no `javaClass`, no `java.*`/`javax.*` import, and no per-target duplication | `moba/game/src/commonMain/.../MobaScreenEffects.kt` compiles into every target `moba:game` builds for; **M1** (delete the DSL entry → that file stops compiling, `Unresolved reference 'shaders'`) and **M4** (delete the overload → same file, `Ref<Shader>` vs `String`) both prove the accessor form is what it uses. **A1** and **A2** are clean with the control at 4 lines. `MobaShaderAssetTest.the common source that builds the shader names nothing JVM-only` re-runs the scan on every build **with its own positive control inside it**. `ShaderSourceReadTest` is in `udea-assets`' **`commonTest`** and therefore compiled for wasmJs and both iOS targets. "Runs" is the proof transcript and the four PNGs |
| **2** | The GLSL reaches the GPU through the existing asset path — the same packing and the same reader every other file-backed asset uses. **No new loader** | The proof's first line reads the shader out of `MobaAssets.registry` with `reference<Shader>(…)` — the same `BundleReader` the sprites use — and asserts the packed `file` and the presence of the game's own `uStrength` in the packed text before anything is drawn. **M2** (stop filling `source`) fails `udeaValidateAssets` with exactly 1 diagnostic. No file is opened anywhere in the runtime path: the design section says what was checked before choosing this, and there is no new reader in the diff |
| **3** | A misspelled or missing shader is a **build** failure with a `UdeaDiagnostic`, a rule id, a source span and a did-you-mean — not a null at first draw. Its own id in `UDEA0018`'s family | `UDEA0041`, `UdeaRules.SHADER_SOURCE`. `ShaderAssetTest` has nine cases: a clean one, and refusals for a misspelled file (with `Did you mean 'shaders/scanlines.frag'?` and a span ending `shaders/shaders.udea.kts` at line ≥ 1), a `#version`, a `#version` in a comment, no `udeaMain`, an empty file, a wrong extension, and a build-tool defect. **M3** removes the validator and exactly the predicted five go red. **M5** is the end-to-end form: a real `#version` in the real game's real `.frag` fails `:moba:game:udeaValidateAssets` with one diagnostic naming the declaring line |
| **4** | `docs/new-game.md` and `templates/new-game/` show the accessor form; the `path`/`source` overload **stays**, and its KDoc says which case it is for | Both documents rewritten around the accessor form; **A3**, run properly, finds the token in **0 fenced code blocks** of either and 1 prose mention in each, naming it as the thing not to do. The overload is still there — **M4** deletes it and 14 compile errors follow — and its KDoc opens *"the form for GLSL a game made up at run time"* |
| **5** | The spec gets the asset decision written into it, in "Assets, editing and errors" and as a row in the ticket table | `docs/superpowers/specs/2026-09-20-shader-api-design.md`: the "discovered by KSP" line replaced, the `source = "shaders/palette.frag"` sketch corrected to the accessor form, the errors bullet extended with `UDEA0041`, and an **S1a** row added to the ticket table |

## Regenerated files

- **`udea-codegen/net-protocol.lock`** — **not** regenerated, and correctly so. No replicated
  component was added; `proto` reads `0x07b6` in the replay header both before and after.
- **`udea-codegen/src/test/resources/expected-generated-hashes.txt`** — **not** regenerated, same
  reason. `udeaCheckProtocolLock` runs on `check` and is green.
- **`moba/desktop/src/test/resources/fixtures/moba-3600.udearep` and `moba-36000.udearep`** —
  regenerated with `:moba:desktop:test -Dupdate.replay.fixtures=true`, twice (the second time because
  a comment edit inside the `.frag` moves the packed text). Byte-level diff in finding 3: 35 bytes in
  each, being the 32-byte asset-graph hash and a 4-byte trailer, and no recorded input.
