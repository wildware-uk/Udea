# Hollow: a 3D game that tests the whole engine

Owner request (dashboard, 2026-09-19): *"Create a feature rich 3D game to test out the engine."*
The lead designed it without asking questions, per the dev-team rules; every decision below can be changed
on the epic.

## The game in one paragraph

**Hollow** is a third-person, 3D co-op survival arena. One to four players, each an animated human,
defend a lit forest clearing against waves of creatures, led by foxes. Players run, dodge and fight with
abilities, pick up health and power-ups, and survive as many waves as they can. The game plays on one
machine or across machines over UDP, and every match can be replayed exactly. The clearing is a level
you open and edit in the Udea editor with the 3D gizmos.

## Why this game

moba is 2D: sprites on a flat map. Hollow is chosen to exercise what moba cannot:

| Engine part | How Hollow uses it |
|---|---|
| Kool rendering (`udea-render`) | textured PBR models, shadows, sky and ambient light, a third-person camera |
| glTF and FBX import, typed clips, `Animator` (#240-#244) | the FBX human player, glTF foxes, and CC0 environment models |
| `Transform3D` | every entity in the game |
| `udea-physics2d` (Box2D) | collisions on the ground plane, with bodies driving `Transform3D` |
| `udea-gas` | attack, dash, heal and power-ups as abilities and effects, all tick-based |
| ComposeGL HUD (`CapturedUi`) | health, ability cooldowns, wave and score |
| `udea-audio` | cues for hits, pickups and waves |
| `udea-net` | 2-4 player co-op over UDP, with 3D positions replicated |
| `.udealevel` and the editor (#191-#196, #231) | the clearing is a level, edited with 3D gizmos |
| `udea-replay` | replay equality for a recorded match |
| agent tools | the game's own `hollow.*` tools, and screenshots |

## Shape

- **Projects:** `:hollow:game` is a library, like `:moba:game`. `:hollow:desktop` is the JVM launcher
  with `run`, `runServer`, `runClient`, `runEditor`, the shot mains and the proofs. There is no Android
  launcher in this epic.
- **Axes:** Z is up and the ground is the XY plane, matching `Transform3D` and the editor's 3D camera.
- **Assets:** CC0 only.
  - The human is the Quaternius Animated Human already in the tree (#244).
  - The fox is the Khronos glTF sample (#240).
  - Environment models (trees, rocks, fences, grass) come from one CC0 pack, e.g. Quaternius or Kenney
    nature. Record each file's licence next to it.
- **Plain floats in components** (owner rule). No Kool type in any component.

## Engine gaps this epic closes first

1. **3D replication.** `Transform3D` is `@Replicated`, but every field is `@Sim`, so nothing reaches a
   client. Position and heading need `@Net`, and the render side needs interpolation for 3D, as `Position`
   has.
2. **Ground-plane physics for 3D.** `PhysicsBody` carries its own x, y and angle. A 3D entity needs its
   body to drive `Transform3D.x/y/rotationZ` (and kinematic bodies to follow it), leaving `z` to the game.
3. **A third-person camera.** `CameraRig` is 2D. A 3D follow camera behind and above a target, turned by
   the mouse and smoothed in seconds, lives in `udea-render` and writes nothing into the world.

## Tickets, in order

| # | Ticket | Needs |
|---|---|---|
| E1 | `Transform3D` replicates: position and heading `@Net`, 3D render interpolation | - |
| E2 | Ground-plane physics drives `Transform3D` | - |
| E3 | Third-person 3D camera rig | - |
| H1 | Hollow scaffold: projects, CC0 environment, a lit clearing level, run and shot tasks | - |
| H2 | The player: FBX human, WASD and mouse, idle/walk/run from speed, collisions | H1, E2, E3 |
| H3 | Creatures: fox AI (wander, chase, flee), animated, waves from `RngService` | H2 |
| H4 | Combat and abilities through `udea-gas`, plus the ComposeGL HUD | H3 |
| H5 | Pickups, score, game over, and audio cues | H4 |
| H6 | Multiplayer hardening: lossy links, four players, UDP net proof, replay equality | H5, E1 |
| H7 | Editor and agent tools: `runEditor` on the clearing, `hollow.*` tools | H1 |

E1, E2, E3 and H1 touch different modules and run in parallel. The H tickets share `:hollow:game`, so
they run one after another; H7 can run beside H3-H5 because it lives in `:hollow:desktop`'s editor and
agent source sets.

## Multiplayer from the first ticket (owner, 2026-09-19)

Owner: *"The game should be multiplayer and exercise the engine's features."* So multiplayer is not a
last step bolted on at H6. It is the shape of the game from H1:

- **Server-authoritative from H1.** The scaffold has `run` (listen server plus a local player), `runServer`
  (headless) and `runClient host|join`, as moba does, and the clearing loads on the server and replicates.
- **Every H ticket proves its feature with two players.** A headless test runs a server and two clients
  in-process, and the ticket's feature (movement, foxes, combat, pickups) agrees on every machine. Its
  screenshot shows two clients where that makes sense.
- **H6 becomes the hardening ticket:** lossy and laggy links, up to four players, the UDP net proof and
  replay equality over a recorded match.
- Prediction for the local player uses `udea-net`'s `OwnerPredicted` authority, so movement feels immediate.

## Proof every ticket owes

- A screenshot or collage for anything visible (the owner wants pictures).
- `sh gradlew build` green, and the GL suites under xvfb with `-Pudea.render.requireGl=true`.
- Simulation stays tick-based and deterministic: no wall clock, `RngService` only. H6 proves it with
  replay equality.
