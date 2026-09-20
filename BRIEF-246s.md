85c1e9a

(The code under review is `85c1e9a`, one commit on `origin/master` at `51a129e`. After a fetch, `HEAD..origin/master` was empty, so there was nothing to merge. This brief is committed on top and changes no code.)

# #246 reopened: a world that has ticked with `RenderModule` saves as a level

## Evidence command

    sh gradlew :udea-render:jvmTest --tests dev.wildware.udea.render.interp.PresentationLevelTest

Green on `85c1e9a`, 2 tests. It goes red when the fix is taken away.

**On master's code.** The test file was run against `51a129e` before any fix existed. The new `PresentationOnly.kt` was written while that run was starting, so it may have been compiled into it. Nothing referenced it yet. From `scratchpad/issue246/reopen/red-master.log`:

    > Task :udea-render:jvmTest FAILED

    PresentationLevelTest[jvm] > a world that has run RenderModule saves, and the level holds no presentation state()[jvm] FAILED
        dev.wildware.udea.core.level.LevelSaveException at PresentationLevelTest.kt:71

    PresentationLevelTest[jvm] > loading the level recreates the pose records from the loaded world on the next tick()[jvm] FAILED
        dev.wildware.udea.core.level.LevelSaveException at PresentationLevelTest.kt:84

    2 tests completed, 2 failed

The exception message, from the saved report `red-master.xml`, is the one dev-249 reported:
`entity Entity(id=0, version=0) holds dev.wildware.udea.render.interp.Interp3D, which no generated level component list names. Mark it @Serializable in a module that runs udea-codegen, or keep it out of worlds that are saved as levels.`

**Mutations, against the committed fix.** Each diff is from `scratchpad/issue246/reopen/<name>.diff`. The failing tests are from `<name>.log`. The worktree was clean after the runs (`after-mutations-status.txt` is empty).

| Mutation | Diff | Red |
|---|---|---|
| m1: the save ignores the marker | `-            if (component is PresentationOnly) continue` in `LevelService.saveable` | `LevelServiceTest > a presentation-only component no module lists is left out - and the level is the world without it` (AssertionFailedError at LevelServiceTest.kt:62, which reads `LevelSaveException ... holds dev.wildware.udea.core.level.Trail`), and both `PresentationLevelTest` tests (LevelSaveException at :71, :84) |
| m2: `Interp3D` unmarked | `-) : Component<Interp3D>, PresentationOnly {` / `+) : Component<Interp3D> {` | both `PresentationLevelTest` tests. The message names `Interp3D` on `Entity(id=0)` |
| m3: `Interp` (2D) unmarked | `-) : Component<Interp>, PresentationOnly {` / `+) : Component<Interp> {` | both `PresentationLevelTest` tests. The message names `Interp` on `Entity(id=1)`, so the 2D record is covered as well as the 3D one |

## Summary

**Why moba never hit it.** It was luck, not design. `InterpSnapshotSystem` attaches `Interp` only to a `PhysicsBody` entity, and `Interp3DSnapshotSystem` attaches `Interp3D` only to a `Transform3D` entity. Moba has neither: its units move by `Position` (`MobaPoses` KDoc: "this game has none"). Moba does include `RenderModule` in every mode, so the first moba entity with a body or a 3D transform would have hit this too.

**The fix is option (a).** `udea-core` gets a marker interface, `dev.wildware.udea.core.level.PresentationOnly`. `udea-render`'s `Interp` and `Interp3D` implement it. `LevelService.saveable` skips a component that carries it. That skip is an `is` check made on a save, not reflection and not on a tick. Every other unlisted component still refuses the save and names the class. The `Link` refusal test pins that, unchanged.

**Loading recreates the records** without new code. `loadSnapshot` replaces every entity, so no loaded entity has a record. On the next tick each snapshot system's `none(Interp…)` family gives it a fresh one from the loaded `Transform3D` or `PhysicsBody`. Before that tick, a paused editor draws the live pose: `Interpolator3D.interpolate` already returns the plain transform when there is no `Interp3D`.

**Rejected:**
- Option (b), saving only what the generated lists name: a component that someone forgot to make `@Serializable` would vanish from the level without a word.
- A registration on `LevelHooks`, filled by `RenderModule.level`: it does the same job, but the fact lives away from the class, so a new record type can be added and its registration forgotten.

The decision is on the issue: https://github.com/wildware-uk/Udea/issues/246#issuecomment-5744415522

**Deliberately not marked:** `ModelRenderer`, `SpriteRenderer`, `SpriteAnimation`, `DebugLabels`. They say *what* to draw, and nothing puts them back after a load, so marking them would load a world with its models missing. Reading the source, a world holding one of them still refuses to save, exactly as on master. This is not exercised by a test here. **Out of scope, and relevant to Hollow's editor Save (H7 #255):** a Hollow entity with a `ModelRenderer` will still be refused. That needs a serializable model reference, which is a design question for that ticket.

No `docs/contracts/` file changed. No replicated component was added or removed, so `net-protocol.lock` and `expected-generated-hashes.txt` were not touched. Nothing is visible, so there are no screenshots.

## `sh gradlew build`

`ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew --max-workers=6 --console=plain build --continue` on `85c1e9a` (`full-sha.txt`). From the tail of `scratchpad/issue246/reopen/build.log`:

    BUILD SUCCESSFUL in 3m 6s
    984 actionable tasks: 719 executed, 131 from cache, 134 up-to-date
    Configuration cache entry stored.
    EXIT 0

## GL, for real, under xvfb

The ticket touches `udea-render` (`Interp`, `Interp3D`), so the GL tests were run for real:

    xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest -Pudea.render.requireGl=true

From `scratchpad/issue246/reopen/gl.log`:

    > Task :udea-agent-host:udeaAgentGlTest
    > Task :udea-render:udeaGlTest
    > Task :udea-editor:udeaEditorGlTest
    ...
    BUILD SUCCESSFUL in 2m 5s
    126 actionable tasks: 12 executed, 114 up-to-date

Totals from the JUnit XML those tasks wrote: `udeaGlTest` 24 tests, `udeaAgentGlTest` 2, `udeaEditorGlTest` 6. Each had 0 skipped and 0 failed.

## Images

None. The change is invisible: a save that used to throw now returns bytes.

## The criterion

> A world with render-side presentation components (Interp3D, and the 2D Interp) saves and loads as a level. Presentation state is never written into the level, and loading recreates it. Headless test, red on master.

- **Saves, with both components present.** `PresentationLevelTest` first test. It asserts that both `Interp3D` and `Interp` are on the entities after five ticks, then calls `saveNow`. Red on master (above).
- **Never written into the level.** The same test checks that the bytes match a save of the identical world built *without* `RenderModule`, which never had either record. `LevelServiceTest`'s new test does the same in `udea-core` with a marked test component (`Trail`), with it and without it.
- **Loads, and loading recreates it.** `PresentationLevelTest` second test. After `loadNow`, neither record is on the loaded entities. After one tick both are back, with the loaded values: `Interp3D.lastX == 7`, `lastHeading == 1.5` (the Transform3D was moved between ticks before the save), and `Interp.prevX/prevY == 4/-2`. Second time through: the loaded world, with its records back, saves byte-for-byte equal to the original ticked once more.
- **Headless.** `RenderMode.Headless`, no GL context, in `udea-render`'s `jvmTest`.

## Regenerated files

None.
