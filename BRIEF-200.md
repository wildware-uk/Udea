39a5e33

# BRIEF-200 — Spike: Kool renders Offscreen under xvfb and reads a frame back to PNG

Branch `issue-200-kool-offscreen-spike`, off `origin/kmp` at `6097ae7`. The SHA above is the last
commit of the change; this brief is committed on top of it.

**The answer is yes.** Kool 0.19.0 on its OpenGL backend, on llvmpipe under xvfb, opens a hidden
window, draws a textured quad into an `OffscreenPass2d`, reads it back and writes a PNG that
matches the picture it drew. It took two workarounds and one fact about row order, below. Vulkan on
lavapipe also works and gives a byte-identical PNG.

## 1. Evidence command

```
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew -p spikes/kool-offscreen run
```

Run from the worktree root, with `WAYLAND_DISPLAY` unset (it is unset on this box). The program
is its own assertion: it exits 0 only if the read-back matches `ExpectedFrame`, so `run` fails the
Gradle build otherwise. It writes `spikes/kool-offscreen/build/spike/kool-offscreen-quad.png`.

Green, from `spikes/kool-offscreen/transcripts/final-gl.log` (lines 1, 21, 142-144, 151, 153; the
first line and the last are written by my wrapper script, not by Gradle):

```
HEAD=f05a9f1 dirty=0
[...]
spike: GLFW pre-initialised on X11 (DISPLAY=:99)
[...]
spike: backend=OpenGL device=llvmpipe (LLVM 20.1.2, 256 bits) window-visible=false
spike: wrote /srv/ssd1/workspace/Udea/.claude/worktrees/agent-ac814346642cc446c/spikes/kool-offscreen/build/spike/kool-offscreen-quad.png
spike: PASS - the textured quad's known pixels are in the read-back
[...]
BUILD SUCCESSFUL in 10s
[...]
exit=0
```

### It goes red when the feature is reverted

Each mutation is the literal `git diff` saved beside its log in `spikes/kool-offscreen/transcripts/`.
M1, M2 and M4 each ran the evidence command above on a worktree at `f05a9f1` plus that one diff; M3 ran the unit tests.

**M1, skip the draw** (`mutation1-skip-draw.diff`):

```
[... diff and index headers ...]
@@ -100,7 +100,6 @@ fun main() {
 private fun offscreenPass(): OffscreenPass2d {
     val bg = ExpectedFrame.BACKGROUND
     val drawNode = Node("spike-draw-node")
-    drawNode.addTexturedQuad()
     val pass = OffscreenPass2d(
         drawNode = drawNode,
         attachmentConfig = AttachmentConfig {
```

`mutation1-skip-draw.log` lines 143, 162, 164:

```
spike: FAIL - frame is a single colour: rgb(255,0,255)
[...]
BUILD FAILED in 4s
[...]
exit=1
```

**M2, no row flip on OpenGL** (`mutation2-no-gl-flip.diff`):

```
[... diff and index headers ...]
@@ -76,7 +76,7 @@ fun main() {
             val copy = pass.copyOutput(isCopyColor = true, isCopyDepth = false, isSingleShot = false).colorCopy2d
             addScene { addOffscreenPass(pass) }
             delayFrames(FRAMES_BEFORE_READ_BACK)
-            val frame = readBack(copy, bottomRowFirst = ctx.backend is RenderBackendGl)
+            val frame = readBack(copy, bottomRowFirst = false)
             writePng(frame, out)
             println("spike: wrote ${out.absolutePath}")
             val mismatches = ExpectedFrame.mismatches(frame)
```

`mutation2-no-gl-flip.log` lines 143-146, 165, 167:

```
spike: FAIL - quad top-left: 1024 pixels differ from rgb(255,0,0), first (80,80) is rgb(0,0,255)
spike: FAIL - quad top-right: 1024 pixels differ from rgb(0,255,0), first (144,80) is rgb(255,255,255)
spike: FAIL - quad bottom-left: 1024 pixels differ from rgb(0,0,255), first (80,144) is rgb(255,0,0)
spike: FAIL - quad bottom-right: 1024 pixels differ from rgb(255,255,255), first (144,144) is rgb(0,255,0)
[...]
BUILD FAILED in 5s
[...]
exit=1
```

**M4, no X11 pre-init** (`mutation4-no-x11-preinit.diff`):

```
[... diff and index headers ...]
@@ -50,7 +50,7 @@ fun main() {
         else -> error("spike.backend must be gl or vk, was $backendName")
     }
     startWatchdog()
-    initGlfwOnX11WhenNoWayland()
+    // initGlfwOnX11WhenNoWayland()
 
     val config = KoolConfigJvm(
         renderBackend = backend,
```

`mutation4-no-x11-preinit.log` lines 21, 38, 68, 70:

```
	Description : Wayland: Failed to connect to display
[...]
Exception in thread "main" java.lang.IllegalStateException: Unable to initialize GLFW
[...]
BUILD FAILED in 3s
[...]
exit=1
```

**The check itself** has unit tests, `FrameCheckTest`, run with
`sh gradlew -p spikes/kool-offscreen test` (6 tests, green at `39a5e33`). **M3** makes the checker
ignore the quad (`mutation3-check-ignores-quad.diff`):

```
[... diff and index headers ...]
@@ -67,10 +67,10 @@ internal object ExpectedFrame {
             add(Block("background top-right", SIZE - cell, 0, BACKGROUND))
             add(Block("background bottom-left", 0, SIZE - cell, BACKGROUND))
             add(Block("background bottom-right", SIZE - cell, SIZE - cell, BACKGROUND))
-            add(Block("quad top-left", QUAD_MIN, QUAD_MIN, TOP_LEFT))
-            add(Block("quad top-right", QUAD_MIN + cell, QUAD_MIN, TOP_RIGHT))
-            add(Block("quad bottom-left", QUAD_MIN, QUAD_MIN + cell, BOTTOM_LEFT))
-            add(Block("quad bottom-right", QUAD_MIN + cell, QUAD_MIN + cell, BOTTOM_RIGHT))
+            if (false) add(Block("quad top-left", QUAD_MIN, QUAD_MIN, TOP_LEFT))
+            if (false) add(Block("quad top-right", QUAD_MIN + cell, QUAD_MIN, TOP_RIGHT))
+            if (false) add(Block("quad bottom-left", QUAD_MIN, QUAD_MIN + cell, BOTTOM_LEFT))
+            if (false) add(Block("quad bottom-right", QUAD_MIN + cell, QUAD_MIN + cell, BOTTOM_RIGHT))
         }
         return blocks.mapNotNull { block ->
             var wrong = 0
```

`mutation3-check-ignores-quad.log` lines 26, 29, 32, 35:

```
FrameCheckTest > a cleared frame with no quad drawn fails on all four quadrants() FAILED
[...]
FrameCheckTest > a read-back upside down fails() FAILED
[...]
FrameCheckTest > a texture mirrored left to right fails() FAILED
[...]
6 tests completed, 3 failed
```

The TDD order in practice: `ExpectedFrame` and the program's exit path were written first, with an
`OffscreenPass2d` that had no quad in it. That run failed with `frame is a single colour:
rgb(255,0,255)`. The quad came next and failed with every quadrant swapped top-for-bottom, which is
where the row-order finding came from. Those two runs were on code that was never committed, so
their logs are not kept. M1 and M2 recreate the same two states on the committed code.

## 2. Summary

A standalone Gradle build at `spikes/kool-offscreen/` with its own `settings.gradle.kts`. The root
build does not include it, so it adds no module, leaves `AGENTS.md` and `udeaVerifyAgentsMd`
alone, and cannot turn a root task red. Kool `0.19.0` is declared in the spike's
`build.gradle.kts`: it was `<latest>`/`<release>` in
`repo1.maven.org/maven2/de/fabmax/kool/kool-core/maven-metadata.xml` on 2026-09-16. It is not in
`gradle/libs.versions.toml`, because #211 chooses the real version.

The program sets up a 256x256 `OffscreenPass2d` cleared to magenta, with an orthographic camera
at one world unit per pixel. It draws a quad over the middle half, textured by a 2x2
nearest-filtered texture (red and green on top, blue and white below) through Kool's own
`KslUnlitShader`. After 5 frames it downloads the pixels on `KoolDispatchers.Backend`, writes a
PNG, and checks eight blocks: the four background corners and the four quadrant centres, each
inset from its edges. Every channel is 0 or 255, so no sRGB/linear step can move a value. A
missing draw, a flipped read-back and a mirrored texture each change a checked pixel, and
`FrameCheckTest` pins all three. A daemon watchdog halts with exit 3 after 120s, so a render loop
that never produces a frame still ends red.

**What it took.** These are the parts the issue asks for (window hints and backend flags). Two
decision comments on the issue give the reasoning and the alternatives I rejected:
<https://github.com/wildware-uk/Udea/issues/200#issuecomment-5703979328>,
<https://github.com/wildware-uk/Udea/issues/200#issuecomment-5703980498>.

1. **GLFW has to start on X11 before Kool touches it.** Kool 0.19.0's
   `GlfwWindowSubsystem.onEarlyInit` hints `GLFW_PLATFORM_WAYLAND` whenever GLFW was built with
   Wayland, and it has no fallback. Under xvfb that fails (M4). The spike calls
   `glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11)` and `glfwInit()` first; Kool's own `glfwInit`
   then returns at once because GLFW is already running. `Configuration.STACK_SIZE` has to be set
   to 128 before that call. Otherwise LWJGL sizes the main thread's stack at its default, and
   Kool's Vulkan instance creation dies with `OutOfMemoryError: Out of stack space`
   (`vk-attempt1-stack-oom.log` line 24, from an uncommitted state that had the pre-init but not
   the stack size).
2. **Read back through a frame copy.** Kool's Vulkan backend only gives a pass's colour image
   `VK_IMAGE_USAGE_TRANSFER_SRC_BIT` when the pass has a frame copy. Without one, `download()`
   throws `Texture is not copyable` (`vk-attempt2-nvidia-not-copyable.log` line 162, also from an
   uncommitted state). The spike downloads `pass.copyOutput(isCopyColor = true, ...)` on both
   backends, so there is one capture path.
3. **OpenGL gives rows bottom-first; Vulkan gives them top-first.** Same scene, same code. The
   spike flips rows only when the backend is `RenderBackendGl` (M2 shows what happens without the
   flip).
4. Config: `showWindowOnStart = false` (the log prints `window-visible=false`),
   `numSamples = 1`, `isVsync = false`, `useOpenGlFallback = false`. The last one means a
   Vulkan attempt that fails says so, instead of quietly answering with OpenGL. Env:
   `LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe`. For Vulkan, add
   `-Pspike.backend=vk` and `VK_DRIVER_FILES=/usr/share/vulkan/icd.d/lvp_icd.json`.

**A surprise worth knowing.** This box has a real GPU (NVIDIA GeForce RTX 2070 SUPER). Without
`VK_DRIVER_FILES`, Kool's Vulkan backend picks it rather than lavapipe
(`vk-attempt2-nvidia-not-copyable.log` line 159). Any Vulkan claim about "software rendering"
here has to pin the ICD. For OpenGL, `LIBGL_ALWAYS_SOFTWARE=1` did pin llvmpipe, as the device
line shows.

**Vulkan on lavapipe**, the alternative the issue names. From
`spikes/kool-offscreen/transcripts/final-vk-lavapipe.log` (lines 1, 157, 162, 178, 180):

```
HEAD=f05a9f1 dirty=0
[...]
spike: backend=Vulkan device=llvmpipe (LLVM 20.1.2, 256 bits) window-visible=false
[...]
spike: PASS - the textured quad's known pixels are in the read-back
[...]
BUILD SUCCESSFUL in 4s
[...]
exit=0
```

Command: the evidence command with `VK_DRIVER_FILES=/usr/share/vulkan/icd.d/lvp_icd.json` added
to the `env` and `-Pspike.backend=vk -Pspike.out=<path>` added to Gradle. The GL PNG and the
Vulkan PNG have the same sha256, `75a40151d8707b817462225edc9c54b1923dd8eea698d847e00e6ed56ff03382`.

**What this does not show.** It does not cover Kool's `Offscreen` render mode in `udea-render`
(which does not exist yet), a window larger than the screen, many frames or resizes, text, or
Kool's Swing window subsystem. It also does not show the X11 workaround coexisting with a real
Wayland session: that path returns early and was not run.

## 3. `sh gradlew build`

`JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue` on this branch
with the spike committed (`f05a9f1`). Full log:
`spikes/kool-offscreen/transcripts/root-build-continue.log`, lines 488-489 and 491:

```
BUILD SUCCESSFUL in 1m 26s
220 actionable tasks: 139 executed, 81 from cache
[...]
exit=0
```

- **Tasks this ticket turned green:** none in the root build; the ticket's own task is
  `-p spikes/kool-offscreen run` (and `test`), both green.
- **Baseline failures, unchanged:** the build had no failing tasks, so there was nothing to
  compare against. I did not run a separate baseline on `origin/kmp` before the first change. The
  spike changes no file that a root task reads, and the branch build is fully green, so no
  baseline-green task can have turned red.
- `udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd`: `BUILD SUCCESSFUL`,
  42 tasks up-to-date (log in scratchpad only, not committed).
- **GL:** `udeaGlTest` / `udeaAgentGlTest` ran with no `DISPLAY`, so they skipped. This ticket
  touches neither `udea-render` nor `udea-agent-host`, so I did not run the xvfb GL suite. The GL
  evidence for this ticket is the spike's own xvfb run above, and a green root build says nothing
  about GL.
- `udeaDaemonBudget` passed inside the build; nothing to re-run.

## 4. Images

In `/srv/ssd1/workspace/Udea/build/debug-screenshots/`:

- `issue200-kool-offscreen-quad.png`: the OpenGL read-back at `f05a9f1`, red and green on top,
  blue and white below, on magenta. Proves the draw, the texture and the read-back orientation.
  The same bytes are committed as `docs/issue-media/issue200-kool-offscreen-quad.png`.
- `issue200-kool-vulkan-lavapipe-quad.png`: the Vulkan-on-lavapipe read-back. Byte-identical to
  the GL one, so both backends agree once GL's rows are flipped.
- `issue200-gl-readback-before-row-flip.png`: the M2 frame, upside down. Shows what the GL
  download looks like without the flip.

## 5. Acceptance criteria

| Criterion | Proof |
|---|---|
| A comment on the issue: yes/no, the exact command, the Kool version, what it took (window hints, backend flags) | The answer comment <https://github.com/wildware-uk/Udea/issues/200#issuecomment-5704023996>, plus the two decision comments above. Every fact in it is backed by sections 1-2 and their transcripts |
| The PNG, committed to `docs/issue-media/` and linked from the comment | `docs/issue-media/issue200-kool-offscreen-quad.png` in `39a5e33`, linked as `https://github.com/wildware-uk/Udea/blob/kmp/docs/issue-media/issue200-kool-offscreen-quad.png`. The link resolves once this merges to `kmp` |
| If no: failure quoted and at least one alternative tried | The answer is yes, so this does not apply. Even so, the failures along the way are quoted (M4, the two Vulkan attempt logs), and the Vulkan-on-lavapipe alternative was tried and passes |

## 6. Regenerated files

None. No `net-protocol.lock` or `expected-generated-hashes.txt` change; no replicated component
touched.
