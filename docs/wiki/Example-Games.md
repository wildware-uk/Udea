# Example Games

Udea ships two games, in the same repository as the engine. **`moba`** is a 2D five-a-side lane game
and is the mature one — it exercises replication, abilities, items, audio, the HUD, the editor and
the agent surface. **`hollow`** is a 3D third-person survival arena, newer, and deliberately
incomplete: it exists to exercise what 2D cannot.

They are examples in the strict sense: everything the wiki claims the engine does, one of these two
does for real, and the proof tasks below produce pictures you can look at.

## moba

A 5v5 three-lane MOBA, drawn as sprites seen from above.

`:moba:desktop:runLaneShot` is the picture of it: a creep wave walking down the lane, the clash
under two towers, and a champion farming, with the HUD and the ability bar along the bottom.

### The projects

| Project | What it is |
|---|---|
| `moba:game` | The game as a **library**: components, systems, assets and what it draws. No entry point in it |
| `moba:desktop` | The desktop launcher: every `run*` task, the proofs, and the agent and editor entry points. JVM |
| `moba:android` | One activity. It boots the simulation **headless**, because `udea-render` has no Android Kool backend yet |

Splitting the library from the launcher is not tidiness. A launcher opens a GL context, binds
sockets and reads system properties, and the game must not be able to name any of them — which is
why `moba:game` compiles for every target `udea-render` has, and each launcher's targets are its
platform's.

### What is in it

- **Units and a fight.** A roster out of the retired example game: soldiers, a priest, orcs,
  skeletons. `UnitBattleSystem` decides who to go for, walks there and faces the right way;
  `udea-gas` owns health as an attribute, damage as an effect, cooldowns that rewind, the priest's
  heal and the soldier's arrow.
- **A lane** (`moba/game/.../lane/`) — creeps, towers, gold, experience and levels, as a module of
  its own. A definition assembled *without* it is the three-faction brawl the game was before, which
  is what a combat unit test wants and a player does not.
- **A match** — spawning, respawn, the scoreboard, and an end condition.
- **Items**, an ability bar and a HUD drawn in ComposeGL into the captured frame, so an agent's
  screenshot shows the cooldown a player sees.
- **Nine sound cues**, routed through `udea-audio`. See [Audio](Audio).
- **An editor**, `runEditor`, with `Position` carrying `@PositionHandle` and a hand-written
  `TowerRangeGizmo`. See [The Editor](The-Editor) and [Gizmos](Gizmos).
- **The agent surface**, in `moba/desktop/src/agent/`. See [Agent Tool Surface](Agent-Tool-Surface).
- **A level**, `.udealevel`, and `-Plevel=<path>` on the launchers.
- **A 3D model**, drawn from an `.fbx` converted at build time — `moba` is 2D but the engine's model
  path is exercised here.

Assets live under `moba/game/assets/`: `ability`, `blueprint`, `champion`, `character`, `control`,
`effects`, `item`, `models`, `sounds`, `sprites`, and a top-level `config.udea.kts`.

### Running it

```sh
sh gradlew :moba:desktop:run -PdebugPort=7841   # Offscreen, with the agent surface
sh gradlew :moba:desktop:runClient              # a visible window
sh gradlew :moba:desktop:runServer              # headless, no GL, no agent surface
sh gradlew :moba:desktop:runEditor              # the editor window, paused
```

`runClient` takes a mode: `local`, `listen`, `host [port]` or `join <host[:port]>`.

### The proof tasks

Each writes something you can look at. They run **by name**, not on `check`: a couple need a GL
driver, and a gate that skips without one hides the failure it exists to show.

| Task | What it produces |
|---|---|
| `:moba:desktop:runShot` | One frame of the character roster |
| `:moba:desktop:runMatchShot` | The melee, the HUD, the spin, the item bar and the result |
| `:moba:desktop:runLaneShot` | A creep wave, the clash under the towers, a champion farming |
| `:moba:desktop:runModelShot` | The build-time-converted FBX character with its texture |
| `:moba:desktop:runLevelShot` | Loads a saved match into a fresh process and checks the picture matches |
| `:moba:desktop:runLevelShotBoot` | The launch level as it loads |
| `:moba:desktop:runNetProof` | Server plus two clients: perfect link, 150ms with 5% loss, and a named bad link |
| `:moba:desktop:runUdpProof` | The whole battle over real UDP, three OS processes, perfect and lossy |

The last two are deliberately outside `check`: `runUdpProof` is wall-clock timing across three OS
processes, so re-run a red one alone before believing it.

## Hollow

A third-person co-op survival arena in a lit forest clearing. New (epic #245), and **only partly
built** — what is below is what exists on master today.

`:hollow:desktop:runPlayerShot` is the picture of it: a low-poly human in a low-poly forest
clearing, seen from behind and slightly above, running across a patch of bare earth ringed by
trees, rocks and flowers.

### The projects

| Project | What it is |
|---|---|
| `hollow:game` | The library: components, CC0 models, the `clearing.udealevel` level, what it draws, and the server and client sessions |
| `hollow:desktop` | The launcher: `run`, `runServer`, `runClient`, `runShot`, `runPlayerShot`. JVM |

There is no Android launcher in this epic.

### What is built

**The clearing** (#249). A lit 3D scene from the CC0 Kenney Nature Kit: ground, trees, stones, logs,
fences, flowers and mushrooms, with a sun and a sky. It is a saved `.udealevel`, and
`:hollow:desktop:udeaWriteClearing` rewrites it from `ClearingLayout` — an authoring tool run by
name, which becomes the wrong thing to run once the level is edited in an editor.

Static props carry a `Scenery` component saying only *which* prop they are. Where they stand is
`Transform3D`; what they look like is presentation's business, so the simulation, a headless server
and a level file never hold a model or a texture.

**A player** (#250). A human model who walks and runs with the camera behind him.

- **Camera-relative movement.** WASD moves him relative to where the camera is looking, not relative
  to the world, and the mouse turns the camera. The camera is `udea-render`'s `ThirdPersonRig`
  (#248): behind and above the followed `Transform3D`, eased in seconds, writing nothing into the
  world.
- **Gait from speed.** Idle, walk or run is decided by how fast he is actually going, not by which
  key is down — so being pushed by something else animates correctly.
- **Collisions.** He stops at a rock. A dynamic Box2D body drives his `Transform3D` through
  `udea-physics2d`; the solver runs with no gravity, because the world is a ground plane seen from
  above and nothing falls across it.
- **Controls are named keys**, `InputKey.W` and never `87`. Hollow declares its two bindings
  (`hollow/move`, `hollow/run`) in Kotlin rather than in an asset, and `HollowControls`' own KDoc
  says why: the asset *reader* `moba` uses is two hundred lines inside `moba:game`, which
  `hollow:game` cannot depend on, and copying it would be the copy-pasted-logic smell the charter
  rejects.

**Multiplayer from the first ticket, not bolted on at the end.** `run` is a listen server with a
local player on it; `runServer` is a headless dedicated server; `runClient` joins one.

A client's world is a **replicated view**: it runs none of Hollow's own systems and no solver. Every
character in it arrived over the wire, and the server decides where each one is and what it is
playing. It still *ticks* — the clock advances, interpolation records a pose per tick, and the
keyboard is sampled so the window has a command to send — which is why its animation plays.

One movement rule, `HollowMovement`, is shared by the server's authority and by what a client
replays when the server's answer arrives. Two approximations of the same rule is what rubber-banding
is made of.

### Running it

```sh
sh gradlew :hollow:desktop:run                       # listen server + a window, UDP 27025
sh gradlew :hollow:desktop:runServer                 # headless dedicated server
sh gradlew :hollow:desktop:runClient --args="join localhost"
sh gradlew :hollow:desktop:runShot                   # one PNG of the lit clearing
sh gradlew :hollow:desktop:runPlayerShot             # one PNG per known tick: standing, walking, running a lap
```

`run` and the others take `-Plevel=<path>` for a level other than the bundled clearing.

`runPlayerShot` is the one worth knowing about: it steps the simulation deliberately and writes a
PNG per **known tick**, so a frame can be named back to a tick and to the event log. That is better
than a video for this, and it is how #250 was proved.

### What is not built yet

Named as not built rather than described, because none of it exists on master:

- **Creatures** — H3 of the epic.
- **Combat and abilities, and the HUD** — H4.
- **Pickups, score, game over and audio cues** — H5. Hollow emits no cues and builds no mixer today.
- **Multiplayer hardening** — lossy links, four players, a UDP net proof and replay equality — H6.
- **The editor and Hollow's own agent tools** — H7, issue #255. `hollow:desktop` has no `agent`
  source set and no `runEditor`.

## Which one to read

Pick by what you are trying to do.

| You want | Read |
|---|---|
| Replication, prediction, desync, the wire | `moba` — `moba/game/src/commonMain/kotlin/dev/wildware/moba/net/` |
| Abilities, effects, attributes, cooldowns | `moba` — `moba/game/.../ability/` and `udea-gas` |
| A HUD, an item bar, a result screen | `moba` — `MobaHud`, `MobaHudScreen` |
| Sound | `moba` — `MobaAudio`, `MobaCueSounds`, `MobaDesktopAudio` |
| Wiring the agent surface | `moba/desktop/src/agent/.../MobaAgent.kt` |
| An editor entry point and a hand-written gizmo | `moba/desktop/src/editor/` |
| 3D models, shadows, a lit scene | `hollow` — `hollow/game/.../render/` |
| A third-person camera and camera-relative input | `hollow` — `ThirdPersonRig`, `CameraRelativeIntent` |
| Physics driving `Transform3D` | `hollow` — `Player`, `ClearingBodies` |
| A server-authoritative game from the first commit | `hollow` — `hollow/game/.../net/` |
| The smallest possible starting point | Neither — `templates/new-game/`, and [Tutorial: Make a Game](Tutorial-Make-a-Game) |

## A note on the art

`moba/game/assets/sprites/` is **gitignored**. It is third-party licensed art from the Tiny RPG
Character Asset Pack, which this repository has no right to sublicense, so a fresh clone carries
none of it. The build stages it: `:moba:game:udeaStageCharacterArt` copies the sheets out of
`example-assets/sprites/`, where they already are, ahead of the asset pipeline, on every build.

So a clone builds, `git status` stays clean, and there is **no art step to run by hand**. A
`UDEA0032` about a `spritePath` is a real defect, not a step you forgot. `docs/art-assets.md` has the
licensing.

Hollow's models are CC0 and are checked in: the clearing's props are Kenney's Nature Kit, and the
player is Quaternius' Animated Human, shipped as an `.fbx` that the asset build converts to glTF.
`hollow/game/assets/models/human/NOTICE.md` carries its licence and what was changed.

## See also

- [Getting Started](Getting-Started) — building and running these two
- [Tutorial: Make a Game](Tutorial-Make-a-Game) — starting your own, outside this repository
- [Architecture](Architecture) — the module arrows these games sit on top of
- [Replication and Networking](Replication-and-Networking) — what the net proofs prove
- [Audio](Audio) — `moba`'s cue routing, and why Hollow has none
- [The Editor](The-Editor) — `runEditor`, which is `moba`'s only
- [Agent Tool Surface](Agent-Tool-Surface) — driving `moba` from a program
