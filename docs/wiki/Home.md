# Udea

Udea is a game engine written in Kotlin. It is built so that an AI agent can do most of the work of making a game: every running game answers questions and takes commands over a tool surface, and the build fails loudly, with a named rule, when something is wrong. Rendering is [Kool](https://github.com/fabmax/kool), the entity system is [Fleks](https://github.com/Quillraven/Fleks), and the example game is `moba`, a 5v5 three-lane MOBA.

This wiki explains how the engine works, in detail. Each page starts with a short summary and then goes deeper.

## Who it is for

- **People making a game with Udea.** Start with [Getting Started](Getting-Started), then [Tutorial: Make a Game](Tutorial-Make-a-Game).
- **Agents working on a Udea game or on the engine.** Read [Architecture](Architecture) and [Tick Model and Determinism](Tick-Model-and-Determinism) first. The rules they describe are the ones that are cheap to break and expensive to find. `AGENTS.md` at the repository root is the short brief every agent reads.
- **People changing the engine itself.** Add [Build and Verification](Build-and-Verification). It covers every gate the build runs and what each one catches.

## The one-minute tour

Think of a Udea game as a board game played by a very strict referee.

- **The board is a Fleks world.** Entities are rows, components are plain data on them (floats, ints, enums, never engine objects). See [ECS and Components](ECS-and-Components).
- **The referee moves in fixed turns.** The simulation runs at 60 ticks a second. Time is counted in `Tick`s, never in seconds, so two machines that ran the same ticks agree exactly. See [Tick Model and Determinism](Tick-Model-and-Determinism).
- **Changes from outside wait for the start of a turn.** Scene swaps, agent edits, asset reloads and network snapshots queue on one `SimBarrier` and apply at the top of the next tick, so no system ever sees a half-changed world.
- **The build writes the boring code.** A KSP processor reads annotations such as `@Replicated`, `@Net` and `@Sim` and generates one `Replicator<T>` per component. That one class serves networking, rewind, replays and the agent's field access. See [ECS and Components](ECS-and-Components) and [Replication and Networking](Replication-and-Networking).
- **Content is data, checked at build time.** Characters, abilities, controls and models are `.udea.kts` scripts. The asset build compiles them into one `.udeapak` and fails with a rule id and a did-you-mean when something is wrong. See [Assets](Assets), [Levels](Levels) and [Diagnostics and Compiler Plugin](Diagnostics-and-Compiler-Plugin).
- **Drawing is separate from the game.** `udea-render` is the only module that touches the GPU. The same simulation runs with no window (a server), with a hidden window (an agent taking screenshots), or with a visible one (a player). See [Rendering with Kool](Rendering-with-Kool).
- **Everything can be driven by a tool.** A debug build exposes an MCP tool surface: query entities, step time, rewind, take screenshots, edit fields. The editor window is a screen over those same tools. See [Agent Tool Surface](Agent-Tool-Surface) and [The Editor](The-Editor).
- **Every run can be replayed.** Inputs are recorded to a `.udearep`; replaying one reproduces the match bit for bit, and the tools can land on the tick where two runs first disagree. See [Replay and Time Travel](Replay-and-Time-Travel).

## Every page

**Start here**

- [Getting Started](Getting-Started): what to install, how to build, how to run the examples.
- [Tutorial: Make a Game](Tutorial-Make-a-Game): a small game from nothing, step by step.
- [Example Games](Example-Games): `moba` (2D) and Hollow (3D, in progress).

**How the engine works**

- [Architecture](Architecture): the modules, the arrows between them, and how a game is assembled.
- [Tick Model and Determinism](Tick-Model-and-Determinism): `Tick`, `SimClock`, `SimBarrier`, random streams, and the gates that keep the simulation repeatable.
- [ECS and Components](ECS-and-Components): Fleks, components as plain data, the annotations, and what the build generates.
- [Replication and Networking](Replication-and-Networking): `NetId`, snapshots, input, prediction, UDP, relevancy and the protocol hash.
- [Assets](Assets): `.udea.kts`, the asset compiler, `.udeapak`, models and clips, and the asset daemon.
- [Levels](Levels): `.udealevel` files, saving and loading, and `-Plevel`.
- [Abilities (GAS)](Abilities-GAS): attributes, effects, abilities and cues.
- [Physics](Physics): the `PhysicsWorld` interface, Box2D, and `Transform3D` on the ground plane.
- [Replay and Time Travel](Replay-and-Time-Travel): `.udearep`, replay equality, bisecting a divergence, and rewind.
- [Diagnostics and Compiler Plugin](Diagnostics-and-Compiler-Plugin): `UdeaDiagnostic`, rule ids, the K2 checkers and the KSP checks.
- [Build and Verification](Build-and-Verification): the build, every `udeaVerify*` gate, the frozen contracts, GL tests and CI.

**Presentation and tools**

- [Rendering with Kool](Rendering-with-Kool): `udea-render`, render modes, render systems, interpolation and screenshots.
- [Models and Animation](Models-and-Animation): 3D models, PBR, shadows, the `Animator`, crossfades and skinning.
- [UI with ComposeGL](UI-with-ComposeGL): menus, the HUD, and editor panels.
- [Input](Input): keys, intents and the pointer.
- [Cameras](Cameras): the 2D camera rig, the editor camera and the third-person rig.
- [Audio](Audio): cues, the `AudioDevice` interface and the Kool audio device.
- [The Editor](The-Editor): the editor window, play and stop, save, undo, picking and the inspector.
- [Gizmos](Gizmos): editor handles and how to write your own.
- [Agent Tool Surface](Agent-Tool-Surface): the MCP tools, the HTTP endpoints and `gamebridge.json`.

## Other documents in the repository

The wiki explains. These documents are the authority when the two disagree:

- `AGENTS.md`: the agent brief. Module table, do-not list, tick model, frozen contracts.
- `docs/engineering-standards.md`: the charter. Binding on every module; section 8 is the list a reviewer rejects against.
- `docs/contracts/`: the frozen contracts. See [Build and Verification](Build-and-Verification) for how the freeze is enforced.
- `docs/module-graph.md`: every module-graph rule and why it exists.
- `docs/superpowers/specs/2026-08-22-udea-ai-native-rewrite-design.md`: the design of the engine.
- `docs/superpowers/specs/2026-09-16-kool-kmp-port-design.md`: the move to Kool and Kotlin Multiplatform.

## See also

- [Architecture](Architecture)
- [Getting Started](Getting-Started)
- [Build and Verification](Build-and-Verification)
