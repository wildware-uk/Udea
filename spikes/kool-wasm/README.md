# Kool main on toolchain 21, for jvm, android and wasmJs (issue #225)

**Answer: yes.** Kool `main`, pinned at
[`ab762acde2bac4f2c078a34da636e49423b11811`](https://github.com/fabmax/kool/commit/ab762acde2bac4f2c078a34da636e49423b11811)
("Bump dependencies", 2026-08-25), builds `kool-core` for desktop JVM as Java 21 bytecode, for
Android and for wasmJs, with a **13-line build-script patch in 3 files and no source change**. A
Kotlin 2.4.20 consumer on Udea's Gradle 8.13 compiles against it for `jvm` and `wasmJs`, and its
Wasm scene draws in headless Chrome on WebGL 2.

This is a throwaway spike. It is not in the root `settings.gradle.kts`, it publishes nothing
(Kool goes into a scratch Maven directory), and the Kool clone lives outside the repo.

## Reproduce

    sh spikes/kool-wasm/reproduce.sh

It clones Kool into `/srv/ssd1/workspace/kool-spike-225/kool` if it is not there (`SPIKE_WORK`
moves it), checks out the pinned commit, applies `toolchain-21.patch`, empties Kool's build
directories and the scratch repository, and then:

1. **kool**: `:kool-core:publishToMavenLocal` into `$SPIKE_WORK/m2`, with `--no-build-cache`;
   `javap` on two desktop classes must say major 65; the Android `.aar` must exist; the wasm-js
   `.klib` manifest must say `builtins_platform=WASM`.
2. **consumer**: `consumer/` with Udea's own wrapper and Kotlin 2.4.20 runs `compileKotlinJvm` and
   `wasmJsBrowserDistribution` against the scratch repository.
3. **browser**: `draw-check.mjs` serves the distribution to headless Chrome and fails unless the
   page's canvas holds a WebGL 2 context and the screenshot shows a drawn cube.

`KOOL_NO_PATCH=1` builds the pinned commit as upstream has it; that run fails. In
`transcripts/green.log` the Kool build took 78s and the consumer build 26s, with Gradle's dependency
caches already warm. The very first Kool build on this box, which also downloaded Gradle 9.7.1 and
Kool's dependencies, took 206s; its log was overwritten by a later run.

Needs: git, a JDK 21 (`KOOL_JAVA`), the Android SDK (`ANDROID_HOME`, default `~/Android/Sdk`),
Node 24 (built-in `WebSocket`), and a Chrome (`CHROME`, default: the newest Playwright
Chrome for Testing under `~/.cache/ms-playwright`).

## What it took

`toolchain-21.patch` (13 lines changed, 3 files):

- `buildSrc/src/main/kotlin/kool.lib-conventions.gradle.kts`: `jvmToolchain(25)` to
  `jvmToolchain(21)`. One line. Kool's own code needs nothing newer than 21: it compiled without a
  source change, and a grep of `kool-core/src` for `java.lang.foreign`, `MemorySegment`,
  `ScopedValue`, `StructuredTaskScope` and `java.lang.classfile` finds nothing.
- `buildSrc/src/main/kotlin/kool.androidlib-conventions.gradle.kts` and `kool-core/build.gradle.kts`:
  uncomment the Android target and `androidMain`'s `androidsvg` dependency (12 lines). Kool `main`
  ships Android switched off; unpatched, no `kool-core-android` is published at all. This is what
  Kool's own `enableAndroidPlatform` task does, limited to the files `kool-core` needs.

Kool's wasmJs target needed **nothing**: the unpatched build already publishes `kool-core-wasm-js`
(`transcripts/red-no-patch.log`, "published variants of kool-core").

Kool's Gradle 9.7.1 wrapper was launched on Temurin 21.0.11, the same JDK as Udea.

## Data points for #223

- **Kotlin**: Kool `main` is on Kotlin 2.4.10 (`gradle/libs.versions.toml` at the pin). The wasm klib
  manifest says `compiler_version=2.4.10`, `abi_version=2.4.0`, `metadata_version=2.4.0`. A Kotlin
  2.4.20 consumer read both the klib (`compileKotlinWasmJs`) and the desktop jar
  (`compileKotlinJvm`) without complaint.
- **Android bytecode**: Kool's Android block targets `JVM_11`, so the `.aar` classes are major 55;
  `compileSdk 34`, `minSdk 24`.
- **Nothing in the metadata guards the JVM level.** The desktop variant's Gradle module metadata
  carries no `org.gradle.jvm.version` attribute, so an unpatched (major 69) jar would resolve for a
  JDK 21 consumer without error and fail only at class load. The `javap` check is what catches it.
- **Transitive dependencies of `kool-core-wasm-js`** (its `.module`): kotlinx-coroutines-core 1.11.0,
  kotlin-stdlib 2.4.10, kotlinx-browser 0.5.0, kotlinx-serialization-json 1.11.0, atomicfu 0.33.0,
  and `org.jetbrains.compose.runtime:runtime` 1.11.1.
- The published version is Kool's own `0.20.0-SNAPSHOT` under `de.fabmax.kool`. Publishing it for
  real (#223 option 1) would need a group of our own; this spike did not do that.

## The draw

A vertex-coloured cube on a dark teal clear colour, 800x600. `reproduce.sh` writes it to
`$SPIKE_WORK/out/issue225-kool-wasm-webgl2-scene.png`; the copy from the run in
`transcripts/green.log` is in the gallery as
`/srv/ssd1/workspace/Udea/build/debug-screenshots/issue225-kool-wasm-webgl2-scene.png`. From that log:

    [console.log] KOOL_SPIKE_READY frames=60 backend=WebGL api=WebGL 2.0
    page WebGL probe: {"webgl2":true,"webgl1Null":true,"version":"WebGL 2.0 (OpenGL ES 3.0 Chromium)","glsl":"WebGL GLSL ES 3.00 (OpenGL ES GLSL ES 3.0 Chromium)","renderer":"ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero) (0x0000C0DE)), SwiftShader driver)","drawingBuffer":"800x600"}

The first line is Kool's backend reporting itself; the second is the page asked directly.

## Transcripts, and the checks seen failing

All in `transcripts/`, each ending in the script's exit status.

| Run | What changed | Result |
|---|---|---|
| `green.log` | nothing | `exit=0` |
| `red-no-patch.log` | `KOOL_NO_PATCH=1` | `FAIL: de/fabmax/kool/KoolSystem.class is major 69, expected 65 (Java 21)`; no `kool-core-android` in the published list |
| `mutation1-android-hunks-removed.log` | `mutation1-android-hunks-removed.diff`: the patch keeps only the toolchain hunk | desktop major 65, then `FAIL: no android aar at ...` |
| `mutation2-cube-invisible.log` | `mutation2-cube-invisible.diff`: `isVisible = false` on the cube | WebGL 2 probe passes, then `FAIL: the centre is the clear colour: the cube was not drawn` |
| `control-webgl1-page.log` | `draw-check.mjs` on `controls/webgl1` (a WebGL 1 page) | `FAIL: the canvas has no WebGL 2 context` |
| `control-webgl2-page.log` | the same page asking for `webgl2` | `exit=0` |

The two control pages differ only in the context type they request, so the pair shows the WebGL 2
probe tells the two apart rather than passing on any canvas.

The mutation and control runs happened before the final `green.log` run, against the committed
`reproduce.sh` and `draw-check.mjs`.
