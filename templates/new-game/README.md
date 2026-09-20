# new-game

A Udea game that lives in its own repository. Copy this directory somewhere else, rename it, and
it builds against the engine as a published library - no path to a Udea checkout anywhere in it.

```sh
cp -r <udea>/templates/new-game ../my-game
cd ../my-game
# a wrapper of your own: any Gradle 8.13 will do
gradle wrapper --gradle-version 8.13
./gradlew build
./gradlew run
```

`udeaVersion` in `gradle.properties` says which engine to build against, and everything else reads
it - the plugin ids, the module coordinates, the compiler plugin.

Udea has no release yet, so that is a pinned snapshot, resolved from `mavenLocal()` first and then
from Central's snapshot repository. **It changes when the owner says a new snapshot is the one to
build against** - a game does not follow the engine's tip. "Getting a newer engine" in the
engine's `docs/new-game.md` has both steps, and the `--refresh-dependencies` flag you will need
the first time Gradle's 24-hour snapshot cache surprises you.

To build against an engine change you have not published, publish it to your own machine:

```sh
cd <udea>
./gradlew publishToMavenLocal
./gradlew -p build-logic publishToMavenLocal
```

Two commands, because the second is a separate build: it is the one that carries the convention
plugins and the version catalog this game applies. `mavenLocal()` is first in every repository
list, so what you publish there wins.

What you get, and what each piece is for, is in **`docs/new-game.md`** in the Udea repository.
The short version:

| File | What it decides |
|---|---|
| `settings.gradle.kts` | which engine version, and where its artifacts come from |
| `build.gradle.kts` | which project ships, and which packages are simulation |
| `game/build.gradle.kts` | the conventions, the engine modules, the agent port range |
| `game/src/main` | the game: components and systems |
| `game/src/agent` | the debug-only launcher that binds the MCP surface. Never shipped |
| `game/src/test` | a headless test over the real tick loop |

## Giving your game a look of its own

A screen effect is GLSL you wrote in a `.frag` file. It is an **asset**, like a model or a sound:
you declare it, the build reads it, checks it and packs it, and your Kotlin names it by the
accessor the build generated.

```kotlin
// assets/shaders/shaders.udea.kts
shader(name = "scanlines", file = "shaders/scanlines.frag")
```

```kotlin
// anywhere in your game's shared code
val scanlines = UdeaShader.fragment(GameAssets.shaders.scanlines, assets) {
    float("uStrength", 0.25f)
}
registry.screenPass(scanlines)
```

No path, no string, and nothing that reads a file - so that line compiles on every platform your
game ships on. Reaching for `javaClass.getResource("/shaders/scanlines.frag").readText()` instead
gives you a line that compiles on the desktop alone; misspell the name here and it does not
compile at all, and a `.frag` that is missing, empty, states its own `#version` or defines no
`udeaMain` fails the build with `UDEA0041` and a did-you-mean.

**This template does not ship one**, and that is a fact about the template rather than about the
engine: it is one JVM project with no renderer and no asset pipeline applied, so there is nothing
here for a screen effect to run over. Add `id("dev.wildware.udea.assets")` and a renderer when
your game draws; `moba/game/assets/shaders/` in the Udea repository is the whole worked example,
and `docs/new-game.md` has the long version.

Two commands worth knowing before you change anything:

```sh
./gradlew build                  # compiles, tests, and runs Udea's gates over this game
./gradlew run -PdebugPort=7861   # an instance an agent can drive, on this game's own port range
```
