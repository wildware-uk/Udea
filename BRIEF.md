# BRIEF.md — issue #275, parts 1 and 3 (branch `issue-275-overlay-pointer`)

<!-- PREDICTIONS COMMITTED BEFORE THE MUTATION RUN. Measured columns are filled in a later commit. -->

**SHA at the time predictions were frozen:** see `git log` for this commit.

## Predictions, frozen before the mutation run

Each row: a mutation of production code, the tests it is run against, and what I expect **before
running anything**. `udeaGlTest` under xvfb with `-Pudea.render.requireGl=true --no-build-cache`.

| # | Mutation | Tests run | Predicted |
|---|---|---|---|
| m0 | none (control) | `GlOverlay*`, `GlPointerPosition*` | EXIT=0, 5 tests, 0 failures |
| m1 | `KoolThread.run` stops calling `report(t)` | `GlOverlayFailure*` | both fail, at check 3: "the render loop's failure did not reach stderr with its stack trace" |
| m2 | `KoolThread.awaitExit` returns instead of throwing | `GlOverlayFailure*` | both fail, at check 2: "awaitExit() returned normally for a render loop that died of an exception" |
| m3 | `KoolBackend` passes `null` instead of the cause to `closeCaptures` | `GlOverlayFailure*` | both fail, at check 1: "the capture failed without the exception that stopped the render loop" |
| m4 | `KoolBackend.close` stops calling `kool.stopDriving()` first | `GlOverlayLongRun*` | both fail at the very end, with `GlContextException: ... RenderPipeline has been disposed and cannot draw` out of `awaitExit()` |
| m5 | the draw-outside-begin message drops `$OPEN_FIRST` | `GlOverlayFailure*` | both fail: "the draw-outside-begin message does not name the call to make" |
| m6 | `KoolPointer.onPosition` stops writing `pointerX` | `GlPointerPosition*` | fails: "pointerX after moving the cursor to (60, 50)", expected 60.0, actual 0.0 |
| m7 | `KoolPointer.isPointerOver` starts `true` | `GlPointerPosition*` | fails: "the pointer reported a position before the cursor was ever over the window" |
| m8 | **all** production changes reverted to `origin/master` (tests kept) | `GlOverlay*`, `GlPointerPosition*` | the two `GlOverlayFailure` tests fail; **the two `GlOverlayLongRun` tests and `GlPointerPosition` pass** — a correct overlay was never the bug, and the pointer API is #262's, unchanged here |

m8's second half is the honest part: the soak test the reviewer rule asks for does **not** go red on
`origin/master`, because an overlay that draws correctly always worked. What goes red is the pair of
failure tests. The evidence command covers both, so the command as a whole goes red.
