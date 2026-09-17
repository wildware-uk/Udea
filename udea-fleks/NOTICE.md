# udea-fleks: vendored Fleks

This module is **Fleks**, the Kotlin entity component system by Simon Klausner, brought into
this repository as source (issue #215).

- **Upstream:** https://github.com/Quillraven/Fleks
- **Release:** `2.14`, tag `2.14`, commit `c3b69c240dc23e6869d7e54d53da8f1cb0e73c95`
- **Licence:** MIT. The full text is `LICENSE` in this directory, unchanged from upstream.
- **What was copied:** `src/commonMain` and `src/commonTest`, unchanged. Upstream's build
  scripts, benchmarks, docs and assets were not copied.

## Why it is vendored

Fleks publishes no iOS variant at any version, and `udea-core` exposes Fleks through its `api`,
so `udea-core` and every module above it could not have an iOS target while Fleks came from
Maven Central. Fleks is pure common Kotlin: building its source here gives it every target the
rest of the engine has. The package names are upstream's, so no code that uses Fleks changed.

## How this module's build differs from upstream's

The source is not edited. These are the build differences, all in `build.gradle.kts`:

- Udea's multiplatform convention (`jvm`, `android`, `wasmJs`, `iosArm64`,
  `iosSimulatorArm64`) instead of upstream's target list, which has no iOS.
- Explicit API mode is switched off. Udea requires it; Fleks relies on default visibility.
- `kotlinx-serialization-json` is a test dependency only. Upstream declares it on the main
  classpath, but its main source does not use it.
- Compiled with the Kotlin version in `gradle/libs.versions.toml` rather than upstream's.

## Changing the source

Do not refactor it: it is third-party code, and Udea's engineering standards do not apply to it.
If an edit is unavoidable, or a new Fleks release is vendored, the `@version fleks` pin in
`determinism-allowlist.txt` fails `udeaVerifyDeterminism` until `determinism-audit.md` has been
re-read against the new source and the pin moved. The pin includes a digest of `src/commonMain`,
so it notices an edit as well as a release.
