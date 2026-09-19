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

`udeaVersion` in `gradle.properties` says which engine release to build against, and everything
else reads it - the plugin ids, the module coordinates, the compiler plugin. Upgrading is that one
line.

**Udea has not been released yet**, so until it is, publish it to your own machine once:

```sh
cd <udea>
./gradlew publishToMavenLocal
./gradlew -p build-logic publishToMavenLocal
```

Two commands, because the second is a separate build: it is the one that carries the convention
plugins and the version catalog this game applies.

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

Two commands worth knowing before you change anything:

```sh
./gradlew build                  # compiles, tests, and runs Udea's gates over this game
./gradlew run -PdebugPort=7861   # an instance an agent can drive, on this game's own port range
```
