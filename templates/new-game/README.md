# new-game

A Udea game that lives in its own repository. Copy this directory somewhere else, rename it, and
it builds against the engine as a published library - no path to a Udea checkout anywhere in it.

```sh
cp -r <udea>/templates/new-game ../my-game
cd ../my-game
# a wrapper of your own: any Gradle 8.13 will do
gradle wrapper --gradle-version 8.13
./gradlew build
./gradlew run          # headless: no window, 600 ticks, and where the rovers ended up
./gradlew runWindow    # a window: three rovers driving across a field
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
| `game/build.gradle.kts` | the conventions, the engine modules, the asset root, the agent port range, `runWindow` |
| `game/assets` | the models and the screen effect, declared in `.udea.kts` and packed into the jar |
| `game/src/main` | the game: components and systems, what the window draws, and the window itself |
| `game/src/agent` | the debug-only launcher that binds the MCP surface. Never shipped |
| `game/src/test` | headless tests over the real tick loop |

## Assets, and the names the build gives them

An asset - a model, a screen effect - is declared in a `.udea.kts` under `game/assets/`, and your
Kotlin names it by an accessor the build generates, never by a path. The rover is declared in
`game/assets/models/models.udea.kts`:

<!-- quoted from templates/new-game/game/assets/models/models.udea.kts -->
```kotlin
model(name = "rover", file = "models/rover.glb")
```

and the simulation says each rover is drawn with it:

<!-- quoted from templates/new-game/game/src/main/kotlin/com/example/newgame/sim/RoverSystem.kt -->
```kotlin
it += Drawn(GameAssets.models.rover, assets)
```

`models` is the folder the `.udea.kts` sits in under `game/assets/`. Move that line into a script
at the top of `game/assets/` and the accessor becomes `GameAssets.root.rover`.

The screen effect is the same shape: `game/assets/shaders/scanlines.frag`, declared beside it,
named `GameAssets.shaders.scanlines` in `NewGameScene`. A `.frag` that is missing, empty, states
its own version line or defines no `udeaMain` fails the build with `UDEA0041` and a did-you-mean.

Commands worth knowing before you change anything:

```sh
./gradlew build                  # compiles, tests, packs the assets, and runs Udea's gates over this game
./gradlew runWindow              # the game in a window
./gradlew run -PdebugPort=7861   # a headless instance an agent can drive, on this game's own port range
```
