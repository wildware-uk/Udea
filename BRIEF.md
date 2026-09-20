# BRIEF — #266 (and #259): screen-effect shaders a game writes in GLSL

d1b633f

Every figure in this document was measured at that SHA. The branch tip is one commit later and
adds this document and nothing else.

Branch `issue-266-screen-shader`, worktree
`/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a5832e6b1af778f5d`, merged with `origin/master`
at `a549816` (#262). Ticket S1 of the shader design: the screen-effect shader. Materials (S2),
object shaders (S3), the editor inspector (S4) and compute (S5) are not mine and are not here.

---

## 1. The evidence command

```
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew :moba:desktop:runShaderProof \
  --no-configuration-cache --max-workers=3 --console=plain
```

It lives in `:moba:desktop` on purpose. #266's first criterion is *"a game outside this repository
registers a post-process pass, with no Kool type in its source"*. `udea-render`'s own test source
set has Kool on its classpath, so a test there could only ever **grep** for the absence of a Kool
import. `:moba:desktop` is not in `ModuleGraphRules.GL_ALLOWED_PROJECTS`, so `UDEA-MG-002` refuses
`de.fabmax.kool:*` on that project outright — which makes "no Kool type in the game's source" a
build gate rather than a claim. `ShaderProof.kt` could not compile if it named one.

Transcript of the run at this SHA, spliced from
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/final-proof.log`:

```
shader-proof: control: two unprocessed captures of the paused scene differ by 0.0 levels/channel
shader-proof: control: 99% of the unprocessed frame is NOT a palette colour
shader-proof: palette: 0% off-palette, frame moved 13.255876 levels/channel
shader-proof: outline: 469 pixels darkened around the boxes, 0 on open ground
shader-proof: both: differs from the palette alone by 0.36317998 and from the outline alone by 13.19968 levels/channel
shader-proof: off again: the frame differs from the first unprocessed capture by 0.0 levels/channel
shader-proof: refusal: a game-written #version is refused - shader 'shaders/versioned.frag' states its own #version. The engine prepends the version and the precision qualifiers the backend needs, which differ between OpenGL and OpenGL ES; a shader that states its own works on one and fails on the other. Delete the #version line and write the body alone.
shader-proof: all checks passed; pictures in /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a5832e6b1af778f5d/moba/desktop/build/reports/udea/shader
```

### Both sides of every threshold

The lead asked for a demonstrated failing case on both sides, not one number that passes.

| Figure | Passing run | The other side, demonstrated |
|---|---|---|
| Two unprocessed captures differ | `0.0` levels/channel | **not** demonstrated as a failing case at this SHA; it *did* fail earlier at `116.07` levels/channel, before `settle` existed — the opening frames of a fresh context are not the scene |
| Palette moves the frame | `13.255876`, against a control of `0.0` | mutation 1 below: `0.0` |
| Frame is made of palette colours | `0%` off-palette, against `99%` unprocessed | mutation 1: `99%` |
| Outline darkens pixels around the boxes | `469` | mutation 2: `0` |
| Outline darkens open ground | `0` | asserted `== 0` on every green run; the palette-only frame darkens `0` there too |
| `uDepth` separates two distances | `0.015181172` vs `0.017600346` | mutation 3: `0.0` vs `0.0` |
| Each uniform kind reaches the GPU | five changes, five different frames | mutation 4: the `vec2` step alone goes red |

## 2. Proof it goes red — three mutations, with their literal diffs

Each diff is `git diff --unified=1` taken from the run that produced the failure beside it. The
control is the run above: unmutated, both proofs green.

### Mutation 1 — the chain never runs

```diff
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/GlScreenPasses.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/GlScreenPasses.kt
@@ -162,3 +162,3 @@ internal class GlScreenPasses(
         val enabled = programs.filter { it.shader.enabled }
-        if (enabled.isEmpty()) return
+        if (enabled.isEmpty() || true) return
```

`:moba:desktop:runShaderProof` → `EXIT=1`
(`scratchpad/mutation1.log`):

```
shader-proof: palette: 99% off-palette, frame moved 0.0 levels/channel
Exception in thread "main" java.lang.IllegalArgumentException: 99% of the paletted frame is not one of the 4 palette colours
```

### Mutation 2 — the object mask goes back to the shape that was broken

This is the actual defect I found and fixed, restored exactly: the mask published as a depth-only
attachment, which this backend gives a screen shader nothing to sample from.

```diff
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
@@ -188,3 +188,3 @@ internal class ModelStage(
         // Read through lambdas: Kool replaces both textures when the frame is resized (#234).
-        passes.setScreenInputs({ depthCopy.depthCopy2d }, { maskPass.colorTexture })
+        passes.setScreenInputs({ depthCopy.depthCopy2d }, { maskPass.depthTexture })
```

`:moba:desktop:runShaderProof` → `EXIT=1` (`scratchpad/mutation2.log`):

```
shader-proof: palette: 0% off-palette, frame moved 13.255876 levels/channel
shader-proof: outline: 0 pixels darkened around the boxes, 0 on open ground
Exception in thread "main" java.lang.IllegalArgumentException: the outline darkened nothing around the boxes: the mask is empty or the effect did not run
```

Note what this one proves about the *other* figures: the palette is still worth exactly
`13.255876`. The mutation moved the mask and nothing else, and only the mask's assertion went red.

### Mutation 3 — no depth input

The depth criterion is proved by `GlScreenShaderTest`, not by the proof main, so this one is
measured there.

```diff
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
@@ -188,3 +188,3 @@ internal class ModelStage(
         // Read through lambdas: Kool replaces both textures when the frame is resized (#234).
-        passes.setScreenInputs({ depthCopy.depthCopy2d }, { maskPass.colorTexture })
+        passes.setScreenInputs({ null }, { maskPass.colorTexture })
```

`:udea-render:udeaGlTest --tests '…GlScreenShaderTest'` → `EXIT=1`; from the saved JUnit XML
(`scratchpad/mutation3.xml`):

```
screen-shader: uDepth ranges from 0.0 to 0.0 across the frame
screen-shader: uDepth over the box 0.0, over open ground 0.0
org.opentest4j.AssertionFailedError: the box reads 0.0 and the open ground reads 0.0 in uDepth, a difference of less than 5.0E-4. Two surfaces at different distances read the same, so uDepth is not carrying the scene's depth. See screen-depth.png
```

### Mutation 4 — one uniform kind stops being uploaded

The ticket asks for *typed* uniforms, and a shader that merely compiles proves none of them
arrived. Scenario 7 of `GlScreenShaderTest` declares all five kinds in one shader, reads all five
in its body, and changes them **one at a time**, requiring the frame to change with each. This
mutation neutralises the `vec2` upload and nothing else.

```diff
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/GlScreenProgram.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/GlScreenProgram.kt
@@ -122,3 +122,3 @@ internal class GlScreenProgram private constructor(
             override fun write() {
-                gl.uniform2f(location, uniform.x, uniform.y)
+                gl.uniform2f(location, 0f, 0f)
             }
```

`:udea-render:udeaGlTest --tests '…GlScreenShaderTest'` → `EXIT=1` (`scratchpad/mutation4.xml`):

```
org.opentest4j.AssertionFailedError: changing the vec2 uShift uniform left the frame exactly as it was, so that kind is not reaching the shader
```

Exactly one step went red, and it named the kind. That is what makes the other four steps worth
reading: the mutation could have turned the whole scenario red and told you nothing.

`vec2` was also the one declared kind nothing else used — a `public` declaration with no caller is
on the reject list, and this closes it with a test rather than by deleting the ticket's "vector".

## 3. What I did, what I decided, and what I got wrong

### The shape

- **`UdeaShader.fragment(path, source) { … }`** takes GLSL the game authored and uniforms declared
  in Kotlin: `float`, `int`, `vec2`, `color` and `texture`. A shader is text somebody wrote; the
  per-backend header is a **prepended constant**, not a generator. Nothing inspects or rewrites the
  body.
- **`RenderRegistry.screenPass(shader)`** is the ordered list. It runs at render resolution, before
  any upscale, inside the captured frame (so `render.screenshot` sees it) and on the capturable
  pass only — a `UiLayer` and the editor's gizmos are drawn elsewhere and are untouched.
- **Engine inputs:** `uColor`, `uDepth`, `uMask`, `uResolution`, `uTexel`, `uTime`, plus
  `udeaMasked(uv)` and `udeaOutline(uv, width)`.
- **`uTime` is render seconds.** It is accumulated from the render `FrameTime`; simulation time is
  not reachable from `udea-render`'s screen chain by construction.
- **Built-ins:** `ScreenEffects.palette(colours)` and `ScreenEffects.outline(colour, width)`.
- **`ScreenShaderException`** carries the rule id, the author's `.frag` path, the author's line and
  the driver's message verbatim.
- **No Kool type in the public API.** `RenderModuleGraphTest` and `UDEA-MG-002` on `:moba:desktop`
  are the gates.

### Decisions (also on the issue, `#266#issuecomment-5752712958`)

1. **The mask is a colour pass read through alpha, not a depth-only pass.** Rejected: keeping the
   `DepthMapPass` and sampling it as `sampler2DShadow` (answers a comparison, not a value, and puts
   a second sampler type in a public header for one backend); a stencil buffer (not on Kool's
   `GlApi` surface, and not sampleable without a copy). **Cost:** a marked model is shaded twice.
   **To reverse:** one line in `ModelStage.init` plus the `udeaMasked` body — `udeaMasked` is the
   only thing that reads `uMask`.
2. **`uDepth` is documented as the backend's own direction, not "1.0 is far".** Measured on
   llvmpipe: `0` is the far plane and a nearer surface is a larger number (`near / distance`). The
   assertion is that two distances read *differently*, not which way round, so it survives a
   backend that reverses it back.
3. **`ScreenShaderException` carries plain fields, not a `UdeaDiagnostic`.** The lead overruled my
   first choice here and was right: `udea-render` ships inside every game, so a public
   `UdeaDiagnostic` on it would put `udea-diagnostics` on every shipped game's runtime classpath
   forever. `ScreenShaderRuleIdTest` pins the string literals against `UdeaRules` so the two cannot
   drift.
4. **The built-in effects' GLSL is Kotlin raw strings, not `.frag` resources.** `udea-render` is
   KMP across `jvm`, `android` and `wasmJs`, and a resource loaded per target is three packaging
   problems for two shaders. A *game's* shader is the text it hands us, from wherever it likes.
5. **`udeaOutline(uv, width)`, not the spec's `udeaOutline(uMask, uv, 1.0)`.** The helper reads
   `uMask` and `uTexel`, both of which the engine owns; passing the sampler in would let a body
   pass a different one and get a silently meaningless answer.

### Rule id bands — a finding worth keeping

`UDEA0020–0029` is reserved for `AssetCompilerRules` and `UDEA0030–0039` for
`AssetValidationRules`, and `ModuleContractTest` fails if `UdeaRules` grows into either. So the
second new rule is **`UDEA0040`**, not `UDEA0020`. The reason is written into `UdeaRulesTest`
beside the assertion, because the next person to add a rule will reach for the next free number.

### What I got wrong

- **A test of mine passed by luck, and I caught it by re-running it unchanged.**
  `UdeaShader.enabled` is written from the game thread and read on the render thread.
  `GlScreenShaderTest` passed one run and failed the next with the production code untouched: the
  capture after a toggle is sometimes the frame that was already in flight. `enabled` is now
  `@Volatile` (visibility), and both proofs wait for the picture to settle after every toggle
  (timing). Volatile cannot fix the timing and does not claim to — the renderer is decoupled by
  design — so it is documented on `enabled`, because anyone offering an effect as a graphics
  setting and screenshotting the result meets the same thing.
- **The mask was empty for four GL runs and I chased the wrong cause twice.** First I blamed the
  test fixture, then the depth convention. The cause was that a `DepthMapPass` publishes nothing a
  screen shader can sample on this backend, so `resolve()` fell back to the stand-in texture every
  frame. Mutation 2 restores that exact shape.
- **I documented `uDepth` backwards** in `docs/new-game.md` ("`0` at the near plane, `1` where
  nothing was drawn") before measuring it. It is the other way round. Fixed in `cc6b51b`, and the
  guide now says the direction is the backend's and points you at `uMask` for "is anything here".

### What I did not exercise

- **iOS and Android.** `udea-render` has no iOS target; Android has no Kool backend. The GLES half
  of the header is written from Kool's own `GlslGeneratorHints` and is **not** exercised here.
- **A real GPU.** Everything below is llvmpipe. See section 4.
- **Resize.** `GlScreenPasses` rebuilds its intermediate targets when `frameWidth`/`frameHeight`
  change and reads both engine inputs through lambdas for that reason, but no test drags a divider
  with a screen pass installed.
- **More than two passes in the chain**, and a pass whose only enabled member is the last one. The
  ping-pong indexing is exercised at one and two.
- **A texture uniform whose `SpriteTexture` is still loading.** `textureOf` answers `NO_TEXTURE`
  for it deliberately; no test holds a half-loaded texture.

### Contracts

**No file in `docs/contracts/` was changed.** The diagnostics contract says K2 checkers and the
asset validator emit the same rule ids; this adds two ids to `UdeaRules` and to `UdeaRulesTest`,
which is a registry entry and not a contract edit. `udeaVerifyContracts` ran in the full build above and reported `UP-TO-DATE` — its inputs, the
files under `docs/contracts/`, are byte-identical to `origin/master`'s.

### Files that are `dev-262`'s

`model/ModelView.kt`, `view/PickBounds.kt`, the camera rigs, `PointerState` and the intents are
**untouched** by this branch. `ModelStage.kt` and `ModelRenderer.kt` are mine; `origin/master` at
`a549816` merged cleanly into this branch with no conflict in any file.

---

## 4. What is proved on llvmpipe, and what would need hardware

llvmpipe's own report, from `scratchpad/final-proof.log`:

```
   0.413 s|f:   0  I  GlImpl: Detected OpenGL version 4.5
   0.422 s|f:   0  D  GlImpl: Compute shader support available. Max workgroups: (65535, 65535, 65535), max workgroup size: (1024, 1024, 1024), max invocations per workgroup: 1024
   0.423 s|f:   0  D  RenderBackendGlImpl: Setting depth range to zero-to-one
```

**Proved here, and backend-independent:** the public surface and its refusals (a `#version` in a
game's body, a missing `udeaMain`, an absolute path, a uniform name that clashes with an engine
one, a repeated name), the rule ids, the ordered list, and every Kotlin-side argument check. Those
are unit tests and do not touch a driver.

**Proved here, on this driver only:** that the chain compiles, links, uploads uniforms and
textures, ping-pongs two intermediate targets, blits the frame in and out, saves and restores GL
state, and that a driver's line number maps back to the author's line. A different driver could in
principle refuse source this one accepts.

**Not proved here, and it would need hardware or another platform:**

- **Multi-sampling.** Kool's GL backend logs, on both the picture pass and the mask pass:
  `OffscreenPass2d udea-models requests a sample count of 4 but multi-sampling is not yet
  implemented in OpenGL backend. Falling back to single-sample.` So the mask and the picture are
  single-sampled in every run here. On a backend that does implement it, `colorTexture` is the
  resolved texture and the chain reads it the same way, but that path has not been executed.
- **The GLES header.** No Android or iOS run; the precision block is Kool's, qualifier for
  qualifier, and that is the argument, not a test.
- **The reversed depth direction.** `0` is far *on this backend*. A backend that does it the other
  way would still pass the GL test, which asserts a difference and not a direction — that is why it
  is written that way.
- **Driver message formats.** `GlScreenProgram.diagnose` parses Mesa's `0:23(9)` and NVIDIA's
  `0(23)`. Only the Mesa form has been seen by a test here:
  `0:42(2): error: initializer of type float cannot be assigned to variable of type vec3`.

---

## 5. `sh gradlew build` — real output

Merged tree, SHA `00f9506`, no exclusions, from
`scratchpad/final-build.log`:

```
BUILD SUCCESSFUL in 1m 24s
1122 actionable tasks: 29 executed, 3 from cache, 1090 up-to-date
EXIT=0
```

The task count is low because this is the last of several full `build` runs on the same daemon and
worktree; the run before it, at `00f9506`, executed 153 tasks and was also `EXIT=0`. Nothing was
excluded in either: the command is the one above, verbatim, with no `-x`.

`:udea-assets-compiler:udeaDaemonBudget` does **not** appear in that log and was not run: it is
registered as its own task and excluded from `test`, so it is not on `check`. I did not run it
separately either — nothing on this branch touches the asset daemon. Grep for it in
`final-build.log` and you get zero hits, which is what I am reporting rather than a pass.

### The GL run, for real, under xvfb

```
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest \
  -Pudea.render.requireGl=true \
  --continue --no-configuration-cache --max-workers=4 --console=plain
```

At `d1b633f`:

```
> Task :udea-render:udeaGlTest
> Task :udea-agent-host:udeaAgentGlTest FROM-CACHE
> Task :udea-editor:udeaEditorGlTest FROM-CACHE
BUILD SUCCESSFUL in 1m 47s
126 actionable tasks: 10 executed, 2 from cache, 114 up-to-date
EXIT=0
```

**Two of the three came from the build cache here**, because the only thing that changed since the
previous run is a file in `udea-render`. That previous run, at `00f9506`, executed all three for
real and is the one to read for the other two modules:

```
> Task :udea-render:udeaGlTest
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-editor:udeaEditorGlTest
BUILD SUCCESSFUL in 3m 2s
126 actionable tasks: 12 executed, 114 up-to-date
EXIT=0
```

Counted out of the JUnit XML in
`*/build/test-results/<task>/TEST-*.xml`:

| Task | tests | skipped | failures | errors |
|---|---|---|---|---|
| `udeaGlTest` | 29 | 0 | 0 | 0 |
| `udeaAgentGlTest` | 2 | 0 | 0 | 0 |
| `udeaEditorGlTest` | 6 | 0 | 0 | 0 |
| **total** | **37** | **0** | **0** | **0** |

`skipped=0` is the number that matters: with `$DISPLAY` empty and `requireGl` defaulting to false
these tasks skip silently, and a green `build` says nothing about GL.

---

## 6. The images

All in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`.

**From `:moba:desktop:runShaderProof` at `00f9506`** (the evidence command, 640×360):

| File | What it shows, and what it proves |
|---|---|
| `issue266-1-unprocessed.png` | ground and two crates, no effect. The baseline every figure is measured against; `99%` of it is off-palette, so the palette check below can fail |
| `issue266-2-palette.png` | the same frame in four colours. `0%` off-palette, moved `13.255876` levels/channel against a control of `0.0` |
| `issue266-3-outline.png` | a thin dark line round **both crates and not the ground**. `469` pixels darkened around the crates, `0` on open ground — the outline came from the mask, not from brightness |
| `issue266-4-palette-and-outline.png` | both, in the order the game registered them: the outline in its own colour over an already-quantised picture |
| `issue266-5-effects-off-again.png` | effects off. Differs from `issue266-1` by `0.0` — the chain leaves no residue and restores GL state |
| `issue266-proof-sequence.png` | the five above tiled, in order |

**From `:udea-render:udeaGlTest --tests '…GlScreenShaderTest'` at `00f9506`** (320×240):

| File | What it shows, and what it proves |
|---|---|
| `issue266-gltest-plain.png` | grey ground, one sand box, its shadow. Unprocessed |
| `issue266-gltest-mask.png` | white box on black — `udeaMasked` drawn straight out. The **object/mask input**, #266's second criterion, in one picture: the box is in it and the ground is not |
| `issue266-gltest-depth.png` | the decoded depth, stretched between the frame's own nearest and furthest. The **depth input**: near ground white at the bottom, the box mid-grey, sky black at `0`. Stretched from the frame rather than by a chosen gain, so no constant here was picked to make it look right |
| `issue266-gltest-palette.png` | three-colour quantisation over the whole frame |
| `issue266-gltest-outline.png` | the box outlined, the ground not |
| `issue266-gltest-palette-outline.png` | both |
| `issue266-gltest-sequence.png` | the six above tiled |

Scenario 7, the uniform-kind walk, deliberately saves no picture. Its frames are a probe rather
than a style - a quantised, shifted, tinted, ramp-multiplied picture that nobody would ship - and
what it asserts is that consecutive frames *differ*, which a still image cannot show. Mutation 4 is
its evidence instead.

One thing to look at rather than measure, and it is not a defect: in the paletted frames the
brightest corner of the ground snaps to the lightest palette colour, which reads as a sand-coloured
wedge at the top right. That is a three- and four-colour palette doing its job on a lit gradient —
the unprocessed ground runs from 128 to 154 levels across the frame, and 154 is nearer the light
entry than the mid one.

---

## 7. The issue, criterion by criterion

### #266

| Criterion | Proof |
|---|---|
| A game registers a post-process pass **with no Kool type in its source**, and its output differs from the unprocessed frame | `ShaderProof.kt` in `:moba:desktop`, where `UDEA-MG-002` bans `de.fabmax.kool:*` outright, so this is a build gate. `palette: 0% off-palette, frame moved 13.255876 levels/channel` against a control of `0.0`. `issue266-1-unprocessed.png` → `issue266-2-palette.png`. Mutation 1 makes it `0.0` |
| The engine supplies that pass with **depth and an object/mask input**, so an outline can be drawn without rendering the scene twice | `issue266-gltest-mask.png` and `issue266-gltest-depth.png`. `uDepth ranges from 0.0 to 0.022083813`, box `0.015181172` vs ground `0.017600346`. The mask is one extra pass over the marked models only — the scene is not redrawn. Mutations 2 and 3 |
| The **per-backend header** is prepended by the engine; a game's shader states no `#version` | `ScreenShaderSource.fragment` joins `version + preamble + body + footer`, the version read from Kool's `GlslGenerator.Hints`. A body that states its own is refused: `shader 'shaders/versioned.frag' states its own #version…`. `ScreenShaderRuleIdTest` also pins the **known negative**, and it is not the one you would guess: a `#version` inside a *comment* is refused too, deliberately, because it is one line away from being uncommented. The test says which way round the fence points rather than leaving it to be inferred |
| **Typed uniforms declared in Kotlin** — float, int, colour, vector, texture handle | Scenario 7 of `GlScreenShaderTest`: one shader declaring all five, each changed on its own, each required to change the frame. Mutation 4 turns exactly the `vec2` step red |
| An **ordered pass list** a game configures | `registry.screenPass(palette); registry.screenPass(outline)` in `ShaderProof`, and `both: differs from the palette alone by 0.36317998 and from the outline alone by 13.19968` — neither alone is the answer. `issue266-4-palette-and-outline.png` |
| Passes run **at render resolution, before any upscale**, and are **excluded from the editor's gizmo capture** | The chain is a view on the capturable pass at `frameWidth`/`frameHeight`, added by `addOnTop`; gizmos are drawn in `udea-editor`'s own Scene view, which is a different pass. `udeaEditorGlTest`: 6 tests, 0 failures, unchanged by this branch |
| **Compile failures are `UdeaDiagnostic`s** — rule id, file, line, driver's message; not a stack trace | Shape changed on the lead's instruction: plain fields carrying the same four things, so `udea-diagnostics` stays off every shipped game's classpath. `UDEA0019` at `shaders/broken.frag:3`, message `0:42(2): error: initializer of type float cannot be assigned to variable of type vec3`. `ScreenShaderRuleIdTest` pins both literals against `UdeaRules` |

### #259

| Criterion | Proof |
|---|---|
| A **palette pass** ships as a built-in | `ScreenEffects.palette(colours)`. `issue266-2-palette.png`, `0%` off-palette |
| An **outline pass** ships as a built-in | `ScreenEffects.outline(colour, width)`. `issue266-3-outline.png` |
| **A model on the ground gets a 1-pixel outline, and the ground itself does not** | `469` pixels darkened around the crates, **`0`** on open ground, asserted both ways. `issue266-3-outline.png`, and the same in `issue266-gltest-outline.png` for a single box |

---

## 8. Regenerated files

**None.** No replicated component was added or removed, so neither
`udea-codegen/net-protocol.lock` nor `udea-codegen/src/test/resources/expected-generated-hashes.txt`
moved, and no id shifted. `udeaCheckProtocolLock` runs on `check` and is green in the full build
above — which is the evidence, rather than my say-so.

Two rule ids were added to `UdeaRules` (`UDEA0019`, `UDEA0040`) and pinned in `UdeaRulesTest`,
which asserts `UdeaRules.all.size == 20`. That is a hand-written registry, not a generated file.
