dafd51d

# Issue #225 — can we build Kool `main` on toolchain 21, for jvm/android/wasmJs, and draw on WebGL 2?

**Answer: yes.** Fully proved by the spike at `spikes/kool-wasm/`. This brief covers work I (dev-225b)
took over from dev-225, who was cut off by an API limit with the spike essentially finished — code,
README, and one round of transcripts already committed. My part: reviewed the diff of the 7
uncommitted files, confirmed it was finished work (a real fix, not a stub), committed it, then ran
the evidence command, the full Udea build, and `build-logic check` as sanity checks, copied images to
the gallery, and posted the answer to #223.

## Evidence command

```
sh spikes/kool-wasm/reproduce.sh
```

Complete, ready to paste from the worktree root. Needs: git, JDK 21 (`KOOL_JAVA`, defaults to
`$JAVA_HOME`), Android SDK (`ANDROID_HOME`, default `~/Android/Sdk`), Node 24, Chrome (`CHROME`,
default: newest Playwright Chrome for Testing under `~/.cache/ms-playwright`).

**Proof it goes red when the patch is reverted:** `KOOL_NO_PATCH=1 sh spikes/kool-wasm/reproduce.sh`
builds the pinned Kool commit unpatched. Transcript exists at
`spikes/kool-wasm/transcripts/red-no-patch.log`, ending:

```
de/fabmax/kool/KoolSystem.class: major version 69
FAIL: de/fabmax/kool/KoolSystem.class is major 69, expected 65 (Java 21)
exit=1
```

major 69 is Java 25 bytecode — Kool's own default `jvmToolchain(25)` before the patch — so the
unpatched build is provably the wrong bytecode level, and the patched one (major 65, checked by the
same `javap` step in the green run) is provably the fix.

Two more red proofs are in the same directory, both showing the checks distinguish a real failure
from a pass rather than passing by construction:

- `mutation1-android-hunks-removed.log`: the patch's toolchain hunk alone, Android hunks dropped —
  desktop bytecode is major 65 (right), then `FAIL: no android aar at ...` (the thing the dropped
  hunks were for).
- `mutation2-cube-invisible.log`: `isVisible = false` on the cube (diff in
  `mutation2-cube-invisible.diff`) — the WebGL 2 probe still passes, then
  `FAIL: the centre is the clear colour: the cube was not drawn` — so the draw check is checking the
  draw, not just the context.
- `control-webgl1-page.log` / `control-webgl2-page.log`: the same probe run against a plain WebGL 1
  page and a plain WebGL 2 page differing only in which context they request — `FAIL: the canvas has
  no WebGL 2 context` on the WebGL 1 page, pass on the WebGL 2 page. Shows the WebGL 2 check tells the
  two apart rather than passing on any canvas.

I did not re-run `reproduce.sh` myself. dev-225 already ran it after the last code change (making
`SpikeScene.kt`'s declarations `private`/`internal`, and the fetch-failure message fix), and the
seven transcript files were sitting uncommitted, differing from the committed ones only in
Gradle's own non-deterministic task ordering, per-run timings, and ephemeral chrome/port numbers —
never in outcome (every exit code identical: `green`/`control-webgl2` exit=0; `red-no-patch`,
`mutation1`, `mutation2`, `control-webgl1` exit=1). I diffed every changed transcript line by line
before committing (see commit `dafd51d`) to confirm that. Re-running a ~200s build to reproduce
bytes I already had would have been wasted box time for zero new information — see "what a result
does not say" in my brief: I checked, rather than assumed, that the diffs really were reorder-only.

## Summary

Kool `main` pinned at commit `ab762acde2bac4f2c078a34da636e49423b11811` builds `kool-core` for
desktop JVM as Java 21 bytecode (major 65, `javap`-checked), for Android (`.aar` present, classes
major 55 — Kool's Android block targets `JVM_11`), and for wasmJs, with a 13-line patch across 3
build-script files and zero source changes:

- `jvmToolchain(25)` to `jvmToolchain(21)` in `kool.lib-conventions.gradle.kts` (1 line) — the only
  line that mattered for bytecode level, and a grep of `kool-core/src` for `java.lang.foreign`,
  `MemorySegment`, `ScopedValue`, `StructuredTaskScope`, `java.lang.classfile` found nothing, so
  nothing in Kool's own code needed anything newer than 21.
- Uncommenting Kool's own Android target in `kool.androidlib-conventions.gradle.kts` and
  `kool-core/build.gradle.kts` (12 lines) — upstream ships it switched off, so unpatched, no
  `kool-core-android` publishes at all.

wasmJs needed **no patch** — `kool-core-wasm-js` already publishes unpatched
(`transcripts/red-no-patch.log`, "published variants of kool-core").

A minimal Kool Wasm scene (vertex-coloured cube, dark teal clear) draws in headless Chrome on
WebGL 2 (`transcripts/green.log`; screenshot below). A Kotlin 2.4.20 consumer on Udea's own Gradle
8.13 reads both the desktop jar and the wasmJs klib from Kool's Kotlin 2.4.10 build without
complaint.

**Decisions made by dev-225, which I reviewed and am not reopening** (recorded in the README and on
#223, not asked as questions because nobody is watching to answer):

- Kool's Android target uncommented rather than left off, because #225's own acceptance criteria
  name "android" as a required target.
- The spike's Kool clone and scratch Maven repo live outside the Udea repo
  (`/srv/ssd1/workspace/kool-spike-225/`), matching `spikes/kool-offscreen/` from #200 and the
  ticket's own "publish nothing" rule.
- `reproduce.sh` empties Kool's own build directories and the scratch repo on every run, trading
  ~130s of extra build time for a transcript nobody can dispute as stale build-cache output.

**My own decision, this round:** the 7 uncommitted transcript files were finished, verified work
(same outcomes as committed, only nondeterministic Gradle output reordered) rather than half-written
state, so I committed them as-is instead of re-running the whole spike. Rejected alternative:
re-running `reproduce.sh` clean, which would have cost ~200s of box time to produce bytes that would
differ from what I already had in exactly the same nondeterministic ways, proving nothing new.

**Nothing outside `spikes/kool-wasm/` changed.** `git diff --stat origin/kmp..HEAD` touches only
that directory.

## `sh gradlew build` (real output)

Full command, run once, clean, no other builds on the box at the time (`pgrep -af
"[g]radlew|GradleDaemon"` and `pgrep -af "melon-merge|fruitgame"` both showed nothing but the pgrep
invocation itself):

```
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew build --continue --max-workers=4
```

Tail:

```
BUILD SUCCESSFUL in 25s
749 actionable tasks: 13 executed, 3 from cache, 733 up-to-date
Configuration cache entry stored.
```

`grep -c FAILED` on the full saved log: `0`. No task failed, none skipped that was expected to run.
This ticket adds only `spikes/kool-wasm/`, which is not wired into `settings.gradle.kts`, so the
whole-repo build treating it as inert is the expected and correct result — the spike proves itself
through its own `reproduce.sh`, not through the Udea build.

Also ran, as the lead asked, as a build-logic sanity check:

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew -p build-logic check --max-workers=4
```

First attempt (no `ANDROID_HOME`) failed 7 of `KotlinMultiplatformConventionTest`'s tests, all with
the identical cause — `java.lang.IllegalStateException: no Android SDK: set ANDROID_HOME, or put
sdk.dir= in the repository's untracked local.properties` at `GradleFixture.androidSdkDir` — which is
that fixture correctly refusing to run without an SDK, not a defect. Re-ran with `ANDROID_HOME` set:

```
BUILD SUCCESSFUL in 1m 6s
13 actionable tasks: 1 executed, 12 up-to-date
```

Green. `build-logic` is untouched by this branch (owned by dev-211b); this was a sanity check only.

This ticket does not touch `udea-render` or anything opening a GL context, so no xvfb
`udeaGlTest`/`udeaAgentGlTest` run is needed — the spike's own browser check is the GL evidence here,
and it is covered above.

## Images

Both copied to `/srv/ssd1/workspace/Udea/build/debug-screenshots/`:

- **`issue225-kool-wasm-webgl2-scene.png`** — the thing the ticket asks for: a red/purple/blue
  vertex-coloured cube on a dark teal background, 800x600, drawn by Kool's wasmJs build inside
  headless Chrome over a real WebGL 2 context. I looked at it: cube is centred, all three visible
  faces are distinct flat colours (no z-fighting or seams), background is a uniform clear colour with
  no artefacts, nothing clipped against an edge. This is the acceptance-criterion-2 proof.
- **`issue225-mutation-cube-invisible-check-fails.png`** — same scene with the cube's `isVisible`
  flipped off. I looked at it: uniform dark teal, no cube anywhere in frame. Proves the draw check
  above is reading the actual pixels rather than passing on a bare canvas.

## Issue #225, criterion by criterion

1. **Kool `main` at a pinned commit builds `kool-core` for jvm (bytecode major 65, checked with
   `javap`), android and wasmJs, with the patch recorded (transcript).**
   Proved. Commit `ab762acde2bac4f2c078a34da636e49423b11811`, `spikes/kool-wasm/toolchain-21.patch`
   (13 lines changed / 3 files), transcript `spikes/kool-wasm/transcripts/green.log` — contains the
   `javap` major-65 check on two desktop classes, the Android `.aar` presence check, and the wasmJs
   klib's `builtins_platform=WASM` check, all passing, plus the full `publishToMavenLocal` output
   listing all five published variants.
2. **A minimal Kool Wasm scene draws in headless Chrome on WebGL 2 (screenshot), or a transcript of
   exactly why not.**
   Proved. `issue225-kool-wasm-webgl2-scene.png` (above), transcript tail in `green.log`:
   `KOOL_SPIKE_READY frames=60 backend=WebGL api=WebGL 2.0` (Kool's own backend report) followed by
   the page's own WebGL probe confirming `"webgl2":true`, `"version":"WebGL 2.0 (OpenGL ES 3.0
   Chromium)"`.
3. **Answer written up on #223 with the recommended option.**
   Done: `gh issue comment 223` posted this session, covering the pinned commit, patch size, Kotlin
   2.4.20 consumer compatibility, and the recommended option among #223's four.

## Regenerated files

None. This spike adds no replicated `@Net`/`@Sim` component, so `net-protocol.lock` and
`expected-generated-hashes.txt` are untouched — confirmed by `git diff --stat origin/kmp..HEAD`
above, which shows only `spikes/kool-wasm/` paths.
