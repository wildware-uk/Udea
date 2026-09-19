7493d01

# Issue #221 - a Kool-backed `AudioDevice` in `udea-render`, desktop

**SHA:** `7493d01` is the code under review. The commit on top of it adds only this file.

Branch `issue-221-kool-audio-device`, off `origin/kmp` at `0520f27`. Worktree
`/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a86393fdc904896ad`.

Every log, diff and probe quoted here is saved in `build/reports/issue221/` in this worktree (gitignored).
Quoted blocks are copied from those files; `[...]` marks a gap. The browser half is shelved (web, 2026-09-18),
so the criteria ruled on are the revised two: a desktop run, and `udea-audio` free of Kool.

## 1. Evidence command

    ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew :udea-render:jvmTest --tests 'dev.wildware.udea.render.audio.KoolAudioDeviceTest' --rerun --console=plain

It leaves `udea-render/build/test-results/jvmTest/TEST-dev.wildware.udea.render.audio.KoolAudioDeviceTest.xml`,
whose `<system-out>` is the desktop-run transcript. From the run at `7493d01`
(`build/reports/issue221/evidence-green-KoolAudioDeviceTest.xml`):

    <testsuite name="dev.wildware.udea.render.audio.KoolAudioDeviceTest" tests="10" skipped="0" failures="0" errors="0" timestamp="2026-09-19T00:02:14.376Z" hostname="wild-home-server" time="0.768">
    [...]
      <system-out><![CDATA[[kool-audio] line Kool opened: format=PCM_SIGNED 44100.0 Hz, 16 bit, mono, 2 bytes/frame, little-endian frames=22050 peak=4190
    [kool-audio] loaded 'audio/tone_440hz_500ms.ogg': Kool AudioClip duration=0.5s isEnded=true
    [kool-audio] after drain: Kool isEnded=false currentTime=6.57E-4s line starts=1 gain=-6.0206003dB
    [kool-audio] 150ms later: Kool isEnded=false currentTime=0.153356s
    [kool-audio] finished: Kool isEnded=true currentTime=0.5s line events=[Open, Start, Stop]

What each line is read from, because none of it is a flag this code sets about itself:

- `line Kool opened` - the Java Sound line Kool's `AudioClipImpl` opened: all 22 050 frames of the tone
  (0.5s at 44.1kHz), peak 4190 (ffmpeg's `sine` source is an eighth of full scale, 4096).
- `duration`, `isEnded`, `currentTime` - Kool's own `AudioClip` getters.
- `starts=1`, `gain=-6.02dB` - the `start()` Kool called on the line and the `MASTER_GAIN` Kool set, after a
  `Cue` was emitted into a `CueQueue` and drained by `udea-audio`'s `CueAudio` at volume 0.5 (20·log10 0.5 = -6.02).
- `Open, Start, Stop` - the line events, in order; Kool's `isEnded` flips on the `Stop`.

**What stands in for the sound card.** This box has no audio output the build user can open (section 5).
`CaptureMixerProvider` (jvmTest only, found by Java Sound's own service lookup, selected by the
`javax.sound.sampled.Clip` property) replaces the operating system's line and nothing above it. Its line's position
advances with real time from `start()` and it fires `STOP` at the end, as a hardware line does. **Nobody heard
anything, and nothing here claims a sound was heard.**

**It goes red with `AudioDevice.Silent` swapped in** (`build/reports/issue221/m1-silent.diff`):

    -public fun koolAudioDevice(assetRoot: Path): AudioDevice = KoolAudioDevice(DesktopClipLoader(assetRoot))
    +public fun koolAudioDevice(assetRoot: Path): AudioDevice = AudioDevice.Silent

`build/reports/issue221/m1-silent.log`: `10 tests completed, 10 failed`. The evidence test fails at
`KoolAudioDeviceTest.kt:89`, `assertNotNull(CaptureMixer.lines.singleOrNull(), "loading opened exactly one line: ...")`
- nothing reached a line, not a failed cast (the test holds the device as `AudioDevice`, as a game does).

**And red with `play` a no-op** (`m2-play-noop.diff`, `m2-play-noop.log`: `10 tests completed, 5 failed`). The
evidence test fails at line 110, `assertEquals(1, line.starts, "the drained cue started Kool's line")`.

The full mutation table is section 2.4.

## 2. Summary

### 2.1 What was built

- **`KoolAudioDevice`** (`udea-render` `commonMain`, `internal`) implements `udea-audio`'s `AudioDevice` over
  Kool's `AudioClip`: `load` makes one clip per distinct path, `play` starts a voice and sets its volume, `close`
  stops and forgets. The SPI was not changed.
- **`KoolClipLoader`** (`internal fun interface`) is the one step Kool does differently per platform: making a clip.
- **`DesktopClipLoader`** (`jvmMain`) reads the file under an asset root and builds Kool's desktop
  `AudioClipImpl(bytes, format)`. It maps every failure to an `AudioLoadException` with the cause kept:
  missing file, "no audio output" (`IllegalArgumentException` from `AudioSystem.getClip()`, or
  `LineUnavailableException`), "could not decode" (`IllegalStateException`).
- **`OggToWav`** (`jvmMain`) - see 2.2.
- **One public declaration:** `koolAudioDevice(assetRoot: Path): AudioDevice`. Nothing outside the module calls it yet;
  its caller is `moba`'s composition root, which #212 owns and I was told not to touch. Flagging it so the reviewer
  does not have to find it.
- `udea-render` gains `api(project(":udea-audio"))` (the factory returns the SPI type).

### 2.2 The surprise: Kool 0.19.0 crashes the JVM when it loads an `.ogg`

The first green attempt died with `SIGSEGV` (`build/reports/issue221/red-2-kool-vorbis-crash.log`, the hs_err is
`red-2-hs_err_pid2106198.log`):

    # Problematic frame:
    # C  [libjemalloc.so+0xa551]
    [...]
    j  org.lwjgl.system.MemoryUtil.memFree(Ljava/nio/ShortBuffer;)V+13
    j  de.fabmax.kool.modules.audio.AudioClipImpl$ClipWrapper.loadVorbis([B)Ljavax/sound/sampled/AudioInputStream;+448

Kool's `loadVorbis` (bytecode in `build/reports/issue221/javap-clip-bytecode.txt`, from line 691) calls
`STBVorbis.stb_vorbis_decode_memory` and frees the result with `MemoryUtil.memFree`. Kool declares
`lwjgl-jemalloc`, so that free goes to jemalloc. Reproduced outside the engine with only those calls
(`vorbis-probe.sh`, `VorbisFreeProbe.java`, output `vorbis-probe.out`):

    === LWJGL 3.3.6, default allocator
    LWJGL 3.3.6+1, allocator property=null
    decoded: channels=1 rate=44100 samples=22050
    memFree(pcm) with org.lwjgl.system.jemalloc.JEmallocAllocator ...
    memFree returned
    #
    # A fatal error has been detected by the Java Runtime Environment:
    Aborted (core dumped)
    === LWJGL 3.4.3, default allocator
    LWJGL 3.4.3+4, allocator property=null
    decoded: channels=1 rate=44100 samples=22050
    memFree(pcm) with org.lwjgl.system.jemalloc.JEmalloc$Allocator ...
    #
    # A fatal error has been detected by the Java Runtime Environment:
    #
    === LWJGL 3.4.3, -Dorg.lwjgl.system.allocator=system
    LWJGL 3.4.3+4, allocator property=system
    decoded: channels=1 rate=44100 samples=22050
    memFree(pcm) with org.lwjgl.system.MemoryManage$StdlibAllocator ...
    memFree returned

3.3.6 is what Kool 0.19.0 declares; 3.4.3 is what `udea-render` resolves, via `composegl-kool`'s `lwjgl-bom`
(`lwjgl-bom-insight.txt`). Both crash, 3.3.6 later than 3.4.3; 3.4.3 with the system allocator does not (3.3.6 was not run that way). What
this does **not** establish: which allocator stb uses internally. It shows only that freeing stb's buffer through
jemalloc crashes, and through the C library does not.

**Decision:** `OggToWav` decodes with `stb_vorbis_open_memory` / `get_samples_short_interleaved` / `close` into a
buffer it allocates and frees itself, writes a WAV with Java Sound, and Kool's clip opens that WAV (Kool's WAV path
makes no LWJGL call). Kool still owns the clip, the line, the gain and the playback state. **Rejected:** switching
LWJGL to the system allocator - a process global that must be set before LWJGL's first allocation, which the
renderer makes long before audio loads, and it would change the allocator under all of Kool's rendering.

The crash is heap-layout dependent. With Kool decoding and the suite as it stood then, runs crashed and passed
(`m5-kool-decodes-ogg.log` passed; `m5-rerun-1.log`, `m5-rerun-2.log` crashed; `m5-rerun-3.log` passed). So a
regression test, `decoding the ogg many times leaves the process standing`, loads the tone through 32 devices. With
the mutation back (`m5-kool-decodes-ogg.diff`), three runs out of three crashed (`m5-kool-decodes-ogg-a.log`, `-b`, `-c`):

    #  SIGSEGV (0xb) at pc=0x0000777ffe80a551, pid=2138686, tid=2138688
    [...]
    > Process 'Gradle Test Executor 477' finished with non-zero exit value 134

The failure is the test JVM dying, not an assertion - which is the defect.

### 2.3 Other decisions (each also commented on #221)

- **Volume is honoured; pitch and pan are not.** `javap` of the 0.19.0 desktop and Android jars (`javap-audio.txt`,
  `javap-android-audio.txt`) and the JS klib's metadata: `AudioClip` has `volume`, `masterVolume`, `play`, `stop`,
  `currentTime`, `duration`, `isEnded`, `loop`, `minIntervalMs` - no rate, no pan. `AudioOutput` has per-node
  `speed`/`gain` but is mono. Rejected: faking pan as a volume drop; building a mixer on `AudioOutput`.
- **Two Kool clip behaviours handled.** A clip silently drops a `play()` within `minIntervalMs` (150ms default) of the
  last - `load` sets it to 0, because `CueAudio` already caps voices. A clip's `volume` setter writes to the most
  recently started voice - so `play` starts the voice first and sets its volume after. Both have mutations in 2.4.
- **No output is loud.** `load` throws `AudioLoadException("no audio output: ...")`; the device never degrades to
  `Silent` itself. The SPI's KDoc says `Silent` is chosen at the composition root. What a player with no sound card
  gets is `moba`'s call in #212 (catch `AudioLoadException` while building bindings, use `Silent`, log it).
- **Files come from a directory**, because `.udeapak` carries `SoundCue` records but no audio bytes.
- **Android: no loader.** Kool's Android clip is `AudioClipImpl(android.net.Uri, android.content.Context)` over
  `MediaPlayer` (`javap-android-audio.txt`). `udea-render`'s android target has no `Context` seam and this box has
  no Android runtime to prove one, so nothing Android-specific ships; `KoolAudioDevice` itself compiles for Android
  (`commonMain`). No `TODO()`, no stub.
- **Test fixture** `audio/tone_440hz_500ms.ogg` is generated by ffmpeg (command in the test KDoc), not copied from
  `moba`, whose recordings have no recorded provenance (`docs/art-assets.md`).
- **Not a contract change.** Nothing in `docs/contracts/` moved.

### 2.4 Mutation table

Each row: the literal diff (file in `build/reports/issue221/`), run with the evidence test class plus the rest of
`dev.wildware.udea.render.audio.*`, then reverted. Rows 1-4 and 6-7 run against the final test file (10 tests).

| # | Diff | Log | Result | Failing tests (line) |
|---|---|---|---|---|
| 1 | `m1-silent.diff` - factory returns `AudioDevice.Silent` | `m1-silent.log` | 10 of 10 failed | all; evidence test at 89 |
| 2 | `m2-play-noop.diff` - `play` body replaced by `loaded[sound.slot]` | `m2-play-noop.log` | 5 of 10 | drained cue (110), volume zero (169), twice at once (131), pitch/pan (179), close (223) |
| 3 | `m3-volume-before-play.diff` - `clip.volume = volume` before `clip.play()` | `m3-volume-before-play.log` | 1 of 10 | twice at once (133: "the first play kept its own volume") |
| 4 | `m4-kool-rate-limit.diff` - `clip.minIntervalMs = 0F` deleted | `m4-kool-rate-limit.log` | 2 of 10 | twice at once (131), pitch/pan (179) |
| 5 | `m5-kool-decodes-ogg.diff` - `.ogg` handed to Kool undecoded | `m5-kool-decodes-ogg-{a,b,c}.log` | JVM `SIGSEGV`, exit 134, 3 of 3 | see 2.2 |
| 6 | `m6-no-line-uncaught.diff` - the `LineUnavailableException` catch deleted | `m6-no-line-uncaught.log` | 1 of 10 | no output (212) |
| 7 | `m7-close-no-stop.diff` - `loaded.forEach { it.stop() }` deleted | `m7-close-no-stop.log` | 1 of 10 | close (227) |

Rows 2, 3 and 4 are:

    -        val clip = loaded[sound.slot]
    -        clip.play()
    -        clip.volume = volume
    +        loaded[sound.slot]

    -        clip.play()
             clip.volume = volume
    +        clip.play()

    -        clip.minIntervalMs = 0F

Rows 6 and 7:

    -        } catch (noLine: LineUnavailableException) {
    -            throw noOutput(path, noLine)

    -        loaded.forEach { it.stop() }

Row 3 is the one that nearly slipped: only the overlapping-voices test catches it, because with one voice the most
recently started voice *is* the one about to play.

## 3. `sh gradlew build --continue`

**Baseline**, `origin/kmp` at `0520f27`, before any change (`build/reports/issue221/baseline-build.log`):

    > Task :moba:compileKotlin FAILED
    [...]
    BUILD FAILED in 1m 34s
    761 actionable tasks: 524 executed, 237 from cache

**This branch at `7493d01`** (`build/reports/issue221/branch-build-final.log`):

    > Task :moba:compileKotlin FAILED
    [...]
    BUILD FAILED in 15s
    752 actionable tasks: 21 executed, 2 from cache, 729 up-to-date

Most tasks there are up-to-date from the full run at `572d099` (`branch-build-2.log`, same single failure); `7493d01`
only rewords a KDoc. The task-count difference is `build-logic`'s own tasks, which a configuration-cache hit does not
list (`diff` of the two task lists). Both logs carry 96 `e:` lines, all in `moba`, starting at `Healthbar.kt:98`.

- **Tasks this ticket turned green:** none were red. The ticket adds tests to `:udea-render:jvmTest` (10 new) and
  keeps `:udea-render:allTests`, `:udea-gradle:test` and `:udea-render:udeaVerifyHeadless` green.
- **Baseline failures, unchanged:** `:moba:compileKotlin` (D9, moba on LibGDX until #212).
- **Red on the way, fixed on this branch** (first full run, `branch-build.log`): `WallClockBudgetCensusTest` (the
  two new audio test sources read `System.nanoTime`; both now have census rows) and `RenderModuleGraphTest` (the
  allowed project list gains `:udea-audio`, with its reason).

**GL.** Audio opens no Kool context, and the audio tests run in `jvmTest`, not the GL suite. The branch still touches
`udea-render`'s test classpath (a `META-INF/services` entry), so the GL suite was run for real at `572d099`
(`build/reports/issue221/xvfb-gl.log`):

    xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew :udea-render:jvmTest --rerun :udea-render:udeaGlTest --rerun -Pudea.render.requireGl=true --console=plain

`BUILD SUCCESSFUL in 50s`; the XML reports sum to `udeaGlTest` 12 tests, 0 skipped, 0 failed, and `jvmTest` 212
tests, 0 skipped, 0 failed.

## 4. What Kool 0.19.0 audio is, from the artifacts

- **Desktop** (`kool-core-desktop-0.19.0.jar`, `javap-audio.txt`, `javap-clip-bytecode.txt`): `AudioClipImpl(byte[],
  String)` keeps a pool of up to 5 `ClipWrapper`s, each a `javax.sound.sampled.Clip` from `AudioSystem.getClip()`,
  opened in the wrapper's constructor. Decode: `AudioSystem.getAudioInputStream`, falling back to `STBVorbis` for
  `ogg` (the crash, 2.2). Volume is `FloatControl.Type.MASTER_GAIN` in dB, clamped to -79.9..0. `isEnded` follows the
  line's `STOP` event. There is no `close`/`dispose`. `AudioOutput` is a `SourceDataLine` fed by a thread from a mono
  `MixNode`. Not OpenAL.
- **Android** (`kool-core-android` release aar, `javap-android-audio.txt`): `AudioClipImpl(Uri, Context)` over
  `MediaPlayer`, plus `AudioClipImpl(InputStream, String, Context)`. Same `AudioClip` interface.
- **JS** (`kool-core-js-0.19.0.klib`, fetched for this, metadata strings only): `AudioClipImpl(assetPath: String)`
  over an HTML `Audio` element. No `wasmJs` artifact exists.

## 5. What happened when the device opened on this box

The unsubstituted desktop run: `udea-render`'s shipped jvm runtime classpath, no test mixer, `moba`'s real asset root
(`NoMixerDesktopRun.java`, output `no-mixer-desktop-run.txt`):

    Java Sound mixers visible to this process: 0
    decoded sounds/effects/melee_hit_1.ogg: 240000 frames, PCM_SIGNED 96000.0 Hz, 16 bit, mono, 2 bytes/frame, little-endian
    [...]
    decoded sounds/orc/orc_big_grunt.ogg: 94854 frames, PCM_SIGNED 44100.0 Hz, 16 bit, stereo, 4 bytes/frame, little-endian
    [...]
    decoded sounds/orc/orc_hurt_5.ogg: 38400 frames, PCM_SIGNED 44100.0 Hz, 16 bit, mono, 2 bytes/frame, little-endian
    device: KoolAudioDevice(0 clip(s), DesktopClipLoader(/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a86393fdc904896ad/moba/assets))
    load(sounds/effects/melee_hit_1.ogg) threw AudioLoadException: no audio output: Java Sound gave Kool no line to play 'sounds/effects/melee_hit_1.ogg' on (No line matching interface Clip supporting format PCM_SIGNED unknown sample rate, 16 bit, stereo, 4 bytes/frame, big-endian is supported.). A process that should be silent uses AudioDevice.Silent.
      path: sounds/effects/melee_hit_1.ogg
      cause: java.lang.IllegalArgumentException: No line matching interface Clip supporting format PCM_SIGNED unknown sample rate, 16 bit, stereo, 4 bytes/frame, big-endian is supported.
    exit=0

Why zero mixers: `/dev/snd/*` is `root:audio` mode 660 and the build user's groups (`id`) do not include `audio`.
So every moba `.ogg` decodes (every file under `moba/assets/sounds`, one of them stereo, all in one process with no
crash), and the device fails exactly where Kool asks the JDK for a line - loudly, with the JDK's own exception as the
cause. `Probe.java` / `probe-javasound.txt` is the same `getClip()` failure from a bare JDK, with no Udea or Kool code.

## 6. The criteria

| Criterion | Proof |
|---|---|
| Kool device plays a cue in a desktop run (transcript) | Section 1: a `Cue` drained by `CueAudio` through `koolAudioDevice` starts the line Kool opened, at the cue's gain, and Kool's own `currentTime` advances and `isEnded` flips at 0.5s. Red with `Silent` (row 1) and with `play` a no-op (row 2). The unsubstituted run on this box is section 5: no output, reported, not hidden. |
| `udea-audio` stays free of Kool (module-graph gate) | `:udea-audio:udeaVerifyModuleGraph --rerun` green (`ac2-green.log`, `BUILD SUCCESSFUL in 4s`). With Kool added to `udea-audio`'s `jvmMain` (`ac2-mutation-audio-gains-kool.diff`) it fails (`ac2-red.log`), quoted below. |

    > Task :udea-audio:udeaVerifyModuleGraph FAILED
    [...]
    > udeaVerifyModuleGraph: 52 violations
      UDEA-MG-002 :udea-audio jvmCompileClasspath -> de.fabmax.kool:kool-core
          only udea-render may see Kool, a ComposeGL backend, a GL backend or a native platform artifact
          resolution path: :udea-audio -> de.fabmax.kool:kool-core

(My first try put Kool in `udea-audio`'s `commonMain`; that failed on resolution - Kool has no wasm or iOS artifact -
which is red for the wrong reason, so the mutation was moved to `jvmMain`.)

Also exercised: the same sound twice at once (a second pooled line, each at its own gain); one path loaded twice
(one clip); volume 0 (gain -79.9dB, no error); pitch 0.5/2 and pan -1/1 (plays, at the requested volume); a missing
file; an undecodable `.ogg` and `.wav`; a line the system refuses; `close` while playing, then `play` after `close`.
**Not exercised:** an older overlapping voice after `close` (Kool's `stop()` reaches only the latest voice, which the
`close` KDoc states); a real sound card; Android.

## 7. What a browser implementation would take

A Kool release with a `wasmJs` artifact (none exists for 0.19.0; #223). Then: add the `wasmJs` target to
`udea.kotlin-multiplatform-render`, and a `wasmJsMain` loader that builds Kool's web clip from a URL (in the 0.19.0
JS artifact that is `AudioClipImpl(assetPath: String)` over an HTML `Audio` element), plus a factory like
`koolAudioDevice` taking a base URL. `KoolAudioDevice` does not change. Two things to check then: whether the web
clip has the same `minIntervalMs` and latest-voice volume behaviour, and that browsers only start audio after a user
gesture, which a game's first cue may precede.

## 7a. Regenerated files

None. No replicated component was added; `net-protocol.lock` and `expected-generated-hashes.txt` are untouched.

## 7b. Images

None. Nothing in this ticket draws. The evidence is the transcript in section 1.
